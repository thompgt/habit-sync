import Foundation

/// One round trip's worth of work: everything this device wants to send, and where it wants
/// to resume reading from.
///
/// Push and pull are combined deliberately. The common case on a phone is "I made two edits
/// and want whatever I missed", and splitting that into two requests doubles the radio
/// wake-ups for no benefit. A request with no `ops` is a pure pull.
///
/// This is the transport-independent shape, not the wire format. Mapping it to JSON —
/// stringly-typed enums, HLCs in their compact encoding, a protocol version — is the
/// ``Transport`` implementation's job, because that encoding is a compatibility surface
/// that belongs with the code that speaks HTTP.
public struct SyncRequest: Hashable, Sendable {
    /// The highest `serverSeq` this device has *durably* applied.
    public let sinceSeq: Int64
    /// Locally originated changes awaiting acknowledgement; may be empty.
    public let ops: [Change]

    public init(sinceSeq: Int64, ops: [Change]) {
        precondition(sinceSeq >= 0, "sinceSeq must be >= 0, got \(sinceSeq)")
        self.sinceSeq = sinceSeq
        self.ops = ops
    }

    /// A request that pulls from `sinceSeq` without pushing anything.
    public static func pullOnly(sinceSeq: Int64) -> SyncRequest {
        SyncRequest(sinceSeq: sinceSeq, ops: [])
    }
}

/// The server's answer to a ``SyncRequest``.
public struct SyncResponse: Hashable, Sendable {
    /// Ops the server has committed — including ones it had already seen before this
    /// request. A replay is reported as applied rather than rejected, which is what lets a
    /// client whose push timed out after the server committed clear its outbox instead of
    /// retrying forever.
    public let appliedOpIds: Set<UUID>
    /// Changes after the request's watermark, in sequence order.
    public let changes: [SequencedChange]
    /// The watermark to store *once every change in this page is durable locally*. Not the
    /// server's head: it is the last sequence actually included, so storing it can never
    /// skip a change the page omitted.
    public let nextSeq: Int64
    /// More changes remain beyond this page; pull again immediately.
    public let hasMore: Bool
    /// The device's watermark is below the server's GC horizon, so the server cannot prove
    /// the device has seen the relevant tombstones. Local state must be wiped and rebuilt
    /// from sequence 0 (ADR-003).
    public let resyncRequired: Bool
    /// Machine-readable reason for ``resyncRequired``, for logs; `nil` otherwise.
    public let resyncReason: String?
    /// The server's wall clock, for drift diagnostics. Deliberately *not* fed into the HLC:
    /// trusting a server timestamp would reintroduce the central-sequencer dependency HLCs
    /// exist to avoid.
    public let serverTimeMillis: Int64

    public init(
        appliedOpIds: Set<UUID>,
        changes: [SequencedChange],
        nextSeq: Int64,
        hasMore: Bool,
        resyncRequired: Bool,
        resyncReason: String?,
        serverTimeMillis: Int64
    ) throws {
        guard nextSeq >= 0 else {
            throw TransportError("nextSeq must be >= 0, got \(nextSeq)", retryable: false)
        }
        guard !resyncRequired || changes.isEmpty else {
            // A resync directive means "throw away what you have"; shipping changes
            // alongside it invites a client to apply them onto state it is about to wipe.
            throw TransportError("A resync response must not carry changes", retryable: false)
        }
        self.appliedOpIds = appliedOpIds
        self.changes = changes
        self.nextSeq = nextSeq
        self.hasMore = hasMore
        self.resyncRequired = resyncRequired
        self.resyncReason = resyncReason
        self.serverTimeMillis = serverTimeMillis
    }

    /// A response carrying nothing new.
    public static func empty(seq: Int64) -> SyncResponse {
        // The initialiser only throws on inputs this one cannot produce.
        try! SyncResponse(
            appliedOpIds: [], changes: [], nextSeq: seq, hasMore: false,
            resyncRequired: false, resyncReason: nil, serverTimeMillis: 0)
    }
}

/// What one call to ``SyncEngine/sync()`` accomplished.
///
/// Returned rather than logged, for the same reason ``Resolution`` is: the core has no
/// logger, and the consumers want this in different forms — the UI shows "last synced, 3
/// changes", the scheduler decides whether to run again immediately, and tests assert on it.
public struct SyncOutcome: Hashable, Sendable {
    /// Local ops the server confirmed, now cleared from the outbox.
    public let opsAcknowledged: Int
    /// Remote changes merged into local state, including ones fully superseded — this
    /// counts what arrived, not what won.
    public let changesApplied: Int
    /// Round trips made.
    public let pagesFetched: Int
    /// The watermark after this call.
    public let watermark: Int64
    /// Local state was wiped and rebuilt on the server's instruction.
    public let resynced: Bool
    /// Work is still outstanding — either the server has more pages or the outbox is not
    /// empty. Callers should run again promptly rather than waiting for the next scheduled
    /// sync.
    public let moreRemaining: Bool
    /// Work this sync discarded or hid, capped at ``SyncEngine/maxReportedConflicts``.
    /// ADR-001 and ADR-003 both accept losing data on the condition that the loss is
    /// surfaced; this is what a client shows to honour that.
    public let conflicts: [Conflict]
    /// How many conflicts actually occurred, which exceeds `conflicts.count` once the cap
    /// bites. Kept as a count so a UI can say "and 40 more" rather than implying it listed
    /// everything.
    public let conflictsObserved: Int

    public init(
        opsAcknowledged: Int,
        changesApplied: Int,
        pagesFetched: Int,
        watermark: Int64,
        resynced: Bool,
        moreRemaining: Bool,
        conflicts: [Conflict],
        conflictsObserved: Int
    ) {
        self.opsAcknowledged = opsAcknowledged
        self.changesApplied = changesApplied
        self.pagesFetched = pagesFetched
        self.watermark = watermark
        self.resynced = resynced
        self.moreRemaining = moreRemaining
        self.conflicts = conflicts
        self.conflictsObserved = conflictsObserved
    }

    /// Whether anything at all changed — useful for deciding to refresh a UI.
    public var idle: Bool { opsAcknowledged == 0 && changesApplied == 0 && !resynced }

    /// Conflicts that cost the user work they did on *this* device.
    ///
    /// The subset worth an unprompted notice. Everything else — a write another replica lost
    /// to a value this device already held — belongs in a debug screen, where ``conflicts``
    /// serves it.
    public var lostLocalWrites: [Conflict] { conflicts.filter(\.lostLocalWrite) }

    /// Conflicts that occurred but did not fit in the bounded list.
    public var unreportedConflicts: Int { Swift.max(0, conflictsObserved - conflicts.count) }
}

/// The device's channel to the server, reduced to the one call the engine needs.
///
/// Everything platform-specific lives behind this: HTTP, JSON encoding, the protocol version
/// header, bearer tokens, refresh-on-401, retry and backoff. ``SyncEngine`` knows none of
/// it, which is what lets a test substitute an in-process transport it can partition, delay,
/// reorder and duplicate at will, and still be driving the real engine rather than a model
/// of it.
///
/// Implementations must treat `exchange` as **safe to retry**. The engine replays a request
/// whose outcome it never learned, and relies on the server's op-id idempotency to make that
/// harmless.
public protocol Transport: Sendable {
    /// Sends a push-and-pull round trip.
    ///
    /// - Throws: ``TransportError`` if the request could not be completed. The engine treats
    ///   this as "try again later" and leaves both the outbox and the watermark untouched,
    ///   so no work is lost.
    func exchange(_ request: SyncRequest) async throws -> SyncResponse
}
