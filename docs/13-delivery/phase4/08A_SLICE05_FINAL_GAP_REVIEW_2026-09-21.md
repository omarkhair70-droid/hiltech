# Phase 4 / Slice 05 — Final Gap Review

Date: 2026-09-21  
Status: **IMPLEMENTATION GAP REVIEW PASS / EXACT-HEAD CI + MERGE + POST-MERGE BOOTSTRAP STILL REQUIRED**

## 1. Scope reviewed

This review compares the Slice 05 implementation against:

- `08_SLICE05_REVIEW_PROGRESS_HEALTH_CONTRACT_2026-09-20.md`;
- `10_PHASE4_COMPLETION_GATES_AND_CODEX_HANDOFF_2026-09-20.md`;
- the already-merged Slice 01–04 source truth;
- the Phase 5 Warehouse/Assets/Materials/Tools boundary;
- the Phase 6 technician execution/submission/offline boundary.

No production SubmitCompletion command was added. Integration tests seed an authoritative
`SUBMITTED_FOR_REVIEW` row only inside the contract fixture, exactly as the frozen Slice 05
contract permits.

## 2. Review / rework / acceptance

**PASS**

Implemented on the existing WorkOrder and `work_review_decision` truth:

- forward-only `V0025__work_review_progress_health__slice05.sql`;
- typed ReviewPolicy mode: `ANY_ONE / ALL / SEQUENTIAL / QUORUM`;
- exact submitted-version binding remains mandatory;
- typed ReviewStep selector metadata and execution ordering;
- append-only review decisions;
- deterministic/idempotent AcceptWork and RequestRework commands;
- final acceptance stores `accepted_at` and the accepted decision pointer;
- rework preserves decision history and moves WorkOrder to `REWORK_REQUIRED`;
- stale WorkOrder version is rejected;
- BEFORE_ACCEPT requirements count only authoritative `READY` Evidence;
- policy-required rework reason is enforced.

No ResumeRework execution behavior was added; that remains Phase 6.

## 3. Reviewer source truth and authorization

**PASS, fail-closed where no authoritative source exists**

Decision-time eligibility rechecks current source data rather than trusting stale OpenFGA tuples.

Proven current-source paths:

- Project manager / projected `can_review_work`;
- current organization membership;
- current Team membership;
- current WorkforceAssignment role when a ROLE selector is used;
- specific user selector plus current organization/project review authority.

The contract fixture proves both:

- stale Team membership is denied while an old authorization tuple may still exist;
- organization-membership expiry/offboarding-style revocation is denied immediately.

The repository has no authoritative Delegation, Subject Manager Chain, or Client Relationship
source model in the Phase 0–4 source truth. Slice 05 therefore does **not** fabricate those
relationships. Those selectors fail closed with typed reason codes. `allowDelegation` is stored
as policy semantics, but no reviewer grant is manufactured without a real delegation source.

This is intentional source-truth preservation, not a local fallback.

## 4. Accepted operational progress

**PASS**

Canonical calculation is:

`accepted current-baseline included weight / total current-baseline included weight * 100`

Implementation properties:

- only `ACCEPTED` Work contributes to the numerator;
- `SUBMITTED_FOR_REVIEW`, `REWORK_REQUIRED`, blocked/in-progress states contribute zero accepted weight;
- cancelled or `counts_toward_project_progress = false` Work is excluded from numerator and denominator;
- only WorkOrders matching `Project.baselineVersion` participate;
- WorkOrder snapshotted `progress_weight` is used;
- zero denominator returns `progressPercent = null`;
- projection stores Project version + baseline version;
- projection is deterministic and rebuildable.

Accept and Rework recompute immediately.

There is currently no production Work cancellation command and no production command that
revises `Project.baselineVersion` after the frozen baseline. Therefore Slice 05 does not invent
listeners for nonexistent mutation events. Every progress read rebuilds from authoritative
WorkOrder truth, so direct database/history reconstruction remains safe. When a future real
cancel/baseline mutation command is introduced, it must invoke the same recomputation path.

No commercial, invoice, claim, earned-value, or payment progress fields were added.

## 5. Project Health

**PASS**

Implemented states:

- `UNKNOWN`
- `HEALTHY`
- `ATTENTION`
- `CRITICAL`
- `ON_HOLD`

Typed rebuildable health signals:

- `OVERDUE_WORK`
- `BLOCKED_WORK`
- `REWORK_BACKLOG`
- `READINESS_FAILURE`
- `MILESTONE_DELAY`
- `CLIENT_ACTION_REQUIRED`

Each active signal persists/exposes:

- signal code;
- severity;
- source type/id;
- first/last observed timestamps;
- safe summary;
- current flag;
- source version when available.

ProjectHealthPolicy rules are parsed as typed JSON, not executable scripts and not substring
matching. Aggregation precedence is policy-driven. `ON_HOLD` Project lifecycle overrides
normal health aggregation.

ATTENTION/CRITICAL output carries contributing source object references.

## 6. Project hold / resume

**PASS**

Implemented:

`ACTIVE -> ON_HOLD -> ACTIVE`

PutProjectOnHold:

- exact Project version;
- Project manage authority;
- mandatory reason;
- durable open hold record;
- Project lifecycle transition;
- Health becomes `ON_HOLD`;
- audit + domain event.

ResumeProject:

- exact Project version;
- current Project manage authority;
- requires current open hold record;
- mandatory resolution;
- resolves hold record before lifecycle transition;
- returns to `ACTIVE`;
- rebuilds normal health;
- audit + domain event.

No Delivery Review / Handover lifecycle was pulled into Phase 4.

## 7. PM Command Center / Windows

**PASS**

The Windows PM Command Center consumes the real shared HTTP contract and exposes:

- Project identity/lifecycle/PM;
- baseline version;
- accepted operational progress;
- Health + Why;
- typed source references for health signals;
- next Milestone;
- Waiting On readiness/assignment exceptions;
- submitted review queue;
- rework queue;
- ProjectSite and WorkPackage drill references;
- BEFORE_ACCEPT Evidence gate state;
- current reviewer policy/step/source;
- Accept/Rework actions;
- Hold/Resume actions.

No unexplained red-dot status and no commercial-progress card exists.

The command-center read is optional for a user who cannot manage that Project; denial does not
destroy the base Project screen.

## 8. Activity

**PASS**

The previous Evidence-only `activity_event` projection was extended forward-only in V0025 to
support typed Work/Project activity without changing V0011.

New projected activity types:

- `WORK_ACCEPTED`
- `WORK_REWORK_REQUESTED`
- `PROJECT_HEALTH_CHANGED`
- `PROJECT_PUT_ON_HOLD`
- `PROJECT_RESUMED`

Projection is idempotent by `source_event_id`. Work activity remains readable through the
existing WorkOrder Activity route. Project activity is durably projected for the Project context
without inventing a second Project source of truth.

## 9. Routes and shared client

**PASS**

Implemented frozen routes:

- `GET /v1/review/work-items`
- `POST /v1/work-orders/{id}/accept`
- `POST /v1/work-orders/{id}/request-rework`
- `GET /v1/projects/{id}/command-center`
- `GET /v1/projects/{id}/progress`
- `GET /v1/projects/{id}/health`
- `POST /v1/projects/{id}/put-on-hold`
- `POST /v1/projects/{id}/resume`

Shared KMP DTO/client tests prove device header and Idempotency-Key behavior and reject invalid
identifiers before transport.

## 10. Contract proof

The Slice 05 PostgreSQL/OpenFGA vertical proves:

- exact submitted-version acceptance;
- stale-version rejection;
- current reviewer eligibility;
- stale Team reviewer revoke;
- organization-membership/offboarding-style revoke;
- BEFORE_ACCEPT Evidence block;
- unauthorized reviewer denial;
- accepted decision history;
- idempotent Accept replay;
- rework history;
- accepted-weight formula;
- excluded/cancelled denominator behavior;
- zero denominator -> null;
- typed health rule isolation;
- health source trace;
- ON_HOLD override;
- Hold/Resume authority;
- PM Command Center references real ProjectSite/WorkPackage/WorkOrder objects;
- Activity event projection and Activity idempotency.

The Windows render proof captures:

1. review blocked by missing BEFORE_ACCEPT evidence;
2. accepted progress + Health/Why + source refs + Waiting On;
3. ON_HOLD state with Resume control.

## 11. Later-phase boundaries preserved

Not implemented in Slice 05:

- technician field Start/Complete/Submit workflow;
- production SubmitCompletion;
- ResumeRework execution;
- offline durable outbox/inbox/process-death recovery;
- Warehouse availability;
- Asset/Material/Tool authoritative availability;
- commercial/finance progress;
- synthetic delegation/client relationship truth.

These remain later-domain work and are not represented as fake Phase-4 state.

## 12. Gap result

No remaining **Slice-05-owned production object/action/surface** gap was found after the Activity
and typed-health review fixes.

Operational closure still requires the repository-level gates on one exact head:

1. Slice-specific workflow green;
2. applicable Phase 0–3 / earlier Phase 4 regression workflows green;
3. exact tested PR head merged;
4. post-merge Bootstrap green on `main`.

`SLICE05_GAP_REVIEW = PASS`

`SLICE05_MERGE = PENDING_EXACT_HEAD_GATES`
