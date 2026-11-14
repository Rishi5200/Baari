# Progress log

Update this file at the end of every Claude Code session.

## Current phase
Phase 0 — Bootstrap (**complete**, tagged `phase-0`). Next: Phase 1.

## Done
- Parent POM: Java 21, Spring Boot 4.1.2, Modulith 2.1.2, Spotless (palantir), JaCoCo, Surefire (`*Test`), Failsafe (`*IT`)
- Modules `baari-app` and `baari-simulator` (placeholder main), Maven wrapper 3.9.16
- App skeleton: `BaariApplication`, `common` (injected `Clock`, `Ids`, `DomainException` + ProblemDetail advice)
- Flyway `V1__clinic.sql`; `application.yml` and `application-dev.yml`
- Tests: `MutableClock`, `AbstractIntegrationTest` (singleton Postgres 17 / Redis 7 / Kafka), `ApplicationSmokeIT`, Modulith `verify()`
- `ops/docker-compose.yml`, `ops/prometheus.yml`, `.github/workflows/ci.yml`, `.gitattributes`
- ADR 0001

## Verified locally (2026-11-10, Docker 29.9.3)
- `./mvnw verify`: BUILD SUCCESS. 8 unit tests, Modulith verify, and 2 integration tests (`ApplicationSmokeIT`: health UP, Flyway V1 applied) all pass
- Dev profile: app started against the compose stack, `/actuator/health` = 200 UP, `flyway_schema_history` has V1 with success = true
- Spotless fails on a badly formatted file and passes after it is removed

## CI
- GitHub Actions run on `f8f4c69` (ubuntu, Temurin 21, `./mvnw verify` with Testcontainers): success. https://github.com/Rishi5200/Baari/actions/runs/43581592618

## Bugs found and fixed while verifying
- Failsafe was only in `pluginManagement`, so `*IT` tests never ran and the earlier "green" build proved nothing about them. Now active.
- Java files were committed with CRLF, which Spotless rejects under `.gitattributes` (`eol=lf`). Repo now has `core.autocrlf=false` and sources are LF.

## In progress
- Nothing

## Next
1. Phase 1 — Clinics, staff & auth (PLAN §12)

## Open questions for the user
- Port 8080 on this machine is held by an unrelated process (`AgentService`, PID 5688), so the app can't use its default port here. Run it with `--server.port=8081` locally, or tell me if you want a different default.
- Prometheus and Grafana images use `:latest` for now; pin versions in Phase 8?
- The compose stack (`ops-*` containers) is left running; stop with `docker compose -f ops/docker-compose.yml down`.

## Notes / decisions
- `Ids.newId()` uses random UUIDs, not UUIDv7: UUIDv7 needs the current time and the only allowed time source is an injected `Clock`.
- Boot 4.2 is only at RC, so we stay on 4.1.2.
- Git identity on this machine is `Admin` with a different email from the Claude account; commits use that.

## Session log
<!-- newest first: YYYY-MM-DD — what was done, what's next -->
- 2026-11-14 — Pushed to github.com/Rishi5200/Baari; CI green. Phase 0 done, tagged `phase-0`. Next: Phase 1.
- 2026-11-10 (later) — Docker fixed. Full verify with ITs green, dev profile healthy, Failsafe and line-ending bugs fixed. Next: remote + CI, then Phase 1.
- 2026-11-10 — Phase 0 built without Docker: POMs, wrapper, skeleton, test infra, ops files, CI, ADR 0001. Integration tests unrun.
