# 29 — Bootstrap Durable Command Replay Core

Date: 2026-09-19
Status: **IMPLEMENTED / CI VERIFICATION PENDING**

## Purpose

Create the production bootstrap replay core that consumes the frozen Room queue contract without pretending that WorkManager or HTTP is business truth.

Canonical source:
- `04_ROOM_OFFLINE_SYNC.md`
- `02_API_AND_READ_MODELS.md`
- ADR-007 / ADR-011
- accepted SPIKE-04 / SPIKE-13 / SPIKE-15 semantics.

## Boundary

Room remains authoritative for local queued intent.

`PendingCommandReplayEngine`:
1. loads only due `PENDING` / `RETRYABLE` commands,
2. preserves local sequence,
3. waits for persisted dependencies to be `APPLIED`,
4. executes through a typed `PendingCommandTransport` boundary,
5. persists the transport result transactionally,
6. never deletes local intent merely because replay failed.

The engine deliberately does not persist a long-lived `SYNCING` lease yet.
If the process dies during an ambiguous HTTP result, the durable command remains replayable with the same `operationId`.
Server idempotency protects the authoritative side effect.

## Result mapping

### Applied
- state → `APPLIED`
- operationId unchanged
- server version/correlation stored
- duplicate replay is recorded as `DUPLICATE_REPLAY`, not executed as a second business action.

### Retryable
- state → `RETRYABLE`
- retry count increments
- next retry is persisted.

Frozen baseline:
- 30s
- 1m
- 2m
- 4m
- 8m
- 15m cap
- ±20% jitter.

### Conflict
In one Room write transaction:
- parent command → `CONFLICT`
- typed `ConflictRecord` persisted
- direct dependent queued commands → `BLOCKED_BY_CONFLICT`
- local payload remains intact.

No last-write-wins merge exists.

### Terminal
- state → `FAILED_TERMINAL`
- payload remains for UI/support/recovery.

## Tests

Bootstrap tests cover:
- accepted command becomes APPLIED without operationId/payload mutation,
- first retry delay is exactly 30s under deterministic zero jitter,
- stale conflict persists safe conflict detail,
- dependent command is blocked and not executed,
- local intent remains intact.

## Android scheduling scaffold

`HiltechSyncScheduler` creates unique WorkManager work with:
- CONNECTED network constraint,
- battery-not-low constraint,
- exponential WorkManager backoff starting at 30s,
- KEEP semantics to avoid duplicate scheduler work.

The worker is still intentionally fail-closed until the Ktor transport/runtime composition is wired.

## Next gate

Implement the typed Ktor command transport:
- bearer token provider,
- Idempotency-Key,
- baseVersion/body contract,
- correlation / trace propagation,
- client metadata,
- representative success / duplicate / conflict / retryable / terminal mappings.

Then wire that transport into the Android WorkManager runtime.
