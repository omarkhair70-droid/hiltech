# SPIKE-03 Result — Room KMP Local DB

Date: 2026-09-18
Decision: **ACCEPT — LOCAL DATABASE FEASIBILITY PASSED**

## Hypothesis

HILTECH can use one shared KMP Room schema/repository contract for Android and JVM Desktop while retaining small platform-specific database builders.

## Exact tested line

- Room3 3.0.3
- SQLite bundled driver 2.7.1
- KSP 2.3.10
- Kotlin 2.4.20
- kotlinx.coroutines 1.11.0
- Android API 36 / min API 23
- JDK 17

## Evidence

GitHub Actions run: 35295195896

Linux:
- real SQLite desktop test PASS.
- Android compile with generated Room code PASS.

Windows:
- real SQLite desktop test PASS.

Representative local object:
- PendingCommandEntity
- operationId
- commandType
- objectType/objectId
- baseVersion
- payload
- sync state
- attempt count
- timestamps.

The test inserted, queried and updated a real SQLite row through generated Room DAO code.

## Findings

Room3 differs from Room 2.x in configuration/API details:
- Gradle extension is `room3 {}`.
- query coroutine configuration uses `setQueryCoroutineContext(...)`.

These were tooling/API migration issues, not persistence failures.

## What this accepts

- Room3 as a credible shared local database direction.
- BundledSQLiteDriver as a credible cross-platform SQLite driver.
- shared entity/DAO code across Android + JVM Desktop.
- local durable storage for sync metadata/commands.

## What this does NOT accept yet

- final production local schema.
- schema migration policy across released versions.
- full offline command lifecycle.
- restart/reconnect behavior.
- conflict semantics.
- WorkManager/background scheduling.

Those remain SPIKE-04 / SPIKE-13 and final schema work.

## Production status

Disposable spike evidence only.
Not production bootstrap.
