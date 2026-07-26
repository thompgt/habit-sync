package dev.thompgt.habitsync.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AuthFlowTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private JsonNode register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.RegisterRequest(
                                email, "correct-horse-battery-staple", "Pixel 9"))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void registerIssuesTokensAndRegistersADevice() throws Exception {
        JsonNode body = register(uniqueEmail());

        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(UUID.fromString(body.get("deviceId").asText())).isNotNull();
    }

    @Test
    @DisplayName("registration creates the sync counter row, so push never has to upsert it")
    void registrationSeedsTheSyncCounter() throws Exception {
        JsonNode body = register(uniqueEmail());
        UUID userId = UUID.fromString(body.get("userId").asText());

        Long nextSeq = jdbc.queryForObject(
                "SELECT next_seq FROM user_sync_counter WHERE user_id = ?", Long.class, userId);

        assertThat(nextSeq).isEqualTo(1L);
    }

    @Test
    void accessTokenAuthenticatesAgainstAProtectedEndpoint() throws Exception {
        String email = uniqueEmail();
        JsonNode body = register(email);

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + body.get("accessToken").asText()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
                .andExpect(result -> {
                    JsonNode me = json.readTree(result.getResponse().getContentAsString());
                    assertThat(me.get("email").asText()).isEqualTo(email);
                    assertThat(me.get("devices")).hasSize(1);
                });
    }

    @Test
    void protectedEndpointsRejectMissingAndGarbageTokens() throws Exception {
        mvc.perform(get("/v1/me"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        mvc.perform(get("/v1/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected")
    void rejectsForgedSignature() throws Exception {
        JsonNode body = register(uniqueEmail());
        String token = body.get("accessToken").asText();

        // Tamper with the payload; the signature no longer matches.
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + forged))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    void duplicateRegistrationIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new AuthDtos.RegisterRequest(email, "correct-horse-battery-staple", "Other"))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("login with a known device id keeps the HLC node identity stable")
    void loginReusesAnExistingDevice() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = register(email);
        UUID deviceId = UUID.fromString(registered.get("deviceId").asText());

        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.LoginRequest(
                                email, "correct-horse-battery-staple", "Pixel 9", deviceId))))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(UUID.fromString(body.get("deviceId").asText())).isEqualTo(deviceId);
    }

    @Test
    void loginWithoutADeviceIdRegistersANewOne() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = register(email);
        UUID originalDevice = UUID.fromString(registered.get("deviceId").asText());

        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.LoginRequest(
                                email, "correct-horse-battery-staple", "iPad", null))))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(UUID.fromString(body.get("deviceId").asText())).isNotEqualTo(originalDevice);
    }

    @Test
    void loginWithTheWrongPasswordIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new AuthDtos.LoginRequest(email, "wrong-password-entirely", "Pixel", null))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("failed login reveals nothing about whether the account exists")
    void unknownAndKnownAccountsFailIdentically() throws Exception {
        String known = uniqueEmail();
        register(known);

        MvcResult wrongPassword = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new AuthDtos.LoginRequest(known, "wrong-password-entirely", "d", null))))
                .andReturn();

        MvcResult unknownUser = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.LoginRequest(
                                uniqueEmail(), "wrong-password-entirely", "d", null))))
                .andReturn();

        assertThat(unknownUser.getResponse().getStatus())
                .isEqualTo(wrongPassword.getResponse().getStatus());
        assertThat(unknownUser.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
    }

    @Test
    void shortPasswordsAreRejectedWithFieldDetail() throws Exception {
        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new AuthDtos.RegisterRequest(uniqueEmail(), "short", "d"))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("password"));
    }

    @Test
    void passwordsAreNeverStoredInPlaintext() throws Exception {
        String email = uniqueEmail();
        register(email);

        String stored = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE lower(email) = lower(?)", String.class, email);

        assertThat(stored).isNotNull().doesNotContain("correct-horse-battery-staple").startsWith("$2");
    }

    @Test
    void refreshTokensAreNeverStoredInPlaintext() throws Exception {
        JsonNode body = register(uniqueEmail());
        String refreshToken = body.get("refreshToken").asText();

        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE token_hash = ?", Integer.class, refreshToken);

        assertThat(matches).isZero();
    }
}
