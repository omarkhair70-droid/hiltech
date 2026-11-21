# Phase 6 / Slice 03 — Engineering Gap Review (interim)

Date: 2026-11-21
Review status: **DRAFT / NOT MERGED / NOT FIELD-ACTIVATED**
PR: [#115](https://github.com/omarkhair70-droid/hiltech/pull/115)

## Implemented on branch
- Existing Phase2 authoritative `EvidenceApiClient` reused end-to-end, not replaced.
- Room v3 adds immutable capture context (assignment ID/version, WorkOrder version, EvidencePolicy binding revision, reserved server Evidence ID). Additive auto migration preserves v1/v2 data; older rows missing capture provenance remain preserved but ineligible for automatic replay.
- Exact-source local policy: online-verified session, active Project, current assignment, current policy/revision, requirement key/MIME/max count/offline allowance, hard 16MiB limit.
- Android private non-backup file import with streamed SHA-256 and fsync, atomic file rename, opaque local file ref, re-hash and length check before upload. System document picker and FileProvider camera flow; zero external storage/public upload.
- UI presents local `LOCAL_READY`, `UPLOADED_UNVERIFIED`, `REJECTED` and verified server `READY` separately; offline cold-start remains read-only.
- Online actor/installation-scoped Worker invokes shared S03 coordinator after S02 commands, revalidates the **live** current JobBundle and policy on replay. Stable Evidence reservation/finalize operation IDs across retry; signed upload URLs are never persisted.
- Binary 2xx is not READY: finalized READY only after Evidence server confirms. QUARANTINED remains pending; REJECTED/source-change/expiry retains the local file for review; 401 pauses and 429/503 retry through WorkManager.
- Unit/Room migration and replay tests; dedicated native emulator file/capture persistence and corruption smoke defined.

## Required before engineering closure
1. Exact-head Phase6 S03 CI source tests + Android compile + native emulator **PASS**; review emitted schema v3 JSON, commit schema artifact and verify v1→v2→v3 preservation.
2. Full Phase1/3/4/5 regression/Bootstrap exact head **PASS**, plus independent diff/code review and postmerge Bootstrap.
3. Current PostgreSQL/OpenFGA/S3 existing Phase2 lifecycle regression must remain passing; add demonstrable client-to-backend live evidence handling or a narrow traceable equivalent.
4. Confirm picked file/camera UX on representative real Android device (permissions, orientation, process death, no SD card, phone space constraints). Current emulator is synthetic only.
5. Confirm practical photo/file/typed-form policy, offline classification and required/optional capture with HILTECH technician + supervisor. No arbitrary file auto-READY: server may return QUARANTINED with no production scanner/release.
6. Test expired upload-session manual recovery; server currently has no general refresh contract. Must not auto-create replacement Evidence rows.
7. Detect/reconcile same-actor WorkOrder version shifts that follow Start/Block/Resume before captured photo sync; current version mismatch deliberately blocks auto-rebase and preserves the file.
8. Privacy/security review on retention and device-loss handling; app-private no-backup storage is not a claim of full encrypted-at-rest policy on unmanaged/lost devices.
9. Real in-app Evidence status refresh currently occurs on opening WorkOrder; a visible action to sync/recheck pending records and operator diagnostics may improve usability.
10. Typed form Evidence is not modelled by Phase2 binary contract; needs canonical typed form source before production claim.

`P6_S03_SOURCE_IMPLEMENTED = PARTIAL_VERTICAL`
`P6_S03_EXACT_HEAD_CI = NOT_YET_VERIFIED`
`P6_S03_READY_FOR_MERGE = NO`
`P6_S03_OPERATOR_ACCEPTANCE = OPEN`
