# Phase 6 / Slice 02 — Field Execution Transition Contract

Date: 2026-11-21
Status: **SOURCE-BOUND ENGINEERING VERTICAL / CLIENT REPLAY & NATIVE OPERATOR GATES OPEN**
Base: Phase 6 Slice 01 merged `95991581b9e797239274f8ab045cad3b9e31c6eb` and post-merge Bootstrap run 44872281157 SUCCESS.

## Source reuse

- Canonical `work_order.lifecycle_state` values already include `ASSIGNED`, `IN_PROGRESS`, `BLOCKED`, `SUBMITTED_FOR_REVIEW`, `REWORK_REQUIRED`. No new lifecycle dimension.
- Existing OpenFGA work_order `can_start`, `can_block`, `can_resume` relations permit only current assigned user/team/crew/subcontractor membership. Validate **both** current assignment source and OpenFGA on every server replay; never accept client role labels.
- Use existing `idempotent_operation`, exact WorkOrder `version`, `work_assignment` ID/version, `work_blocker` and `audit_event`. Actual start stored in `work_order.actual_start`; no fake local completion.
- Reuse existing Room3 pending_command and dependency graph plus Ktor transport routes; do not invent a second queue or material/stock authority.

## Frozen transition matrix for S02

| Command | Required current work | Other gates | Server result |
| --- | --- | --- | --- |
| StartWork | ASSIGNED | Readiness READY; Project ACTIVE; exact current assignment ID/version; current actor + can_start | IN_PROGRESS, actual_start set once |
| BlockWork | IN_PROGRESS | Exact WorkOrder/assignment versions; can_block; nonempty reason | BLOCKED and one OPEN work_blocker typed FIELD_EXECUTION |
| ResumeWork | BLOCKED | Ready, Project ACTIVE, exact current assignment and single OPEN FIELD_EXECUTION blocker ID; can_resume; resolution reason | IN_PROGRESS, targeted blocker RESOLVED |

All writes use an exact baseVersion and transactionally lock WorkOrder, then CAS update the version. Version stale/reassigned/cancelled/project-held/permission-revoked **never auto-rebase**. Same operationId + same body returns original authoritative result via existing idempotency outcome; different payload reuse conflicts.

Existing Phase4 Review/RequestRework is not changed. `ResumeWork` here does **not** resume `REWORK_REQUIRED`; that requires separate Slice05 source contract. Blocker types for genuine readiness are left untouched; no generic waiver or readiness override is introduced.

## Mobile and offline expectations

Android user may create durable PENDING intent only for a work item previously online-verified under the current same-session identity. Show local pending state separately from server-acknowledged IN_PROGRESS/BLOCKED. If app is cold-start OfflineFieldReadOnly, no new execution intent may be created. Replay through WorkManager only after current identity bootstrap, same actor/install, auth and source revalidation; stable operationId. 401 pauses for reauth without destroying intent; 403/404/409 conflict/deny without retry loop. A dependent Block/Resume must wait until prior Start/Block is APPLIED, otherwise base version must be refreshed rather than guessed.

## Required exit tests

- Server: Start/Block/Resume exact transitions, duplicate replay and fingerprint mismatch, current assignee, OpenFGA denial, version mismatch, blocked readiness and Project non-active, concurrent CAS, blocker-specific resolution, audit/event.
- Client: Room persisted operation after process death, same-user/device isolation, transport identity headers/idempotency, replay & conflict classification, WorkManager constrained scheduling, no fake UI success.
- Native Android + Windows source projections, exact-head CI, real device/operator gates separate.

`P6_S02_EXECUTION = BUILDING`
`P6_S02_SERVER_AUTHORITY = NOT_YET_CI_VERIFIED`
`P6_S02_ANDROID_REPLAY = NOT_YET_CI_VERIFIED`
`P6_S02_PRODUCTION_ENABLED = NO`
