package dev.thompgt.habitsync.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * The device's credentials on disk, next to its database.
 *
 * <p>The tokens are stored in plain text, and that is a real limitation rather than a
 * simplification: anyone who can read this file can act as the device until the refresh token
 * expires, which is 90 days. It is acceptable here because this is a reference client whose
 * database sits beside it unencrypted anyway — the file is no weaker than the data it guards.
 * A shipping client would put both in the platform keystore, and Android's is the reason the
 * interface is this narrow.
 *
 * <p>What matters more for correctness is that {@code deviceId} is persisted at all. It is the
 * HLC node id, so a device that loses it and registers afresh splits its own causal history
 * across two identities.
 */
final class SessionFile {

    private static final ObjectMapper JSON = HttpTransport.defaultMapper();

    private SessionFile() {}

    record Stored(UUID userId, UUID deviceId, String accessToken, String refreshToken, String email) {}

    static Optional<Stored> read(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(Files.readAllBytes(path), Stored.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the saved session at " + path, e);
        }
    }

    static void write(Path path, Session session, String email) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.write(
                    path,
                    JSON.writeValueAsBytes(new Stored(
                            session.userId(),
                            session.deviceId(),
                            session.accessToken(),
                            session.refreshToken(),
                            email)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the session to " + path, e);
        }
    }

    static Session toSession(Stored stored) {
        return new Session(stored.userId(), stored.deviceId(), stored.accessToken(), stored.refreshToken());
    }
}
