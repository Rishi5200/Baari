# Progress log

Update this file at the end of every Claude Code session.

## Current phase
Phase 0 — Bootstrap (**not complete**: three checks need Docker, which isn't working yet)

## Done
- Parent POM: Java 21, Spring Boot 4.1.2, Modulith 2.1.2, Spotless (palantir), JaCoCo, Surefire (`*Test`), Failsafe (`*IT`)
- Modules `baari-app` and `baari-simulator` (placeholder main), Maven wrapper 3.9.16
- App skeleton: `BaariApplication`, `common` (injected `Clock`, `Ids`, `DomainException` + ProblemDetail advice)
- Flyway `V1__clinic.sql`; `application.yml` and `application-dev.yml`
- Tests: `MutableClock`, `AbstractIntegrationTest` (singleton Postgres/Redis/Kafka), `ApplicationSmokeIT`, Modulith `verify()`
- `ops/docker-compose.yml`, `ops/prometheus.yml`, `.github/workflows/ci.yml`, `.gitattributes`
- ADR 0001
- Verified locally: 8 unit tests pass (incl. Modulith verify); Spotless rejects a badly formatted file and passes once it's removed; `docker compose config` validates

## In progress
- Waiting on Docker Desktop (see open questions)

## Not verified yet (needs Docker)
- `./mvnw verify` with integration tests: `ApplicationSmokeIT` compiles but has never run, so the Testcontainers setup (Postgres 17, Redis 7, `apache/kafka:4.0.0`) and Flyway applying V1 are unproven
- App starts with the dev profile, health UP (needs `docker compose -f ops/docker-compose.yml up -d`)
- CI is green on GitHub (workflow written, never run; repo has no remote yet)

## Next
1. Get Docker working, run `./mvnw verify` and the dev-profile start, fix whatever breaks
2. Add a GitHub remote, push, confirm CI is green
3. Tick the three "Done when" boxes, tag `phase-0`, then start Phase 1

## Open questions for the user
- Docker Desktop crashes on start (stale `dockerInference` and `userAnalyticsOtlpHttp.sock` files in `%LOCALAPPDATA%\Docker\run` can't be deleted: error 1920). Restart the PC or delete them as Administrator.
- GitHub remote for CI: which repo/account?
- Prometheus and Grafana images use `:latest` for now; pin versions in Phase 8?

## Notes / decisions
- `Ids.newId()` uses random UUIDs, not UUIDv7: UUIDv7 needs the current time and the only allowed time source is an injected `Clock`.
- Boot 4.2 is only at RC, so we stay on 4.1.2.
- Git identity on this machine is `Admin` with a different email from the Claude account; commits use that.

## Session log
<!-- newest first: YYYY-MM-DD — what was done, what's next -->
- 2026-11-10 — Phase 0 built without Docker: POMs, wrapper, skeleton, test infra, ops files, CI, ADR 0001. Integration tests unrun. Next: fix Docker, run full verify, push and check CI.
