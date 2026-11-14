# Progress log

Update this file at the end of every Claude Code session.

## Current phase
Phase 1 — Clinics, staff & auth (**complete**, tagged `phase-1`, CI green). Next: Phase 2.

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

## In progress
- Nothing

## Next
1. Phase 2 — Queue core (PLAN §12)

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

## Session log
<!-- newest first: YYYY-MM-DD — what was done, what's next -->
- 2026-11-14 (end) — Phase 1 pushed, CI green, tagged `phase-1`. Next: Phase 2.
- 2026-11-14 (later) — Phase 1 built in 5 commits (schema, admin services, security, dev seed, admin pages); all Done-when items covered by tests. Next: push + CI + tag, then Phase 2.
- 2026-11-14 — Pushed to github.com/Rishi5200/Baari; CI green. Phase 0 done, tagged `phase-0`. Next: Phase 1.
- 2026-11-10 (later) — Docker fixed. Full verify with ITs green, dev profile healthy, Failsafe and line-ending bugs fixed. Next: remote + CI, then Phase 1.
- 2026-11-10 — Phase 0 built without Docker: POMs, wrapper, skeleton, test infra, ops files, CI, ADR 0001. Integration tests unrun.
