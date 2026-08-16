# habit-sync — iOS client

A native SwiftUI client for the same account, the same protocol, and the same conflict
rules as the reference JVM client. Two devices here — or one here and one `client/` CLI
pointed at a second `--home` directory — are two replicas on one account, which is the
thing worth watching.

## Why the sync layer is ported rather than wrapped

There is no shared binary between this and `sync-core`. The merge rule therefore exists
twice, which is a real cost, and the alternative was worse: shipping a JVM to a phone, or
putting the resolution logic behind an RPC and giving up offline-first entirely.

What makes two copies safe is that neither is free to drift. `HabitSyncCore` is a
line-by-line port, its tests are the JVM suite's tests, and the wire format is asserted as
bytes rather than as a round trip. The three things that would silently diverge if
translated carelessly are called out in the source:

- **The `nodeId` tiebreak** in `Hlc`'s total order. Drop it and two writes in the same
  millisecond are unordered, so two replicas can pick different winners and never converge.
- **The logical counter's 32-bit bound.** The JVM parses that component with
  `Integer.parseInt`; a wider value would encode here and fail to decode there.
- **Null versus absent field values.** `dict[key] = nil` *removes* a key in Swift, so the
  naive translation turns "clear this field" into "this change does not touch it" — an
  edit that syncs perfectly and never appears. `WireChange` writes its own `Codable` for
  exactly this reason.

## Layout

```
Sources/HabitSyncCore/     The port of sync-core. Foundation only: no SQLite, no UIKit,
                           no network. HLC, merge engine, wire codec, SyncEngine, and the
                           LocalStore / Transport seams.
Sources/HabitSyncClient/   The port of client/: SQLite store, URLSession transport,
                           Keychain session, and the habit/completion projections.
Sources/HabitSyncUI/       The screens, plus the observable model they share.
App/                       The app shell: one WindowGroup and the background refresh
                           registration. Deliberately almost empty.
Tests/                     The JVM suite's assertions in Swift.
```

`HabitSyncCore` has no I/O by design, which is what lets the merge and convergence tests
run on a plain toolchain with no simulator — `swift test` from a terminal is the whole
story, and it is what CI runs.

## Building

The libraries and their tests need nothing but a Swift toolchain:

```bash
cd ios
swift build
swift test
```

The app needs Xcode. The `.xcodeproj` is not checked in — it is a generated artefact that
conflicts on every merge and bakes in absolute paths and a personal team id — so generate
it from the spec:

```bash
brew install xcodegen
cd ios && xcodegen generate && open HabitSync.xcodeproj
```

Set your own signing team on the target before running on a device.

## Pointing it at a server

The server address is a field on the sign-in screen rather than a build setting, because
running the phone against a laptop is the interesting case and a rebuild is a bad way to
get there.

- **Simulator:** `http://localhost:8080` reaches a server on the same Mac.
- **Device:** use the Mac's address on the network, e.g. `http://192.168.1.20:8080`.
  `NSAllowsLocalNetworking` in `App/Info.plist` permits cleartext to the local network and
  nothing else; a shipping build points at an HTTPS host and needs none of it.

The server refuses to start without a 32-byte `JWT_SECRET`, by design. Registration wants
a password of at least 12 characters — length only, no composition rules.

## What the app does

Everything the reference CLI does, in the same field names, so an account edited from both
converges: create a habit with a name, weekly target and colour; rename it; write or
**clear** any field; tombstone it; restore it; log and remove completions; sync; and read
the device's watermark, outbox depth and clock.

Two behaviours are less obvious and are the point of the project:

- **Every write is local first.** Adding a habit on a plane is durable before the aircraft
  doors close, sits in an outbox, and reaches the server whenever there is a network. No
  screen in the app waits on a request except the one the user pulled to refresh.
- **Losses are shown.** Last-writer-wins discards work; a tombstone hides concurrent
  edits. ADR-001 and ADR-003 accept both *on the condition that the loss is surfaced*, so
  a sync that overwrote something you typed here puts up a notice you have to acknowledge,
  and the sync screen lists the rest.

## Notes for the next person

- Invariants are asserted with `precondition`, not `assert`, so they survive into release
  builds. The failures they catch — a record whose fields and clocks disagree, a node id
  containing the HLC separator — corrupt replicated state, and continuing past one would
  push the corruption to every other device on the account. Untrusted input from the
  network is checked and *thrown* instead, in `ChangeCodec`.
- Local edits run inline on the main actor. One small SQLite transaction, with the user
  waiting for it; the alternative is an optimistic in-memory copy that can disagree with
  the database, which is a bug factory in an app where a background sync can change any
  register at any moment.
- Background refresh builds its own store and session rather than sharing the app's. It
  can, because everything it needs is already durable — the session in the Keychain, the
  outbox and watermark in SQLite — which is what lets the system launch the app cold for a
  wake. A wake killed halfway costs one re-pushed page and no data.
- Workout entities (`EXERCISE`, `WORKOUT_SESSION`, `WORKOUT_SET`) exist in the protocol and
  replicate correctly, but no client has a UI for them yet — here or on the JVM side.
