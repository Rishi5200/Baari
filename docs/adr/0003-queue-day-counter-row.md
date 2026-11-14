# 0003. Per-queue-day counter row over a database sequence

Date: 2026-11-14 · Status: Accepted

## Context
Every token gets a number shown to the patient (`A-017`). At 9:00 up to a few hundred people scan
the QR code at once. The numbers must be unique, start at 1 every day for every queue, have no
gaps (a gap makes patients think they were skipped), and a retried request must get the token it
already has, not a new number. One phone may hold only one active token per queue day, and a day
may hold at most `max-open-tokens` open tokens.

## Decision
The counter lives on the `queue_day` row (`last_number`). Issuing a token is one transaction
(`TokenService`):

1. Lock the queue day row (`SELECT … FOR UPDATE`, through JPA's `PESSIMISTIC_WRITE`).
2. While holding the lock, check in order: the idempotency key (a hit returns the existing token),
   the day's status (paused/closed), an active token for the same phone hash, the open-token limit.
3. Increment `last_number`, insert the token, the `token_event` row and the outbox row.

Every other change to a day or its tokens takes the same lock first (queue day before token), so
the day's event sequence (`last_event_seq`) is also gap-free and per-day ordered.

The unique constraints (`(queue_day_id, number)`, `(queue_day_id, idempotency_key)`, and the
partial unique index on active phone hashes) stay as a backstop. If one fires anyway, the whole
transaction has rolled back, and it is run once more so the checks see the row that won.

## Consequences
- Good: numbers are unique and gap-free per queue day: a failed request rolls its increment back
  with everything else. Tested with 200 concurrent joins (exactly 1..200), 50 identical retries
  (one token) and one phone joining 20 times at once (one token, 19 × 409).
- Good: a join racing with day close is either committed before the close (and expired by it) or
  sees the day closed; never a waiting token in a closed day (tested).
- Good: because the checks run under the lock, PLAN's unlocked idempotency pre-check isn't needed
  and no number is ever burned, not even in the race PLAN said would be acceptable.
- Bad: joins to one queue are serialized. Each transaction holds the lock for a few milliseconds,
  so a queue handles hundreds of joins per second, far above a clinic's peak.
- Follow-up: the k6 test in Phase 8 checks join p95 under load and verifies gap-free numbering.

## Alternatives considered
- A Postgres `SEQUENCE`: per database, not per queue day (one per queue per day would mean
  creating sequences at runtime), and sequences leave gaps on rollback by design.
- `MAX(number) + 1` without a lock: two transactions read the same max; the unique constraint
  then fails one of them, which needs a retry loop and still races under load.
- `UPDATE queue_day SET last_number = last_number + 1 … RETURNING` as the first statement (PLAN
  §6.1's sketch): the same lock, but the number is taken before the checks, so a retry or a
  refused join would use up a number unless rolled back. Locking first and numbering last is
  simpler to reason about.
