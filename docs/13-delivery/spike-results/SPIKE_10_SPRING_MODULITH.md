# SPIKE-10 Result — Spring Modulith

Date: 2026-09-18
Decision: **ACCEPT — MODULAR MONOLITH + DURABLE MODULE EVENT RECOVERY PASSED**

## Environment

- Spring Boot 4.1.1
- Spring Modulith 2.1.1
- Kotlin 2.4.20
- Java 21

GitHub Actions run: 35304479330.

H2 file persistence was used only as disposable spike storage for restart/recovery. PostgreSQL remains the accepted production database direction.

## Modules

Representative modules:
- people
- work
- warehouse
- audit

## Architecture Verification

`ApplicationModules.of(HiltechBackendSpikeApplication::class.java).verify()` passed.

This proved the candidate module boundaries were valid under Spring Modulith's architecture verification.

## Failure / Recovery Scenario

1. Work module transaction completed Work Order `wo-42`.
2. WorkCompleted event was published.
3. Audit module listener intentionally threw.
4. Spring Modulith left the event publication incomplete/durable.
5. First application context closed.
6. New application context opened against the same persistent database.
7. Failed publication was still present.
8. Official incomplete-publication resubmission API was invoked.
9. Healthy audit listener processed the event.
10. audit_log contained one projection row.
11. incomplete publication registry became empty.

## Important Finding

Automatic startup replay was not relied on as the operational contract.

The accepted pattern is:
- persist module publication,
- observe incomplete publications,
- explicitly/controlably resubmit incomplete publications,
- make listeners idempotent.

This is more suitable for production operations and multi-instance control.

## Accepted Direction

HILTECH backend may start as:
- Kotlin/JVM,
- Spring Boot,
- Spring Modulith modular monolith,
- one deployable backend initially,
- explicit module ownership/boundaries,
- durable internal events where asynchronous module coordination is useful.

An external message broker is not required solely for reliable internal module events at the initial architecture stage.

## Still Open

- final PostgreSQL-backed publication registry configuration,
- production retry/backoff policy,
- dead-letter/escalation policy,
- observability dashboards,
- multi-instance scheduling/locking behavior,
- exact module package conventions.

## Production Status

Disposable technical evidence only.
Not production backend bootstrap.
