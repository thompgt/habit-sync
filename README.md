# habit-sync

An offline-first habit and workout tracker — built as a vehicle for the part that's
actually hard: **a provably convergent sync layer** between multiple offline clients
and a server.

The tracker is the excuse. The sync engine is the project.

> **Status:** early construction. See [Project status](#project-status) for what's
> actually built versus planned. Nothing here is claimed to work until its milestone
> is checked off.

---

## The problem

Two phones. Both offline for a week. Both edit the same habit, log workouts, delete
things. On reconnect, in any order, with packets dropped, reordered, and duplicated
along the way — **every device and the server must converge to identical state.**

That's a distributed systems problem wearing a fitness app costume.

## The approach

Habit-tracker data comes in two shapes, and they want different treatment:

| Shape | Examples | Strategy |
|---|---|---|
| **Append-only facts** | "completed *Run* at 07:12", "set 3: 8 reps @ 185 lb" | Immutable events with client-generated UUIDs. Commutative — genuinely cannot conflict. |
| **Mutable entities** | habit name, weekly target, workout notes | **Per-field** last-writer-wins, ordered by **Hybrid Logical Clocks**. |
| **Deletes** | removing a habit | Tombstones, never physical deletes. Delete dominates concurrent edits. |

Per-field (not per-row) LWW is what lets one device rename a habit while another
changes its weekly target, with **both edits surviving**. Per-row LWW would silently
discard one of them — the single most common flaw in hand-rolled sync.

Hybrid Logical Clocks give a **total order** across devices without a central
sequencer and without trusting wall clocks. The `nodeId` tiebreak is load-bearing:
without it, two events at the same instant are unordered and convergence fails.

### Architecture

```
sync-core/    Pure Java 17. No Spring. No Android. No ORM.
              HLC, ops, merge engine, sync orchestration — behind
              LocalStore / Transport / TimeSource interfaces.

server/       Spring Boot 3.5 + Postgres. Depends on sync-core.
android/      Java Android client. Room + WorkManager. Depends on sync-core.
```

The server and the client **run the same `MergeEngine`**. They cannot disagree about
who won a conflict, because it is literally the same code path.

That same property is what makes the convergence simulator possible: it spins up N
in-memory replicas driving the real engine over an in-process transport it can
partition, delay, reorder, and duplicate at will — no emulator, no network, no mocks
of the logic under test.

## Project status

| Milestone | State |
|---|---|
| M0 — Scaffolding, build, CI, ADRs | 🔨 in progress |
| M1 — `sync-core` primitives (HLC, merge engine) | ⬜ |
| M2 — Server domain, schema, auth | ⬜ |
| M3 — Android client, fully offline | ⬜ blocked (no Android SDK on dev machine) |
| M4 — Sync protocol v1 | ⬜ |
| M5 — Conflict semantics & hardening | ⬜ |
| M6 — Deterministic convergence simulator | ⬜ |
| M7 — Multi-device UX & observability | ⬜ |

## Build

Requires a JDK 17+ (developed against JDK 24) and Docker for the integration tests.
Gradle comes via the wrapper — no local install needed.

```bash
./gradlew build          # compile + test everything
./gradlew :sync-core:test    # fast: pure-JVM merge and HLC tests, no Docker
```

The Android module is intentionally absent from `settings.gradle.kts` until an SDK is
present, so `sync-core` and `server` build on any plain JDK — including CI.

## Design decisions

Architecture decision records live in [`docs/adr/`](docs/adr/). The load-bearing ones:

- [ADR-001 — Conflict resolution model](docs/adr/001-conflict-resolution-model.md)
- [ADR-002 — Change-log ordering](docs/adr/002-change-log-ordering.md)
- [ADR-003 — Delete-wins semantics](docs/adr/003-delete-wins-semantics.md)

ADR-002 is the one worth reading even if you skip the rest — it documents a data-loss
bug that the obvious design walks straight into.

## License

MIT
