# Baari — Detailed Plan & Specification

This is the source of truth for product behaviour, data model, algorithms, and the build
order. `CLAUDE.md` holds the working rules. When this document and the code disagree, raise
it with the user. Don't silently pick one.

Contents
1. Problem & goals
2. Users, screens, and user journeys
3. Domain model
4. Token lifecycle & state machine
5. Queue ordering (NextTokenPolicy)
6. Concurrency design
7. Data model (Flyway DDL)
8. ETA: estimation, projection, ranges, ML
9. Security, privacy, abuse prevention
10. Real-time updates
11. Events, outbox, Kafka, notifications
12. Phases, tasks, and acceptance criteria
13. ADR template & initial ADR list
14. Configuration reference
15. Interview talking points (why each decision)

---

## 1. Problem & goals

**Problem.** In Indian OPDs and small clinics, patients often wait 1–2 hours for a
consultation of about 5 minutes, with no information about when they'll be seen. Receptionists
field constant "how many more?" questions. Crowded waiting rooms spread infection.
Government QR "Scan & Share" registration cut the *registration* queue, but the long wait to
see the doctor remains.

**Goal.** A multi-tenant web app that:
- lets a patient join a clinic queue from their phone (QR, no app install, no login),
- shows their live position and an **honest predicted wait range**,
- notifies them when their turn approaches,
- gives reception and doctors a fast console to run the queue,
- learns from each clinic's own data to make predictions better over time,
- **publishes its own prediction accuracy** (actual vs predicted).

**Non-goals (do not build):** electronic medical records, prescriptions, payments, insurance,
appointment slots booked days ahead (stretch at most), native mobile apps, video consults,
ABDM/ABHA integration (mention as future work only).

**Success metrics** (shown in README and Grafana):
- ETA mean absolute error (MAE) and % of patients whose actual wait fell inside the shown range
- p95 latency of join and call-next endpoints
- zero duplicate/skipped token numbers under concurrent load (proven by tests)
- in the pilot: number of patients served, receptionist feedback

---

## 2. Users, screens, and user journeys

### 2.1 Roles
| Role | Who | Can |
|---|---|---|
| `PATIENT` (anonymous) | Anyone with the QR / a token link | Join queue, view own token, cancel own token, link Telegram |
| `RECEPTIONIST` | Clinic front desk | Open/pause/close day, issue walk-in tokens, call next, mark no-show, recall, set priority, cancel |
| `DOCTOR` | Doctor | Everything reception can do for their own queue(s); start/complete consultation |
| `CLINIC_ADMIN` | Clinic owner | Manage doctors, queues, staff, settings; view analytics |
| `PLATFORM_ADMIN` | You | Create clinics; view platform health |
| `DISPLAY` | TV screen | Read-only display stream via a per-queue display key |

### 2.2 Screens (Thymeleaf + htmx; mobile-first; English + Hindi via `messages_hi.properties`)
1. **Join page** `/j/{clinicSlug}/{queueSlug}`: clinic name, doctor, current queue length,
   current typical wait; form with name (required), phone (optional but needed for SMS),
   visit type (New / Follow-up), consent checkbox with a short privacy notice. Submit → token page.
2. **Token page** `/t/{publicCode}`: big display code (`A-017`), status, position ("5 ahead
   of you"), ETA range ("about 20–30 min"), "Now serving A-012", buttons: Cancel, Get Telegram
   alerts. Updates live via SSE. When status becomes `CALLED`: full-screen "It's your turn,
   go to Room 2", vibration (`navigator.vibrate`) and sound if allowed.
3. **Staff login** `/login`.
4. **Queue console** `/staff/queues/{queueId}`: today's list (waiting / called / in consult /
   done / no-show), big buttons: **Call next**, Start, Complete, No-show, Recall, Priority,
   Add walk-in. Shows each waiting patient's predicted start time. Keyboard shortcuts
   (`N` = call next, `C` = complete). Updates live via SSE.
5. **TV display** `/display/{queueId}?key=…`: "Now serving A-012 → Room 2", next 5 display
   codes (no names), rotating QR code for joining (if ROTATING mode), typical wait.
6. **Clinic admin** `/staff/admin/...`: doctors, queues (slug, mode SINGLE/POOL, QR mode,
   settings), staff users, print QR poster (PDF/HTML print view).
7. **Analytics** `/staff/analytics`: per day: patients served, median/p90 wait, no-show rate,
   ETA accuracy, busiest hours heatmap.

### 2.3 Key journeys (write an integration test for each)
- **J1 Patient joins & is served:** scan → join → WAITING (position 4, ETA 15–25) → others
  complete → "3 ahead" notification → CALLED → IN_CONSULTATION → COMPLETED.
- **J2 Walk-in without a phone:** receptionist issues token for "Ramesh" → prints/says `A-018`.
- **J3 No-show & recall:** called patient absent for 3 min → reception marks NO_SHOW → patient
  arrives → Recall → re-inserted after 2 waiting patients (configurable).
- **J4 Emergency:** reception marks a waiting token EMERGENCY → it's next on call-next.
- **J5 Double submit:** patient taps "Join" twice / flaky network retry → same token returned.
- **J6 Day close:** reception closes day → remaining WAITING/CALLED tokens → EXPIRED; patients
  see "Queue closed for today".
- **J7 Pool queue:** 2 doctors share one queue; each "call next" assigns the next patient to the
  calling doctor; projections account for 2 servers.

---

## 3. Domain model

```
Clinic 1─* Doctor
Clinic 1─* StaffUser (role, optional doctorId)
Clinic 1─* ServiceQueue (mode SINGLE|POOL, qrMode STATIC|ROTATING, settings)
ServiceQueue *─* Doctor   (queue_doctor; SINGLE queues have exactly one)
ServiceQueue 1─* QueueDay (unique per service_date)
QueueDay 1─* Token
Token 1─* TokenEvent      (append-only audit + SSE sequence)
Token 1─* EtaPrediction   (prediction log for accuracy measurement)
Token 1─* Notification
Clinic 1─* MlModel        (trained ETA models, champion/challenger)
```

Value objects (records): `DisplayCode` (prefix + number, formats `A-017`), `PublicCode`,
`PhoneNumber` (E.164, Indian default +91), `EtaRange(minSeconds, maxSeconds, pointSeconds, modelVersion)`.

Clinic settings (JSONB or columns, with defaults in §14): `timezone`, `defaultServiceMinutes`,
`noShowGraceMinutes`, `recallInsertOffset`, `maxConsecutivePriority`, `notifyAheadCount`,
`piiRetentionDays`, `displayCodePrefix` per queue.

---

## 4. Token lifecycle & state machine

```
                 ┌──────────── cancel ─────────────┐
                 │                                  ▼
 [join] → WAITING ──call──► CALLED ──start──► IN_CONSULTATION ──complete──► COMPLETED
            ▲  │              │  │
            │  └─ cancel ─►   │  └─ cancel ─► CANCELLED
            │                 └─ noShow ─► NO_SHOW ──recall──┐
            └────────────────────────────────────────────────┘
 day close: WAITING | CALLED | NO_SHOW → EXPIRED
 IN_CONSULTATION at day close → COMPLETED with completed_at = close time, flagged `auto_closed`
```

Rules (enforced in `TokenStateMachine`, a pure class: `Transition apply(TokenState, Command)`
returning either a new state or an `IllegalTransition`):
- `call` only from WAITING, and only via `CallNext` (or explicit `CallSpecific` by staff,
  which is allowed but logged as an override).
- `start` from CALLED. (Optionally allow WAITING→IN_CONSULTATION directly for "walked straight
  in"; it's logged as `direct_start`.)
- `complete` from IN_CONSULTATION.
- `noShow` from CALLED only, and only after `noShowGraceMinutes` since `called_at` (staff can
  force with `force=true`, logged).
- `recall` from NO_SHOW only, same day only.
- `cancel` by patient: from WAITING or CALLED. By staff: from WAITING, CALLED, NO_SHOW.
- `setPriority` allowed in WAITING only.
- Every transition appends a `token_event` row (type, actor, at, seq) in the same transaction
  and writes an outbox event (§11).

Timestamps captured on Token: `joined_at`, `called_at`, `consult_started_at`, `completed_at`,
`cancelled_at`, plus `served_by_doctor_id`. These are the training data, so get them right.

---

## 5. Queue ordering (NextTokenPolicy)

One pure class decides who is next. It is used by:
1. the `call-next` command (real decision), and
2. `QueueProjector` (simulate repeated calls to get each waiting patient's position and ETA).

### Input
`QueueSnapshot`: list of `TokenView(id, number, priority, status, sortKey, joinedAt)`,
plus `PolicyState(consecutivePriorityServed)` and `PolicySettings(maxConsecutivePriority)`.

### Algorithm
```
candidates = tokens where status == WAITING
if any EMERGENCY in candidates: return the EMERGENCY with smallest sortKey
priority = PRIORITY candidates ordered by sortKey
normal   = NORMAL candidates ordered by sortKey
if priority non-empty and (normal empty or state.consecutivePriorityServed < maxConsecutivePriority):
    return priority.first          (state.consecutivePriorityServed += 1)
if normal non-empty:
    return normal.first            (state.consecutivePriorityServed = 0)
return none
```
`sortKey` is a `long`: normally `number * 1000`. On **recall**, the token gets
`sortKey = sortKey of the (recallInsertOffset)-th NORMAL waiting token + 1` (or end of queue if
fewer), so it's re-inserted after N people. Using gaps of 1000 avoids renumbering.

`consecutivePriorityServed` is persisted on `queue_day` and updated on each real call.

### Required unit tests (examples, not exhaustive)
- FIFO among NORMAL; EMERGENCY always first; PRIORITY capped at N in a row then one NORMAL.
- PRIORITY served when NORMAL list is empty regardless of cap.
- Recall inserts after exactly `recallInsertOffset` normals; at end if fewer exist.
- Projection order == order produced by calling `next` repeatedly on a copy.
- Property-style test: for 1,000 random queues, every WAITING token appears exactly once in the projection.

---

## 6. Concurrency design

### 6.1 Token issuance (the main concurrency story)
Requirement: 200 people scanning at 9:00 get **unique, gap-free, increasing** numbers; a retried
request returns the same token; one phone holds at most one active token per queue-day.

Single transaction:
```sql
-- 1) atomically allocate a number (row lock held until commit)
UPDATE queue_day
   SET last_number = last_number + 1,
       last_event_seq = last_event_seq + 1
 WHERE id = :queueDayId AND status = 'OPEN'
RETURNING last_number, last_event_seq;
-- 0 rows → QueueClosed / QueuePaused

-- 2) insert token
INSERT INTO token (...) VALUES (...);
-- unique (queue_day_id, idempotency_key)            → retry returns existing token
-- partial unique (queue_day_id, phone_hash) WHERE status IN ('WAITING','CALLED','IN_CONSULTATION','NO_SHOW')
--                                                    → 409 already-in-queue. Do NOT return the other token's
--                                                      publicCode (anyone knowing a phone number could hijack it);
--                                                      return its display code only, e.g. "You already hold A-012".

-- 3) insert token_event + outbox row
```
**Idempotency.** The join form includes a hidden `idempotencyKey` (UUID generated when the page
renders); the API also accepts an `Idempotency-Key` header. Do a pre-check
`SELECT … WHERE queue_day_id=? AND idempotency_key=?` before step 1, so a retry doesn't burn a
number. The unique constraint is the backstop for a race between two identical requests: catch
the violation, roll back, and return the existing token. (A burned number in that rare race is
acceptable; document it in an ADR.)

**Why not a Postgres SEQUENCE?** Sequences are per-database, not per queue-day, and they leave
gaps on rollback. The counter row gives per-day numbering and makes the lock explicit. Put this
in an ADR.

### 6.2 Call-next
```sql
SELECT ... FROM queue_day WHERE id = :id FOR UPDATE;   -- serialize call-next per queue-day
-- load WAITING tokens, run NextTokenPolicy, update chosen token → CALLED, set called_by_doctor,
-- update consecutive_priority_served, last_event_seq; insert event + outbox
```
Serializing per queue-day is correct and cheap at clinic scale (a few calls per minute). We
**don't** use `SKIP LOCKED` here because the policy needs a consistent view of the whole queue;
two doctors in a POOL must not both pick the same patient, and they must respect priority order.
Write this reasoning in an ADR. (We do use `SKIP LOCKED` for the outbox relay, §11.)

### 6.3 Other transitions
Load token with `@Version`; apply state machine; save. A concurrent double-click raises
`OptimisticLockException` → 409 `concurrent-modification`. Each of these also takes the
`queue_day` row lock briefly to bump `last_event_seq` (keeps SSE ordering simple).
Always lock in the same order (**queue_day first, then token**) to avoid deadlocks.

### 6.4 Required concurrency tests (Testcontainers Postgres, real threads)
1. 200 concurrent joins (virtual threads + `CountDownLatch` start gate) → 200 tokens, numbers
   exactly 1..200, no duplicates.
2. 50 concurrent requests with the **same** idempotency key → exactly 1 token.
3. Same phone, different idempotency keys, concurrent → exactly 1 active token, others get 409.
4. Two staff call-next at the same instant on a POOL queue with 1 waiting patient → one succeeds
   with the patient, the other gets "no one waiting". Never both.
5. Complete and no-show at the same time on one token → one wins, the other gets 409.
6. Join racing with day close → token is either created before close (then EXPIRED by close) or
   rejected. Never left WAITING in a CLOSED day.

---

## 7. Data model (Flyway DDL sketch)

Write as `V1__clinic.sql`, `V2__queue.sql`, `V3__events_outbox.sql`, `V4__eta.sql`,
`V5__notifications.sql`, … Adjust types as needed but keep constraints.

```sql
CREATE TABLE clinic (
  id uuid PRIMARY KEY,
  slug text NOT NULL UNIQUE,
  name text NOT NULL,
  timezone text NOT NULL DEFAULT 'Asia/Kolkata',
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL
);

CREATE TABLE doctor (
  id uuid PRIMARY KEY,
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  name text NOT NULL,
  room_label text,
  active boolean NOT NULL DEFAULT true
);
CREATE INDEX ON doctor (clinic_id);

CREATE TABLE staff_user (
  id uuid PRIMARY KEY,
  clinic_id uuid REFERENCES clinic(id),          -- null only for PLATFORM_ADMIN
  username text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  role text NOT NULL CHECK (role IN ('PLATFORM_ADMIN','CLINIC_ADMIN','DOCTOR','RECEPTIONIST')),
  doctor_id uuid REFERENCES doctor(id),
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL
);

CREATE TABLE service_queue (
  id uuid PRIMARY KEY,
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  slug text NOT NULL,
  name text NOT NULL,
  mode text NOT NULL CHECK (mode IN ('SINGLE','POOL')),
  qr_mode text NOT NULL DEFAULT 'STATIC' CHECK (qr_mode IN ('STATIC','ROTATING')),
  display_code_prefix text NOT NULL DEFAULT 'A',
  display_key_hash text NOT NULL,                  -- for TV display access
  qr_secret bytea NOT NULL,                        -- HMAC key for rotating QR
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (clinic_id, slug)
);

CREATE TABLE queue_doctor (
  queue_id uuid NOT NULL REFERENCES service_queue(id),
  doctor_id uuid NOT NULL REFERENCES doctor(id),
  PRIMARY KEY (queue_id, doctor_id)
);

CREATE TABLE queue_day (
  id uuid PRIMARY KEY,
  queue_id uuid NOT NULL REFERENCES service_queue(id),
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  service_date date NOT NULL,
  status text NOT NULL CHECK (status IN ('OPEN','PAUSED','CLOSED')),
  last_number int NOT NULL DEFAULT 0,
  last_event_seq bigint NOT NULL DEFAULT 0,
  consecutive_priority_served int NOT NULL DEFAULT 0,
  opened_at timestamptz NOT NULL,
  closed_at timestamptz,
  UNIQUE (queue_id, service_date)
);

CREATE TABLE token (
  id uuid PRIMARY KEY,
  queue_day_id uuid NOT NULL REFERENCES queue_day(id),
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  number int NOT NULL,
  sort_key bigint NOT NULL,
  public_code text NOT NULL UNIQUE,
  patient_name text,                    -- nullable: purged after retention
  phone_enc bytea,                      -- AES-GCM ciphertext; nullable
  phone_hash bytea,                     -- HMAC-SHA256(pepper, E.164); nullable
  phone_last4 text,
  visit_type text NOT NULL CHECK (visit_type IN ('NEW','FOLLOW_UP')),
  priority text NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','PRIORITY','EMERGENCY')),
  status text NOT NULL,
  source text NOT NULL CHECK (source IN ('QR','RECEPTION')),
  consent_version text,                 -- privacy notice version accepted (null for reception walk-ins)
  idempotency_key uuid,
  joined_at timestamptz NOT NULL,
  called_at timestamptz,
  consult_started_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  served_by_doctor_id uuid REFERENCES doctor(id),
  flags text[] NOT NULL DEFAULT '{}',   -- direct_start, auto_closed, forced_no_show, override_call
  pii_purged_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (queue_day_id, number),
  UNIQUE (queue_day_id, idempotency_key)
);
CREATE UNIQUE INDEX token_one_active_per_phone
  ON token (queue_day_id, phone_hash)
  WHERE phone_hash IS NOT NULL AND status IN ('WAITING','CALLED','IN_CONSULTATION','NO_SHOW');
CREATE INDEX token_queue_status ON token (queue_day_id, status, sort_key);

CREATE TABLE token_event (
  id uuid PRIMARY KEY,
  token_id uuid NOT NULL REFERENCES token(id),
  queue_day_id uuid NOT NULL REFERENCES queue_day(id),
  seq bigint NOT NULL,
  type text NOT NULL,
  actor_type text NOT NULL CHECK (actor_type IN ('PATIENT','STAFF','SYSTEM')),
  actor_id uuid,
  at timestamptz NOT NULL,
  data jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (queue_day_id, seq)
);

CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,
  aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL,
  partition_key text NOT NULL,          -- queue_day_id → per-queue ordering in Kafka
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  published_at timestamptz,
  attempts int NOT NULL DEFAULT 0,
  last_error text
);
CREATE INDEX outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (
  consumer text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL,
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE eta_prediction (
  id uuid PRIMARY KEY,
  token_id uuid NOT NULL REFERENCES token(id),
  clinic_id uuid NOT NULL,
  predicted_at timestamptz NOT NULL,
  trigger text NOT NULL,                -- JOIN, POSITION_10, POSITION_5, POSITION_3, POSITION_1
  position int NOT NULL,
  predicted_wait_s int NOT NULL,
  range_min_s int NOT NULL,
  range_max_s int NOT NULL,
  estimator text NOT NULL,              -- DEFAULT, ROLLING, ML:<version>
  actual_wait_s int                     -- filled when consult starts
);
CREATE INDEX ON eta_prediction (clinic_id, predicted_at);

CREATE TABLE ml_model (
  id uuid PRIMARY KEY,
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  version int NOT NULL,
  kind text NOT NULL,                   -- SERVICE_TIME
  algorithm text NOT NULL,
  trained_at timestamptz NOT NULL,
  train_from date NOT NULL, train_to date NOT NULL,
  metrics jsonb NOT NULL,               -- mae, p90_ae, baseline_mae, n_train, n_test
  status text NOT NULL CHECK (status IN ('CANDIDATE','CHAMPION','RETIRED','REJECTED')),
  artifact bytea NOT NULL,              -- Tribuo protobuf (or ridge coefficients JSON)
  UNIQUE (clinic_id, kind, version)
);

CREATE TABLE notification (
  id uuid PRIMARY KEY,
  token_id uuid NOT NULL REFERENCES token(id),
  kind text NOT NULL,                   -- JOINED, NEAR_TURN, CALLED, QUEUE_CLOSED
  channel text NOT NULL,                -- LOG, TELEGRAM, SMS
  status text NOT NULL CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
  attempts int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  last_error text,
  created_at timestamptz NOT NULL,
  sent_at timestamptz,
  UNIQUE (token_id, kind, channel)      -- idempotent planning
);

CREATE TABLE patient_channel (            -- Telegram link per token (ephemeral, purged with PII)
  token_id uuid PRIMARY KEY REFERENCES token(id),
  telegram_chat_id_enc bytea NOT NULL,
  linked_at timestamptz NOT NULL
);
```
Spring Session (Redis) holds staff sessions; no session tables in Postgres.

---

## 8. ETA: estimation, projection, ranges, ML

### 8.1 Definition
*Wait* for a token = `consult_started_at − predicted_at` (time until the consultation starts).
We predict at join and at milestones, and show a **range**, never a single exact minute.

### 8.2 Architecture
```
ServiceTimeEstimator (interface)
   estimate(ServiceContext ctx) → Distribution(meanS, p20S, p80S)
     ctx = clinicId, doctorId (nullable for pool), visitType, priority, localHour, dayOfWeek,
           queueLengthAtJoin, isFirstPatientOfSession
   ├── DefaultEstimator         — clinic.defaultServiceMinutes (cold start)
   ├── RollingStatsEstimator    — EWMA + fallback hierarchy (8.3)
   └── MlEstimator              — Tribuo model per clinic (8.6), falls back to Rolling

QueueProjector (pure): given QueueSnapshot + doctors' current state + estimator + now
   → List<Projection(tokenId, position, expectedStartAt, rangeMin, rangeMax)>

EtaService: picks estimator (champion model if present, else Rolling if ≥ minSamples, else
   Default), runs projector, applies hysteresis per token, logs predictions at milestones.
```

### 8.3 RollingStatsEstimator
- Observations: completed tokens' `serviceS = completed_at − consult_started_at` (drop
  `auto_closed`, drop outliers > 60 min or < 20 s and log a count of them).
- Keys with fallback: `(doctor, visitType, hourBucket)` → `(doctor, visitType)` → `(doctor)` →
  `(clinic, visitType)` → `(clinic)` → Default. Use the first key with ≥ `minSamples` (default 20).
- Statistic: EWMA of mean (α = 0.1) and an EWMA-based estimate of p20/p80 (or keep the last 200
  samples per key in Redis and compute empirical quantiles; choose one and record it in an ADR).
- Also learn per clinic: `transitionGapS` (time between a completion and the next consult start),
  `noShowRate` by hour, `doctorLateStartS` (session start vs first consult).
- Recompute incrementally on each `ConsultCompleted` event (consumer in Phase 5; before that,
  synchronously in-process).

### 8.4 QueueProjector algorithm (list scheduling)
```
servers = doctors serving this queue today (SINGLE → 1, POOL → k active doctors)
for each server s:
   if s is in consultation with token c:
        freeAt[s] = now + max(E[service_c] − elapsed_c, 0.25 × E[service_c])
   else if s has not started the session yet:
        freeAt[s] = max(now, sessionStart + doctorLateStart)
   else freeAt[s] = now
CALLED tokens (called, not yet started) are placed first, in called_at order.
order = repeated NextTokenPolicy.next on a copy of the snapshot
for token t in order:
   s = argmin freeAt
   t.expectedStart = freeAt[s]
   d = estimator.estimate(ctx(t, s))
   freeAt[s] += transitionGap + (1 − pNoShow(t)) × d.mean
   accumulate variance (sum of per-patient (p80−p20)² / 1.68² as a normal approximation)
range = expectedStart ± z × sqrt(accumulatedVar), clipped at ≥ 0, then widened by the clinic's
        empirical calibration factor (8.5)
```
Keep it a pure function of its inputs (inject `now`). Unit test with hand-computed examples:
1 doctor, 3 patients at 5 min each → 0, 5, 10 min (+ gaps); POOL with 2 doctors → roughly halves;
doctor 4 min into a 5-min consult → next patient starts in max(1, 1.25) min.

### 8.5 Ranges, calibration, hysteresis
- **Calibration:** nightly, compute for each clinic the fraction of last 14 days' predictions
  where `actual_wait_s ∈ [range_min, range_max]`. Target 60–70% (p20–p80 nominal). Keep a
  multiplicative widening factor in `[1.0, 2.5]` adjusted by ±0.1 per day toward the target.
- **Rounding for display:** < 5 min → "a few minutes"; < 30 → nearest 5 min range;
  ≥ 30 → nearest 10 min range; > 2 h → "more than 2 hours".
- **Hysteresis (`EtaSmoother`, pure):** keep the last displayed range per token; only change it
  if the new midpoint differs by ≥ 2 min **and** ≥ 10%, or if the position changed. Never let the
  displayed upper bound increase by more than 5 min in one update unless the position got
  worse (a priority patient jumped ahead), so patients aren't whipsawed.

### 8.6 ML model (Phase 7)
**Target:** per-patient service time (seconds). (Predicting service time and feeding it into the
projector keeps the model explainable and reuses the queue logic. A direct "wait-time"
regression is an optional stretch comparison.)

**Features** (all available at prediction time; no leakage):
`doctorId` (one-hot), `visitType`, `priority`, `hourOfDay` (one-hot buckets or sin/cos),
`dayOfWeek`, `positionInDay` (nth patient of the doctor's session, fatigue/warm-up effect),
`queueLengthAtStart` (doctors speed up when the queue is long), `minutesSinceSessionStart`,
`isFirstVisitOfDayForDoctor`.

**Training job:** `@Scheduled` nightly at 02:30 clinic time (and an admin "train now" button):
1. Skip clinic if < `ml.minTrainingSamples` (default 300) completed consultations.
2. Time-based split: train on days `[D−60, D−8]`, test on `[D−7, D−1]`. **Never random split.**
3. Train candidates: Tribuo `LinearSGDTrainer` (squared loss) and `CARTRegressionTrainer`
   (max depth ~6), optionally bagged. Optionally log-transform the target.
4. Metrics on test: MAE, median AE, p90 AE for service time; then **replay the test days through
   the projector** to get wait-time MAE and range coverage for: Default, Rolling, each candidate.
5. Promote a candidate to CHAMPION only if its wait-time MAE beats the current champion (or
   Rolling if none) by ≥ 5%; otherwise mark REJECTED. Save metrics either way.
6. Persist artifact (Tribuo protobuf serialization; **not** Java serialization).
7. Expose metrics in the analytics screen and append a summary to `docs/eta-evaluation.md`
   via the backtest CLI.

**Fallback if Tribuo is troublesome on Java 21/Boot 4:** Tribuo's last feature release is v4.3
and the project is not very active. If integration issues take more than about 2 hours, implement
`RidgeRegression` (closed-form `(XᵀX + λI)⁻¹Xᵀy` using a tiny matrix helper, features above
one-hot encoded) and keep the same `MlEstimator` interface. Ask the user before switching.

### 8.7 Prediction logging & accuracy
- Log an `eta_prediction` row at JOIN and when the position first reaches 10, 5, 3, 1.
- When the consultation starts, fill `actual_wait_s` for that token's rows (consumer).
- Metrics: `baari.eta.abs_error.seconds` (distribution summary, tags: estimator, trigger),
  `baari.eta.in_range` (counter with tag hit=true/false).
- This is the project's headline number. Keep it honest: include cancelled tokens? No (no
  actual). No-shows? Exclude, but report the no-show rate alongside.

---

## 9. Security, privacy, abuse prevention

### 9.1 Authentication
- Staff: Spring Security form login, BCrypt (strength 12), sessions in Redis (Spring Session),
  CSRF enabled (htmx sends the token via `hx-headers` from a meta tag), session fixation
  protection, 12 h idle timeout, logout. Lock account for 15 min after 5 failed logins (Redis counter).
- Why sessions not JWT: server-rendered UI on one domain; sessions are simpler and revocable.
  Write an ADR. (JWT stays an option for a future mobile app.)
- Patients: no accounts. The `publicCode` (≥128 bits of randomness from `SecureRandom`, base62)
  is a capability. Treat it like a password in logs (mask all but last 4 chars).
- TV display: per-queue display key (random, shown once to the admin, stored as a hash).

### 9.2 Authorization & tenant isolation
- `TenantContext` resolves `clinicId`, `role`, `doctorId` from the authenticated principal.
- Every staff service method takes the `StaffPrincipal`; repositories have methods like
  `findByIdAndClinicId`. Accessing another clinic's resource → **404** (don't reveal existence).
- DOCTOR can only act on queues they're assigned to; RECEPTIONIST on all queues of their clinic.
- Integration test matrix: for each staff endpoint, a user from clinic B gets 404/403.

### 9.3 Abuse prevention
- Rate limits (Bucket4j + Redis): join 10/min per IP and 3/day per phone per queue; token page
  60/min per IP; login 10/min per IP. Return 429 with `Retry-After`.
- **Rotating QR** (`qr_mode=ROTATING`): the TV shows a QR whose URL includes
  `t=<window>&sig=<HMAC(qr_secret, queueId|window)>` with 60 s windows; join accepts the current
  and previous window. Stops people joining from home with a photo of an old poster.
  STATIC mode is for clinics without a TV.
- Max open tokens per queue-day (setting, default 300) → "Queue full".
- Honeypot field on the join form + minimum form-fill time (1.5 s) to deter dumb bots.

### 9.4 Privacy (designed with India's DPDP Act principles in mind: notice, consent, minimisation, retention)
- Collect only name, optional phone, visit type. No medical details. Short notice + consent
  checkbox on the join page (versioned text; store `consent_version` on token).
- Phone: normalise to E.164 → `phone_enc` (AES-256-GCM, key from env `BAARI_DATA_KEY`, random
  IV per value, JPA `AttributeConverter`) + `phone_hash` (HMAC-SHA256 with `BAARI_PHONE_PEPPER`)
  for uniqueness + `phone_last4` for display.
- Retention job (daily): for tokens older than `piiRetentionDays` (default 30): null
  `patient_name`, `phone_enc`, `phone_hash`, delete `patient_channel`, set `pii_purged_at`.
  Timing columns stay (they're anonymous and power the model).
- Staff UI shows masked phones (`••••••3210`). TV never shows names.
- Don't claim "DPDP compliant" in the README. Say "designed with DPDP principles in mind".

### 9.5 Hardening checklist (Phase 8)
Security headers (CSP allowing only self + htmx from self, HSTS in prod, frame-ancestors none),
actuator exposed only on a management port / behind auth (health public), dependency check in
CI (OWASP dependency-check or GitHub Dependabot), secrets only via env, no stack traces in responses.

---

## 10. Real-time updates

### 10.1 Transport
SSE everywhere (patients, staff console, TV). Commands are normal POSTs. SSE is one-way, works
through proxies, and `EventSource` reconnects automatically, which is all we need.
Enable virtual threads (`spring.threads.virtual.enabled=true`) so thousands of idle SSE
connections are cheap. Write an ADR (SSE vs WebSocket).

Endpoints:
- `GET /api/public/tokens/{publicCode}/stream` → events `snapshot` (token view + ETA)
- `GET /api/staff/queues/{queueId}/stream` → `snapshot` (full staff board)
- `GET /api/display/queues/{queueId}/stream?key=…` → `snapshot` (display board)

### 10.2 Flow
1. A transaction changes queue state and bumps `queue_day.last_event_seq`.
2. `@TransactionalEventListener(phase = AFTER_COMMIT)` publishes `QueueChanged(queueDayId, seq)`
   to Redis channel `baari:qd:{queueDayId}`.
3. Every app instance subscribes (pattern `baari:qd:*`) and, for local emitters of that
   queue-day, **coalesces** notifications for 200 ms, then builds fresh snapshots (one DB read +
   one projection per queue-day, then per-token views) and sends them with `id: <seq>`.
4. On (re)connect: send a snapshot immediately (so `Last-Event-ID` handling is trivial: the
   snapshot is always complete).
5. Heartbeat comment every 15 s; emitter timeout 30 min; clean up on completion/error/timeout.
6. Track gauges: `baari.sse.connections{type}`.

Snapshots are complete state, not deltas. Clients replace their view. This avoids out-of-order
bugs and makes reconnects safe.

### 10.3 Test
Integration test with two app contexts (or one context + direct Redis publish) proving an event
on instance A reaches an emitter on instance B. Use a test SSE client (WebClient/`HttpClient`
reading lines) with a timeout.

---

## 11. Events, outbox, Kafka, notifications

### 11.1 Domain events (records, in `queue` module API)
`TokenJoined`, `TokenCalled`, `ConsultStarted`, `ConsultCompleted`, `TokenNoShow`,
`TokenRecalled`, `TokenCancelled`, `TokenPriorityChanged`, `QueueDayOpened`, `QueueDayPaused`,
`QueueDayClosed`. Each has `eventId` (UUID), `occurredAt`, `clinicId`, `queueDayId`, `tokenId`
(when relevant), `seq`.

### 11.2 Outbox → Kafka
- Same transaction as the state change: insert into `outbox_event` (JSON payload, type, partition
  key = queueDayId).
- `OutboxRelay` (`@Scheduled(fixedDelay = 500ms)`), safe on multiple instances:
  ```sql
  SELECT * FROM outbox_event WHERE published_at IS NULL
   ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED;
  ```
  send each to topic `baari.queue-events` (key = partition_key), wait for ack, set
  `published_at`. On failure: `attempts++`, `last_error`, retry next cycle.
- Gauge `baari.outbox.lag.seconds` (age of oldest unpublished row). Cleanup job deletes published
  rows older than 7 days.
- Delivery is **at-least-once** → consumers must be idempotent via `processed_event`.
- ADR: why outbox instead of publishing to Kafka inside the transaction (dual-write problem).

### 11.3 Consumers (Spring Kafka, group per consumer)
| Consumer | Reacts to | Does |
|---|---|---|
| `NotificationPlanner` | Joined, Called, positions changed (any queue event), QueueDayClosed | recompute positions for the queue-day; create `notification` rows (unique key makes it idempotent): `NEAR_TURN` when position ≤ `notifyAheadCount`, `CALLED`, `QUEUE_CLOSED` |
| `EtaActualsRecorder` | ConsultStarted | fill `eta_prediction.actual_wait_s`, record error metrics |
| `RollingStatsUpdater` | ConsultCompleted | update RollingStatsEstimator state |
| `AnalyticsAggregator` | Completed, NoShow, Cancelled | update daily aggregates |

Error handling: `DefaultErrorHandler` with exponential backoff (3 tries), then
`DeadLetterPublishingRecoverer` → `baari.queue-events.DLT`. Metric on DLT count. An admin page
lists DLT messages (stretch).

### 11.4 Notification dispatch
- `NotificationDispatcher` (`@Scheduled` every 2 s): pick `PENDING` rows with
  `next_attempt_at ≤ now` using `FOR UPDATE SKIP LOCKED`, send via `NotificationChannel`,
  mark `SENT`, or on failure `attempts++`, `next_attempt_at = now + backoff(30 s, 2 m, 10 m, 30 m)`,
  `FAILED` after 5 attempts. Don't send `NEAR_TURN` if the token is no longer WAITING (`SKIPPED`).
- Channels (`NotificationChannel` interface, selected by config and what the patient linked):
  - `LogChannel`: dev/test; logs masked message.
  - `TelegramChannel`: free and great for demos. Patient taps "Get Telegram alerts" → deep link
    `https://t.me/<bot>?start=<publicCode>`; a webhook (`/api/telegram/webhook/{secret}`)
    receives `/start <publicCode>`, stores the encrypted chat id in `patient_channel`, replies
    "You'll be alerted for token A-017".
  - `SmsChannel`: interface + stub implementation only. Note in README: Indian SMS providers
    require DLT template registration, so it's left as a pluggable stub.
- Message texts in English and Hindi (`messages*.properties`), e.g.
  "A-017: 3 patients ahead of you. Expected in 10–15 min. Please return to the clinic."

---

## 12. Phases, tasks, and acceptance criteria

Rough effort for one developer working part-time with Claude Code: 7–9 weeks.
**Don't skip "Done when".** Each phase ends with a green `./mvnw verify`, an updated
PROGRESS.md, and a tag `phase-N`.

### Phase 0 — Bootstrap (½–1 day)
Tasks
- Parent POM (Java 21, Boot 4.1.x BOM, Testcontainers 2.x BOM, Spotless, JaCoCo, Surefire for
  `*Test`, Failsafe for `*IT`), modules `baari-app`, `baari-simulator`.
- `ops/docker-compose.yml`: postgres:17, redis:7, apache/kafka (KRaft single node), prometheus, grafana.
- App skeleton: `BaariApplication`, `common` with `Clock` bean, `MutableClock` in test sources,
  `Ids`, `ProblemDetail` advice, `/actuator/health`.
- Flyway enabled with `V1__clinic.sql` (clinic table only for now).
- `AbstractIntegrationTest` with singleton Postgres/Redis/Kafka containers via `@ServiceConnection`.
- Modulith verify test. GitHub Actions: checkout, Temurin 21, cache Maven, `./mvnw verify`.
- `docs/PROGRESS.md`, `docs/adr/0001-record-architecture-decisions.md`.
Done when
- [ ] `./mvnw verify` green locally and in CI (Testcontainers runs on GitHub's ubuntu runner)
- [ ] App starts with dev profile; health is UP; Flyway applied V1
- [ ] Spotless check fails on badly formatted code

### Phase 1 — Clinics, staff & auth (2–3 days)
Tasks
- Migrations for doctor, staff_user, service_queue, queue_doctor.
- Entities, repositories, admin services (create clinic [platform admin], CRUD doctors, queues,
  staff [clinic admin]).
- Security: form login, roles, Redis sessions, CSRF, lockout, `TenantContext`.
- Dev profile seed: clinic "Sharma Clinic, Chandigarh" with 2 doctors, queues `dr-sharma` (SINGLE)
  and `general` (POOL), one user per role.
- Minimal Thymeleaf admin pages (lists + forms) using a base layout.
Done when
- [ ] Login/logout works; wrong password ×5 locks for 15 min (test with MutableClock or Redis TTL)
- [ ] Tenant isolation test matrix passes (clinic B user → 404 on clinic A resources)
- [ ] DOCTOR cannot manage staff; RECEPTIONIST cannot manage doctors (403)

### Phase 2 — Queue core (4–5 days). The heart of the project
Tasks
- Migrations: queue_day, token, token_event, outbox_event (outbox written but not relayed yet).
- `TokenStateMachine` (pure) + exhaustive tests (every state × every command).
- `NextTokenPolicy` (pure) + tests from §5.
- `QueueDayService`: open (idempotent for the same date), pause, resume, close (expire tokens).
- `TokenService`: join (atomic issuance §6.1, idempotency, one-active-per-phone),
  issueWalkIn, callNext (§6.2), start, complete, noShow (grace check), recall, cancel,
  setPriority. Every mutation appends `token_event`, bumps seq, writes outbox.
- REST APIs (§12 appendix A) with ProblemDetail errors.
- Phone normalisation + encryption + hash (`common.crypto`), with tests (round-trip, different
  IVs, hash stable).
Done when
- [ ] All six concurrency tests in §6.4 pass, repeated 5× in CI without flakes
- [ ] Journeys J1–J7 pass as API-level integration tests (ETA fields may be null for now)
- [ ] State machine and policy have 100% branch coverage (JaCoCo report)

### Phase 3 — UI & real-time (3–4 days)
Tasks
- Join page, token page, staff console, TV display (§2.2), mobile-first CSS (no CSS framework
  needed; a small hand-written stylesheet is fine), English + Hindi.
- SSE endpoints and Redis fan-out (§10). Coalescing, heartbeat, cleanup, gauges.
- htmx for staff actions (buttons POST and re-render fragments; SSE refreshes the board).
- QR poster print view (use a small QR library such as ZXing; ask before adding).
Done when
- [ ] Two browsers: patient page updates within 1 s of staff clicking "Call next"
- [ ] Cross-instance SSE test passes (§10.3)
- [ ] Killing the network and reconnecting restores the correct state (manual check; describe in PROGRESS)
- [ ] TV display shows no patient names

### Phase 4 — ETA v1 (3–4 days)
Tasks
- `ServiceTimeEstimator` + Default + RollingStats (in-process updates for now).
- `QueueProjector` (pure, §8.4) with hand-computed tests (single, pool, mid-consult, before session).
- `EtaSmoother` (pure, §8.5) with tests for every hysteresis rule. Display rounding helper.
- `EtaService` wired into snapshots (token page, staff console shows predicted start per patient).
- Prediction logging at JOIN and milestones; fill actuals when a consult starts (synchronous for now).
- Metrics: abs error, in-range counter.
Done when
- [ ] Token page shows a range that updates as the queue moves, without jitter
- [ ] With a seeded history, Rolling is used instead of Default (test asserts estimator name)
- [ ] `eta_prediction.actual_wait_s` filled correctly in J1 integration test

### Phase 5 — Events & notifications (3–4 days)
Tasks
- `OutboxRelay` with `SKIP LOCKED`, lag gauge, cleanup job. Kafka topic config (6 partitions locally fine).
- Consumers from §11.3 with `processed_event` dedup; move actuals and rolling-stats updates
  from synchronous code to consumers.
- `NotificationPlanner`, `NotificationDispatcher`, `LogChannel`, `TelegramChannel` (webhook +
  deep link), `SmsChannel` stub. Error handler + DLT.
Done when
- [ ] Integration test: Kafka down (pause container) during joins → outbox accumulates → Kafka back
      → all events delivered exactly once *in effect* (consumer side-effects not duplicated)
- [ ] Duplicate delivery of the same event (publish twice manually) causes no duplicate notification
- [ ] A poison message lands in the DLT after retries
- [ ] Manual: Telegram alert received on a real phone for NEAR_TURN and CALLED

### Phase 6 — Simulator & backtesting (3–4 days)
`baari-simulator` (plain Java, no Spring; picocli if approved, else hand-rolled arg parsing).
Tasks
- Discrete-event simulation engine: priority queue of events (arrival, call, start, complete,
  no-show), `SimClock`.
- Config JSON (`sim/demo-clinic.json`): sessions (e.g. 09:30–13:30, 17:00–20:00), doctors with
  lognormal service-time parameters per visit type, hour-of-day effects, fatigue factor by
  position in session, late-start distribution, arrival rates per hour (non-homogeneous Poisson),
  visit-type mix, priority mix, no-show probability, walk-in vs QR share.
- `generate` command: writes CSVs (tokens with all timestamps) for N days; an admin-only import
  endpoint or a `psql \copy` script loads them into a clinic.
- `backtest` command (lives in `baari-app` as a Spring Boot CLI runner or test-scope main):
  replays held-out days event by event, asks each estimator for predictions at JOIN/milestones,
  compares with actuals, writes a Markdown table to `docs/eta-evaluation.md`.
- Optional `drive` command: plays a live day against a running app via HTTP with an accelerated
  demo clock (`baari.clock.mode=accelerated`, demo profile only) for the README GIF and load tests.
Done when
- [ ] 60 simulated days generated and imported for the demo clinic
- [ ] `docs/eta-evaluation.md` shows Default vs Rolling: MAE, p90 AE, range coverage, by trigger
- [ ] Simulator is deterministic with a fixed `--seed` (test)

### Phase 7 — ML ETA (3–5 days)
Tasks: §8.6 end to end: feature extraction (shared by training and inference; one class,
tested), Tribuo trainers, time-based split, wait-time replay evaluation, champion/challenger
promotion, `ml_model` persistence, `MlEstimator` with fallback, nightly job + admin "train now",
analytics page section "Prediction accuracy".
Done when
- [ ] On simulated data with real structure (doctor and hour effects), ML beats Rolling on
      wait-time MAE, or the evaluation honestly shows it doesn't and explains why
- [ ] Feature extraction unit tests prove no future information is used
- [ ] Restarting the app loads the champion model from the DB; corrupt artifact → fallback + alert log

### Phase 8 — Hardening & observability (2–3 days)
Tasks: rate limits, rotating QR, retention job, honeypot, security headers, actuator lockdown,
Grafana dashboard JSON (tokens/hour, wait p50/p90, ETA error by estimator, outbox lag, SSE
connections, notification failures, DLT count), structured JSON logs with `clinicId`,
`queueDayId` in MDC, k6 load test (`load/join-and-poll.js`: 300 virtual patients join over 2 min
and hold SSE streams; staff calls every 20 s).
Done when
- [ ] k6: join p95 < 300 ms, zero 5xx, numbering still gap-free afterwards (verify query)
- [ ] Rotating QR: an old-window signature is rejected (test)
- [ ] Retention job test: PII nulled, timings intact
- [ ] Dashboard screenshot saved to `docs/images/`

### Phase 9 — Deploy & README (2 days)
Tasks: multi-stage Dockerfile (Temurin 21 JRE, non-root, layered jar), `docker-compose.prod.yml`
(app ×2 instances to prove fan-out, postgres, redis, kafka, caddy), deploy to a small VM
(AWS Lightsail/EC2 or another provider), HTTPS via Caddy, backups (nightly `pg_dump` to object
storage or at least off-box), README:
- problem, 30-second demo GIF, live link, architecture diagram (Mermaid), key design decisions
  with links to ADRs, ETA accuracy table, concurrency guarantees and how they're tested,
  how to run locally, tech stack, future work (ABDM Scan & Share integration, WhatsApp, appointments).
Done when
- [ ] Public URL works over HTTPS with the demo clinic and simulator-driven activity
- [ ] A stranger can run it locally with 3 commands from the README

### Phase 10 — Real-clinic pilot (2–4 weeks, runs in parallel with job applications)
Checklist (mostly non-code):
- Find a small clinic, diagnostic lab, or even a barber shop/salon willing to try it for free.
- Print the QR poster; train the receptionist (10 minutes); keep paper tokens as a backup.
- Turn on Telegram alerts; collect feedback weekly (2-question form).
- After 2–3 weeks: retrain on real data, update `docs/eta-evaluation.md` with real numbers.
- Write a blog post / LinkedIn post: problem, what was built, real accuracy, lessons.
- Get the clinic's permission before naming it publicly; never publish patient data.

### Appendix A — REST API
Public (no auth; rate-limited):
| Method | Path | Notes |
|---|---|---|
| GET | `/api/public/queues/{clinicSlug}/{queueSlug}` | queue info: name, doctor(s), status, waiting count, typical wait |
| POST | `/api/public/queues/{queueId}/tokens` | body `{name, phone?, visitType, consentVersion, qrWindow?, qrSig?, idempotencyKey}` · header `Idempotency-Key` optional · 201 `{publicCode, displayCode, status, position, eta}` · 409 already-in-queue · 423 queue paused · 410 queue closed · 429 |
| GET | `/api/public/tokens/{publicCode}` | token view |
| GET | `/api/public/tokens/{publicCode}/stream` | SSE |
| POST | `/api/public/tokens/{publicCode}/cancel` | |
| POST | `/api/telegram/webhook/{secret}` | Telegram updates |

Display: `GET /api/display/queues/{queueId}/stream?key=…`

Staff (session + CSRF; tenant from principal):
| Method | Path |
|---|---|
| POST | `/api/staff/queues/{queueId}/days/today/open` · `/pause` · `/resume` · `/close` |
| GET | `/api/staff/queues/{queueId}/board` |
| GET | `/api/staff/queues/{queueId}/stream` |
| POST | `/api/staff/queues/{queueId}/tokens` (walk-in) |
| POST | `/api/staff/queues/{queueId}/call-next` (body: `{doctorId?}` for POOL) |
| POST | `/api/staff/tokens/{tokenId}/start` · `/complete` · `/no-show` (`{force?}`) · `/recall` · `/cancel` · `/priority` (`{priority}`) · `/call` (override) |
| GET/POST/PUT | `/api/staff/admin/doctors`, `/queues`, `/staff` |
| POST | `/api/staff/admin/ml/train` |
| GET | `/api/staff/analytics/daily?from=&to=` |

All mutation responses return the updated token view or board. Errors are `ProblemDetail`.

---

## 13. ADR template & initial ADR list

Template (`docs/adr/NNNN-title.md`):
```
# NNNN. Title
Date: YYYY-MM-DD · Status: Accepted
## Context
## Decision
## Consequences (good, bad, follow-ups)
## Alternatives considered
```
Write these as the phases happen:
0001 Record architecture decisions · 0002 Modular monolith over microservices ·
0003 Per-queue-day counter row over DB sequence · 0004 Serialize call-next with a row lock (not SKIP LOCKED) ·
0005 Transactional outbox for Kafka · 0006 SSE over WebSocket · 0007 Sessions over JWT for staff ·
0008 Predict service time, project wait (vs direct wait regression) · 0009 Rolling-stats quantile method ·
0010 Thymeleaf + htmx over SPA · 0011 Tribuo (or ridge fallback) for in-JVM ML

---

## 14. Configuration reference (`application.yml`, prefix `baari`)
```yaml
baari:
  clock.mode: system            # system | accelerated (demo profile only)
  queue:
    default-service-minutes: 6
    no-show-grace-minutes: 3
    recall-insert-offset: 2
    max-consecutive-priority: 2
    max-open-tokens: 300
  eta:
    min-samples: 20
    ewma-alpha: 0.1
    milestone-positions: [10, 5, 3, 1]
    calibration-target: 0.65
  notify:
    ahead-count: 3
    channels: [LOG]             # LOG, TELEGRAM, SMS
    telegram:
      bot-username: ""
      token: ${BAARI_TELEGRAM_TOKEN:}
      webhook-secret: ${BAARI_TELEGRAM_WEBHOOK_SECRET:}
  privacy:
    pii-retention-days: 30
    data-key: ${BAARI_DATA_KEY}           # base64 32 bytes; required outside dev/test
    phone-pepper: ${BAARI_PHONE_PEPPER}
  ratelimit:
    join-per-ip-per-minute: 10
    join-per-phone-per-day: 3
  ml:
    min-training-samples: 300
    promote-min-improvement: 0.05
    train-cron: "0 30 2 * * *"
spring:
  threads.virtual.enabled: true
```
Per-clinic overrides live in `clinic.settings` / `service_queue.settings` JSON and take precedence.

---

## 15. Interview talking points (keep README and ADRs consistent with these)
- **"How do you guarantee unique token numbers?"** Counter row + `UPDATE … RETURNING` in the
  same transaction as the insert; idempotency key pre-check + unique constraint backstop;
  partial unique index for one active token per phone; proved by a 200-thread test.
- **"Why not microservices?"** One developer, one deployable, strong module boundaries enforced
  by Spring Modulith; the event-driven parts (outbox → Kafka) are where a split would happen later.
- **"How do you avoid the dual-write problem?"** Transactional outbox; relay with `SKIP LOCKED`
  for safe multi-instance publishing; idempotent consumers via `processed_event`.
- **"How do multiple instances push live updates?"** After-commit Redis pub/sub fan-out to local
  SSE emitters; complete snapshots instead of deltas so reconnects and reordering are safe.
- **"How good are your predictions?"** Quote the eta-evaluation table: Default vs Rolling vs ML
  MAE and range coverage, time-based split, measured on real pilot data if available.
- **"How do you handle time in tests?"** Injected `Clock`, `MutableClock`, a deterministic simulator with seeds.
- **"What about privacy?"** Minimal data, encryption at rest for phones, HMAC lookup hash,
  retention purge, no names on public screens, DPDP-inspired notice and consent.
