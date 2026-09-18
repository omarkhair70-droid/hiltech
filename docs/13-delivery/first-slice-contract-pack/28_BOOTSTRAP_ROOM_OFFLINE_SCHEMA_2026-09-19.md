# 28 — Bootstrap Room / Offline Schema v1

Date: 2026-09-19
Status: **IMPLEMENTED / CI VERIFICATION PENDING**

## Purpose

Materialize the frozen first-slice Room/offline contract as the first production local schema.

Canonical source:
- `04_ROOM_OFFLINE_SYNC.md`
- ADR-006 / ADR-007 / ADR-011
- `17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`

## Room schema

Current:
- `ROOM_SCHEMA_VERSION = 1`
- `MIN_DIRECT_MIGRATABLE_ROOM_SCHEMA_VERSION = 1`
- command contract version: 1
- supported pending payload version: 1

There is no older production Room schema, so v1 is the migration baseline.
No destructive fallback exists.

First-slice local tables now cover:
- Project/Site/Work bundle context,
- immutable policy-binding identity,
- requirements,
- assignments,
- document refs,
- asset refs,
- pending commands,
- command dependencies,
- local evidence,
- explicit conflict records,
- scoped sync cursors.

UUID/domain IDs are persisted as canonical text values.
Local indexed time values use epoch milliseconds.
Pending payloads remain UTF-8 JSON with explicit `payloadVersion`.

## Queue safety

`PendingCommand` is inserted with ABORT-on-duplicate semantics.
A duplicate operationId cannot silently replace a different queued command.

Sync selection is restricted to:
- `PENDING`
- `RETRYABLE`

and respects durable `nextRetryAt` ordering.

Dependencies are persisted separately rather than inferred from timestamps.

## Persistence proof

Desktop Room contract test closes the database and reopens the same file, then proves:
- queued commands remain,
- state remains unresolved,
- local sequence remains,
- dependency edge remains.

This is production bootstrap verification of durable local intent across process/database restart.

## WorkManager safety

The existing Android worker previously returned unconditional success although no production sync engine was wired.

That is no longer allowed.

Until the Ktor/queue replay engine is wired, the worker fails explicitly with:
`SYNC_ENGINE_NOT_WIRED`

This preserves the invariant:
**no false sync success**.

## Exported schema

Room schema export is enabled at:
`shared/core/schemas`

Bootstrap CI must preserve the generated v1 schema in Git so future migrations can be verified against a real historical schema.

## Next after PASS

- Ktor durable replay orchestration,
- retry/backoff mapping,
- WorkManager constraints/scheduling,
- server error/conflict mapping,
- local evidence upload state machine.
