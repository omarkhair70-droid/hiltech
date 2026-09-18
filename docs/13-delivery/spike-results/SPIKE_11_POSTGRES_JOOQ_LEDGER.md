# SPIKE-11 Result — PostgreSQL + jOOQ Ledger / Concurrency

Date: 2026-09-18
Decision: **ACCEPT — AUTHORITATIVE LEDGER / CONCURRENCY THESIS PASSED**

## Environment

Real PostgreSQL service in GitHub Actions.

Tested line:
- PostgreSQL 18.6
- jOOQ 3.21.8
- pgJDBC 42.7.13
- Kotlin 2.4.20
- Java 21

## Evidence

GitHub Actions run: 35296301835

All concurrency tests passed.

## Asset Checkout Collision

Two concurrent requests attempted to check out the same asset from version 1.

Proven:
- one APPLIED,
- one CONFLICT,
- one custodian,
- asset version increments once,
- one asset movement row.

## Idempotent Retry

Same operationId submitted twice.

Proven:
- first APPLIED,
- retry ALREADY_APPLIED,
- one movement only,
- version increments once.

## Stale Version

A second command used an old expected version.

Proven:
- rejected as CONFLICT,
- no second movement.

## Stock Collision

Two concurrent requests each attempted to issue 7 units from stock of 10.

Proven:
- one APPLIED,
- one CONFLICT,
- final quantity 3,
- quantity never goes negative due to database constraint/conditional update,
- one stock movement,
- version increments once.

## Accepted Direction

For HILTECH authoritative financial/physical state:
- PostgreSQL constraints protect impossible states.
- compare-and-update with expected version protects concurrency.
- operationId uniqueness protects retry idempotency.
- physical/financial movements are append-ledger oriented.

## jOOQ Finding

jOOQ successfully served as the transaction/SQL access layer in the spike.

This spike does not yet prove or freeze:
- generated-schema/codegen conventions,
- repository abstractions,
- query/read-model performance at production scale.

## Production Status

Disposable evidence only.
Final schema/migrations/repos remain pre-code freeze work.
