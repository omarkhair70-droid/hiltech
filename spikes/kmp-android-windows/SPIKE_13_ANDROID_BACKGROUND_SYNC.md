# SPIKE-13 — Android Background Sync

Status: RUNNING

## Candidate

AndroidX WorkManager 2.11.2.

## Goal

Prove the accepted HILTECH offline-command model can be scheduled reliably by Android after connectivity/process changes.

This spike tests Android scheduling/OS behavior. It does not replace SPIKE-04's command/idempotency/conflict semantics.

## Real Emulator Scenarios

### 1. Offline queue + process death + reconnect

1. Android emulator is put offline.
2. unique WorkManager request is enqueued with CONNECTED network constraint.
3. user-visible probe state becomes QUEUED.
4. application process is killed.
5. work must not become SYNCED while offline.
6. connectivity returns.
7. Android JobScheduler/WorkManager starts the work after process death.
8. probe state becomes SYNCED.

### 2. Retry / backoff

1. online constrained work starts.
2. first worker attempt intentionally returns Result.retry().
3. probe state becomes RETRYABLE.
4. WorkManager applies 10-second exponential backoff.
5. later attempt succeeds.
6. state becomes SYNCED with attempts >= 2.

### 3. Battery-not-low constraint

1. emulator battery is set low/unplugged.
2. work requiring battery-not-low is enqueued.
3. it remains QUEUED.
4. battery becomes okay.
5. WorkManager runs it.
6. state becomes SYNCED.

## User-visible state

The spike writes durable app-private state:

- QUEUED
- RETRYABLE
- SYNCED
- attempt count

Production UI will use the accepted Room/sync model rather than this probe file.

## Pass

ACCEPT if a real API 36 Android emulator proves:
- network-constrained work does not falsely complete offline,
- process death does not lose queued work,
- reconnect runs queued work,
- retry/backoff executes a later successful attempt,
- battery-not-low constraint delays work,
- user-observable sync state reflects the lifecycle,
- no foreground Activity is required to complete background sync.

## Still Open

- production worker orchestration around Room queue batches,
- exact upload scheduling policy,
- foreground-service threshold for long evidence uploads,
- OEM-specific field-device testing,
- final notification/user messaging,
- production battery/network budgets.

## Production status

Disposable Android scheduling evidence only.
