# SPIKE-13 Result — Android Background Sync

Date: 2026-09-18
Decision: **ACCEPT — WORKMANAGER BACKGROUND EXECUTION PATH PASSED**

## Evidence

GitHub Actions run: 35315342936

Real Android emulator:
- API 36
- WorkManager 2.11.2
- same Android/KMP client line used by the accepted offline spikes

## Proven Scenarios

### Connectivity + Process Death
- work was queued behind a CONNECTED constraint,
- the app process was killed,
- queued work was not falsely marked complete while offline,
- connectivity returned,
- WorkManager resumed without a foreground Activity,
- durable user-visible state reached SYNCED.

### Retry / Backoff
- first attempt returned retry,
- state became RETRYABLE,
- exponential backoff was exercised,
- a later attempt succeeded.

### Battery Constraint
- battery-not-low constrained work remained QUEUED while the simulated battery state was insufficient,
- after battery recovery the work executed,
- state reached SYNCED.

### User-Visible State
The proof exposed durable:
- QUEUED,
- RETRYABLE,
- SYNCED,
- attempt count.

Final proof marker:

`HILTECH_WORKMANAGER_PASS reconnect_after_process_death=PASS retry_backoff=PASS battery_constraint=PASS user_state=PASS`

Room3 and offline-command queue regression workflows also passed on the same head.

## Accepted Direction

Android background scheduling:
- WorkManager is accepted for constrained reconnect/background sync scheduling,
- Room/local command state remains authoritative for queued business work,
- WorkManager schedules/retries execution but does not replace idempotency, conflict, or command semantics,
- no foreground Activity is required for normal constrained replay.

This composes with:
- SPIKE-03 Room KMP local persistence,
- SPIKE-04 offline command queue/conflict semantics,
- ADR-011 offline command/sync semantics.

## Still Open

- production worker orchestration around real Room batches,
- foreground-service threshold for long-running evidence uploads,
- OEM-specific device reliability tests,
- final user messaging/notification policy,
- production network/battery budgets.

## Production Status

Disposable Android scheduling evidence only.
