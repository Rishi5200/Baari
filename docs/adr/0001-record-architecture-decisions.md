# 0001. Record architecture decisions

Date: 2026-11-10 · Status: Accepted

## Context
Baari makes several non-obvious choices (a counter row instead of a sequence, an outbox instead
of direct Kafka writes, SSE instead of WebSocket). Reasons get forgotten, and in an interview
the reasoning matters as much as the code.

## Decision
We write a short Architecture Decision Record in `docs/adr/NNNN-title.md` whenever we make a
design decision that PLAN.md doesn't already cover, using the template in PLAN §13. ADRs are
numbered in order, never deleted, and a changed decision gets a new ADR that supersedes the old one.
The planned list is in PLAN §13.

## Consequences
- Good: the "why" lives next to the code, and the README can link to it.
- Bad: a few minutes of writing per decision.
- Follow-up: each concurrency-related code comment points to its ADR number.

## Alternatives considered
- Wiki or Notion pages: drift away from the code and aren't versioned with it.
- No written record: cheapest now, costly later.
