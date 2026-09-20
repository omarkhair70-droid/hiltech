# Phase 4 / Slice 05 — Review / Rework / Accepted Progress / Project Health

Date: 2026-09-20  
Status: **REALITY CLOSED / IMPLEMENTATION CONTRACT FROZEN / PRODUCTION CODE AUTHORIZED ONLY FOR SLICE 05**

## 1. Goal

Close the management loop from reviewable work to accepted operational truth and explainable Project status.

Phase 6 still owns field execution/submission.

## 2. Production boundary before Phase 6

Slice 05 implements real review/rework/acceptance behavior for a WorkOrder that is authoritatively in SUBMITTED_FOR_REVIEW.

It does **not** add a production "fake submit" command.

Integration tests may seed controlled server-authoritative submitted fixtures directly to prove:
- exact-version review;
- rework history;
- acceptance;
- progress;
- health.

When Phase 6 later implements SubmitCompletion, it enters this existing production review path.

## 3. ReviewPolicy normalization

The existing configuration scaffold is extended to the frozen policy semantics.

ReviewPolicy:
- mode: ANY_ONE / ALL / SEQUENTIAL / QUORUM;
- bindExactSubmittedVersion = true by default and cannot be disabled for Phase 4 acceptance;
- clientAcceptanceSeparate;
- allowDelegation.

ReviewStep:
- stepKey;
- sequence;
- selectorType: RELATIONSHIP / ROLE / TEAM / SPECIFIC_USER / SUBJECT_MANAGER_CHAIN / CLIENT_RELATIONSHIP;
- selectorValue;
- quorumCount?;
- reauthRequired;
- reasonRequiredOnRework;
- reasonRequiredOnReject;
- evidenceVisibilityMode;
- escalationPolicyRef?.

No compile-time named reviewer.

## 4. Reviewer eligibility

Eligibility evaluated at decision time from:
- bound ReviewPolicy revision;
- Project relationships/OpenFGA;
- current People/Team/Delegation source truth;
- exact submitted WorkOrder version.

A stale reviewer tuple cannot defeat current PostgreSQL revoke/offboarding/delegation expiry.

## 5. Review decisions

Existing `work_review_decision` remains append-only.

AcceptWork:
- WorkOrder SUBMITTED_FOR_REVIEW;
- baseVersion equals exact submitted version;
- eligible current reviewer;
- required ReviewPolicy steps satisfied;
- BEFORE_ACCEPT Evidence requirements satisfied by READY authoritative Evidence;
- no material WorkOrder mutation since submission.

Result:
- WorkOrder ACCEPTED;
- acceptedAt;
- accepted decision ref;
- event work.accepted.

RequestRework:
- exact submitted version;
- eligible reviewer;
- reason as policy requires;
- append decision;
- lifecycle REWORK_REQUIRED;
- event rework.requested.

Slice 05 does not implement ResumeRework; Phase 6 owns return to execution.

## 6. Accepted operational progress

Canonical formula:

`accepted current-baseline included weight / total current-baseline included weight * 100`

Rules:
- only ACCEPTED contributes numerator;
- IN_PROGRESS/SUBMITTED/REWORK contributes zero accepted progress;
- CANCELLED/baseline-excluded contributes neither numerator nor denominator;
- WorkOrder progressWeight/countsTowardProjectProgress were snapshotted at PlanWork;
- only WorkOrders matching Project.baselineVersion participate;
- zero denominator => progressPercent null, not fake zero;
- projection is rebuildable;
- Project baseline/version reported with projection.

On acceptance/rework/cancellation/baseline event, recompute deterministically.

This is operational delivery progress only. No measured claim/invoice percentage.

## 7. ProjectHealth

States:
- UNKNOWN
- HEALTHY
- ATTENTION
- CRITICAL
- ON_HOLD

Create normalized `project_health_signal` projection/source rows or equivalent rebuildable typed projection.

Required Phase-4 signal families:
- OVERDUE_WORK;
- BLOCKED_WORK;
- REWORK_BACKLOG;
- READINESS_FAILURE;
- MILESTONE_DELAY;
- CLIENT_ACTION_REQUIRED.

Each active signal exposes:
- signalCode;
- severity;
- sourceType/id;
- firstObservedAt;
- lastObservedAt;
- summary;
- current Boolean.

ProjectHealthPolicy controls aggregation precedence/typed thresholds. No executable scripts.

ON_HOLD Project lifecycle overrides to health ON_HOLD.

Every ATTENTION/CRITICAL result exposes contributing source objects.

If no sufficient evaluated signal context exists, UNKNOWN is valid.

## 8. Project hold/resume

Slice 05 may enable:

`ACTIVE -> ON_HOLD -> ACTIVE`

PutProjectOnHold:
- exact version;
- reason;
- Project manage authority;
- emits project.put_on_hold;
- health ON_HOLD.

ResumeProject:
- exact version;
- reason/resolution;
- mandatory hold blocker resolved/waived according policy;
- Project manage authority;
- emits project.resumed.

No delivery-review/handover lifecycle is pulled into Phase 4.

## 9. PM Command Center

Read model includes only authoritative Phase-4 truth:
- Project identity/state/PM;
- current baseline;
- accepted operational progress;
- health + source reasons;
- next Milestone;
- Waiting On;
- open blockers;
- readiness failures;
- unassigned/assignment-confirmation work;
- submitted review items;
- rework items;
- ProjectSites/WorkPackages drill context.

Phase-5 resource availability appears only after Phase 5 exists.

Commercial/finance signals are absent unless a later permissioned source is implemented.

## 10. Work Queue / Activity

Project:
- review actions;
- rework action;
- severe blocker;
- no eligible assignment;
- client action required.

Activity:
- work reviewed/accepted/rework;
- project health meaningful change;
- project hold/resume.

Source domains remain authoritative.

## 11. Routes

- GET `/v1/review/work-items`
- POST `/v1/work-orders/{workOrderId}/accept`
- POST `/v1/work-orders/{workOrderId}/request-rework`
- GET `/v1/projects/{projectId}/command-center`
- GET `/v1/projects/{projectId}/progress`
- GET `/v1/projects/{projectId}/health`
- POST `/v1/projects/{projectId}/put-on-hold`
- POST `/v1/projects/{projectId}/resume`

## 12. Migration

`V0025__work_review_progress_health__slice05.sql`.

Must:
- extend ReviewPolicy typed semantics;
- add accepted-decision pointer if required without destroying V0005 history;
- add health signal/rebuild indexes;
- preserve project_progress_projection and project_health_projection as rebuildable projections;
- not create commercial claim tables.

## 13. Windows

Command Center:
- Progress;
- Health;
- Why;
- Waiting On;
- review queue;
- rework queue;
- blocker/assignment exceptions;
- direct drill to authoritative WorkOrder/requirement/source.

No unexplained red dot.

## 14. Tests

- exact submitted version acceptance.
- stale review rejected.
- reviewer policy eligibility.
- revoked/offboarded reviewer denied with stale tuple.
- BEFORE_ACCEPT evidence requirement enforced.
- rework decision history preserved.
- accepted decision history preserved.
- accepted weight formula.
- cancelled/excluded denominator behavior.
- zero denominator -> null.
- baseline mismatch excluded.
- health signal source trace.
- ON_HOLD override.
- hold/resume authority.
- no commercial progress fields.
- PM Command Center drill references real objects.
- Windows render proves health reason/progress/review/rework.

## 15. Exit

Accepted work changes operational Project progress deterministically, Project health is explainable, and PM/management can drill from an exception to its authoritative source object.

`SLICE05_CONTRACT = FROZEN`
