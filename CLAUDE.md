# CLAUDE.md — Baari (smart clinic queue with wait-time prediction)

> "Baari" is Hindi for "turn", as in *meri baari kab aayegi?* ("when will my turn come?").
> This file is loaded into every Claude Code session. Keep it accurate. The full
> specification lives in `docs/PLAN.md`. Read the relevant section of it before starting any task.

---

## 1. What we are building (30-second version)

Patients in Indian clinics wait 1–2 hours for a 5-minute consultation, with no idea when
their turn will come. Baari lets a patient **scan a QR code at the clinic, get a token,
and watch a live, honest estimate of their waiting time** ("your turn in ~20–30 min")
on their phone. They can step out instead of sitting in a crowded waiting room.
Reception and doctors run the queue from a simple web console; a TV in the waiting room
shows the current token.

What makes this project stand out (protect these features; they are the point):
1. **Wait-time prediction.** Starts with a simple per-doctor average, moves to rolling
   statistics, then to an ML regression model trained in Java. Every model is evaluated
   against the simple average on held-out data, and the error numbers are published.
2. **Correctness under concurrency.** No duplicate or skipped token numbers, no patient
   called twice, and a repeated join request returns the same token.
3. **Reliable events.** Transactional outbox → Kafka → idempotent consumers →
   notifications with retries and a dead-letter topic.
4. **Live updates.** Server-Sent Events, fanned out across instances via Redis pub/sub.
5. **Privacy and abuse resistance.** Phone numbers encrypted at rest, PII purged after a
   retention period, rate limiting, rotating QR codes.

This is a portfolio project for a **Java backend developer job search**. Code quality,
tests, and a clear README matter as much as features. The developer must be able to
explain every line in an interview, so prefer clear code over clever code.

---

## 2. Tech stack (do not change without asking)

| Concern | Choice |
|---|---|
| Language | **Java 21** (records, sealed interfaces, pattern matching, virtual threads) |
| Framework | **Spring Boot 4.1.x** (Spring Framework 7). Use the latest 4.1.x patch; if 4.2 is GA, ask before upgrading |
| Build | Maven (wrapper `./mvnw`), multi-module |
| DB | PostgreSQL 17, **Flyway** migrations, Spring Data JPA (Hibernate 7) + `JdbcClient` for hot-path SQL |
| Cache / pub-sub / sessions / rate-limit | Redis 7 (Spring Data Redis, Spring Session) |
| Messaging | Apache Kafka (KRaft, single node locally) via Spring for Apache Kafka |
| UI | Server-rendered **Thymeleaf + htmx** + small vanilla JS (`EventSource`). No SPA framework |
| Real-time | Server-Sent Events (`SseEmitter`), virtual threads enabled |
| ML | **Tribuo** (Oracle, Apache-2.0) for regression. Fallback: hand-written ridge regression (see PLAN §8.6) |
| Rate limiting | Bucket4j with Redis backend |
| Tests | JUnit Jupiter, AssertJ, Mockito (sparingly), **Testcontainers 2.x** with `@ServiceConnection`, Spring Modulith `verify()` |
| Observability | Spring Boot Actuator, Micrometer → Prometheus, Grafana dashboard JSON in repo |
| Formatting | Spotless with palantir-java-format |
| CI | GitHub Actions |
| Deploy | Docker image + docker compose on one VM, Caddy for HTTPS |

### Spring Boot 4 / Testcontainers 2 gotchas (your training data may predate these)
- Boot 4 splits auto-configuration into smaller modules and **renames some starters**
  (e.g. `spring-boot-starter-webmvc` for Spring MVC). Generate the initial POM from
  start.spring.io conventions for Boot 4.1 and check the official docs if a starter is missing.
- Boot 4 uses **Jackson 3** (`tools.jackson.*` packages). Jackson *annotations* stay in
  `com.fasterxml.jackson.annotation`. If an import fails, check which Jackson major version applies.
- Testcontainers 2.x: artifacts use the `testcontainers-` prefix (e.g. `testcontainers-postgresql`),
  container classes moved to module packages (e.g. `org.testcontainers.postgresql.PostgreSQLContainer`),
  and JUnit 4 support is gone.
- JUnit 4 is not used anywhere. Use `org.junit.jupiter.*` only.
- If something doesn't compile and you're unsure of the Boot 4 API, **look it up in the official
  Spring docs** rather than guessing from Boot 3 memory.

---

## 3. Repository layout

```
baari/
├── CLAUDE.md                  ← this file
├── README.md                  ← public-facing (written in Phase 9)
├── docs/
│   ├── PLAN.md                ← full spec: domain, data model, algorithms, API, phases
│   ├── PROGRESS.md            ← running log: what's done, what's next (update every session)
│   ├── adr/                   ← architecture decision records (0001-*.md …)
│   └── eta-evaluation.md      ← model metrics (Phase 6+)
├── pom.xml                    ← parent POM (dependency management, plugins)
├── baari-app/                 ← the Spring Boot application (modular monolith)
│   └── src/main/java/com/baari/
│       ├── BaariApplication.java
│       ├── common/            ← Clock config, errors (ProblemDetail), ids, crypto, web utils
│       ├── clinic/            ← Clinic, Doctor, ServiceQueue, staff users, admin CRUD
│       ├── identity/          ← Spring Security config, login, roles, tenant context
│       ├── queue/             ← QueueDay, Token, state machine, NextTokenPolicy, join/call APIs
│       ├── eta/               ← estimators, projection, ranges, prediction log, ML training
│       ├── realtime/          ← SSE emitters, Redis pub/sub fan-out, snapshots
│       ├── events/            ← outbox, relay to Kafka, processed-event dedup
│       ├── notification/      ← planner, dispatcher, channels (log, telegram, sms stub)
│       ├── analytics/         ← daily stats for clinic dashboard
│       └── web/               ← Thymeleaf controllers & view models (thin)
├── baari-simulator/           ← plain Java 21 CLI: discrete-event clinic simulator
├── ops/
│   ├── docker-compose.yml     ← local dev: postgres, redis, kafka, prometheus, grafana
│   ├── docker-compose.prod.yml
│   ├── Caddyfile
│   ├── prometheus.yml
│   └── grafana/dashboards/baari.json
├── load/                      ← k6 scripts
└── .github/workflows/ci.yml
```

Each top-level package under `com.baari` is a **Spring Modulith application module**.
Modules talk to each other only through their public API (top-level package types) or
through domain events. Internals go in a sub-package named `internal`. A test runs
`ApplicationModules.of(BaariApplication.class).verify()` and must stay green.

Allowed dependencies (arrow = "may call"):
`web → queue, clinic, eta, realtime, analytics` ·
`queue → clinic, common` · `eta → queue (read models), clinic, common` ·
`realtime → queue, eta, common` · `notification → queue, eta, clinic, events, common` ·
`events → common` · `identity → clinic, common` · everything → `common`.
`queue` must NOT depend on `eta`, `realtime`, or `notification`. It publishes events instead.

---

## 4. Commands

```bash
# start infrastructure (postgres, redis, kafka, prometheus, grafana)
docker compose -f ops/docker-compose.yml up -d

# run the app with the dev profile (seeds a demo clinic, log notification channel)
./mvnw -pl baari-app spring-boot:run -Dspring-boot.run.profiles=dev

# full build: format check + unit + integration tests (needs Docker running)
./mvnw verify

# fix formatting
./mvnw spotless:apply

# only unit tests (fast)
./mvnw -pl baari-app test -Dgroups=unit

# simulator: generate 60 days of synthetic history for the demo clinic
./mvnw -pl baari-simulator exec:java -Dexec.args="generate --config sim/demo-clinic.json --days 60 --out build/sim"

# load test
k6 run load/join-and-poll.js
```

Dev URLs: app http://localhost:8080 · Grafana http://localhost:3000 · Prometheus http://localhost:9090.
Dev seed logins are defined in `application-dev.yml` (never used outside the dev profile).

---

## 5. How to work in this repo (rules for Claude)

### Workflow
1. **At the start of a session:** read `docs/PROGRESS.md`, then the PLAN.md section for the
   current phase. Say in 3–6 bullets what you will do before writing code.
2. Work on **one phase at a time**, and within a phase one task at a time. Don't start the
   next phase until the current phase's "Done when" checklist in PLAN.md is fully met.
3. Keep changes small and reviewable. After each task: run `./mvnw verify`, then commit
   with a Conventional Commit message (`feat(queue): atomic token issuance`).
4. **At the end of a session:** update `docs/PROGRESS.md` (done / in progress / next /
   open questions). If you made a design decision not covered by PLAN.md, write an ADR in
   `docs/adr/` (template in PLAN §13).
5. If PLAN.md doesn't cover a requirement or seems wrong, **stop and ask the user**.
   Don't invent product behaviour.

### Hard rules
- **Never** disable, skip, or delete a failing test to make the build pass, and never
  weaken an assertion. Fix the code, or explain why the test is wrong and ask.
- **Never** use H2 or any in-memory DB substitute. All persistence tests use Testcontainers
  PostgreSQL (we rely on partial unique indexes, `RETURNING`, `FOR UPDATE`, `SKIP LOCKED`).
- **Never** call `Instant.now()`, `LocalDate.now()`, `System.currentTimeMillis()` etc. in
  production code. Inject `java.time.Clock` (bean in `common`). Tests use `MutableClock`.
  Time is the core input of this product; untestable time is a bug.
- **Never** edit a Flyway migration that has been committed. Add a new `V{n}__*.sql`.
- **Never** log patient names, phone numbers, or Telegram chat ids. Use `Masking.phone()`.
- **Never** put business logic in controllers. Controllers translate HTTP ↔ service calls.
- **Never** read `clinicId` from a request parameter or body in staff endpoints. It comes
  from the authenticated principal (`TenantContext`). See PLAN §9.2.
- **Do not add dependencies** beyond those in §2 and PLAN.md without asking.
- No Lombok. Use records for DTOs, events, value objects. Constructor injection, `final` fields.
- Don't build an SPA, GraphQL, microservices, or Kubernetes manifests unless asked.

### Code conventions
- Package by feature (modules above), not by layer.
- Entities: JPA entities live in `internal` sub-packages; expose read models (records) publicly.
- Ids: `UUID` generated in the application (`Ids.newId()`, UUIDv7 if available, else random).
- Timestamps: `Instant` in Java, `timestamptz` in Postgres. "Service date" is a `LocalDate`
  computed in the clinic's time zone (default `Asia/Kolkata`).
- Errors: throw domain exceptions (`TokenNotFound`, `IllegalTransition`, `QueueClosed`, …)
  mapped to RFC 9457 `ProblemDetail` in one `@RestControllerAdvice`. Error `type` URIs like
  `https://baari.app/problems/illegal-transition`.
- Validation: Jakarta Bean Validation on request records.
- Hot paths (token issuance, call-next) may use `JdbcClient` with explicit SQL for clarity and
  locking control; everything else uses Spring Data JPA.
- Optimistic locking: `@Version` on `Token`. A version conflict → HTTP 409.
- Tests: name them `*Test` (unit, tagged `unit`) and `*IT` (integration, Testcontainers).
  Integration tests extend `AbstractIntegrationTest`, which starts singleton containers once per JVM.
- Every public algorithm (`NextTokenPolicy`, `QueueProjector`, estimators, hysteresis) is a
  **pure function or pure class** with exhaustive unit tests. No Spring needed to test them.
- Comments explain *why*, not *what*. Every non-obvious concurrency decision gets a comment
  pointing to its ADR.

### Definition of done (every task)
- [ ] `./mvnw verify` passes locally (format, unit, integration, modulith verify)
- [ ] New behaviour has tests, including at least one failure/edge case
- [ ] No PII in logs; no `now()` calls; no new unapproved dependencies
- [ ] PROGRESS.md updated; ADR written if a decision was made

---

## 6. Domain cheat-sheet (details in PLAN §3–§7)

- **Clinic** (tenant): has a time zone, doctors, staff users, service queues.
- **ServiceQueue**: a line patients join. Served by one doctor (`SINGLE`) or a pool (`POOL`).
- **QueueDay**: one queue on one service date. Holds the atomic `last_number` counter and
  `last_event_seq`. Status `OPEN → PAUSED ⇄ OPEN → CLOSED`.
- **Token**: a patient's place in a QueueDay. Display code like `A-017`; secret `publicCode`
  (22-char base62) is the patient's capability URL.
- **Token states**: `WAITING → CALLED → IN_CONSULTATION → COMPLETED`;
  `CALLED → NO_SHOW → (recall) WAITING`; `WAITING|CALLED → CANCELLED`;
  open tokens → `EXPIRED` when the day closes. All transitions go through `TokenStateMachine`.
- **Priority**: `EMERGENCY` (always next), `PRIORITY` (elderly, pregnant, disabled; at most
  N in a row before a NORMAL patient is served), `NORMAL`.
- **NextTokenPolicy**: the single source of truth for who is called next. It is reused by
  `QueueProjector` to compute positions and ETAs, so the predicted order is always the real order.
- **ETA** = projected time until the patient's consultation *starts*, shown as a range
  (p20–p80), smoothed with hysteresis so it doesn't jump around.

---

## 7. Current status

See `docs/PROGRESS.md`. Phase list (details and acceptance criteria in PLAN §12):

0 Bootstrap · 1 Clinics, staff & auth · 2 Queue core · 3 UI & real-time · 4 ETA v1 ·
5 Events & notifications · 6 Simulator & backtesting · 7 ML ETA · 8 Hardening & observability ·
9 Deploy & README · 10 Real-clinic pilot
