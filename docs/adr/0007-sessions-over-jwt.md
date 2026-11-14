# 0007. Sessions over JWT for staff

Date: 2026-11-14 · Status: Accepted

## Context
Staff (receptionists, doctors, clinic admins) use a server-rendered Thymeleaf + htmx console on
one domain. They need to log in, stay logged in through a 12-hour shift, and be locked out or
disabled immediately when an admin says so. The app may run as more than one instance behind a
proxy, so login state can't live in one JVM's memory.

## Decision
Spring Security form login with server-side HTTP sessions stored in Redis (Spring Session).
The browser holds only an opaque `SESSION` cookie (HttpOnly, SameSite=Lax). CSRF protection is
on, the session id changes at login (fixation protection), the idle timeout is 12 h, and logout
is a POST that deletes the session. The logged-in `StaffPrincipal` is read from the session by
`TenantContext`; the password hash is erased before the session is stored.

## Consequences
- Good: revocation is instant (delete the session in Redis). Disabling a user or ending a
  session needs no token blacklist.
- Good: nothing for the browser JavaScript to store or leak; the cookie is HttpOnly.
- Good: works across instances because every instance reads the same Redis.
- Bad: every request reads the session from Redis (sub-millisecond on the same host, fine here).
- Bad: cookies mean CSRF protection is mandatory. htmx sends the token from a meta tag.
- Follow-up: the session is Java-serialized, so `StaffPrincipal`/`StaffUserDetails` carry a
  `serialVersionUID`; changing their fields logs everyone out once, which is acceptable.

## Alternatives considered
- JWT in a cookie or header: stateless, but can't be revoked before expiry without a server-side
  blacklist, which brings the state back. It suits a future mobile app or third-party API, not a
  same-domain server-rendered UI.
- In-memory sessions with sticky load balancing: simpler, but a restart logs everyone out and
  it pins users to one instance.
- JDBC sessions (Postgres): works, but adds write load to the main database on every request;
  Redis is already in the stack for pub/sub and rate limits.
