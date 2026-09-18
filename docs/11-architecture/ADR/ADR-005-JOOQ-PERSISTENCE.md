# ADR-005 — PostgreSQL Persistence Access

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH's authoritative data model requires:
- explicit SQL visibility,
- strong PostgreSQL constraint usage,
- transaction boundaries around business commands,
- optimistic version checks,
- idempotency keys,
- ledger/movement tables,
- performance-sensitive read models,
- predictable migration behavior.

An ORM that hides SQL/domain boundaries is not required.

## Decision

Use **jOOQ** as the primary PostgreSQL SQL/persistence access layer for the HILTECH backend.

jOOQ is used for:
- transactional reads/writes,
- explicit SQL composition,
- PostgreSQL-native capabilities,
- type-safe access where useful,
- read projections and reporting queries.

Spring owns transaction/application integration.
PostgreSQL remains authoritative.
jOOQ does not own domain modeling.

## Evidence

SPIKE-11 / GitHub Actions run 35296301835 used:

- PostgreSQL 18.6
- jOOQ 3.21.8
- pgJDBC 42.7.13
- Kotlin 2.4.20
- Java 21

The spike proved:
- concurrent asset checkout yields one APPLIED + one CONFLICT,
- optimistic expected-version checks,
- idempotent operationId retry,
- one authoritative asset custodian,
- append ledger integrity,
- concurrent stock issue cannot drive stock negative.

jOOQ successfully executed the transactional SQL path used by those invariants.

## Persistence Rules

- each backend module owns its tables,
- no cross-module direct table writes,
- command transaction boundaries stay in application services,
- database constraints protect impossible states,
- version-aware writes use compare-and-update semantics,
- physical/financial histories use append-oriented movement/ledger tables where appropriate,
- JSONB is not a replacement for modeled domain fields.

## Code Generation

The exact jOOQ code-generation convention is **not frozen by this ADR**.

At schema freeze we still must decide:
- generated package structure,
- per-module generated schema ownership,
- whether all write paths use generated tables or a mixed DSL style,
- repository/query object naming,
- generated-source placement in the monorepo.

Those are implementation conventions, not reasons to reopen the jOOQ choice.

## Migrations

Flyway remains the leading migration tool, but migration baseline/versioning is frozen only after exact schemas exist.

## Revisit Triggers

Revisit jOOQ only if:
- production schema ergonomics become materially worse than an alternative,
- Kotlin/JVM compatibility becomes problematic,
- generated-source workflow creates unacceptable build/developer cost,
- a verified requirement cannot be expressed cleanly through PostgreSQL + jOOQ.
