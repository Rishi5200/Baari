# 0010. ETA v1: rolling statistics, list-scheduling projection, smoothing and the prediction log

Date: 2026-11-20 · Status: Accepted

## Context
Phase 4 turns a queue position into a wait time (PLAN §8). Patients get a range, not a minute; staff
get a predicted start time; every prediction is stored with the wait that really happened so the
error can be measured (and, in Phase 6 and 7, compared with better models). PLAN §8.3 left one
choice open: EWMA statistics versus empirical quantiles of recent samples.

## Decisions
1. **Rolling statistics = empirical statistics of the newest 200 samples per group.** Mean and
   p20/p80 are computed from the clinic's last 200 finished consultations in the group, not an
   EWMA. Why: quantiles come out exactly (an EWMA of p20 and p80 is only an approximation), the
   code is a short pure class that is easy to test by hand, and no Redis state is needed. The groups
   fall back in the order PLAN §8.3 gives (doctor, visit type, hour, then wider). Consultations
   shorter than 20 s or longer than 60 min, and those closed automatically at the end of the day
   (`AUTO_CLOSED`), are not used.
2. **History is read from Postgres, not kept as running state.** `queue.ServiceHistory` returns the
   clinic's newest consultations (last 90 days, at most 5,000). `EtaService` keeps the resulting
   estimator in memory for one minute and drops it when a consultation completes. A restart or a
   second instance needs no warm-up and cannot drift.
3. **Estimator choice per clinic.** Rolling once the clinic has at least 20 usable samples overall,
   otherwise the default length (6 minutes, p20 = 0.6x, p80 = 1.5x). Inside Rolling, a patient whose
   group is too small falls back to the wider group, and finally to the default.
4. **The projector is a pure list-scheduling function** (PLAN §8.4) built on
   `NextTokenPolicy.projectOrder`, so the predicted order is the real call order. Each doctor is a
   lane; uncertainty adds up along a lane as independent normal variances (sd from the p20 to p80
   span); the range is the start time plus or minus 0.8416 sd, never below now.
5. **What Phase 4 does not model yet** (each is a named gap, not a hidden one): the session start
   and a doctor's late start (the schema has no sessions; a free doctor is assumed ready), a
   learned transition gap (fixed 30 s, `baari.eta.transition-gap-seconds`), a learned no-show rate
   (fixed 0, `baari.eta.no-show-rate`), and the nightly calibration factor of PLAN §8.5 (fixed 1.0).
   Phase 6 backtesting decides which are worth adding.
6. **Hysteresis is applied per patient, in memory, after the projection.** The previously shown range
   is aged by the time that has passed (so it counts down by itself) and replaced only by the rules
   of PLAN §8.5 (`EtaSmoother`). The state lives in the instance that serves the request. With
   several instances a patient's connection may move between them and briefly show a fresh range;
   moving this state to Redis is a Phase 8 option if measured jitter justifies it.
7. **Projections are cached** per queue while the day's event sequence number is unchanged and the
   projection is under 10 seconds old, so many open token pages cost one projection per change.
8. **ETA is added above the queue module.** `queue` must not depend on `eta`, so
   `eta.EtaService` calls `queue` read models (`QueueLiveState`, `TokenLocator`, `ServiceHistory`) and
   wraps `PublicTokenView` and `Board` in `PatientTokenView` and `StaffBoardView` (`@JsonUnwrapped`,
   so existing JSON fields stay where they were). The token page, the patient stream and the staff
   stream use them. The plain REST `GET /api/public/tokens/{code}` still answers without an ETA.
9. **Prediction log.** `eta_prediction` (migration V4) gets a row at JOIN and when a patient who
   started further back first reaches position 10, 5, 3 or 1 (a patient who joins at position 3 never
   "reaches" 5). `UNIQUE (token_id, trigger)` plus `ON CONFLICT DO NOTHING` keeps it once however
   many instances see the change. The row stores the model's raw range, not the smoothed one shown
   to the patient, so it measures the model. When the consultation starts every open row of the token
   gets `actual_wait_s`, and the metrics `baari.eta.abs_error.seconds` (tags estimator, trigger) and
   `baari.eta.in_range` (tag hit) are updated. A patient who was called, did not show up and was
   recalled has an actual that includes that detour; the accuracy report in Phase 6 will exclude
   these, as PLAN §8.7 asks.
10. **Domain events are also published in-process, and the prediction log runs in the
    background.** `EventLog.publish` now also publishes the event as a Spring application event.
    The ETA listeners only note the change after commit; a per-queue-day lane on a virtual thread
    does the work, one run at a time, merging changes that arrive meanwhile into one more run, and
    swallowing and logging its own failures so a problem in the log can never undo a queue action.
    Why not do it on the request thread: a listener that writes after commit needs a second
    database connection while the first is still held. Under 200 concurrent joins this starved the
    connection pool (the concurrency test timed out at the 30 s pool limit), and it would also add
    latency to every call-next. Consequence of merging: if several changes land within one run, a
    milestone may be logged with the position seen at that run (for example POSITION_10 and
    POSITION_5 together at position 4). A patient who is called within milliseconds of joining may
    get no JOIN row. In Phase 5 this moves to Kafka consumers with `processed_event` dedup.

## Consequences
- Wait estimates work from the first day (default) and improve by themselves after about 20
  consultations. Nothing needs to be configured.
- The pure classes are under the 100% branch-coverage rule next to `NextTokenPolicy`.
- Every estimate shown can later be judged against what happened, which is what Phase 6 and 7 need.
- Known limits: single-instance smoothing state (6), no session or lateness model (5).
