package dev.thompgt.habitsync.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Refresh-token rotation and theft detection.
 *
 * <p>Exercised through {@link AuthService} rather than HTTP, because the interesting
 * assertions are about database state — which tokens are revoked, and what the rotation
 * chain records — not about status codes.
 */
class RefreshRotationTest extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;

    private AuthResult freshAccount() {
        return authService.register(
                "rotate-" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple", "Pixel");
    }

    @Test
    void refreshIssuesANewPairAndRotatesTheOldToken() {
        AuthResult initial = freshAccount();

        AuthResult refreshed = authService.refresh(initial.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.deviceId()).isEqualTo(initial.deviceId());

        UUID replacedBy = jdbc.queryForObject(
                "SELECT replaced_by FROM refresh_token WHERE id = ?", UUID.class, initial.refreshTokenId());
        assertThat(replacedBy).isEqualTo(refreshed.refreshTokenId());
    }

    @Test
    void theRotatedTokenStopsWorking() {
        AuthResult initial = freshAccount();
        authService.refresh(initial.refreshToken());

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("reusing a rotated token revokes every session — it is evidence of theft")
    void reuseRevokesTheEntireChain() {
        AuthResult initial = freshAccount();
        AuthResult second = authService.refresh(initial.refreshToken());
        AuthResult third = authService.refresh(second.refreshToken());

        // An attacker replays the token the legitimate client already rotated away.
        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOf(AuthenticationFailedException.class);

        // The still-live token the legitimate client holds is now dead too. That is the
        // intended trade: a forced re-login beats leaving a thief with a valid session,
        // and the two cases are indistinguishable from the server's side.
        assertThatThrownBy(() -> authService.refresh(third.refreshToken()))
                .isInstanceOf(AuthenticationFailedException.class);

        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                initial.userId());
        assertThat(live).isZero();
    }

    @Test
    void anUnknownRefreshTokenIsRejected() {
        assertThatThrownBy(() -> authService.refresh("not-a-real-token"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        AuthResult initial = freshAccount();

        jdbc.update(
                "UPDATE refresh_token SET expires_at = now() - interval '1 day' WHERE id = ?",
                initial.refreshTokenId());

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void aManuallyRevokedTokenIsRejected() {
        AuthResult initial = freshAccount();

        jdbc.update("UPDATE refresh_token SET revoked_at = now() WHERE id = ?", initial.refreshTokenId());

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOf(AuthenticationFailedException.class);
    }
}
