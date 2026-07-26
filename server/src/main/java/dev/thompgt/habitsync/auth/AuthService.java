package dev.thompgt.habitsync.auth;

import dev.thompgt.habitsync.account.AccountRepository;
import dev.thompgt.habitsync.account.AppUser;
import dev.thompgt.habitsync.account.Device;
import dev.thompgt.habitsync.security.JwtService;
import dev.thompgt.habitsync.security.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, login, and refresh-token rotation. */
@Service
public class AuthService {

    /** 256 bits of entropy; refresh tokens are opaque random strings, not JWTs. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    /**
     * A dummy hash to verify against when no user matches, so a login attempt for an
     * unknown address costs the same as one for a known address. Without it, response
     * time alone enumerates registered users.
     */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7kK5Rr5tGvpvXBHnB6d0Rz7ZQ5eZ4Qi";

    private final AccountRepository accounts;
    private final RefreshTokenRepository refreshTokens;
    private final SessionRevoker sessionRevoker;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public AuthService(
            AccountRepository accounts,
            RefreshTokenRepository refreshTokens,
            SessionRevoker sessionRevoker,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            SecurityProperties securityProperties,
            Clock clock) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.sessionRevoker = sessionRevoker;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
        this.clock = clock;
    }

    @Transactional
    public AuthResult register(String email, String rawPassword, String deviceName) {
        if (accounts.emailExists(email)) {
            throw new AuthenticationFailedException("An account with that email already exists");
        }

        Instant now = clock.instant();
        AppUser user = new AppUser(UUID.randomUUID(), email, passwordEncoder.encode(rawPassword), now);
        accounts.insertUser(user);

        Device device = registerDevice(user.id(), deviceName);
        return issueTokens(user.id(), device, now);
    }

    @Transactional
    public AuthResult login(String email, String rawPassword, String deviceName, UUID existingDeviceId) {
        Optional<AppUser> maybeUser = accounts.findByEmail(email);

        // Always run a hash comparison, even with no matching user, so timing does not
        // reveal whether the address is registered.
        String hash = maybeUser.map(AppUser::passwordHash).orElse(DUMMY_BCRYPT_HASH);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hash);

        if (maybeUser.isEmpty() || !passwordMatches) {
            throw new AuthenticationFailedException("Invalid email or password");
        }

        AppUser user = maybeUser.get();
        Instant now = clock.instant();

        // A client that already knows its device id keeps it. The id doubles as the HLC
        // node id, so re-issuing one on every login would fragment a single device's
        // causal history across many identities and defeat the nodeId tiebreak.
        Device device = Optional.ofNullable(existingDeviceId)
                .flatMap(id -> accounts.findDevice(user.id(), id))
                .orElseGet(() -> registerDevice(user.id(), deviceName));

        return issueTokens(user.id(), device, now);
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the old one.
     *
     * <p>Rotation with reuse detection: each refresh invalidates the token it consumed.
     * Presenting an already-rotated token means a copy exists somewhere it should not, so
     * every session for that user is revoked.
     */
    @Transactional
    public AuthResult refresh(String presentedToken) {
        Instant now = clock.instant();
        StoredRefreshToken stored = refreshTokens
                .findByHash(hash(presentedToken))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));

        if (stored.wasRotated()) {
            // Committed in its own transaction: this method throws immediately below,
            // which would otherwise roll the revocation back. See SessionRevoker.
            sessionRevoker.revokeAllSessions(
                    stored.userId(), now, "refresh token reuse detected (stolen or replayed)");
            throw new AuthenticationFailedException("Refresh token has already been used");
        }
        if (stored.isRevoked()) {
            throw new AuthenticationFailedException("Refresh token has been revoked");
        }
        if (stored.isExpired(now)) {
            throw new AuthenticationFailedException("Refresh token has expired");
        }

        Device device = accounts
                .findDevice(stored.userId(), stored.deviceId())
                .orElseThrow(() -> new AuthenticationFailedException("Device no longer registered"));

        AuthResult result = issueTokens(stored.userId(), device, now);
        refreshTokens.markRotated(stored.id(), result.refreshTokenId(), now);
        return result;
    }

    private Device registerDevice(UUID userId, String deviceName) {
        Device device = new Device(
                UUID.randomUUID(), userId, deviceName == null || deviceName.isBlank() ? "Unnamed device" : deviceName, 0L, null);
        accounts.insertDevice(device);
        return device;
    }

    private AuthResult issueTokens(UUID userId, Device device, Instant now) {
        String accessToken = jwtService.issueAccessToken(userId, device.id(), now);

        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        UUID refreshTokenId = UUID.randomUUID();
        refreshTokens.insert(
                refreshTokenId,
                userId,
                device.id(),
                hash(refreshToken),
                now.plus(securityProperties.refreshTokenTtl()));

        return new AuthResult(
                accessToken,
                refreshToken,
                refreshTokenId,
                jwtService.accessTokenTtlSeconds(),
                userId,
                device.id());
    }

    /**
     * SHA-256, not bcrypt.
     *
     * <p>Deliberate: a refresh token is 256 bits of {@link SecureRandom} output, not a
     * human-chosen password, so there is no dictionary to attack and no work factor
     * needed. It is also looked up by hash on every refresh, and a bcrypt lookup would
     * mean scanning the table rather than hitting the unique index.
     */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
