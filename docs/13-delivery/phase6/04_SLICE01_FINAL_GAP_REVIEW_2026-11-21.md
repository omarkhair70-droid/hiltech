# Phase 6 / Slice 01 — Final Gap Review and Engineering Merge Gate

Date: 2026-11-21
Status: **IMPLEMENTATION PRESENT / EXACT-HEAD CI PENDING / NOT MERGE-APPROVED / OPERATOR ACCEPTANCE OPEN**
Current reviewed PR: #113 (`phase6/slice01-offline-job-bundle-20261121`).
Last source head before this review: `65e15b1f485a74c74a0f42e6190cb50a25719b72`.

## 1. Authoritative ownership and scope

Slice 01 extends Phase-4 current-source authenticated `/v1/field/today` and `/v1/work-orders/{id}/job-bundle` reads. It does **not** create another WorkOrder, Project, Warehouse source, Evidence service or lifecycle transition. It does not enable StartWork/Block/Resume, asset checkout, material consumption, evidence uploads or SubmitCompletion.

Phase-4 server and assignment source truth remains authoritative. Android's local Room store is a **historical read snapshot**, never current authorization, work execution, or successful sync.

## 2. Implementation inventory — present

- `FieldTodayCacheEntity`, `FieldJobBundleCacheEntity`, scoped `FieldOfflineCacheDao` and `FieldOfflineReadCache`; actor-specific caching and transactional wipe only of those read snapshots (pending commands/Evidence retained).
- `HiltechLocalDatabase` automatic forward migration 1→2, committed generated `2.json` and unchanged `1.json`.
- `FieldAssignedWorkOfflineRepository` uses cached read only after `NETWORK_UNAVAILABLE`; 401/403/404 never fall back. Cached Today is never `currentlyActionable`, and cached bundle execution capabilities all false.
- Android normal Today/detail UI marks stale data and source timestamps in Arabic/RTL. It shows no offline-authoritative success.
- A separate `HiltechShellState.OfflineFieldReadOnly` exposes only locally cached assigned work after a cold-start network failure, not authenticated admin or people/warehouse modules.
- Offline identity is a **Keystore-encrypted minimum record** (identity ID, ACTIVE/membership status, installation ID, SHA-256 digest of currently stored access token). A matching still-unexpired token, current device installation, non-rollback local time and prior verified bootstrap are required for cached local read. New token/login and logout invalidate the record.
- An explicit server-side identity/assignment access denial invalidates the encrypted offline lease and purges that actor's read snapshots, so a later outage cannot silently resurrect known-denied work.
- `AssignedWorkEvidenceActivity` debug mode seeds actual Android Keystore and Room, force-stops the app process, then the smoke script starts **actual production MainActivity** against an unreachable API and asserts the read-only offline shell without admin session UI. The debug seed is not included in release source.

## 3. Regression proof — distinguish observed from pending

Previously (before this cold-start extension), S01 code head `61f60a51e16d886105655aad5f2b25da3786495a` had **10/10 GitHub Actions workflow PASS** including Room/Android/bootstrap checks. This is historical proof, **not proof for the current head**.

Direct independent Gradle on a temporary authorized Windows checkout at `46485adf090b70ef2d4c91317a88ad9736f64fdf`:
`BUILD SUCCESSFUL` for `:shared:core:desktopTest` filtered to:
- `OfflineFieldReadLeasePolicyTest`;
- `FieldRoomMigrationTest`;
- `FieldOfflineReadCacheTest`;
- `FieldOfflineFallbackAuthorizationTest`.

CI for `65e15b1f485a74c74a0f42e6190cb50a25719b72`: pending at the time of this review. The previous failed Android compile was traced to a direct Compose Material3 import in the **debug fixture** and fixed by replacing the debug-only seed marker with Android native `TextView`; no new runtime dependency added.

**Required exact-review-commit proof before merge:**
- dedicated Phase 6 durable cache/migration/authorization/Android workflow;
- Bootstrap foundation, PostgreSQL/jOOQ/Room schema;
- Phase 1 native OIDC and Phase 2 shared command runtime;
- Phase 3 People/onboarding/offboarding;
- Phase 4 Project/Work/Assigned Mobile UI, including the native cold-start smoke;
- Phase 5 Warehouse regression;
- review threads and owner merge authorization.

No pipeline is declared PASS until verified for the exact commit being merged.

## 4. Negative/security gates

- Token absent/expired/rotated, old snapshot from a different installation/identity, local clock rollback, disabled identity/missing membership: offline cold-start read denied.
- Explicit HTTP authorization rejection is not treated as a network outage; old cached assignment cannot be restored after denial.
- Local snapshots cannot authorize *any* Work mutation, checkout, evidence capture, payroll/finance/admin action or completed state.
- Signout removes the matching actor's local read snapshots, not pending business intents or Evidence.
- Server may revoke the identity/assignment while the device is fully disconnected: **this cannot be known offline**. Cached read is shown as stale and token-limited; reconnection must recheck source truth.

## 5. Real operations remaining OPEN

- Real HILTECH technician/supervisor with correct current server membership and assignment;
- representative actual Android handset, process-death, airplane mode and reconnect test;
- field/client site access data classification and policy for lost/shared devices, encryption at rest and local retention (ordinary Room read storage is app-private but not a substitute for a formal device policy);
- operating procedure when a previously trusted but now remotely revoked identity is temporarily offline (bounded prior token lifetime);
- real WorkType/supervisor/evidence/stock policy and production data acceptance for later Phase-6 execution Slices.

No device emulator screenshot substitutes for real-person operational acceptance; Phase 5 warehouse live-data cutover is tracked separately.

## 6. Decision

`P6_S01_SCOPE_AND_AUTHORITY = CODE_REVIEWED`
`P6_S01_LOCAL_SHARED_TESTS = PASS_ON_PRIOR_PRE_REVIEW_HEAD`
`P6_S01_EXACT_HEAD_FULL_CI = PENDING`
`P6_S01_NATIVE_COLD_START_SMOKE = PENDING_EXACT_HEAD`
`P6_S01_OPERATOR_AND_DEVICE_ACCEPTANCE = OPEN`
`P6_S01_ENGINEERING_MERGE = NOT_YET`
`P6_S02_PRODUCTION_COMMANDS = NOT_STARTED`

When exact-head CI and review close, merge engineering code and verify postmerge Bootstrap. Do not label `PHASE6 COMPLETE` or live operations on that basis.
