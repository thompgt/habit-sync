# ADR-002 — Change-log ordering and the watermark data-loss trap

**Status:** Accepted
**Date:** 2026-07-25

> If you read one ADR in this repo, read this one. The obvious design silently loses
> writes, and it does so rarely enough that testing usually misses it.

## Context

Clients sync incrementally. Each device stores a **watermark** — the highest change it
has seen — and asks the server "give me everything after this". The server therefore
needs a per-user change log with a monotonic cursor.

The obvious implementation:

```sql
CREATE TABLE change_log (
  server_seq  BIGSERIAL PRIMARY KEY,
  user_id     UUID NOT NULL,
  ...
);
-- pull:
SELECT * FROM change_log WHERE user_id = ? AND server_seq > ? ORDER BY server_seq;
```

**This loses data.**

## The bug

`BIGSERIAL` values are allocated from a sequence at `INSERT` time, but rows become
visible to other transactions at `COMMIT` time. Those orders are not the same.

```
T1: BEGIN → INSERT (gets server_seq = 5) ────────────────────┐ slow, commits second
T2:            BEGIN → INSERT (gets server_seq = 6) → COMMIT ┘ fast, commits first

                    ▲
                    │  Device pulls HERE.
                    │  Sees seq 6. Does not see seq 5 — T1 hasn't committed.
                    │  Advances its watermark to 6.
                    ▼
T1 commits. Change 5 is now visible... but every device is already past 6.
Change 5 is never delivered to anyone. Silently. Forever.
```

Sequences are also non-transactional — a rolled-back insert burns its number, leaving
permanent gaps — so "wait for the gap to fill" is not a workable client-side fix
either.

This is not exotic. It is the default outcome of the most natural schema, and it
surfaces as "one of my workouts didn't sync" months later, with no error anywhere.

## Options considered

### A. Commit-timestamp / snapshot watermarks (`pg_current_snapshot()`, `xmin`)
Track a horizon below which all transactions are known committed, and only serve
changes below it. Correct and used by production CDC systems, but it means a client's
watermark is a *snapshot*, not a scalar; it needs `xmin`/`xmax`/`xip_list` handling and
careful reasoning about long-running transactions. Substantial machinery.

### B. Serialize the whole push endpoint
A global lock. Correct, trivially. Rejected: destroys throughput across all users for
a problem that is per-user.

### C. Per-user sequence allocated under a row lock  ← **chosen**

## Decision

Give each user their own counter row and allocate the sequence **inside the same
transaction as the change insert, under a row-level lock**:

```sql
CREATE TABLE user_sync_counter (
  user_id  UUID PRIMARY KEY REFERENCES app_user(id),
  next_seq BIGINT NOT NULL DEFAULT 1
);
```

```sql
BEGIN;
  -- Serializes all concurrent pushes for THIS user, and only this user.
  SELECT next_seq FROM user_sync_counter WHERE user_id = ? FOR UPDATE;
  UPDATE user_sync_counter SET next_seq = next_seq + :n WHERE user_id = ?;

  INSERT INTO change_log (user_id, server_seq, ...) VALUES ...;  -- uses reserved range
COMMIT;
```

Because every writer for a given user holds that user's counter row from allocation
until commit, **no two of that user's transactions can be in flight simultaneously**.
Sequence order therefore equals commit order, and `server_seq > watermark` is sound.

Contention is a non-issue: the lock is scoped to one user's two or three devices, not
to the system. Different users never block each other.

### Invariants this must uphold

1. The `SELECT ... FOR UPDATE` and the `INSERT` are in **one** transaction. Splitting
   them reintroduces the bug in full.
2. `server_seq` is assigned **only** here. No other code path writes `change_log`.
3. Clients advance their watermark only after a full page is durably applied — see
   the client-side mirror of this bug below.

## The client-side mirror

The same bug has a client-side twin. When a pull returns 500 changes and the client
applies them one at a time, updating the watermark as it goes, a crash halfway through
leaves the watermark ahead of the state actually persisted. The skipped changes are
never re-requested.

**Rule: one page = one transaction.** Apply every change in the page *and* write the
new watermark in a single atomic commit (one Room transaction on Android, one SQL
transaction on the server). Either the whole page lands or none of it does.

## Consequences

**Good**
- Watermarks are a single `BIGINT`. Client logic stays trivial.
- No cross-user contention.
- Ordinary SQL — no reliance on Postgres snapshot internals.

**Bad / accepted**
- Pushes for one user serialize. Correct, and irrelevant at 2–3 devices per user.
- One extra locked row-write per push.
- A wedged transaction blocks that user's other devices until it times out. Mitigated
  by the 5 s Hikari `connection-timeout` in `application.yaml`, which turns it into a
  fast retryable failure instead of a hang.

## Verification

A test must hammer concurrent pushes from many threads for a **single** user while a
reader continuously pulls, and assert the reader observes a strictly increasing,
**gapless** sequence — never skipping a value that later appears. Run it against a real
Postgres via Testcontainers; H2 will not reproduce the failure.
