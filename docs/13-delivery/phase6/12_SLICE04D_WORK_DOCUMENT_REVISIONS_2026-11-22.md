# Phase 6 / Slice 04D — Work Document / Drawing Revision Source

Date: 2026-11-22

## Status

`P6_S04C_STRUCTURED_TEST_LIVE = IMPLEMENTED_DEFAULT_OFF`

`P6_S04C_STRUCTURED_TEST_OFFLINE_INTENT = VERIFIED_GREEN`

`P6_S04D_WORK_DOCUMENT_REVISION_SOURCE = IMPLEMENTED_PENDING_EXACT_HEAD_CI`

`P6_S04_COMPLETE = PENDING_S04D_EXACT_HEAD_CI`

## Purpose

Slice 04 previously had no authoritative Project/Work document-revision source. `safeDocumentRefs()` intentionally returned an empty list, and Readiness blocked `DRAWING_REVISION` / `DOCUMENT` requirements rather than inventing a current drawing.

This checkpoint adds a generic, versioned Work document source without hard-coding:

- HILTECH drawing names;
- revision naming conventions;
- engineer names;
- approval authority;
- approval thresholds;
- vendor-specific document semantics.

The actual approval policy remains organization configuration.

## Canonical model

A logical Work document is identified by:

- organization;
- WorkOrder;
- WorkType document requirement key;
- document code;
- immutable title;
- immutable approval-policy key.

Each revision is append-only and contains:

- revision sequence;
- revision label;
- READY WorkOrder Evidence;
- prior revision link;
- author;
- creation time.

The release record freezes the ApprovalPolicy key/version used for that revision.

## Revision chain

Revision 1 has no predecessor.

Every later revision must reference exactly the immediately prior sequence.

The database rejects:

- cross-WorkOrder Evidence;
- non-READY Evidence;
- branching from an older revision;
- duplicate revision labels/sequences;
- UPDATE/DELETE of the document identity;
- UPDATE/DELETE of revisions;
- UPDATE/DELETE of release metadata.

## Approval

WorkType document requirement configuration must provide a valid `approvalPolicyKey`.

The API does not accept an arbitrary policy from the caller.

A revision is registered against the generic ApprovalEngine subject:

`WORK_DOCUMENT_REVISION / revisionId / version=1`.

If the policy requires approval, the revision remains pending until ApprovalEngine approves it.

If the policy is configured as no-approval-required, the release is authoritative immediately.

No engineer or supervisor is hard-coded. Authority remains ApprovalEngine configuration.

## Current revision semantics

The current field-safe revision is the highest revision that is actually released:

- `NO_APPROVAL_REQUIRED`; or
- ApprovalRequest state `APPROVED`.

A newer pending/rejected/change-requested revision does not invalidate the prior approved revision.

This preserves operational continuity while a new drawing is under review.

When a new revision becomes approved, WorkOrder source version is bumped and a WorkResourceChanged event is emitted so stale field bundles fail closed.

## JobBundle

When `HILTECH_FIELD_WORK_DOCUMENTS_ENABLED=true`, `safeDocumentRefs()` returns only approved/current revisions.

When the feature is OFF, it returns an empty list exactly as before.

No pending drawing is exposed to field users as current.

## Readiness

`DRAWING_REVISION` and `DOCUMENT` requirements now resolve through the same authoritative source.

If a matching approved/current revision exists:

`SATISFIED / APPROVED_DOCUMENT_REVISION_CURRENT`.

If not:

`BLOCKED / APPROVED_DOCUMENT_REVISION_REQUIRED`.

If the feature/source is unavailable, the prior fail-closed result remains:

`BLOCKED / AUTHORITATIVE_DOCUMENT_SOURCE_UNAVAILABLE`.

## Rollout

Default remains OFF:

`HILTECH_FIELD_WORK_DOCUMENTS_ENABLED=false`.

No production drawing, approval policy, approver mapping or WorkType document configuration is seeded by this checkpoint.

## Verification target

Exact-head CI must prove:

1. server source/approval/readiness tests;
2. V0037 migration on disposable PostgreSQL;
3. Rev A approved -> Rev B pending keeps A current;
4. approving Rev B makes B current;
5. cross-Work Evidence rejected;
6. revision branching rejected;
7. document/revision/release immutability;
8. central empty-database migration contract updated through V0037;
9. rollout default remains OFF;
10. clean patch formatting.

Only after these gates pass may Slice 04 be marked engineering-complete/default-OFF.
