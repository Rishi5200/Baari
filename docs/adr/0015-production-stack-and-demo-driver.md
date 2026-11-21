# 0015. Production stack, backups and the demo activity driver

Date: 2026-11-21 · Status: Accepted

## Context
Phase 9 (PLAN §12) asks for a Docker image, a production compose file with two app instances behind
Caddy, backups, a public demo "with the demo clinic and simulator-driven activity", and a README that
lets a stranger run the project with three commands.

## Decisions
1. **One multi-stage Dockerfile, two targets.** The build stage packages both modules; the default
   (last) stage is the app on a Temurin 21 JRE as a non-root user, with the Boot jar extracted into
   layers (`jarmode=tools`) so a code change only replaces the top layer. A `driver` target holds the
   simulator jar. Tests do not run in the image build: they need Docker (Testcontainers) and run in CI.
2. **Compose project `baari-prod`, only Caddy publishes ports.** The dev compose file lives in the same
   folder; without its own project name the prod stack would adopt the dev containers and database
   volume. The app port and the actuator port stay on the compose network, which ADR 0014 relies on
   (only Caddy may set `X-Forwarded-For`; checked: a client's own header does not dodge the login limit).
3. **Caddy round robin, no sticky sessions.** Sessions are in Redis and live updates fan out through
   Redis pub/sub, so any instance can serve any request. Caddy health-checks readiness on the
   management port, never compresses `*/stream` responses, and flushes immediately (`flush_interval -1`).
   `app2` starts after `app1` is healthy, so Flyway and the dev seed never run on both at once.
4. **Backups: a `postgres:17` sidecar running `pg_dump --format=custom` nightly at 02:00 clinic time**
   into a host directory, keeping 14 days, written to a temp file and renamed so a half-written dump
   never looks complete. Copying the directory off the machine is the host's job (rclone, rsync,
   provider snapshots), because it depends on where the VM runs. `backup.sh once` makes a backup now.
5. **Local demo = the production stack with `ops/demo.env`.** Profiles `prod,dev`: production
   settings, plus the dev seed (demo clinic, public logins, public keys). The env file says it is for
   a laptop only. This is what the README's three commands start and what CI starts.
6. **Demo activity is an external client, not code in the app.** `SimulatorMain drive` logs in as a
   receptionist and drives one queue through the same staff API as the console: open the day,
   walk-ins at the simulated clinic's average busy-hour rate, call, start after a 30 to 90 s walk,
   complete after a lognormal consultation length, a 30 s gap between patients. It reads the board
   each tick, so real visitors who join by QR are served like simulated ones. It runs at any hour (a
   demo visitor may come at night), caps the waiting list (default 6) so a visitor's wait stays short,
   and can run faster than real time (`--speed`; the local demo uses 3). The decision logic is a pure
   class (`DrivePolicy`, unit-tested); the app has no "demo mode".
7. **CI starts the production stack** after the build: images, `up --wait`, then
   `ops/check-fanout.sh` (a stream held on app1 must receive a call made on app2), smoke checks
   through Caddy and a manual backup. Dependabot proposes weekly updates for Maven, Actions, the
   Dockerfile base images and the compose images, minor and patch grouped.

## Consequences
- The fan-out check uses the demo clinic's `general` queue and the doctor login, because the driver
  serves `dr-sharma`; it needs nobody waiting on `general` (it calls leftovers first).
- On a real server, the demo driver needs a receptionist created through the admin pages, and the
  dev profile must not be used (its logins and keys are public).
- Prometheus and Grafana are not in the production compose file; they stay in the dev stack for now.
- The live URL needs a VM and a domain, which only the user can provide.
