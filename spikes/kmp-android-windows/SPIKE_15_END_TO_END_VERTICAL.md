# SPIKE-15 — End-to-End Vertical Proof

Status: RUNNING
Branch: `spike/e2e-vertical-20260918`

This is disposable integration evidence, not production code.

## Reused Proven Base

This branch is based on SPIKE-13 so it already contains:
- KMP Android + JVM Desktop client foundation,
- Room3/SQLite local database,
- typed offline command queue,
- conflict/idempotency model,
- WorkManager background scheduling.

SPIKE-15 adds:
- shared Ktor Client 3.5.2 contract,
- real authenticated HTTP boundary,
- Spring Boot / Spring Modulith integration server,
- PostgreSQL/jOOQ authoritative work state,
- OpenFGA authorization checks,
- S3-compatible evidence reservation/upload/finalize,
- end-to-end correlation/audit,
- Desktop PM -> Android technician -> Supervisor -> Desktop PM proof.

## Ktor Boundary

Common code owns:
- command/query DTOs,
- headers,
- JSON contract,
- error/result mapping,
- sync transport adapter.

Platform-specific source sets own only the engine:
- Android: OkHttp.
- JVM Desktop: CIO.

No UI/domain layer imports engine-specific APIs.

## Pass Marker

Final workflow must emit:

`HILTECH_SPIKE15_PASS desktop_create=PASS android_bundle=PASS offline=PASS evidence=PASS workmanager=PASS idempotency=PASS conflict=PASS authz=PASS supervisor=PASS desktop_read=PASS trace=PASS ktor=PASS`

See canonical execution contract:
`docs/13-delivery/SPIKE_15_END_TO_END_VERTICAL_SPEC.md` on main.
