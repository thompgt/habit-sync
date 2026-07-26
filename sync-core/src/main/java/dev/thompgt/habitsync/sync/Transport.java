package dev.thompgt.habitsync.sync;

/**
 * The device's channel to the server, reduced to the one call the engine needs.
 *
 * <p>Everything platform-specific lives behind this: HTTP, JSON encoding, the protocol
 * version header, bearer tokens, refresh-on-401, retry and backoff. {@link SyncEngine}
 * knows none of it, which is what lets the convergence simulator (M6) substitute an
 * in-process transport it can partition, delay, reorder and duplicate at will, and still
 * be driving the real engine rather than a model of it.
 *
 * <p>Implementations must treat {@code exchange} as <b>safe to retry</b>. The engine
 * replays a request whose outcome it never learned, and relies on the server's
 * {@code opId} idempotency to make that harmless.
 */
@FunctionalInterface
public interface Transport {

    /**
     * Sends a push-and-pull round trip.
     *
     * @throws TransportException if the request could not be completed. The engine treats
     *                            this as "try again later" and leaves both the outbox and
     *                            the watermark untouched, so no work is lost.
     */
    SyncResponse exchange(SyncRequest request) throws TransportException;
}
