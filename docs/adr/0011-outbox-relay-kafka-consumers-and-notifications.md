# 0011. Outbox relay, Kafka consumers with dedup, and notifications

Date: 2026-11-20 · Status: Accepted

## Context
Phase 5 (PLAN §11) sends queue events to Kafka and turns them into patient notifications. A queue
change must never be lost and must never be announced if it rolled back, Kafka may be down, and
Kafka delivers at least once.

## Decisions
1. **Transactional outbox, not a Kafka send inside the transaction.** A queue change and its event
   row commit together (Phase 2). Sending to Kafka inside the transaction is a dual write: either
   the send or the commit can succeed alone. With the outbox, a Kafka outage only delays delivery.
2. **`OutboxRelay`** runs every 500 ms, claims up to 100 unpublished rows with
   `FOR UPDATE SKIP LOCKED` (several instances never send the same row at once), sends each with
   the day id as key (one queue day stays in one partition, so in order), and marks it published.
   At the first failed send it stops the batch (order is kept), counts the attempt and retries next
   cycle. Rows sent more than 7 days ago are deleted nightly. Gauge `baari.outbox.lag.seconds` is
   the age of the oldest unsent row. Producer waits are short (`max.block.ms` 5 s) so a Kafka
   outage does not hold database connections for a minute.
3. **At-least-once, and consumers dedupe.** A crash after the send and before the commit sends a row
   again. Every consumer calls `ProcessedEvents.claim(consumer, eventId)` inside the same
   transaction as its side effects; the first claim inserts a `processed_event` row, a redelivery
   finds it and does nothing, and a rollback removes the claim so a retry can run again.
4. **Error policy.** `DefaultErrorHandler` retries a failing message 3 times with growing pauses,
   then publishes it to `baari.queue-events.DLT` and counts it (`baari.events.dlt`), so one bad
   message cannot block the partition. The DLT admin page stays a stretch goal.
5. **Consumers so far.**
   - `NotificationPlanner` (group `notification-planner`): reacts to every queue event. A call
     creates a CALLED notification and also re-checks positions; a closed day creates
     QUEUE_CLOSED for the tokens that expired; any other change creates NEAR_TURN for waiting
     patients at position `notifyAheadCount` (3) or better. `UNIQUE (token_id, kind, channel)` makes
     planning idempotent even without the dedup table.
   - `EtaActualsRecorder` (group `eta-actuals-recorder`): fills the actual wait on `ConsultStarted`.
     This replaces the in-process listener of ADR 0010 decision 10. The JOIN and milestone rows are
     still written by the background lane in `PredictionLog` after commit, because they need the
     projection at the moment of the change, not when Kafka delivers it.
   - Rolling statistics are still read from Postgres with a one-minute cache (ADR 0010), so no
     separate `RollingStatsUpdater` is needed; a restart or another instance cannot drift.
   - `AnalyticsAggregator` is not built; analytics is outside Phase 5.
6. **`NotificationDispatcher`** runs every 2 s, claims due `PENDING` rows with `SKIP LOCKED`, checks
   the message is still true, sends, and records the result. A "come back" message for a patient
   who is no longer waiting, or "your turn" for one who is no longer called, is `SKIPPED`. A failed
   send is retried after 30 s, 2 min, 10 min and 30 min, and the row becomes `FAILED` after 5
   attempts. The send happens while the row is locked, which is acceptable with one send per row
   and short channel timeouts; it would need a claim-then-send split at high volume.
7. **Channels** implement `NotificationChannel` (`name`, `canReach`, `send`). The planner creates a
   row for each channel that can reach the token. `LogChannel` (dev and test, setting
   `baari.notifications.log-enabled`) and an `SmsChannel` stub that reaches nobody (Indian SMS
   providers need DLT template registration). The Telegram channel is built later (its bot token
   comes from the project owner).
8. **Language.** English and Hindi texts exist (`notify.*`), but a token stores no language, so
   every message goes out in English for now. Choosing a language belongs with the Telegram link or
   the join form; left open on purpose.
9. **Test setup.** The relay test pauses the real Kafka container while rows are written, then
   resumes it; the dispatcher's schedule is turned off in notification tests so they decide when a
   batch is sent.

## Consequences
- A Kafka outage delays notifications and actual-wait records but loses nothing.
- Events can arrive twice or out of step with the database; consumers are safe against both.
- `eta` and `notification` now depend on `events` (CLAUDE.md allowed dependencies updated).
- Known limits: English only, no Telegram yet, notification sends under a row lock, and an actual
  wait could in theory arrive before its JOIN row exists (the relay delay makes this very unlikely).
