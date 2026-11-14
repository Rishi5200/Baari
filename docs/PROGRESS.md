# Progress log

Update this file at the end of every Claude Code session.

## Current phase
Phase 2 — Queue core (in progress). Phase 1 complete, tagged `phase-1`.

## Phase 2 plan (7 tasks)
1. [x] `TokenStateMachine` + every status × command test + JaCoCo 100% branch rule
2. [x] `NextTokenPolicy` + §5 tests (incl. 1,000 random queues) + projection + recall sort key
3. [x] `common.crypto`: phone normalisation, AES-GCM, HMAC hash, `Masking.phone()`, JPA converter, keys from config
4. [x] V3 migration (queue_day, token, token_event, outbox_event) + `QueueDayService` (unit tests not run, see below)
5. [x] `TokenService` join / walk-in + concurrency tests 1, 2, 3, 6 (unit tests not run, see below)
6. [ ] Staff transitions (call-next row lock, start, complete, no-show, recall, cancel, priority) + tests 4, 5
7. [ ] REST APIs (Appendix A) + journeys J1–J7 + ADRs 0003–0005

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

## Verified locally (2026-11-14)
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

## In progress
- Phase 2, task 6: staff transitions (call-next row lock, start, complete, no-show, recall, cancel, priority) + tests 4, 5

## Next
1. Phase 2 tasks 4–7 (see plan above)

## Open questions for the user
- Nothing stops a clinic admin from disabling or demoting their own account (could leave a clinic with no admin). Add a guard?
- Port 8080 on this machine is held by an unrelated process (`AgentService`), so run locally with `--server.port=8081`.
- `/actuator/prometheus` is public for local scraping; lock down in Phase 8.
- Prometheus and Grafana images use `:latest`; pin versions in Phase 8?

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
  - While a day is PAUSED, QR joins get 423 but reception can still add walk-ins. **Confirm this is what you want.**
  - Open-token limit (`max-open-tokens`, 300) counts WAITING, CALLED, IN_CONSULTATION and NO_SHOW (same set as the phone index); over the limit → 409 `queue-full`. Applies to walk-ins too.
  - Patients joining by QR are always NORMAL; reception picks the priority for walk-ins. Name required (≤ 60 chars) for both; phone optional; walk-in idempotency key optional.
  - `position` is 1-based (1 = called next), computed with `NextTokenPolicy.projectOrder`.
  - Display code `A-017`: prefix + number padded to 3 digits, longer above 999. Public code: 22 random base62 characters (`SecureRandom`).
  - 409 `already-in-queue` names only the other token's display code, never its public code.
  - `baari.queue.*` settings bound in `QueueProperties` with the PLAN §14 defaults.

## Session log
<!-- newest first: YYYY-MM-DD — what was done, what's next -->
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
