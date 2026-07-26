package dev.thompgt.habitsync.auth;

import java.util.UUID;

/**
 * @param refreshTokenId internal id of the newly issued refresh token, used to record
 *                       the rotation chain; never sent to the client
 */
public record AuthResult(
        String accessToken,
        String refreshToken,
        UUID refreshTokenId,
        long accessTokenExpiresInSeconds,
        UUID userId,
        UUID deviceId) {}
