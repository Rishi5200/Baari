# 0005. Transactional outbox for Kafka

Date: 2026-11-14 · Status: Accepted

## Context
Queue changes (joined, called, completed, day closed, …) must reach other parts of the system:
notifications, ETA actuals, rolling statistics, analytics (PLAN §11). These run as Kafka
consumers. A change and its event must never disagree: an event for a change that rolled back
would notify a patient who was never called, and a committed change without its event would
leave a patient unnotified or skew the statistics.

## Decision
Every queue change writes its domain event (`QueueEvent` records, PLAN §11.1) to the
`outbox_event` table in the same database transaction as the change itself (`EventLog.publish`).
The row's id is the event id, the aggregate is the queue day, and the partition key is the queue
day id, so Kafka keeps the events of one queue day in order. In Phase 5 an `OutboxRelay` reads
unpublished rows with `FOR UPDATE SKIP LOCKED` (safe on several instances), sends them to
`baari.queue-events`, waits for the ack and marks them published. Delivery is at-least-once, so
consumers dedupe on the event id (`processed_event`).

Phase 2 writes the outbox rows; nothing relays them yet.

## Consequences
- Good: no dual write. The event exists if and only if the change committed.
- Good: Kafka being down doesn't block or fail patient joins; events wait in the table and go out
  when it is back (Phase 5 tests this by pausing the Kafka container).
- Good: events carry ids, numbers and statuses only, never names or phone numbers, so nothing in
  Kafka escapes the PII retention purge.
- Bad: events reach consumers with a delay (the relay polls every 500 ms) and may arrive twice;
  consumers must be idempotent.
- Bad: the outbox table grows; a cleanup job deletes published rows after 7 days (Phase 5), and
  the lag gauge `baari.outbox.lag.seconds` shows if the relay falls behind.

## Alternatives considered
- Publishing to Kafka inside the transaction: if the commit fails after the send, the event is
  out for a change that never happened; if the send fails after the commit, it is lost. Kafka
  transactions don't span the Postgres transaction.
- Publishing after commit (`@TransactionalEventListener(AFTER_COMMIT)`): a crash between the
  commit and the send loses the event silently.
- Change data capture (Debezium reading the WAL): no dual write either, but it adds Kafka Connect
  and Debezium to run and explain, which is too much for a single-VM deployment.
