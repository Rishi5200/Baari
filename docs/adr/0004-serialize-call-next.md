# 0004. Serialize call-next with a row lock (not SKIP LOCKED)

Date: 2026-11-14 · Status: Accepted

## Context
In a POOL queue several doctors call patients from the same line. Two doctors pressing "Call
next" at the same moment must never get the same patient, and the order must follow
`NextTokenPolicy` exactly (EMERGENCY first, at most N PRIORITY in a row, then NORMAL). The policy
needs the whole waiting list and the `consecutive_priority_served` counter stored on the day.

## Decision
`TokenActionService.callNext` locks the queue day row (`FOR UPDATE`), loads all WAITING tokens,
runs `NextTokenPolicy.next`, marks the chosen token CALLED, stores the new policy state on the day
and writes the event and outbox rows, all in one transaction. Every other token action (start,
complete, no-show, recall, cancel, priority) takes the same day lock first and loads the token
only after it, so all changes to one queue day happen one at a time, always locking the day
before the token (no deadlocks). `@Version` on the token remains as a safety net (409).

## Consequences
- Good: two doctors can never pick the same patient, and the policy always sees a consistent
  queue (tested: two doctors racing for one waiting patient, repeated 5×: one gets the patient,
  the other "no one waiting").
- Good: conflicting actions on one token (start vs no-show, a double-clicked complete) resolve
  cleanly: the second sees the new status and gets 409 (tested).
- Good: one rule for everything ("lock the day first") is easy to explain and review.
- Bad: call-next on one queue is serialized. Clinics call a few patients per minute per queue,
  and the lock is held for milliseconds, so this costs nothing in practice.

## Alternatives considered
- `SELECT … FOR UPDATE SKIP LOCKED` on token rows: each doctor would grab "some" unlocked waiting
  token, which breaks the priority order and the PRIORITY-in-a-row counter (two doctors would
  each see a different subset). It fits work queues where order doesn't matter, such as the
  outbox relay (ADR 0005), not a patient line.
- Optimistic locking only (`@Version` on tokens): both doctors would pick the same "next" token
  and one would fail with a conflict and have to retry; the counter on the day could still be
  updated twice. Correct with retries, but noisier and harder to reason about.
- Serializable isolation: Postgres would abort one of the transactions, again needing retries
  for an outcome the row lock gives directly.
