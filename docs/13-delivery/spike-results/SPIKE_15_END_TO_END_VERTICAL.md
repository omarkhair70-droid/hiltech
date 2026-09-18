# SPIKE-15 Result — End-to-End Architectural Vertical

Date: 2026-09-18
Decision: **ACCEPT — CORE ARCHITECTURAL THESIS PASSED END TO END**

## Evidence

Primary GitHub Actions run: **35323209954**
Spike branch head: `5fd7e8aac857c25f85ea6b3f25bf9a758a9a045a`

Companion regression runs on the same head:
- Room3 KMP Offline: **35323209973** — PASS
- Offline Command Queue: **35323209948** — PASS
- Android Background Sync: **35323209949** — PASS

Final marker:

`HILTECH_SPIKE15_PASS desktop_create=PASS android_bundle=PASS offline=PASS evidence=PASS workmanager=PASS idempotency=PASS conflict=PASS authz=PASS supervisor=PASS desktop_read=PASS trace=PASS ktor=PASS`

## Proven Vertical

The disposable HILTECH-shaped flow proved:

1. PM Desktop authenticates through Keycloak-backed JWT and creates/assigns authoritative work.
2. Spring Boot/Spring Modulith server persists authoritative state in PostgreSQL through jOOQ.
3. OpenFGA allows the intended PM/technician/supervisor relations and denies an unrelated actor server-side.
4. Android downloads a durable work bundle through the shared Ktor client.
5. Room3 stores the offline bundle and typed commands locally.
6. Technician work is queued while offline with local evidence preserved.
7. WorkManager survives process death and resumes after connectivity returns.
8. Evidence uploads directly through an S3-compatible signed URL and finalizes only after SHA-256 verification.
9. Typed offline commands replay with operationId/baseVersion semantics.
10. Idempotent duplicate replay is recognized without duplicate authoritative action.
11. Supervisor accepts the exact submitted version.
12. PM Desktop reads the authoritative accepted state.
13. Audit/correlation data reconstructs PM, technician and supervisor activity.
14. Spring Modulith projections consume submitted/accepted events.
15. A deterministic stale-version scenario returns conflict and blocks the dependent local command.
16. The authoritative server state remains un-overwritten after conflict.

## Identity / Authorization Evidence

Marker:

`HILTECH_SPIKE15_KEYCLOAK_PASS jwt=PASS native_model=PKCE_S256 native_direct_grant=DISABLED`

OpenFGA marker:

`HILTECH_SPIKE15_OPENFGA_PASS ... pm=ALLOW tech=ALLOW supervisor=ALLOW outsider=DENY`

Server-side deny marker:

`HILTECH_SPIKE15_AUTHZ_DENY_PASS actor=outsider1`

The CI actor tokens are disposable service-account credentials used only to drive the integration harness. The accepted production native model remains Authorization Code + PKCE S256 from SPIKE-08.

## Android Happy Path

Marker:

`HILTECH_SPIKE15_ANDROID_HAPPY_PASS bundle=PASS offline=PASS workmanager=PASS evidence=PASS idempotency=PASS`

Proven:
- durable bundle,
- offline queue,
- local evidence,
- process death,
- constrained WorkManager replay,
- S3 evidence finalize,
- two applied commands,
- duplicate replay protection.

## Conflict Path

Marker:

`HILTECH_SPIKE15_ANDROID_CONFLICT_PASS conflict=PASS dependent_block=PASS evidence_preserved=PASS`

Proven:
- PM changes authoritative version while technician remains offline,
- stale technician command does not overwrite server truth,
- conflict is explicit,
- dependent command becomes blocked,
- local evidence remains available for human resolution.

## Desktop / Supervisor / Audit

Markers included:
- `HILTECH_SPIKE15_DESKTOP_CREATE_PASS work=wo-42 version=2 state=ASSIGNED`
- `HILTECH_SPIKE15_SUPERVISOR_PASS version=5 state=ACCEPTED`
- `HILTECH_SPIKE15_AUDIT_PASS events=6 projections=2`

## Ktor Acceptance

Ktor Client **3.5.2** passed as the shared networking boundary.

Accepted split:
- common/shared code owns DTOs, request conventions, correlation/idempotency headers, result mapping and sync transport contract;
- Android uses the OkHttp engine;
- JVM Desktop uses the CIO engine;
- UI/domain code does not depend on engine-specific APIs.

This satisfies the evidence gate for ADR-007.

## What This Closes

SPIKE-15 closes the remaining technical-spike gate.

Accepted technical evidence now composes across:
- KMP/Compose Android + Windows,
- Room3/SQLite,
- typed offline queue,
- WorkManager,
- Camera/evidence handoff,
- Ktor shared HTTP client,
- Keycloak,
- OpenFGA,
- Spring Boot + Spring Modulith,
- PostgreSQL + jOOQ,
- S3-compatible binary evidence,
- observability/correlation,
- Windows install/update/rollback.

## Still Not Production Code

This spike is disposable evidence.

It does **not** freeze:
- final production schemas,
- final HTTP resource catalog and payload schemas,
- production object-storage provider,
- infrastructure/hosting provider,
- production code-signing/distribution,
- exact backup/RPO/RTO,
- final UI/design system,
- real-company policy/authority facts.

Those remain pre-code freeze work.

## Production Status

Technical-spike gate: **CLOSED / ACCEPTED**.

Next program lanes:
1. reality validation,
2. exact build contracts,
3. representative design/RTL/conflict completion,
4. provider/runtime decisions,
5. Freeze Review,
6. repository bootstrap,
7. production implementation.
