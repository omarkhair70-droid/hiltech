# ADR-007 — Ktor Client Shared Networking Boundary

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH uses one Kotlin Multiplatform client architecture across Android and JVM Desktop.

The networking layer must support:
- shared command/query contracts,
- authenticated HTTP,
- idempotency keys,
- optimistic baseVersion semantics,
- W3C/correlation propagation,
- offline queue replay,
- consistent error/result mapping,
- platform-appropriate engines,
- no engine-specific leakage into domain/UI layers.

## Decision

Use **Ktor Client 3.5.2** as the shared client HTTP/networking boundary.

Common/shared code owns:
- request/response DTOs,
- endpoint-facing client contract,
- serialization,
- Authorization bearer attachment,
- `Idempotency-Key`,
- `X-Correlation-Id`,
- `traceparent`,
- HTTP/result-code mapping,
- offline sync transport adapter.

Platform source sets own only the HTTP engine:
- Android: **OkHttp** engine,
- JVM Desktop: **CIO** engine.

UI/domain/use-case layers must not import engine-specific networking APIs.

## Evidence

SPIKE-15 — End-to-End Architectural Vertical.

GitHub Actions run: **35323209954**

Shared client compile:
- Android: PASS
- Desktop: PASS

Runtime proof:
- PM Desktop create/assign through Ktor,
- technician Android bundle fetch through Ktor,
- S3 evidence reservation/finalize,
- WorkManager offline command replay through Ktor,
- idempotent duplicate replay,
- explicit stale-version conflict mapping,
- supervisor acceptance,
- PM authoritative read/audit.

Final marker:

`HILTECH_SPIKE15_PASS desktop_create=PASS android_bundle=PASS offline=PASS evidence=PASS workmanager=PASS idempotency=PASS conflict=PASS authz=PASS supervisor=PASS desktop_read=PASS trace=PASS ktor=PASS`

Companion regression runs on the same head:
- Room3: 35323209973 — PASS
- Offline queue: 35323209948 — PASS
- WorkManager: 35323209949 — PASS

## Consequences

Positive:
- one networking contract can serve Android and Desktop,
- offline replay uses the same authoritative HTTP semantics as online commands,
- platform engines remain replaceable,
- tracing/idempotency/version headers are centralized,
- serialization and error mapping do not drift by surface.

Costs:
- shared client contract becomes a critical compatibility boundary,
- Ktor upgrades require Android + Desktop + offline regression coverage,
- exact production API schemas still require freeze discipline.

## Not Decided Here

This ADR does not freeze:
- every production endpoint/resource name,
- every payload schema,
- retry policy for every command class,
- production TLS termination/provider,
- API gateway choice,
- realtime transport/provider.

Those are exact-contract/infrastructure freeze decisions.

## Revisit Triggers

Revisit if:
- Ktor prevents a required native capability,
- a platform needs materially different transport semantics,
- measured production performance/reliability is inadequate,
- future iOS requirements expose a blocking engine/interop issue.

## Production Status

Architecture direction accepted.
SPIKE code remains disposable.
