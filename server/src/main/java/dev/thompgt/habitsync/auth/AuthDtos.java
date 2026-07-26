package dev.thompgt.habitsync.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request and response bodies for {@code /v1/auth}. */
public final class AuthDtos {

    private AuthDtos() {}

    /**
     * @param password minimum 12 characters, no composition rules. Length dominates
     *                 character-class requirements for real-world strength, and the rules
     *                 mostly push people towards {@code Password1!}.
     */
    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 12, max = 200) String password,
            @Size(max = 100) String deviceName) {}

    /**
     * @param deviceId the caller's existing device id, if it has one. Supplying it keeps
     *                 the device's HLC node identity stable across logins; omitting it
     *                 registers a new device.
     */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            @Size(max = 100) String deviceName,
            UUID deviceId) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            UUID userId,
            UUID deviceId) {

        static TokenResponse from(AuthResult result) {
            return new TokenResponse(
                    result.accessToken(),
                    result.refreshToken(),
                    "Bearer",
                    result.accessTokenExpiresInSeconds(),
                    result.userId(),
                    result.deviceId());
        }
    }

    public record DeviceResponse(UUID id, String displayName, long lastSeenSeq, String lastSeenAt) {}

    public record MeResponse(UUID userId, String email, java.util.List<DeviceResponse> devices) {}
}
