# 0013. ML consultation-length model

Date: 2026-11-21 · Status: Accepted

## Context
Phase 7 (PLAN §8.6) adds a learned model next to the Rolling statistics. The model predicts one
consultation's length; the projector turns lengths into waits, so the queue logic and the order of
patients stay the same for every estimator.

## Decisions
1. **Tribuo 4.3.2** (`tribuo-regression-sgd`, `tribuo-regression-tree`), as listed in CLAUDE.md.
   Spring Boot manages protobuf at 4.x while Tribuo was built against 3.x; training, protobuf
   serialization and loading were checked to work, so no fallback to hand-written ridge regression
   was needed.
2. **Target and range.** Both candidates learn ln(seconds), which tames the long right tail. The
   mean is e^prediction times the mean of e^error on the training data (Duan's smearing); p20 and p80
   are e^(prediction + the 20th / 80th percentile of the log errors).
3. **Features** come from one class, `ServiceFeatures`, for training and prediction: one-hot doctor,
   visit type, priority, hour and weekday; scaled place in the session, hours into the session,
   queue length, and "first of the session". The `ServiceContext` record carries them; Rolling still
   reads only doctor, visit type and hour.
4. **Sessions without a timetable.** The app stores no session times, so `Sessions` treats a run of
   consultations with no pause longer than 90 minutes between starts as one session. Training
   (`TrainingExamples`) and live prediction (`EtaService` via the projector) use the same rule. Queue
   length at a start counts patients who had joined and were not yet called or cancelled then, so a
   feature never depends on anything after the start (tested by changing the future and checking the
   features do not move).
5. **Candidates.** `LinearSGDTrainer` (squared loss, AdaGrad 0.1, 30 epochs, seed 42) and
   `CARTRegressionTrainer` (depth 6). Fixed seeds: the same data gives the same models.
6. **Evaluation and promotion.** `MlTraining` trains on days D-60 to D-8 and tests on D-7 to D-1.
   The test days are replayed by `WaitReplay` (moved out of the backtest so both use one
   implementation). The best candidate by wait-time error becomes champion only if it beats the
   current champion, or Rolling when there is none, by at least 5%. Every candidate is stored with its
   metrics (`ml_model`, V7); a partial unique index allows one champion per clinic.
7. **Scheduling.** The job runs every hour at minute 30 and trains the clinics whose local hour is
   `baari.ml.train-hour` (2) and which have no model from today. "Train now" on `/admin/accuracy`
   runs the same code for the clinic admin's clinic.
8. **Serving and failure.** `EtaService` checks the champion's id with the estimator (once a minute)
   and loads the artifact only when the champion changes, so a restart reads it from the database.
   A model that cannot be read is logged as `ALERT`, counted in `baari.eta.ml.load_failures`, and the
   clinic keeps Rolling. `MlEstimator` also falls back per prediction if the model cannot answer.

## Consequences
- On the simulated clinic both candidates tie Rolling (wait-time error 7.4 min). The simulator draws
  each length from a lognormal around a per-doctor, per-hour median, which Rolling already captures;
  what is left is noise no feature explains. The 5% rule therefore keeps Rolling in use, as intended.
- When a clinic's consultations depend on something Rolling cannot see (in `MlTrainingIT`, length
  growing through the session), the model wins clearly and is promoted.
- Late session starts still make every estimator lean short (ADR 0012); the model cannot fix waits
  that come from the doctor not being there yet.
- `Backtest` now needs the app's dependencies (Tribuo), so it runs through `exec:java` in `baari-app`.
