# 0016. The first platform admin on a real server

Date: 2026-11-21 · Status: Accepted

## Context
No service can create a `PLATFORM_ADMIN` (PLAN §9.2: clinics cannot grant it), and the only code that
created one was the dev seed, which must never run on a real server (its logins are public). A fresh
production database therefore had no login at all, and the deploy in Phase 9 could not be finished.

## Decision
`PlatformAdminBootstrap` (clinic module, internal) runs at startup when `BAARI_ADMIN_USERNAME` is set.
If no account with that username exists, it creates a `PLATFORM_ADMIN` with `BAARI_ADMIN_PASSWORD`
(username lowercased; password 8 to 72 characters, otherwise startup fails rather than creating an
account nobody can log in to). If the username exists it does nothing, so a later start, or a changed
password in the environment, never overwrites the account. If both instances race, the unique
username constraint keeps one row and the loser logs that the account was created elsewhere (in the
compose file `app2` starts after `app1` is healthy anyway). The password is never logged.

Rejected: a hand-written SQL insert with a BCrypt hash (error-prone, needs the operator to hash a
password); a "first visitor becomes admin" setup page (whoever finds the URL first owns the server).

## Consequences
- The deploy steps are: fill in `ops/.env` (including the admin login), start the stack, log in,
  create the clinic and its first admin under Clinics.
- Checked on the production stack with only the `prod` profile: the admin is created once, the
  password is not in the logs, the dev logins are rejected, and a clinic can be created.
- There is no screen for a platform admin to change their own password; until there is, changing it
  needs a database step. Noted as future work.
