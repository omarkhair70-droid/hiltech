# SPIKE-02 — Room3 KMP Local DB + Offline Queue

Status: RUNNING

## Hypothesis

HILTECH can use one KMP Room database model for Android and JVM Desktop while keeping platform-specific filesystem builders small.

The local database can safely persist the basic metadata required for an offline business-command queue.

## Tested line

- Room3 3.0.3 — stable
- SQLite 2.7.1
- BundledSQLiteDriver
- KSP 2.3.10
- Kotlin 2.4.20
- kotlinx.coroutines 1.11.0

## Representative HILTECH object

PendingCommandEntity:
- operationId
- command type
- object type/id
- baseVersion
- payload
- state
- attempts
- timestamps

This is not the final sync schema.
It exists to prove the platform/persistence path.

## Pass

ACCEPT if:
- Room code generation succeeds for Android + Desktop.
- Android app compiles with the shared DB module.
- a real SQLite JVM Desktop test inserts, queries and updates PendingCommand.
- Windows can run the same Room desktop test.
- BundledSQLiteDriver works without platform-specific DAO/schema duplication.

## Modify

MODIFY if the architecture works but:
- Room3/KSP version alignment needs adjustment,
- driver choice changes,
- target-specific builder changes are required.

## Reject

REJECT if:
- Room generates materially divergent models/DAOs,
- JVM Desktop persistence is unreliable,
- KSP/tooling is unstable enough to threaten normal CI.

## Production status

Disposable technical evidence only.
Not production schema.
