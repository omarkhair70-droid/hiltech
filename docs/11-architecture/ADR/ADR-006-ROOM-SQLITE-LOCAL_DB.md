# ADR-006 — Client Local Database

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH needs durable local data on:
- Android field devices,
- Windows/JVM Desktop,
- offline command queues,
- cached read models,
- sync metadata,
- restart-safe local work.

The local persistence layer should share schema/repository contracts where practical.

## Decision

Use **Room3 with SQLite** as the primary KMP local database direction.

Use **BundledSQLiteDriver** as the baseline driver so Android and JVM Desktop use a consistent SQLite implementation.

Platform-specific database builders remain acceptable for filesystem/context setup.

## Evidence

SPIKE-03:
- GitHub Actions run 35295195896.
- Room3 code generation PASS.
- real SQLite persistence test PASS on Linux JVM Desktop.
- same real SQLite persistence test PASS on Windows JVM Desktop.
- Android app compile with generated Room code PASS.
- shared entity/DAO contract PASS.

Tested line:
- Room3 3.0.3,
- SQLite bundled driver 2.7.1,
- KSP 2.3.10,
- Kotlin 2.4.20,
- coroutines 1.11.0.

## Consequences

Positive:
- shared KMP local schema/DAO code,
- durable SQLite,
- suitable base for offline/sync metadata,
- consistent SQLite runtime across supported client platforms.

Costs:
- KSP/codegen version alignment is a real build dependency,
- Room3 APIs differ from older Room 2.x examples,
- production schema migrations must be explicitly tested.

## Important API Findings

For the tested Room3 line:
- Gradle extension is `room3 {}`.
- query coroutine setup uses `setQueryCoroutineContext(...)`.

Do not copy Room 2.x setup blindly.

## Not Proven By This ADR

This does not freeze:
- final production local schema,
- final sync protocol,
- released-version migration policy,
- background scheduling,
- binary upload persistence.

## Revisit Triggers

Revisit if:
- released migration tests become unreliable,
- desktop performance becomes unacceptable at real HILTECH cache sizes,
- Room/KSP tooling stability degrades materially,
- required encryption/storage policy cannot be met cleanly.
