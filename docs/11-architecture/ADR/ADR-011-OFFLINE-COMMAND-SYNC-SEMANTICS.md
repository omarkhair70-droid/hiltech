# ADR-011 — Offline Command / Sync Semantics

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH field work must continue when connectivity is absent or unstable.

Important user actions are business commands, not arbitrary local row edits.

Examples:
- StartWorkOrder,
- AttachEvidenceMetadata,
- ConsumeMaterial,
- CompleteWorkOrder.

Naive last-write-wins could silently erase or corrupt real work.

## Decision

Offline mutation baseline:

1. persist a typed business command locally before reporting durable local success,
2. assign a stable operationId,
3. preserve stable local order/dependencies,
4. include baseVersion for collision-sensitive authoritative objects,
5. replay after reconnect,
6. treat operationId as idempotency key,
7. represent retryable uncertainty explicitly,
8. represent stale authoritative state as CONFLICT,
9. block dependent later commands when their prerequisite conflicts,
10. preserve local payload/evidence for resolution,
11. never silently overwrite authoritative server changes.

## Evidence

SPIKE-04 — GitHub Actions run 35296098137.

Real SQLite tests passed on Linux and Windows and Android compiled with the same shared sync code.

Proven:
- queue survives database close/reopen,
- commands replay in local sequence,
- APPLIED commands are not resent,
- server-committed/client-response-lost case safely retries,
- same operationId prevents duplicate business application,
- stale server version surfaces CONFLICT,
- later local work becomes BLOCKED_BY_CONFLICT,
- local evidence payload remains intact.

## Baseline Queue States

- PENDING
- RETRYABLE
- SYNCING
- APPLIED
- CONFLICT
- BLOCKED_BY_CONFLICT
- FAILED_TERMINAL

Exact final state names may evolve, but the semantics are accepted.

## Not Decided Here

- HTTP/Ktor transport format,
- binary upload protocol,
- WorkManager scheduling,
- final conflict-resolution UI,
- server permission-revalidation details,
- final local database schema.

## Revisit Triggers

Revisit if real field testing shows the command/dependency model cannot express required HILTECH work safely or if a materially simpler protocol proves the same guarantees.
