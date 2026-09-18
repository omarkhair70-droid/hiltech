# SPIKE-04 — Offline Command Queue

Status: RUNNING

## HILTECH scenario

Technician receives Work Order `wo-42`, then loses connectivity.

Offline, the technician:
1. starts the work,
2. captures evidence metadata,
3. records material consumption,
4. completes the work,
5. closes/restarts the app,
6. reconnects.

The spike proves the local queue survives restart and sends commands exactly in local order.

It also proves two failure cases:

### Ambiguous network outcome

The authoritative side applies a command but the response is lost.

The client retries using the same `operationId`.

The server reports duplicate/already-applied instead of applying the business action twice.

### Authoritative change while offline

PM changes/cancels/reassigns the Work Order while the technician is offline.

The technician's first stale command becomes `CONFLICT`.

Later local commands for the same object become `BLOCKED_BY_CONFLICT`.

Evidence/payload rows remain stored for resolution.

## Queue states

- PENDING
- RETRYABLE
- SYNCING
- APPLIED
- CONFLICT
- BLOCKED_BY_CONFLICT
- FAILED_TERMINAL

## Pass criteria

ACCEPT if real SQLite tests on Linux and Windows prove:
- queued commands survive DB/app restart,
- order is preserved,
- successfully applied commands are not sent again,
- ambiguous network failure does not duplicate the business action,
- stale authoritative version surfaces a conflict,
- later local work is blocked, not silently overwritten,
- local payload/evidence remains available after conflict,
- Android still compiles with the shared queue/sync code.

## Not covered here

- real HTTP/Ktor transport,
- binary file upload,
- Android WorkManager,
- final conflict-resolution UX,
- final production sync schema.

Those are separate spikes/freeze work.
