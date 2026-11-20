# 0009. Open questions resolved at the end of Phase 3

Date: 2026-11-20 · Status: Accepted (decided by Claude at the user's request: "decide the questions yourself for now"; the user can overrule any of them)

## Context
Four questions had been left open in PROGRESS.md. None blocks Phase 4, but each needs a decision so it
is not forgotten.

## Decisions
1. **Self-lockout of a clinic admin: add a guard (before Phase 8).** A clinic admin cannot disable
   or demote their own account, and nobody can disable or demote the last active `CLINIC_ADMIN` of a
   clinic. Rejected with HTTP 409 (`https://baari.app/problems/last-admin`). Why: a clinic with no
   admin cannot fix itself, and the platform admin should not have to repair it by hand. It is a small
   change in `clinic` (staff service) plus an integration test; it does not change the data model.
2. **`/actuator/prometheus` stays public in dev until Phase 8.** Phase 8 puts it behind the
   management port or a scrape credential. Why: local Prometheus scraping works today and the dev
   stack holds no real data. **Risk:** do not deploy (Phase 9) before this is done.
3. **Pin Prometheus and Grafana image versions in Phase 8.** `:latest` makes builds
   non-reproducible. Why Phase 8 and not now: the dev stack works, and the pin should be taken from
   what we actually run when we finish the dashboards.
4. **Port 8080 on the developer machine** is used by an unrelated process, so dev runs use
   `"-Dspring-boot.run.arguments=--server.port=8081"`. Nothing to change in the repo; the
   default port stays 8080 because CI and the compose stack assume it.

## Consequences
- Items 1 to 3 become explicit tasks (see PROGRESS.md "Next").
- Item 2 is a deploy blocker for Phase 9.
