# SPIKE-10 — Spring Modulith

Status: RUNNING

## Hypothesis

HILTECH can begin as a Kotlin/Spring Boot modular monolith with:
- explicit functional modules,
- architecture verification,
- transactional application events,
- durable JDBC event publication log,
- failure recovery after process restart,
- no external broker required for internal module reliability.

## Exact tested line

- Spring Boot 4.1.1
- Spring Modulith 2.1.1
- Kotlin 2.4.20
- Java 21
- H2 only as disposable spike persistence for event-registry recovery

H2 is not the proposed HILTECH production database.

## Representative modules

- people
- work
- warehouse
- audit

## Failure scenario

1. Work module completes Work Order wo-42 transactionally.
2. WorkCompleted event is published.
3. Audit module listener intentionally throws.
4. Event publication remains incomplete in the persistent JDBC registry.
5. Application context shuts down.
6. New application context starts against the same database.
7. Listener is healthy.
8. outstanding publication is automatically republished.
9. audit_log is written exactly once.
10. incomplete publication count becomes zero.

## Pass criteria

ACCEPT if:
- module verification passes,
- event publication is persisted before failed listener is lost,
- failed listener does not erase the publication,
- closing/restarting the app preserves the publication,
- restart republish reaches the listener,
- audit projection is written,
- publication is marked complete.

## Production status

Disposable spike evidence.
Not production backend bootstrap.
