# ADR-017 — Redis Baseline

Status: **ACCEPTED — EXCLUDE FROM INITIAL BASELINE**
Date: 2026-09-18

## Context

Redis is commonly added for caching, queues, sessions, locks and rate limiting.

HILTECH already has:
- PostgreSQL authoritative state,
- Keycloak identity/session infrastructure,
- Spring Modulith internal event reliability,
- client Room/SQLite offline state,
- object storage for binaries.

Adding Redis without a measured requirement would create another consistency and operations surface.

## Decision

Do **not** include Redis in the initial mandatory HILTECH stack.

Do not use Redis as:
- shadow authoritative state,
- substitute for correct PostgreSQL constraints,
- substitute for the Room offline queue,
- mandatory internal event broker,
- duplicate identity-session store owned by HILTECH.

## Acceptable Future Uses

Redis may be introduced when a measured need exists, for example:
- hot ephemeral cache with proven database pressure,
- distributed rate-limit counters,
- short-lived coordination where PostgreSQL is demonstrably unsuitable,
- provider-required infrastructure for a selected component.

Every use must define:
- cache invalidation/TTL,
- failure behavior,
- whether stale/missing Redis can affect correctness,
- observability,
- fallback.

## Correctness Rule

HILTECH business correctness must not depend on an unpersisted Redis value.

## Consequences

Positive:
- simpler baseline,
- fewer consistency classes,
- less infrastructure to secure/backup/monitor.

Cost:
- some future performance features may require adding Redis later.

## Revisit Trigger

Production measurements or a selected integration must show a clear latency/load/coordination requirement that cannot be met cleanly with the accepted baseline.
