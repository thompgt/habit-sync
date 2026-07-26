# ADR-003 — Delete-wins semantics and tombstone lifecycle

**Status:** Accepted
**Date:** 2026-07-25

## Context

Device A deletes the habit "Evening Run" while offline. Device B, also offline, renames
it to "Evening Jog" and logs three completions against it. Both sync.

What should the user see?

There is no objectively correct answer — this is a product decision that happens to be
enforced by the merge function. What is *not* negotiable is that both devices, and the
server, must arrive at the **same** answer.

## Options considered

### A. Add-wins / resurrection
A concurrent edit revives the entity (observed-remove set semantics). Arguably friendlier
— the user's edit and their three completions aren't lost. But: deletes become unreliable.
Delete a habit on your phone, and it reappears because your tablet touched it while
offline. Users read that as the app being broken. Correctly implementing observed-remove
also requires tracking which adds a delete observed, which is meaningful extra metadata.

### B. Delete-wins  ← **chosen**

### C. Ask the user
Surface a conflict-resolution UI. Rejected: it makes an offline sync engine depend on
interactive input to reach a consistent state, which means devices can sit divergent
indefinitely. Convergence must not require a human.

## Decision

**Deletes are tombstones, and a tombstone dominates any concurrent field edit.**

- No row is ever physically deleted while it may still be needed for sync. Deleting sets
  `deleted_at` plus a delete HLC.
- Physical deletion is impossible to sync: a missing row is indistinguishable from a row
  the peer has never seen, so a hard delete on one device would be re-created by the next
  sync from a peer that still has it.
- A field edit **never** resurrects the entity, however recent it is. Deletion is
  terminal. Undo is an explicit `restore` op with its own HLC — a deliberate user action,
  never an accident of clock ordering. `DELETE` and `RESTORE` contend for a single
  lifecycle register, resolved by HLC like any other.

### Correction: the tombstone must not gate field writes

An earlier draft of this ADR said that field updates on a tombstoned entity are
*discarded* while their field HLCs still advance. **That is wrong — it makes merge
non-commutative**, and implementing it surfaced the bug immediately.

Take `UPSERT(name=X, hlc=5)` and `DELETE(hlc=3)` reaching two replicas in opposite orders:

```
Replica A:  UPSERT then DELETE  ->  deleted, name = X
Replica B:  DELETE then UPSERT  ->  deleted, name = <unset>   // discarded by the tombstone
```

The replicas disagree, and no amount of re-syncing fixes it — they have both applied
every change.

The rule is therefore: **an entity's field registers and its lifecycle register are
orthogonal.** Field writes apply unconditionally; the tombstone applies unconditionally;
neither consults the other. Deletion affects **visibility at read time only** — the merge
engine never branches on it, and queries filter on it. Both orders above then land on
`deleted, name = X`, and the user simply never sees it.

The user-visible promise ("your edit to a deleted habit is discarded") is unchanged. It is
delivered by hiding the entity, not by refusing the write.

### Child records

Deleting a habit tombstones the habit. Its `habit_completion` rows are **not** cascaded —
they are immutable historical facts and stay in the log. Queries filter completions whose
parent habit is tombstoned. This keeps append-only data genuinely append-only, and means
a `restore` brings the history back intact.

### Consequence for the user

Device B's rename is lost, and its three completions are hidden behind the tombstone.
That is the accepted cost. We mitigate it rather than pretend it doesn't happen:

- Every discarded edit is logged with both HLCs and the winning side.
- The client shows a non-blocking notice — "*Evening Run* was deleted on another device;
  your changes to it were discarded" — so the loss is visible, not silent.

Silent data loss is a bug. *Visible, explained* data loss under a documented rule is a
design tradeoff.

## Tombstone lifecycle

Tombstones cannot be retained forever, and cannot be dropped freely either — dropping a
tombstone that some device hasn't seen lets that device resurrect the entity.

**Retention: 90 days.** A background job hard-deletes tombstoned rows and their
`change_log` entries older than that.

Devices are tracked via `device.last_seen_seq`. If a device syncs with a watermark older
than the GC horizon, the server cannot prove it has seen the relevant tombstones, so it
responds:

```json
{ "resyncRequired": true, "reason": "watermarkBelowGcHorizon" }
```

The client then wipes local state and bootstraps from scratch. Pending local ops are
**pushed before** the wipe, so offline work is not lost — only the client's cached view
of server state is discarded.

90 days is chosen as comfortably longer than any realistic offline period for a phone,
while keeping the change log bounded. It is a tunable, not a law.

## Consequences

**Good**
- Deletes are predictable and terminal. No spontaneous resurrection.
- No observed-remove metadata to track or garbage-collect.
- Merge stays a simple total function; tombstone dominance is one comparison.

**Bad / accepted**
- Concurrent edits to a deleted entity are discarded.
- Long-offline devices pay a full resync. Rare by construction.
- The GC job is now correctness-critical infrastructure, not just housekeeping: GC that
  outruns a device's watermark causes resurrection. Hence the explicit horizon check on
  every pull rather than trusting the retention window.

## Verification

- Merge tests for each ordering: delete-then-edit, edit-then-delete, exactly concurrent.
- Convergence must hold regardless of the order the two ops arrive at each replica.
- A test that GCs past a device's watermark and asserts the server returns
  `resyncRequired` rather than serving an incomplete change set.
