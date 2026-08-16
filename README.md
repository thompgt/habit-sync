# habit-sync

An offline-first habit and workout tracker — built as a vehicle for the part that's
actually hard: **a provably convergent sync layer** between multiple offline clients
and a server.

The tracker is the excuse. The sync engine is the project.

## Tech Stack

![Java](https://img.shields.io/badge/Java_21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)

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
              HLC, ops, merge engine, wire codec, and the client
              SyncEngine — behind LocalStore / Transport / TimeSource.

server/       Spring Boot 3.5 + Postgres. Depends on sync-core.
simulator/    The M6 convergence simulator. Pure JVM, no Docker.
client/       Reference client: real HTTP transport, SQLite store, CLI.
ios/          Native SwiftUI client. Swift port of sync-core, SQLite store,
              URLSession transport, Keychain session.
android/      Java Android client. Room + WorkManager. Depends on sync-core.
```

Both halves of the protocol live in `sync-core`, and both ends encode through the same
`ChangeCodec`. The server decodes what devices push; the client encodes what it sends.
A payload one side accepts is therefore one the other produces, by construction rather
than by agreement.

`SyncEngine` is the client half: it stamps local edits, pushes the outbox, pulls what the
device missed, merges it, and moves the watermark. It has no I/O of its own — storage,
network and clock are all injected — so the same engine runs against the simulator's
in-process network and against a real server over HTTP without knowing which it is talking
to. `client/` supplies the real implementations of both seams; `ios/` supplies a second,
native set of them.

`ios/` is the one place the shared-code property does not hold, because a phone cannot run
the JVM engine. The merge rule therefore exists twice, and what keeps the copies honest is
that `HabitSyncCore` is a line-by-line port whose tests are this suite's tests, and whose
wire encoding is asserted as bytes rather than as a round trip. The three translations
that would silently diverge — the `nodeId` tiebreak, the logical counter's 32-bit bound,
and Swift's habit of deleting a dictionary key when you assign nil to it — are called out
where they happen. See [`ios/README.md`](ios/README.md).

What remains unbuilt is the Android UI, which is blocked on there being no SDK on the
build machine.

The server and the client **run the same `MergeEngine`**. They cannot disagree about
who won a conflict, because it is literally the same code path.

That same property is what makes the convergence simulator possible: it spins up N
in-memory replicas driving the real engine over an in-process transport it can
partition, delay, reorder, and duplicate at will — no emulator, no network, no mocks
of the logic under test.

<a id="losing-data-on-purpose-visibly"></a>
### Losing data on purpose, visibly

Last-writer-wins loses writes. That is the deal, and both conflict ADRs accept it on one
condition: **the loss is shown to the user rather than swallowed.** Silent data loss is a
bug; visible, explained loss under a documented rule is a trade-off.

So a sync returns what it discarded. `SyncOutcome` carries the field a remote write
overwrote, the delete that hid an entity the user had been editing, and — separately —
whether the losing write was one *this* device made, which is the only kind worth
interrupting anybody about. The list is bounded and the count is not, so a client can say
"and 40 more" instead of implying it listed everything.

### Retention, and what had to be built first

Tombstones cannot be kept forever and cannot be dropped freely: dropping one a device has
not seen lets that device resurrect the entity. ADR-003 sets a 90-day window and makes the
pull-side horizon check — not the collector's care — the safety property.

Collecting the log turned out to need a prior change. A device starting from scratch was
served by *replaying the log*, which requires keeping it back to the account's first write
forever; trim anything and a bootstrap silently omits every entity created in the trimmed
range. That is not a corner case for long-offline devices — a tablet added to a two-year-old
account starts from zero on the day it is bought. Bootstraps are now served from **current
entity state**, one synthesised change per field so each register keeps its own clock, which
makes the log purely a catch-up structure that can be truncated from the front.

The collector deletes a *sequence prefix*, never a time range. `created_at` is a
transaction's start time while `server_seq` is handed out under a lock, so of two
overlapping pushes the earlier-started one can take the higher sequence — deleting by time
punches a hole in the middle of the log instead of trimming its front, and a device below a
hole passes the horizon check while permanently missing what is inside it.

### The simulator, and how much it is worth

`simulator/` runs N replicas of the **real** `SyncEngine` against an in-process server over a
network it can partition, drop, duplicate and reorder — all of it driven by one `long` seed.
A failing run is reported with that seed and a numbered history, and re-running the seed
reproduces it byte for byte.

The lost-**response** fault is the one that earns the whole thing: the server commits the push
and the reply vanishes, so the client must retry and the server must recognise the replay.
That path is nearly impossible to provoke against a real server and is where hand-rolled sync
layers quietly duplicate or drop writes.

A convergence suite that never fails proves nothing, so its detection power was measured
rather than assumed. Two deliberate bugs were introduced into `MergeEngine` and the sweep run
against each:

| Mutation | Seeds failing (of 150, perfect network) |
|---|---|
| Discard field writes on tombstoned entities (couple the two register groups) | 136 |
| Per-row instead of per-field LWW — the flaw ADR-001 exists to avoid | 145 |

Both were caught with **no fault injection at all**. The faults widen coverage; ordinary
concurrent editing is already enough to find broken conflict semantics.

```bash
./gradlew :simulator:test                          # the CI sweep, ~25s
java -cp ... dev.thompgt.habitsync.sim.SeedSweep 0 1000   # a wider sweep, offline
```

### Trying it, as two devices

`client/` is a working client — a real HTTP transport and a durable SQLite store driving the
same `SyncEngine` the Android app will. Two `--home` directories are two devices on one
account, which is how the convergence behaviour can be watched without an emulator.

```bash
./gradlew :client:installDist
CLI=client/build/install/client/bin/client

$CLI --home /tmp/phone  register you@example.com hunter2hunter2 Phone
$CLI --home /tmp/tablet login    you@example.com hunter2hunter2 Tablet

# Works with the server switched off — writes land in SQLite and the outbox.
$CLI --home /tmp/phone add Run --target 3 --colour red
$CLI --home /tmp/phone sync
$CLI --home /tmp/tablet sync

# Now edit different fields of the same habit on each device, still offline...
$CLI --home /tmp/phone  rename 7ebce150 Jog
$CLI --home /tmp/tablet set    7ebce150 weeklyTarget 6
$CLI --home /tmp/tablet set    7ebce150 colour -        # cleared, not absent

# ...reconnect, and both devices agree, with every edit surviving.
$CLI --home /tmp/phone sync && $CLI --home /tmp/tablet sync && $CLI --home /tmp/phone sync
$CLI --home /tmp/tablet list
```

Per-row LWW would keep one device's changes and silently drop the other's. `sync` also prints
what it discarded, including whether the losing write was this device's own — see
[Losing data on purpose, visibly](#losing-data-on-purpose-visibly).

## Project status

| Milestone | State |
|---|---|
| M0 — Scaffolding, build, CI, ADRs | ✅ done |
| M1 — `sync-core` primitives (HLC, merge engine) | ✅ done — 47 tests, ~7k generated cases |
| M2 — Server domain, schema, auth | ✅ done — 24 tests against real Postgres |
| M3 — Mobile client, fully offline | 🟡 partial — `ios/` is a full native client (unbuilt: no Mac on the dev machine); `client/` proves the loop end to end; Android is still blocked on no SDK |
| M4 — Sync protocol v1 | ✅ done — 21 protocol tests on the server, 36 on the client |
| M5 — Conflict semantics & hardening | ✅ done — losses reported to the client, skew rejected, retention collecting |
| M6 — Deterministic convergence simulator | ✅ done — 15 tests; 240 seeds swept per CI run |
| M7 — Multi-device UX & observability | ⬜ |

## Build

Requires a JDK 17+ (developed against JDK 24) and Docker for the integration tests.
Gradle comes via the wrapper — no local install needed.

```bash
./gradlew build          # compile + test everything
./gradlew :sync-core:test    # fast: pure-JVM merge and HLC tests, no Docker
./gradlew :simulator:test    # fast: the convergence sweep, no Docker
./gradlew :client:installDist  # the reference client CLI
```

The Android module is intentionally absent from `settings.gradle.kts` until an SDK is
present, so `sync-core` and `server` build on any plain JDK — including CI.

The iOS client is a separate Swift package and is not part of the Gradle build. Its
library targets and their tests need only a Swift toolchain:

```bash
cd ios && swift test    # HLC, merge, codec and sync-engine suites — no simulator
```

CI runs that on a macOS runner. The app itself needs Xcode; see
[`ios/README.md`](ios/README.md).

## Design decisions

Architecture decision records live in [`docs/adr/`](docs/adr/). The load-bearing ones:

- [ADR-001 — Conflict resolution model](docs/adr/001-conflict-resolution-model.md)
- [ADR-002 — Change-log ordering](docs/adr/002-change-log-ordering.md)
- [ADR-003 — Delete-wins semantics](docs/adr/003-delete-wins-semantics.md)

ADR-002 is the one worth reading even if you skip the rest — it documents a data-loss
bug that the obvious design walks straight into.

## License

MIT
