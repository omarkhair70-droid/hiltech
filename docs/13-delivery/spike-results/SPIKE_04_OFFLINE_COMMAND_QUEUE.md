# SPIKE-04 Result — Offline Command Queue

Date: 2026-09-18
Decision: **ACCEPT — CORE OFFLINE QUEUE SEMANTICS PASSED**

## Scenario Proven

Technician queues:
1. StartWorkOrder.
2. AttachEvidenceMetadata.
3. ConsumeMaterial.
4. CompleteWorkOrder.

The queue is stored in real SQLite through the accepted Room3 path.

The database is closed and reopened to simulate app/process restart.

## Evidence

GitHub Actions run: 35296098137

Passed:
- Linux real SQLite restart/replay tests.
- Windows real SQLite restart/replay tests.
- Android compile with shared queue/sync code.

## Properties Proven

### Durability
Pending commands survive database/app restart.

### Ordering
Commands replay by stable local sequence.

### No replay after success
APPLIED commands are not sent again on a later sync pass.

### Ambiguous network outcome
The authoritative side may commit while the response is lost.

The client retries using the same operationId.

The simulated authoritative side returns DUPLICATE_APPLIED and the business action is counted once.

### Stale authoritative state
If server version changed while the technician was offline:
- first stale command becomes CONFLICT,
- serverVersion is recorded,
- later queued commands for the same object become BLOCKED_BY_CONFLICT,
- local evidence/payload remains stored for resolution.

## Accepted Direction

HILTECH offline writes are durable business commands, not blind row merges.

Baseline semantics:
- operationId/idempotency,
- stable local ordering,
- baseVersion for collision-sensitive commands,
- explicit retryable state,
- explicit conflict state,
- dependent command blocking,
- preserve local work for human/system resolution.

## Still Open

- real Ktor/HTTP transport.
- Android WorkManager execution.
- binary evidence upload.
- production conflict-resolution UI.
- final local schema/migrations.
- permission revocation during offline capture/replay.

These remain separate spikes/freeze gates.
