# ADR-001 — Conflict resolution model

**Status:** Accepted
**Date:** 2026-07-25

## Context

Multiple devices mutate the same user's data while offline, then reconcile. We need
a resolution rule that is:

1. **Deterministic** — every replica reaches the same answer without coordination.
2. **Order-independent** — merge must be commutative, associative, and idempotent,
   because the network will deliver changes reordered and duplicated.
3. **Cheap enough** to run on a phone and small enough to explain.

Habit-tracker data isn't uniform. It splits cleanly in two:

- **Append-only facts.** "Completed *Run* at 07:12." "Set 3: 8 reps @ 185 lb."
  These are immutable historical records. Two devices creating them offline produce
  two distinct facts — there is no conflict to resolve, only a union to take.
- **Mutable entities.** A habit's name, colour, weekly target. A workout's notes.
  These genuinely conflict: two devices can set the same field to different values
  with no happens-before relationship between them.

## Options considered

### A. Full CRDTs (OR-Set, LWW-Register, RGA sequences)
Strongest guarantees, no lost updates on sequences, well-researched. Rejected for
this project: metadata grows without bound absent a GC/causal-stability protocol,
sequence CRDTs (RGA/Fugue) are substantial work, and nothing in the domain needs
collaborative *text* editing. The complexity buys us guarantees we don't use.

### B. Server-authoritative with client rebase
Client submits ops with an expected base version; server rejects stale ones; client
rebases pending ops and retries. Elegant, git-like. Rejected because writing correct
transformation functions for arbitrary domain ops is the hardest option here, and
rebase gives *worse* offline UX: a week-offline device faces a large rejection cascade.

### C. Hybrid — event log + per-field LWW ordered by HLC  ← **chosen**

## Decision

**Match the strategy to the data shape.**

| Data | Strategy |
|---|---|
| `habit_completion`, `workout_set`, `workout_session` | Append-only immutable events, client-generated UUIDv7 primary keys. Merge = set union. Conflict is structurally impossible. |
| `habit`, `exercise` mutable fields | **Per-field** last-writer-wins, ordered by Hybrid Logical Clock. |
| Deletes | Tombstones. See [ADR-003](003-delete-wins-semantics.md). |

### Per-field, not per-row

Each mutable entity carries **one HLC per field**, not one per row:

```
habit_field_hlc(habit_id, field_name, hlc_physical, hlc_logical, hlc_node)
```

Device A renames a habit; device B changes its weekly target. Both edits are
concurrent. With **per-row** LWW one of them is silently discarded — the user's edit
vanishes with no error, which is the most common flaw in hand-rolled sync layers.
With **per-field** LWW both survive, because they touch disjoint fields.

The cost is one metadata row per field per entity. At habit-tracker scale (tens of
entities per user) this is irrelevant. If it ever isn't, collapse to a single JSONB
`field_hlc` map column per row — a migration, not a redesign.

### Hybrid Logical Clocks

```java
record Hlc(long physicalMillis, int logical, String nodeId) implements Comparable<Hlc>
```

Compared lexicographically: `physicalMillis`, then `logical`, then `nodeId`.

- **Physical component** keeps ordering roughly aligned with wall-clock intuition, so
  "the edit I made later usually wins" holds for a human.
- **Logical component** preserves causality when the physical clock doesn't advance,
  giving us Lamport-clock guarantees: if A causally precedes B, then `hlc(A) < hlc(B)`.
- **`nodeId` tiebreak is load-bearing.** Without it, two events with identical
  physical and logical components are *unordered*, `compareTo` returns 0, and two
  replicas can pick different winners. Convergence fails. This is exactly the bug the
  M6 simulator is designed to catch, and we deliberately inject it as a harness test.

Clock skew is bounded, not trusted: an inbound HLC more than `MAX_DRIFT` (5 minutes)
ahead of server time is rejected rather than absorbed, so one device with a badly wrong
clock cannot poison the ordering for every other device indefinitely.

## Consequences

**Good**
- Merge is a pure function over `(Change, State)` — trivially unit-testable and
  property-testable for commutativity and idempotence.
- The same `MergeEngine` runs on server and client, so they cannot disagree.
- The vast majority of real data (completions, sets) is conflict-free by construction.
- Metadata is bounded and needs no causal-stability tracking.

**Bad / accepted**
- LWW *does* lose data when two devices edit the same field concurrently. That is
  inherent to LWW; we accept it because same-field concurrent edits on a personal
  habit tracker are rare, and we mitigate by logging every resolution and surfacing
  recent conflicts in a debug screen.
- No collaborative editing of long text. Fine — there isn't any.
- Ordered collections (exercise ordering within a workout) use a fractional-index
  `ordinal` rather than a sequence CRDT. Concurrent reorders can produce ties, broken
  deterministically by entity UUID. Acceptable for single-user multi-device.

## Verification

- Property test: `merge(a, merge(b, s)) == merge(b, merge(a, s))` over generated changes.
- Property test: `merge(a, merge(a, s)) == merge(a, s)` (idempotence, for retry safety).
- M6 simulator: N replicas + fault injection + assert identical final state.
