# Phase 6 / Field Execution — Source Reality Reconstruction

Date: 2026-11-21
Status: **SOURCE-AUDITED / REAL OPERATOR ACCEPTANCE PENDING / NO PHASE-6 PRODUCTION COMMANDS AUTHORIZED**
Starting integrated `main`: `967a3fbdca0c84a71a0b76e9913a64a12f0f40d4` (Phase-5 Slice 09 merge).
Post-merge Bootstrap run: `44857868201` — result must be checked independently; not claimed PASS here.

## Purpose

Phase 6 turns already-shared Project/Work/People/Warehouse/Evidence foundations into an **actual technician field execution** vertical, including offline intent, reliable photo/test evidence, lifecycle commands, supervisor acceptance and PM progress. This is not a new app, a duplicate WorkOrder or a second inventory ledger.

## Source truth already in the repository

| Source | Verified existing responsibility | Must not duplicate |
| --- | --- | --- |
| Phase 4 Slice 06 `09_SLICE06_ASSIGNED_WORK_MOBILE_HANDOFF_CONTRACT_2026-09-20.md` | `GET /v1/field/today`, `GET /v1/work-orders/{id}/job-bundle`, current assignment USER/TEAM/CREW filtering, Android Arabic-first/read-only Today and context | WorkOrder, WorkAssignment, safe Project/Site context |
| `FieldAssignedWorkApi.kt` / `FieldAssignedWorkService.kt` | Read-only current Job Bundle, exact WorkPolicyBinding / instruction / checklist / evidence / resource information; executionCapabilities false | Execution truth cannot be fabricated from a read-only DTO |
| Phase 4 Slice 05 / Phase-4 Final Gap Review | Reviewer eligibility, AcceptWork / RequestRework, progress/Project Health authority | Do not make independent supervisor acceptance/progress tables |
| Phase 5 Slices 01–09 and Slice 09 Final Gap Review | Authoritative Warehouse/Stock/Asset, reservations, checkout/issue/return, stock consumption and readiness integration | Do not edit balances/custody directly; no offline final warehouse issue by default |
| Phase 2 Evidence | Evidence metadata/reserve/upload/finalize, controlled visibility, server-validated bytes/checksum, shared Evidence API | Do not store binary evidence in Work DB or invent public URLs |
| `OfflineSchemaContract.kt`, `PendingCommandReplayEngine.kt` | Room v1 schema, pending-command states, retry backoff, dependency checks, conflict records | Existing transport/replay engine is infrastructure, **not proof** that field commands are implemented |
| `HiltechSyncWorker.kt` / sync runtime | Android WorkManager worker wired to durable replay | Worker execution is not business-command acceptance |
| `04_ROOM_OFFLINE_SYNC.md`, `06_FILES_EVIDENCE.md`, `OFFLINE_CLASSIFICATION_MATRIX.md` | Stable operationId/baseVersion, durable local file, evidence before completion, conflict safety, server reevaluation | No local queue may claim server success before authoritative ACK |
| `FIELD_OFFLINE_WIREFLOW.md` | Desired operator journey, offline copy, conflict recovery and stale drawing cues | UX wireflow remains a candidate, not accepted HILTECH operator proof |

## Phase 6 source-grounded field journey

PM assigned WorkOrder → technician's authenticated Today / exact Job Bundle → prefetch durable scoped bundle and authorized document refs → local Start/Block/Resume and evidence/test/material intent → queue dependent file uploads/finalization → reconnect and server-side current-source authorization/version checks → valid SubmitCompletion → existing supervisor Review/RequestRework/AcceptWork → existing PM accepted progress/health projection.

Offline screen states differentiate `ON_DEVICE`, `WAITING_TO_SYNC`, `CONFLICT` and `SERVER_CONFIRMED`; **never** mark a local intent accepted as authoritative.

## Operator/process facts still missing

These require representative HILTECH operator validation, not engineer guesses:

1. Real technician, supervisor and authorized engineering reviewer relationships for at least one current Project/Site/WorkOrder; phone/no-login employee and Team/Crew cases.
2. Typical site work types (fiber/UTP/racks/test work), exact Start/Block/Resume/Submit meanings, whether shift/day or WorkOrder is the primary execution unit, and when supervisor or engineer decides technical acceptance.
3. Evidence type requirements per actual WorkType: before/after photos, site-photo restrictions, drawings, signatures, OTDR/Fluke/test files, numerical measurements and typed tolerance/unit rules. **Do not make GPS/photo mandatory globally.**
4. Actual drawing/document revision authority, offline staleness cutoffs and site access restrictions; no automatic use of superseded revision.
5. Material consumption counting at site, proof of issuance and authorized exception handling where issued balance is missing or stale; Warehouse remains movement owner.
6. Real representative Android device(s), restricted camera environments, offline duration, app restart/device loss and upload file size constraints (current evidence max is 16 MiB).
7. Supervisor accept/rework evidence checklist and escalation path for reassignment/cancellation while field intent is pending.

Unknown facts are not blockers for repository-source analysis, test harness planning, or implementing a **strictly read-only** scoped bundle cache after the first-slice contract is reviewed. They *are* blockers for inventing production lifecycle/policy/measurement/acceptance commands.

## Invariants

- One WorkOrder and one current-source WorkAssignment, bound to exact policy/instruction versions.
- Offline captured command is intent, not server state.
- Ended assignment/role/revoked source fails closed on server replay, even if bundle is cached.
- No last-write-wins or silent evidence discard.
- Stable operation IDs survive timeout/restart/retry; explicit dependency graph governs evidence/submit.
- Binary files remain durable until server-confirmed READY or authorized safe discard.
- Latest server truth is authoritative; conflict UI preserves technician work and records why it cannot apply.
- Field consumption uses the existing Stock issued/consumed truth; not an offline StockBalance edit.
- No Phase-7 procurement, Phase-8 finance, Phase-12 hardware integration or external portal in Phase 6.

## Current gate

`PHASE6_SOURCE_RECONSTRUCTION = PREPARED`
`PHASE6_REAL_OPERATOR_VALIDATION = OPEN`
`PHASE6_PRODUCTION_EXECUTION_CONTRACT = NOT_FROZEN`
`PHASE6_CODING_AUTHORIZED = NOT_YET`
