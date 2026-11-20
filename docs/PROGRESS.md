# Progress log

Update this file at the end of every Claude Code session.

## Current phase
Phase 4 — ETA v1 (built and verified locally 2026-11-20, see below; CI and the `phase-4` tag are still open). Phases 1 to 3 complete, tagged `phase-1`, `phase-2`, `phase-3`.

## Phase 4 plan (all built, 2026-11-20)
1. [x] Queue read models for ETA: `QueueLiveState`, `TokenLocator`, `ServiceSample`/`ServiceHistory` (queue module, no patient details); `EventLog.publish` also publishes the domain event in-process
2. [x] Pure classes in the new `eta` module: `Distribution`, `ServiceContext`, `ServiceTimeEstimator`, `DefaultEstimator`, `RollingStatsEstimator`, `QueueProjector`, `EtaSmoother`, `EtaDisplay`; 61 unit tests (hand-computed examples); added to the 100% branch rule
3. [x] `EtaService` (estimator choice, projection cache, per-patient hysteresis), `PredictionLog` (JOIN and position milestones, actual waits, metrics `baari.eta.abs_error.seconds` and `baari.eta.in_range`), migration `V4__eta_prediction.sql`
4. [x] Wired in: patient token page and stream show "Your turn in about 10–15 minutes" (English and Hindi), staff console shows "Expected 10:25 (in about 12 min)" per waiting patient
5. [x] ADR 0010; `EtaIT` (17), two new `LivePagesIT` tests, console and stream assertions

## Phase 4 "Done when"
- [x] Token page shows a range that updates as the queue moves, without jitter: `EtaIT.thePatientPageStaysCorrectWhenSomeoneAheadLeaves`, `EtaIT.theRangeDoesNotJitterAsTimePassesWithoutRealChange`, `EtaSmootherTest` (one test per rule)
- [x] With a seeded history, Rolling is used instead of Default: `EtaIT.onceTheClinicHasTwentyFinishedConsultationsRollingIsUsedInstead` asserts the estimator name (19 consultations: DEFAULT, 20: ROLLING)
- [x] `eta_prediction.actual_wait_s` filled correctly: `EtaIT.theActualWaitIsFilledInWhenTheConsultationStarts` (join, 12 minutes, call, start: 720 s) and `EtaIT.aPredictionThatWasRightCountsAsInRange`. This is not the `JourneysIT` J1 test itself; it is a dedicated test of the same flow.

Phase 4 notes and known gaps (all in ADR 0010):
- Not modelled yet: session start and late start, a learned transition gap (fixed 30 s), a learned no-show rate (fixed 0), the nightly calibration factor (fixed 1.0). Phase 6 backtesting decides which to add.
- Smoothing state is in memory per instance (a patient moving between instances may briefly see a fresh range).
- The plain REST `GET /api/public/tokens/{code}` has no ETA; only the page and the stream do.
- The typical wait on the join page ("if you join now") was in the Phase 3 note but is not in the Phase 4 task list; not built.
- The ETA JavaScript (token page and console) has no automated tests; the server side of the same text is tested.

## Phase 3 plan (all built, 2026-11-14)
1. [x] Live-update plumbing: `QueueChanged` event from `EventLog`, `realtime` module (`SseHub` with coalescing, heartbeat, cleanup, gauges; `RedisFanOut`; `StreamApi`), ADR 0008
2. [x] Patient pages: join `/j/{clinic}/{queue}` (idempotency key, consent, no session), token `/t/{code}` (live, "your turn" overlay with vibration and sound, cancel)
3. [x] Staff console `/staff/queues/{id}` (live board, call next, start/complete/no-show/recall/priority/cancel, walk-in, day controls, keyboard N and C)
4. [x] TV display `/display/{queueId}?key=` (codes and room only) and poster print view `/admin/queues/{id}/poster`
5. [x] English and Hindi (`messages*.properties`, `?lang=hi`, cookie)
6. [x] Tests: `SseHubTest` (7), `StreamIT` (6, real HTTP and Redis), `LivePagesIT` (20)

## Phase 3 "Done when"
- [x] Patient page updates within 1 s of staff clicking "Call next": `StreamIT` asserts under 1000 ms (typically ~250 ms); real browsers measured about 470 ms click to update (2026-11-14)
- [x] Cross-instance SSE test passes: `StreamIT.aMessageFromAnotherInstanceReachesThisInstancesStream` (publishes straight to Redis, as a second instance would)
- [x] Killing the network and reconnecting restores the correct state (checked by hand 2026-11-20 on the dev stack, port 8081). The built-in browser has no offline switch, so the drop was simulated: closed the live `EventSource` and fired its error handler. The "Connection lost" banner showed, the token was called from the console (A-001 became CALLED), and about 5 s later the page reconnected itself, showed "It is your turn" and hid the banner. This exercises the CLOSED-then-retry path. The browser's own back-off and the `online` event path were not exercised by a real network cut.
- [x] TV display shows no patient names: `StreamIT.displayStreamNeedsTheKeyAndShowsNoNames`, `LivePagesIT.displayPageNeedsTheKeyAndShowsNoPatientNames`

Phase 3 notes:
- QR image is **not** built: the user declined adding ZXing, so the poster shows the join URL as text. The TV has no QR either (rotating QR is Phase 8).
- htmx not used (needs a vendored script); vanilla `fetch()` instead, see ADR 0008.
- Predicted start time per waiting patient was built in Phase 4; the typical wait on the join page was not (see Phase 4 notes).
- Telegram alerts button on the token page is Phase 5.
- Console, token and TV JavaScript has no automated tests; checked by hand in a browser on the dev stack (login, open day, walk-in with `<b>` in the name rendered as text, call next, start, complete, live token page in Hindi).

## Phase 2 plan (7 tasks)
1. [x] `TokenStateMachine` + every status × command test + JaCoCo 100% branch rule
2. [x] `NextTokenPolicy` + §5 tests (incl. 1,000 random queues) + projection + recall sort key
3. [x] `common.crypto`: phone normalisation, AES-GCM, HMAC hash, `Masking.phone()`, JPA converter, keys from config
4. [x] V3 migration (queue_day, token, token_event, outbox_event) + `QueueDayService` (unit tests not run, see below)
5. [x] `TokenService` join / walk-in + concurrency tests 1, 2, 3, 6 (unit tests not run, see below)
6. [x] Staff transitions (call-next row lock, start, complete, no-show, recall, cancel, priority) + tests 4, 5 (unit tests not run, see below)
7. [x] REST APIs (Appendix A) + journeys J1–J7 + ADRs 0003–0005 (unit tests not run, see below)

## Phase 2 "Done when" (not tagged yet)
- [x] All six concurrency tests in §6.4 pass, repeated 5× (locally; not yet in CI): `TokenServiceIT` (1, 2, 3, 6), `TokenActionServiceIT` (4, 5)
- [x] Journeys J1–J7 pass as API-level integration tests (ETA null; J1's "3 ahead" notification is Phase 5, J7's two-server projection is Phase 4): `JourneysIT`
- [x] State machine and policy have 100% branch coverage: JaCoCo rule passed in the full `./mvnw verify` (2026-11-14)

Defaults agreed at the start of Phase 2: global `baari.queue.*` settings only; rate limits and rotating-QR checks stay in Phase 8; ETA fields null until Phase 4; doctors act only on queues they're assigned to; POOL call-next needs a doctor; crypto keys from env vars, fixed placeholders in dev/test; concurrency tests use `@RepeatedTest(5)`.

## Phase 1 "Done when"
- [x] Login/logout works; wrong password ×5 locks for 15 min: `StaffLoginIT` (MutableClock moves past the lock)
- [x] Tenant isolation matrix: clinic B admin → 404 on every clinic A doctor/queue/staff endpoint, nothing changed: `AdminPagesIT`, plus service level in `ClinicAdminServicesIT`
- [x] DOCTOR cannot manage staff; RECEPTIONIST cannot manage doctors (403): `AdminPagesIT`

## Done
### Phase 1
- Flyway `V2__doctors_staff_queues.sql` (doctor, staff_user, service_queue, queue_doctor as in PLAN §7)
- `clinic` module: JPA entities in `internal`, clinic-scoped repositories, public read-model records; `AssignedIdEntity` (Persistable) in `common`
- Admin services: `ClinicAdminService` (platform admin creates a clinic + its first CLINIC_ADMIN), `DoctorAdminService`, `QueueAdminService` (display key returned once, stored as SHA-256; 256-bit QR secret), `StaffAdminService`; role checks in `Permissions`
- Common errors: NotFound (404) / Forbidden (403) / Duplicate (409) / InvalidRequest (400); service Bean Validation → 400 without echoing values
- `identity`: Spring Security form login, BCrypt(12), CSRF, Redis sessions (Spring Session, 12 h idle), lockout (`LoginAttempts`: Redis counter, lock end from injected Clock), `TenantContext` + `StaffPrincipal` argument resolver; URL role rules on top of service checks
- Dev seed (`DevSeed`, dev profile only, idempotent): Sharma Clinic, Chandigarh; Dr. Rajesh Sharma, Dr. Neha Gill; queues `dr-sharma` (SINGLE) and `general` (POOL); one login per role (credentials in `application-dev.yml`)
- Thymeleaf admin pages with a fragment-based layout: doctors, queues, staff, platform clinics; HTML error page
- ADR 0007 (sessions over JWT)

### Phase 0
- Parent POM (Java 21, Boot 4.1.2, Modulith 2.1.2, Spotless, JaCoCo, Surefire/Failsafe), modules `baari-app` + `baari-simulator`, wrapper
- App skeleton, Flyway V1, test infra (`MutableClock`, `AbstractIntegrationTest`), compose stack, CI, ADR 0001. Tagged `phase-0`, CI green.

## Verified locally (2026-11-20, after Phase 4)
- `./mvnw verify`: BUILD SUCCESS. 252 unit tests (incl. `ModularityTest`), 205 integration tests, Spotless clean, JaCoCo 100% branch rule met (now also covers the pure `eta` classes)
- A first full run failed: `TokenServiceIT.twoHundredConcurrentJoins...` timed out at the 30 s connection-pool limit. Cause: the ETA listener wrote in a new transaction after commit, which needs a second connection while the first is held. Fixed by moving the prediction log to a background lane (ADR 0010, decision 10); a second run failed the 100% rule on one branch in `QueueProjector`, fixed; third run green
- By hand in the browser on the dev stack (port 8081): token page showed "Your turn in about 15–25 minutes" and the console "Expected 06:02 PM (in about 7 min)" per waiting patient. Hindi and the stream text are covered by `LivePagesIT`

## Verified locally (2026-11-14, after Phase 3)
- `./mvnw verify`: BUILD SUCCESS. 191 unit tests (incl. `ModularityTest`), 186 integration tests, Spotless clean, JaCoCo rule met
- Phase 2: full verify green and CI green on `7f8ab16`; tagged `phase-2`

## Verified locally (2026-11-14, Phase 2)
- `./mvnw verify`: BUILD SUCCESS. 32 unit tests, 55 integration tests, Modulith verify
- Dev profile on the compose stack: seed runs once, seeded receptionist and clinic admin log in through the real form, session stored in Redis, admin pages render with seeded data

## CI
- Phase 1, `951a318`: success. https://github.com/Rishi5200/Baari/actions/runs/43584766134
- Phase 0, `f8f4c69`: success. https://github.com/Rishi5200/Baari/actions/runs/43581592618

## Bugs found and fixed while verifying
- Boot 4 ignores the old property `spring.session.redis.namespace`; the right one is `spring.session.data.redis.namespace`. Sessions were silently using the default key prefix; the session test now checks the keys exist.

## Unit tests not run yet (user will run them later)
At the user's request, from task 4 on the build ran without unit tests:
`./mvnw verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
That also skips `ModularityTest` (Modulith verify, a unit test) and the JaCoCo 100% branch check.
- Task 4: integration tests 72/72 green, Spotless clean; unit tests, Modulith verify and JaCoCo check not run.
- Task 5: integration tests 106/106 green (concurrency tests 1, 2, 3, 6 each passed 5×), Spotless clean; unit tests, Modulith verify and JaCoCo check not run. No new unit tests were written for `DisplayCode` / `PublicCodes` (covered through `TokenServiceIT` only).

- Task 6: integration tests 140/140 green (concurrency tests 4 and 5 each passed 5×), Spotless clean; unit tests, Modulith verify and JaCoCo check not run. The new optimistic-lock handler in `ProblemDetailAdvice` has no unit test yet (`ProblemDetailAdviceTest` untouched).

- Task 7: integration tests 160/160 green, Spotless clean; unit tests, Modulith verify and JaCoCo check not run.

**Full verify run (2026-11-14, after task 7):** BUILD SUCCESS. 184 unit tests (incl. `ModularityTest`), 160 integration tests, Spotless clean, JaCoCo rule met. **Before tagging `phase-2`:** push and check CI.

## In progress
- Phase 4 is built and verified locally. Left: check CI, tag `phase-4`.

## Next
1. Phase 5: events and notifications (outbox relay to Kafka, idempotent consumers; move the ETA prediction log and history invalidation from in-process listeners to consumers)
2. Last-admin guard (ADR 0009 item 1), small `clinic` change with an integration test; can ride along with any phase
3. Phase 8: lock down `/actuator/prometheus`, pin Prometheus and Grafana versions (ADR 0009 items 2 and 3). Deploy blocker for Phase 9.

## Open questions for the user
None. The four earlier questions were decided by Claude on 2026-11-20 and recorded in `docs/adr/0009-open-questions-resolved-after-phase-3.md`; the user can overrule them. Port 8080 on this machine is held by an unrelated process, so run locally with `--server.port=8081`.

## Notes / decisions
- `Ids.newId()` uses random UUIDs, not UUIDv7: UUIDv7 needs the current time and the only allowed time source is an injected `Clock`.
- Boot 4.2 is only at RC, so we stay on 4.1.2.
- Git identity on this machine is `Admin` with a different email from the Claude account; commits use that.
- Phase 1 choices not spelled out in PLAN (kept small, easy to change):
  - A DOCTOR login must be linked to a doctor record; other roles must not be.
  - Creating a clinic also creates its first CLINIC_ADMIN (otherwise nobody could manage it).
  - Doctors and receptionists may read the doctor and queue lists; only CLINIC_ADMIN changes them or sees staff.
  - A SINGLE queue has at most one doctor (zero allowed during setup). Queue slugs can't change after creation (printed on QR posters).
  - Granting PLATFORM_ADMIN from a clinic is a 403. Passwords 8–72 chars (BCrypt limit). Usernames lowercase.
  - Login failures all show one generic message so usernames can't be probed.
  - `settings` jsonb columns are not mapped yet (DB default `{}`).
  - Dev seed: token prefixes A (`dr-sharma`) and B (`general`); one fixed dev TV display key in `application-dev.yml`.
- Phase 2 state machine choices (PLAN §4 leaves these open):
  - WAITING → IN_CONSULTATION ("walked straight in") is allowed and flagged `DIRECT_START`.
  - A forced no-show is flagged `FORCED_NO_SHOW` only when it happens before the grace period ends; the grace boundary itself counts as elapsed.
  - The SYSTEM actor can't cancel; day close uses its own command (open tokens → EXPIRED, IN_CONSULTATION → COMPLETED + `AUTO_CLOSED`).
  - Refusals carry a reason: illegal transition, grace not elapsed, or recall on another day.
- Phase 2 policy choices (PLAN §5 leaves these open):
  - Calling an EMERGENCY leaves the PRIORITY-in-a-row counter unchanged.
  - PRIORITY served because no NORMAL is waiting still counts up (as in the §5 pseudocode), so the next NORMAL to arrive goes first.
  - Recall with fewer waiting NORMALs than the offset: key = highest waiting key (any priority) + 1; if nobody waits, the token keeps its key.
  - Equal sort keys (two recalls behind the same token) are ordered by token number.
  - `maxConsecutivePriority` may be 0 (NORMAL always first); `recallInsertOffset` must be at least 1.
  - `NextTokenPolicy`, `TokenView`, `QueueSnapshot` are public in `queue` because the ETA module will reuse them.
- Phase 2 crypto choices (PLAN §9.4 leaves these open):
  - Accepted phone input: Indian numbers with or without +91 / 91 / 0091 / leading 0, spaces, dashes, dots, brackets; must be a mobile (10 digits, starting 6–9). Other countries need `+` or `00`. Error message never repeats the input (`invalid-phone`, 400).
  - Encrypted value layout: version byte (1) + 12-byte random IV + ciphertext + 16-byte GCM tag. The version byte is there for future key rotation.
  - `BAARI_PHONE_PEPPER` is base64, at least 32 bytes (PLAN didn't fix a format). Both keys are validated at startup; missing or bad keys stop the app and name the env var, never the value.
  - Dev keys in `application-dev.yml`, test keys in `AbstractIntegrationTest` (both public, labelled as such).
  - `common.crypto` is a Modulith named interface so `queue` can use it.
  - `PhoneNumber.toString()` is masked, so an accidental log line can't leak a number.
- Phase 2 queue day choices (PLAN §3, §7, §11.1 leave these open):
  - `token_event.token_id` is nullable (PLAN has NOT NULL) so day events (opened, paused, resumed, closed) take a seq too; the per-day sequence stays gap-free.
  - Added a `QueueDayResumed` event (not in the §11.1 list) so every day status change has one.
  - Repeating an action already in effect (open an open or paused day, pause a paused one, resume an open one, close a closed one) is a no-op that returns the day. A closed day can't be reopened, paused or resumed (410 `queue-closed`). Acting before today's day exists is 409 `queue-not-opened`.
  - Open uses `INSERT … ON CONFLICT DO NOTHING`, so concurrent opens create one day (tested with 20 threads).
  - Who may run a day: any staff of the queue's clinic; a DOCTOR only for assigned queues (else 404); platform admin 403.
  - Day close writes one `token_event` per changed token (EXPIRED, or COMPLETED for auto-closed consultations) and one `QueueDayClosed` outbox event with the counts; no per-token outbox events. Close events are recorded as STAFF with the closing user's id.
  - Outbox row id = event id; aggregate = queue day; partition key = queue day id; event type = record name. `flags` stores `TokenFlag` names (upper case).
  - New `clinic.QueueDirectory` gives the queue module a queue and its clinic's time zone (no permission check; the queue module checks access).
- Phase 2 token issuance choices (PLAN §6.1 / Appendix A leave these open):
  - Order inside the transaction: lock the queue day row, then check idempotency key → day status → active token for the phone → open-token limit, then take the number. Everything is checked under the lock, so the unlocked pre-check PLAN mentions isn't needed, and no number is ever burned (a failed request rolls its number back). The unique constraints stay as a backstop: on a violation the whole transaction is retried once so the checks see the winning row. That retry path isn't exercised by a test (it can't be reached while every issuer takes the lock).
  - A retry with the same idempotency key returns its token whatever happened since, even after the day closed (status EXPIRED, position null).
  - While a day is PAUSED, QR joins get 423 but reception can still add walk-ins (confirmed by the user, 2026-11-14).
  - Open-token limit (`max-open-tokens`, 300) counts WAITING, CALLED, IN_CONSULTATION and NO_SHOW (same set as the phone index); over the limit → 409 `queue-full`. Applies to walk-ins too.
  - Patients joining by QR are always NORMAL; reception picks the priority for walk-ins. Name required (≤ 60 chars) for both; phone optional; walk-in idempotency key optional.
  - `position` is 1-based (1 = called next), computed with `NextTokenPolicy.projectOrder`.
  - Display code `A-017`: prefix + number padded to 3 digits, longer above 999. Public code: 22 random base62 characters (`SecureRandom`).
  - 409 `already-in-queue` names only the other token's display code, never its public code.
  - `baari.queue.*` settings bound in `QueueProperties` with the PLAN §14 defaults.
- Phase 2 token action choices (PLAN §4, §6.2, §6.3 leave these open):
  - Every action locks the token's queue day, then loads the token (fresh, after the lock). The token's location is read first with a projection query so the entity isn't cached before the lock. Because actions on one day are serialized, a conflicting second action normally gets 409 `illegal-transition`; `@Version` conflicts map to 409 `concurrent-modification` as a safety net.
  - Who is calling (call-next and override call): a DOCTOR always calls for themselves (naming another doctor → 400); reception/admin must name the doctor on a POOL queue (400 otherwise); a SINGLE queue defaults to its doctor. The doctor must be assigned to the queue (400).
  - Call-next works while the day is PAUSED, not when CLOSED; nobody waiting → 409 `no-one-waiting`.
  - Override call (`CallSpecific`) doesn't change the PRIORITY-in-a-row counter.
  - Direct start (WAITING → IN_CONSULTATION) sets the doctor only when it's obvious: the caller's own doctor login, or a SINGLE queue's doctor; otherwise it stays empty.
  - No-show before grace without force → 409 `grace-not-elapsed`.
  - Recall clears `called_at` and the doctor, so the timestamps describe the call that led to the consultation. Recall on a later day → 409 `illegal-transition`.
  - Patient cancel uses the public code (unknown → 404); cancelling again → 409.
  - Setting the priority a waiting token already has is a no-op (no event).
  - Staff token view includes name and last 4 phone digits, never the full number or the public code.
  - Concurrency test 5: PLAN's "complete vs no-show" can't race (no status allows both), so the test races start vs forced no-show on a CALLED token, plus a double-clicked complete.
- Phase 2 REST API choices (Appendix A leaves these open):
  - REST controllers live in `com.baari.queue.api` (not `web`), so `WebErrorAdvice` (HTML error pages for `com.baari.web`) doesn't catch their errors and they get RFC 9457 JSON from `ProblemDetailAdvice`.
  - Security: `/api/public/**` open and CSRF-exempt (no session to forge); `/api/staff/**` needs CLINIC_ADMIN, DOCTOR or RECEPTIONIST (platform admin 403) plus CSRF. Not logged in: `/api/**` → 401, pages → redirect to `/login`.
  - Join and walk-in: 201 when created, 200 when the idempotency key was used before. The key may come in the body or the `Idempotency-Key` header (body wins); join without either → 400.
  - Join response: `{publicCode, displayCode, status, priority, position, eta: null}`. `qrWindow`/`qrSig` accepted and ignored until Phase 8.
  - Public queue view (`GET /api/public/queues/{clinicSlug}/{queueSlug}`): queue id, clinic and queue names, active doctors' names, today's status (null = not opened), waiting count. Typical wait added in Phase 4.
  - Public token view: display code, status, position and people ahead (WAITING only), now-serving codes (called or in consultation, latest first), queue name and today's status. No name, phone or ids.
  - Patient cancel returns the token view; staff mutations return the staff token view; walk-in returns the issued token (with its public code, so reception can share the link); day actions return the day.
  - Board: today's day (null if not opened) and tokens split into waiting (in call order), called, in consultation, no-show, finished.
  - ADRs written: 0003 (counter row), 0004 (call-next row lock), 0005 (transactional outbox).

## Session log
- 2026-11-20 (Phase 4) — ETA v1 in one go: queue read models, pure estimators, projector, smoother and display rounding (61 unit tests), `EtaService`, prediction log with actuals and metrics, V4 migration, token page, stream and console wiring in English and Hindi, ADR 0010, `EtaIT` (17). Next: browser check, push, CI, tag `phase-4`, then Phase 5.
- 2026-11-20 (Phase 3 close) — CI green on `cc353ad`, reconnect check done by hand, open questions decided (ADR 0009), tagged `phase-3` on `cc353ad`. Next: Phase 4.
<!-- newest first: YYYY-MM-DD — what was done, what's next -->
- 2026-11-14 (Phase 3) — All Phase 3 tasks in one go: SSE hub with Redis fan-out and coalescing, patient join/token pages, staff console, TV display, poster print view, English and Hindi, ADR 0008, 33 new tests. Pushed. Next: reconnect manual check, CI, tag `phase-3`, then Phase 4.
- 2026-11-14 (Phase 2) — Task 7: public and staff REST APIs, `QueueQueryService` (join page, token page, board), API security rules, journeys J1–J7 and an API security/error matrix (20 integration tests), ADRs 0003–0005. Unit tests not run (user's request). Next: full verify with unit tests, push, CI, tag `phase-2`.
- 2026-11-14 (Phase 2) — Task 6: `TokenActionService` (call-next with day lock, override call, start, complete, no-show, recall, staff and patient cancel, priority), token events, 409 mapping for version conflicts, 34 integration tests incl. concurrency tests 4 and 5 ×5. Unit tests not run (user's request). Next: REST APIs + journeys + ADRs.
- 2026-11-14 (Phase 2) — Task 5: `TokenService` join and walk-in (lock → checks → number), `QueueProperties`, display/public codes, `TokenJoined` event, 34 integration tests incl. concurrency tests 1, 2, 3, 6 ×5. Unit tests not run (user's request). Next: staff transitions.
- 2026-11-14 (Phase 2) — Task 4: V3 migration, `QueueDayService` (open/pause/resume/close), `EventLog` (token_event + outbox), Token/QueueDay entities, 17 integration tests. Unit tests not run (user's request). Next: `TokenService` join / walk-in.
- 2026-11-14 (Phase 2) — Task 3: `common.crypto` (PhoneNumber, Masking, FieldCipher AES-256-GCM, PhoneHasher HMAC-SHA256, EncryptedStringConverter, startup key checks), 48 unit tests. Next: V3 migration + `QueueDayService`.
- 2026-11-14 (Phase 2) — Task 2: `NextTokenPolicy` (next, projectOrder, recallSortKey), 22 unit tests incl. 1,000 random queues; added to the 100% branch rule. Next: `common.crypto`.
- 2026-11-14 (Phase 2) — Task 1: `TokenStateMachine` (pure), 104 unit tests, JaCoCo check enforces 100% branch coverage (48/48). Next: `NextTokenPolicy`.
- 2026-11-14 (end) — Phase 1 pushed, CI green, tagged `phase-1`. Next: Phase 2.
- 2026-11-14 (later) — Phase 1 built in 5 commits (schema, admin services, security, dev seed, admin pages); all Done-when items covered by tests. Next: push + CI + tag, then Phase 2.
- 2026-11-14 — Pushed to github.com/Rishi5200/Baari; CI green. Phase 0 done, tagged `phase-0`. Next: Phase 1.
- 2026-11-10 (later) — Docker fixed. Full verify with ITs green, dev profile healthy, Failsafe and line-ending bugs fixed. Next: remote + CI, then Phase 1.
- 2026-11-10 — Phase 0 built without Docker: POMs, wrapper, skeleton, test infra, ops files, CI, ADR 0001. Integration tests unrun.
