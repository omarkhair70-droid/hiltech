# Phase 6 / Slice 06 — Durable Recovery, Security & Engineering Exit

Date: 2026-11-22

## Status and authority

`P6_S06_SYNTHETIC_RECOVERY = IMPLEMENTED_PENDING_EXACT_HEAD_CI`

`P6_S06_ROOM_V3_TO_V4_FORWARD_MIGRATION = IMPLEMENTED_PENDING_EXACT_HEAD_CI`

`P6_S06_NATIVE_ANDROID_OPERATOR_ACCEPTANCE = NOT_VERIFIED`

`P6_S06_WINDOWS_SUPERVISOR_ACCEPTANCE = NOT_VERIFIED`

`P6_S06_PRODUCTION_CUTOVER = NOT_AUTHORIZED`

`PHASE6_PRODUCTION_COMPLETE = NO`

This checkpoint verifies recovery contracts, not live technician/supervisor acceptance or actual HILTECH customer standards.

## Exact source chain

- S01: assigned WorkOrder/JobBundle durable local read + source freshness
- S02: Start/Block/Resume server commands + Room replay
- S03: local Evidence + private upload/finalize
- S04: material/AssetTag/configurable engineering tests/replay/canonical drawing approvals (#122, #123, #124, #126, #129, #132)
- S05: authoritative SubmitCompletion, dependency graph, reviewed Work state, durable late replay (#136, exact-head run 44972194313 PASS)
- S06: independent recovery/security tests and phase exit evidence (this checkpoint)

All unmerged stacked pull requests and default-OFF feature flags remain subject to owner review and controlled deployment.

## Synthetic recovery matrix

| Scenario | Contract to prove | Proof type |
| --- | --- | --- |
| Mobile process killed after ambiguous HTTP response | Room intent survives, same operationId/payload is used on retry, server duplicate receipt is recognized and not applied twice | Desktop JVM + bundled SQLite |
| Flight mode / transport failure | Local intent remains retryable; no premature confirmation and no loss after database close/reopen | Simulated transport |
| 429 rate limit | Respect server Retry-After and do not send before due | Simulated transport |
| 503 outage | Deterministic retry backoff and stable operation identity | Simulated transport |
| 401 / expired login | Preserve command and pause for reauthentication; do not create a second operation | Simulated authenticated scope |
| Reassignment or revoked permission | Matching identity+installation replay only; 403/409 moves to durable review/conflict, never silent rebase or silent resend | Simulated server conflict |
| Different logged-in user / device | No cross-account or cross-installation replay | Room scope |
| Updated drawing/policy source | Safe server version and allowed recovery actions retained as durable conflict | Simulated server conflict |
| SQLite forward upgrade v3→v4 | Existing pending SubmitCompletion payload and local Evidence provenance survive real Room AutoMigration and reopen; new engineering intent table exists | Actual bundled SQLite schema migration |
| Reviewer acceptance / progress | Existing authoritative Phase-4 review engine exercised by server tests, not a new synthetic acceptance state | Server regression tests |
| Android/Windows binaries | Kotlin/Compose compile against current shared contracts | Native build compile |
| Database correctness | Latest migration, immutable submission and required source foreign keys in disposable PostgreSQL | PostgreSQL contract tests |

## Explicit limitations

No synthetic test claims to have used a real fiber OTDR/Fluke report, a HILTECH engineer's approval account, or a customer drawing version. No user-owned phone receives a build without explicit authorization. Connected device detection alone is **not** acceptance.

Production activation requires a separate owner/operator session with:
1. a real technician identity, an assigned WorkOrder, actual policy/drawing/measurements and the configured acceptance rule;
2. disconnect/reconnect and force-stop/restart on a dedicated authorized Android test device, confirmed from server Evidence/Work ledger;
3. supervisor AcceptWork/RequestRework from permitted Windows account plus denied unrelated actor and PM progress check;
4. real role revocation and lost-device response/credential kill verification;
5. private file retention, malware scanning and actual object-storage path evidence;
6. support trace IDs/correlation, replay conflict inspection and rollback plan;
7. controlled feature-flag enablement only after signoff.

Do **not** claim Phase 6 production completion from green CI. If all automated gates pass, use `P6_S06_ENGINEERING_AUTOMATED = VERIFIED`, `PHASE6_OPERATOR_ACCEPTANCE = PENDING`.

## Rollout

Defaults continue OFF:
- `HILTECH_FIELD_ENGINEERING_TESTS_ENABLED=false`
- `HILTECH_FIELD_WORK_DOCUMENTS_ENABLED=false`
- `HILTECH_FIELD_COMPLETION_ENABLED=false`

No irreversible production migrations, device installs, work-order transitions or other user-owned environment changes performed by this test PR.
