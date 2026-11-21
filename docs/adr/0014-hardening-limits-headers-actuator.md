# 0014. Hardening: rate limits, anti-bot checks, headers, actuator port, last-admin guard

Date: 2026-11-21 · Status: Accepted

## Context
Phase 8 (PLAN §9.3, §9.5) hardens the public surface before deployment. ADR 0009 left two items for
this phase: the last-admin guard and locking down `/actuator/prometheus`.

## Decisions
1. **Per-IP rate limits in a servlet filter** (`RateLimitFilter`, Bucket4j with buckets in Redis, so
   all instances share them): join 10/min, token page 60/min, staff login 10/min. Over the limit:
   429 with `Retry-After` (an RFC 9457 body under `/api`). The filter runs before Spring Security so
   the login POST is counted before the password is checked, and after the forwarded-header filter so
   behind Caddy the address is the client's. If Redis is unreachable the request is let through:
   the limiter must not take the queue down. Tests switch the filter off (all test requests share one
   address); `RateLimitIT` switches it on with small limits.
2. **Per-phone limit as a business rule**, not in the filter: a phone may join one queue by QR at most
   `max-joins-per-phone` (3) times a day, counted in the database under the day's row lock, so it
   holds across instances and races. Reception walk-ins are not limited. 429 `too-many-joins`.
3. **Anti-bot on the join form**: a hidden "website" field (filled only by bots) and the render time
   in a hidden field; a form sent within 1.5 s or with the honeypot filled is shown again with a
   neutral message. Unsigned on purpose: it only has to stop naive bots; rate limits do the rest.
4. **Content Security Policy with a per-request nonce** (`SecurityHeadersFilter`): everything from
   `'self'`, no inline handlers, `frame-ancestors 'none'`. The three pages that pass translated texts
   to their script keep a small inline block that carries the nonce. Plus `Referrer-Policy` and
   `Permissions-Policy`; Spring Security already sends `nosniff`, `X-Frame-Options: DENY` and HSTS on
   HTTPS.
5. **Actuator on a management port** (`BAARI_MANAGEMENT_PORT`, default 8086) that Caddy never
   forwards. Health stays open there for container probes; Prometheus scrapes it inside the network.
   The public port serves no actuator endpoint (tested).
6. **Last-admin guard** (ADR 0009 item 1): an admin cannot disable or demote themselves, and the last
   active admin of a clinic cannot be disabled or demoted (409 `last-admin`). The clinic's active admin
   rows are locked first, so two admins demoting each other at once cannot both succeed.

7. **Rotating QR.** The TV link carries the minute window and the first 128 bits of
   `HMAC-SHA256(qr_secret, queueId|window)`; a join is accepted for the current and the previous
   window only, checked under the day's lock after the retry lookup (so a retried join keeps its
   token). The TV fetches a fresh link every 20 s from `/api/display/queues/{id}/join-link`.
8. **PII retention.** A nightly job removes name and phone (ciphertext, hash, last four digits) from
   tokens older than 30 days, in batches with `SKIP LOCKED`, and bumps the token's version so a stale
   JPA entity cannot write them back. It publishes `PiiPurged` in the same transaction; the
   notification module deletes the Telegram links of those tokens.
9. **Observability.** Prod logs are one ECS JSON object per line (`application-prod.yml`) with
   `clinicId`, `staffUserId` and `queueDayId` from the MDC. New metrics `baari.tokens.issued` and
   `baari.wait.actual.seconds` (histogram) feed the provisioned Grafana dashboard; Prometheus
   v3.16.0 and Grafana 13.3.0 are pinned (ADR 0009 item 3).
10. **Live-update fan-out is bounded** (found by the k6 test). With 300 open streams, every queue
    change rebuilt 300 snapshots at once on unbounded virtual threads; each reads the database, so
    the 10-connection pool had thousands of waiters and joins took 20 s or failed (p95 21 s, 15
    errors). Now one flush per queue runs at a time (later changes merge into the next) and snapshot
    builds share 4 permits. Rerun: join p95 73 ms, no errors, 300 of 300 joined, numbering 1..598
    without gaps. `docs/images/grafana-dashboard.png` shows both runs.
11. **Estimator cache race** (found by the full build): a background projection could store a cache
    entry built from history read just before a consultation finished, hiding the new data for up to
    a minute. Entries now carry a per-clinic generation that a finished consultation bumps.

## Consequences
- In production the app relies on Caddy to overwrite `X-Forwarded-For`; the app port must never be
  exposed directly, or a client could choose its own address for the rate limits.
- The QR image on the TV for rotating mode still needs a QR library (ZXing, to be approved); the
  signing and checking of rotating links does not.
- Dev Prometheus now scrapes `host.docker.internal:8086`.
