# Phase 6 / Slice 05 — Completion submission and reviewer handoff

Date: 2026-11-22

## Status

`P6_S05_SUBMISSION = IMPLEMENTED_DEFAULT_OFF_PENDING_EXACT_HEAD_CI`

`P6_S05_REVIEWER_ACCEPT_REWORK = REUSES_EXISTING_PHASE4_REVIEW_ENGINE`

`P6_S05_OFFLINE_SUBMISSION = DURABLE_LATE_STAGE_INTENT_PENDING_EXACT_HEAD_CI`

`P6_S05_PRODUCTION_ROLLOUT = NOT_ENABLED`

The original Phase 4 reviewer capabilities (`AcceptWork`, `RequestRework`) are reused, not duplicated.

## Authoritative contract

POST `/v1/work-orders/{workOrderId}/submit-completion` requires matching Idempotency-Key / operationId and the frozen assigned WorkOrder, assignment, policy binding and Work version.

The server locks the WorkOrder and checks current identity/assignment, `can_submit_completion`, active project, lifecycle (`IN_PROGRESS` or `REWORK_REQUIRED`), then evaluates completion dependencies. A successful operation moves WorkOrder into `SUBMITTED_FOR_REVIEW`, increments Work version and sets `submitted_review_version` to that exact version.

The submission is **not acceptance**. The existing authorized reviewer may later Accept or request Rework against the submitted review version.

## Completion dependency source

The same gate is exposed in the current live JobBundle with `available`, `satisfied` and stable `reasonCodes`.

Requirements checked:
- mandatory Checklist items;
- required Work DOCUMENT / DRAWING_REVISION satisfaction;
- `BEFORE_SUBMIT` READY Evidence from the bound EvidencePolicy/revision, respecting supersession and configured minimum;
- configured structured engineering tests bound to the exact EvidencePolicy/revision/schema and subject, counting only current `PASS` results and blocking unknown/failure;
- outstanding FIELD_EXECUTION blockers.

No client-side status or technician-entered device PASS is sufficient when the canonical evaluation is UNKNOWN/FAIL.

## Immutable history

V0038 adds `work_completion_submission`, capturing:
- operation ID, organization, WorkOrder, submitting user;
- assignment ID and version;
- source and submitted WorkOrder versions;
- exact binding/evidence/review policy references and revisions;
- source lifecycle;
- dependency snapshot JSON;
- captured and submitted times, correlation ID.

The database rejects invalid assignment/context/binding, broken version advance, duplicate submission version, and UPDATE/DELETE. One work source can produce only one submitted review version.

The generic IdempotentCommandExecutor checks the operation ID *before mutable source validation*; a response lost after success can return the original receipt even if review/Work state has since advanced.

## Durable Android intent

A technician can queue only from an online-verified, noncached JobBundle with:
- `submitOffline = true`;
- authoritative completionGate satisfied;
- allowed live lifecycle;
- no outstanding field transition, engineering attempt or unfinished local Evidence for that work.

The queue reuses Room v4 `pending_command`; no speculative schema migration is needed.

Replay order:
1. Start / Block / Resume and other normal field intents;
2. Evidence upload and authoritative finalize;
3. engineering-test replay;
4. **SubmitWorkCompletion last**.

Both early and late stages filter the same pending-command engine by command type; they do not fork retry, idempotency or conflict semantics. 403/404/409 completion failures become visible conflicts, and 401 retains the intent for reauthentication.

The Android field UI shows the completion gate, reasons to unblock it, a confirmation step, and a locally queued status. The UI does not claim the work was accepted by a reviewer.

## Feature rollout

`HILTECH_FIELD_COMPLETION_ENABLED=false` by default. No production data, reviewer identities, customer thresholds or approval authority are seeded. Production activation requires actual policy configuration, access verification and operator acceptance.

## Verification target

A dedicated GitHub Actions gate on exact PR head must prove:
1. full server suite / architecture / existing review;
2. durable queue + field and engineering regression tests;
3. Android feature-enabled compile;
4. clean PostgreSQL migration through V0038;
5. V0038 actual WorkOrder transition into `SUBMITTED_FOR_REVIEW`, original-version snapshot and immutable/duplicate guards;
6. rollout default OFF;
7. clean patch formatting.

Do not mark this slice production ready or merged simply because a build is green.
