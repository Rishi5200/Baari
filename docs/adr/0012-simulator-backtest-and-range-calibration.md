# 0012. Simulator, backtest and range calibration

Date: 2026-11-21 · Status: Accepted

## Context
Phase 6 (PLAN §12) needs synthetic clinic history and an honest comparison of the estimators. The
first backtest showed the projector's p20 to p80 range held the actual wait only about 45% of the
time, against a 60 to 70% target. PLAN §8.5 already describes a nightly calibration factor.

## Decisions
1. **Simulator without dependencies.** `baari-simulator` is plain Java. Its JSON config is read by a
   small hand-written reader (`Json`, about 150 lines) instead of adding Jackson or picocli to a
   module that has no Spring. Each day draws from its own `Random` seeded from `--seed` and the
   date, so a fixed seed gives byte-identical files, and adding days does not change the days
   already generated.
2. **The simulator copies the next-patient rule** (emergency first, at most 2 priority patients in
   a row) instead of depending on `baari-app`. The two must be kept in step; `NextTokenPolicy` stays
   the source of truth.
3. **The backtest is a pure program inside `baari-app`** (`com.baari.eta.Backtest`). It needs only
   the pure classes (`QueueProjector`, estimators, `NextTokenPolicy`), so it runs from
   `target/classes` with no Spring, database or clock. It replays each doctor's day at every join
   and call, logs predictions at the production triggers, and lets Rolling see only earlier days.
   The newest 14 days are scored. `docs/eta-evaluation.md` is generated, never edited.
4. **Range calibration, as PLAN §8.5 says.** `RangeCalibration.adjust` (pure) moves a per-clinic
   factor by 0.1 per night toward 60 to 70% coverage, within 1.0 to 2.5, and leaves it alone with
   fewer than 50 scored predictions. `CalibrationStore` runs nightly (02:45 UTC), measures the last
   14 days of `eta_prediction`, and stores the factor in `eta_calibration` (V6). The upsert only
   replaces a row from an earlier day, so several instances still make one step per day.
   `EtaService` reads the factor with the estimator (cached for a minute). Logged ranges already
   include the factor in use, so too-wide ranges bring it back down.
5. **No no-show allowance.** The projector can discount each patient by a no-show rate. In the
   backtest this made the error worse, because a no-show still costs the doctor a few minutes of
   waiting before the next call. `baari.eta.no-show-rate` stays 0.
6. **History import as SQL.** `sim/import-history.sql` loads the simulator's CSV into an existing
   clinic (doctors matched by name, past days only, CLOSED days, no names or phones, reruns skip
   loaded days). `sim/import-history.psql` fills its temporary tables with `\copy`;
   `ImportHistoryIT` runs the same SQL file through JDBC.

## Consequences
- In the backtest, calibrated Rolling holds the actual wait 67% of the time; the mean error is
  unchanged (7.4 min, against 8.6 for Default), because calibration only widens the range.
- All estimates still lean short, mostly at JOIN: late session starts and patients who join before
  the doctor arrives are not modelled, because the app stores no session timetable. Adding one is a
  product decision for later.
- The simulator's policy copy can drift from `NextTokenPolicy`; the backtest would then mispredict
  order, so a policy change must update both.
