package dev.thompgt.habitsync.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated principal: which user, and which of their devices.
 *
 * <p>The device matters as much as the user here. Every sync request is scoped to a
 * device — it determines the HLC node id that stamps writes and the watermark that
 * bounds a pull — so carrying it in the principal keeps controllers from having to
 * trust a device id supplied in the request body, which a client could forge to read
 * another device's position or to stamp writes as somebody else.
 */
public record AuthenticatedUser(UUID userId, UUID deviceId) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(deviceId, "deviceId");
    }

    /**
     * The HLC node id for this device.
     *
     * <p>A UUID's text form contains no {@code ':'}, which {@link
     * dev.thompgt.habitsync.sync.Hlc} forbids in a node id.
     */
    public String nodeId() {
        return deviceId.toString();
    }
}
