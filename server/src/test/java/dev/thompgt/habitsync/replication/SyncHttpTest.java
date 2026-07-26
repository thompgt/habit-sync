package dev.thompgt.habitsync.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thompgt.habitsync.auth.AuthDtos;
import dev.thompgt.habitsync.sync.WireChange;
import dev.thompgt.habitsync.replication.dto.SyncDtos;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncRequest;
import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The sync endpoint over real HTTP, including JSON serialisation.
 *
 * <p>Worth having separately from {@link SyncProtocolTest}: a global Jackson
 * {@code non_null} inclusion setting once silently dropped null field values from both
 * the stored payload and the response body, turning every "clear this field" operation
 * into a no-op. Service-level tests would not have caught the response half.
 */
@AutoConfigureMockMvc
class SyncHttpTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private String registerAndGetToken() throws Exception {
        MvcResult result = mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.RegisterRequest(
                                "http-" + UUID.randomUUID() + "@example.com",
                                "correct-horse-battery-staple",
                                "Phone"))))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode sync(String token, SyncRequest request) throws Exception {
        MvcResult result = mvc.perform(post("/v1/sync")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a null field value survives JSON serialisation in both directions")
    void nullFieldValuesSurviveTheWire() throws Exception {
        String token = registerAndGetToken();
        UUID habit = UUID.randomUUID();

        Map<String, String> cleared = new HashMap<>();
        cleared.put("colour", null);

        sync(token, new SyncRequest(0, SyncDtos.PROTOCOL_VERSION, List.of(
                new WireChange(UUID.randomUUID(), "HABIT", habit, "UPSERT", "2000:0:a", cleared))));

        JsonNode response = sync(token, new SyncRequest(0, SyncDtos.PROTOCOL_VERSION, List.of()));
        JsonNode fields = response.get("changes").get(0).get("change").get("fields");

        assertThat(fields.has("colour")).as("the key must be present").isTrue();
        assertThat(fields.get("colour").isNull()).as("its value must be JSON null").isTrue();
    }

    @Test
    void syncRequiresAuthentication() throws Exception {
        mvc.perform(post("/v1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new SyncRequest(0, SyncDtos.PROTOCOL_VERSION, List.of()))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("a client on an unknown protocol version is told to upgrade, not silently served")
    void mismatchedProtocolVersionReturns426() throws Exception {
        String token = registerAndGetToken();

        mvc.perform(post("/v1/sync")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SyncRequest(0, 99, List.of()))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(426));
    }

    @Test
    void unknownEntityTypeIsARejectedRequestNotAServerError() throws Exception {
        String token = registerAndGetToken();

        mvc.perform(post("/v1/sync")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SyncRequest(
                                0,
                                SyncDtos.PROTOCOL_VERSION,
                                List.of(new WireChange(
                                        UUID.randomUUID(),
                                        "NOT_A_REAL_TYPE",
                                        UUID.randomUUID(),
                                        "UPSERT",
                                        "1000:0:a",
                                        Map.of("name", "x")))))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    void malformedHlcIsRejected() throws Exception {
        String token = registerAndGetToken();

        mvc.perform(post("/v1/sync")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SyncRequest(
                                0,
                                SyncDtos.PROTOCOL_VERSION,
                                List.of(new WireChange(
                                        UUID.randomUUID(),
                                        "HABIT",
                                        UUID.randomUUID(),
                                        "UPSERT",
                                        "this-is-not-an-hlc",
                                        Map.of("name", "x")))))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    void getEndpointPullsWithoutABody() throws Exception {
        String token = registerAndGetToken();
        UUID habit = UUID.randomUUID();

        sync(token, new SyncRequest(0, SyncDtos.PROTOCOL_VERSION, List.of(
                new WireChange(UUID.randomUUID(), "HABIT", habit, "UPSERT", "1000:0:a", Map.of("name", "Run")))));

        MvcResult result = mvc.perform(get("/v1/sync?sinceSeq=0").header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("changes")).hasSize(1);
    }
}
