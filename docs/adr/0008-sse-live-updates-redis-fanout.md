# 0008. Live updates: Server-Sent Events, Redis pub/sub fan-out, full snapshots

Date: 2026-11-14 · Status: Accepted

## Context
Patients, the staff console and the waiting-room TV must see queue changes within about a second
(PLAN §10). The app may run on several instances, so the browser that holds a stream is often on a
different instance from the one that handled the staff click. Patient phones go through bad
networks: connections drop and reconnect all the time.

## Decision
- **Transport: SSE, not WebSocket.** The traffic is one-way (server to browser); commands are plain
  POSTs. `EventSource` reconnects by itself, works through HTTP proxies and needs no extra protocol.
  Virtual threads are on, so many idle connections are cheap.
- **Complete snapshots, not deltas.** Every event is the full current view (token view, staff
  board, TV board). The client replaces its state. A missed or reordered message cannot corrupt
  anything, and on reconnect the server simply sends a fresh snapshot first, so `Last-Event-ID`
  needs no special handling.
- **Change signal after commit.** `EventLog.append` (called by every queue change, inside its
  transaction) publishes a Spring event `QueueChanged(queueId, queueDayId, seq)`. The `realtime`
  module handles it with `@TransactionalEventListener(AFTER_COMMIT)`, so a rolled-back change is
  never announced and a reader never sees "changed" before the data is visible. `queue` does not
  depend on `realtime`.
- **Fan-out through Redis pub/sub.** The listener publishes the sequence number on channel
  `baari:queue:{queueId}`. Every instance subscribes to the pattern `baari:queue:*`, including the
  publisher, so there is one delivery path. PLAN §10.2 names the channel per queue *day*; we use
  the queue because a stream is opened per queue (a TV or console stays connected across midnight)
  and the snapshot code finds today's day itself.
- **Coalescing.** The first change notification for a queue starts a 200 ms timer, later ones merge
  into it (highest sequence number wins), and when it fires each local connection gets one fresh
  snapshot. The key is removed from the pending map *before* the subscribers are read, so a change
  that arrives during a flush schedules a new round instead of being lost.
- **Housekeeping.** 15 s heartbeat comment, 30 min emitter timeout (the browser reconnects), cleanup
  on completion, error or failed write, gauge `baari.sse.connections{type=patient|staff|display}`.
- **Access.** The patient stream is keyed by the public code, the staff stream by the session (the
  board query does the clinic and role check), the TV stream by the display key (compared by
  SHA-256 in constant time; a wrong key and an unknown queue are both 404).
- **No htmx.** PLAN lists htmx for staff actions. Adding it would mean vendoring a third-party
  script, which needs approval; the console uses `fetch()` and small vanilla JS instead (the
  server stays the single source of truth, and the board is redrawn from the snapshot). Easy to
  swap later.

## Consequences
- Good: the whole path is tested over real HTTP with a real Redis (`StreamIT`), including a message
  published straight to Redis as another instance would.
- Good: no per-client state on the server beyond the open connection; any instance can serve any
  stream.
- Bad: Redis pub/sub is fire-and-forget. A message lost while Redis is down is repaired by the next
  change or the next reconnect. If Redis publishing fails, local connections are still notified.
- Bad: each connection rebuilds its own snapshot (a few queries per token stream). Fine for a
  clinic; for hundreds of patients on one queue the per-queue part should be cached per flush
  (follow-up for Phase 8, measure first with the k6 script).
- Follow-up: a staff session that expires while a stream is open is not noticed until the next
  push fails the access check.

## Alternatives considered
- WebSocket: two-way is not needed; more moving parts.
- Polling: simple, but a patient page would need a short interval to feel live, which multiplies
  load; SSE keeps one connection per viewer.
- Deltas with `Last-Event-ID` replay: more efficient but needs an event buffer and ordering logic
  for little gain at this size.
