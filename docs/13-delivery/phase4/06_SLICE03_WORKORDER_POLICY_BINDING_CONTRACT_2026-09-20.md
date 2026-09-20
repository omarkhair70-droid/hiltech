# Phase 4 / Slice 03 — WorkOrder + WorkType / Policy Binding

Date: 2026-09-20  
Status: **REALITY CLOSED / IMPLEMENTATION CONTRACT FROZEN / PRODUCTION CODE AUTHORIZED ONLY FOR SLICE 03**

## 1. Goal

Create the canonical executable WorkOrder planning object and bind it to exact revisioned policy/instruction/checklist truth.

This Slice plans work. It does not assign technicians or execute field commands.

## 2. Existing schema reused

V0005 already contains scaffold tables:
- work_order;
- work_policy_binding;
- work_instruction_revision;
- work_checklist_item_instance;
- work_assignment;
- work_blocker;
- work_review_decision;
- work_requirement_instance.

V0003 already contains typed configuration tables.

Slice 03 must harden/complete these through a forward migration. Do not replace them with parallel tables.

## 3. WorkOrder authoritative fields

V0023 must add/harden:
- organizationId;
- baselineVersion;
- WorkPackage FK;
- exact same-tenant Project/Site/ProjectSite constraints;
- indexes/unique constraints missing from V0005;
- current policy binding pointer invariants;
- current instruction revision pointer invariants.

Canonical fields:
- id;
- organizationId;
- workOrderCode;
- projectId;
- siteId;
- projectSiteId?;
- areaId?;
- workPackageId?;
- title;
- description?;
- lifecycleState;
- readinessState;
- plannedStart?;
- plannedEnd?;
- actualStart?;
- submittedAt?;
- acceptedAt?;
- closedAt?;
- priorityCode;
- currentInstructionRevisionId?;
- currentPolicyBindingId?;
- countsTowardProjectProgress;
- progressWeight;
- baselineVersion;
- createdAt/by;
- updatedAt;
- version.

## 4. Code allocation

WorkOrder UUID is identity.

Human `workOrderCode` uses active CodePolicy for targetObjectType WORK_ORDER.

Default supported sequence scope for WorkOrder:
- PROJECT.

Manual code accepted only when active CodePolicy.manualOverrideAllowed.

No compiled prefix/year/padding.

Generated sequence allocation is transactional and idempotency-safe.

## 5. Create vs Plan

### CreateWorkOrder

Creates DRAFT.

Requires:
- Project state READY or PLANNING only when caller is still composing the current plan;
- ProjectSite/Site valid;
- Area/WorkPackage references valid;
- WorkPackage belongs same Project and compatible Site context;
- active WorkType resolvable by explicit code/revision selector;
- CodePolicy resolvable.

DRAFT may have null policy/instruction pointers.

### PlanWork

DRAFT -> PLANNED.

At this exact command:
- bind WorkTypeDefinition revision;
- bind AssignmentPolicy revision;
- bind ReadinessPolicy revision;
- bind EvidencePolicy revision;
- bind ReviewPolicy revision;
- bind optional TrackingPolicy;
- bind optional checklist/instruction templates;
- create WorkPolicyBinding revision 1;
- create initial WorkInstructionRevision;
- materialize checklist items;
- materialize requirement instances from bound policies/templates;
- snapshot progressWeight and countsTowardProjectProgress;
- snapshot baselineVersion;
- set readinessState NOT_EVALUATED;
- set current pointers;
- increment WorkOrder version.

Old config changes never rewrite the binding.

## 6. WorkPolicyBinding

One current unsuperseded binding per WorkOrder.

V0023 adds the missing partial unique current-binding index.

History is append-only:
- bindingRevision increments;
- superseded row remains readable;
- rebind is not silently performed because config changed.

Slice 03 does not expose broad rebind after assignment. A future explicit command must prove stale/offline behavior before it may do so.

## 7. WorkInstructionRevision

Append-only.

Slice 03 supports:
- initial instruction at PlanWork;
- `ReviseWorkInstruction` while lifecycle DRAFT/PLANNED;
- exact baseVersion;
- structured schema version;
- optional source template revision;
- summary;
- changeReason;
- correlationId.

After ASSIGNED, instruction revision behavior belongs to the execution/offline conflict boundary and must not be casually enabled before Phase 6.

## 8. Checklist materialization

Checklist template JSON is configuration input, not runtime completion truth.

At PlanWork create one `work_checklist_item_instance` per template item:
- source template + revision;
- itemKey;
- label;
- required;
- sortOrder;
- PENDING state;
- evidenceRequirementKey?;
- version.

Slice 03 displays checklist definition. It does not let a technician complete it.

## 9. Work requirements

At PlanWork materialize:
- READINESS;
- EVIDENCE;
- ASSET;
- MATERIAL;
- DOCUMENT

instances from exact bound config/templates.

Each instance records:
- requirement key/family;
- source config/revision;
- required flag;
- PENDING/NOT_APPLICABLE initial state as justified;
- no fabricated satisfaction.

Slice 03 does not evaluate Phase-4 readiness yet.

## 10. Work dependencies

Create normalized `work_order_dependency`.

Fields:
- id;
- organizationId;
- projectId;
- predecessorWorkOrderId;
- successorWorkOrderId;
- dependencyType: FINISH_TO_START;
- lagMinutes;
- createdAt/by;
- version.

Rules:
- same Project;
- no self edge;
- no cycle;
- predecessor/successor cannot be CANCELLED at edge creation;
- only typed FINISH_TO_START has Phase-4 semantics.

## 11. WorkType / config reality

The existing V0003 tables remain canonical.

V0023 may add typed columns/children required by the frozen config contract, including:
- WorkType effective defaults;
- template detail needed to materialize checklist/instructions;
- policy revision metadata needed to bind exact revisions.

Synthetic/representative seed config is allowed only when:
- clearly marked as synthetic/seed in name/description/changeReason;
- not claimed as final HILTECH operating catalog;
- no source code branches on the seed name/code;
- organization-specific config may later supersede it normally.

## 12. Project activation

Slice 03 enables:
`READY -> ACTIVE`.

`ActivateProject` requires:
- Project state READY;
- exact Project version/baselineVersion;
- current valid Project responsibility;
- at least one current-baseline included WorkOrder;
- zero current-baseline included WorkOrders in DRAFT;
- every included current-baseline WorkOrder has current WorkPolicyBinding + WorkInstructionRevision;
- planning graph remains valid;
- no hard mandatory activation blocker defined by existing Project policy.

On success:
- lifecycle ACTIVE;
- startDateActual set if absent;
- project.activated event;
- audit.

Activation does not mean every WorkOrder is READY/ASSIGNED.

## 13. Commands/routes

Commands:
- CreateWorkOrder
- UpdateWorkOrderDetails
- PlanWork
- ReviseWorkInstruction
- AddWorkDependency
- RemoveWorkDependency
- ActivateProject

Routes:
- POST `/v1/projects/{projectId}/work-orders`
- GET `/v1/projects/{projectId}/work-orders`
- GET `/v1/work-orders/{workOrderId}`
- PUT `/v1/work-orders/{workOrderId}/details`
- POST `/v1/work-orders/{workOrderId}/plan`
- POST `/v1/work-orders/{workOrderId}/instruction-revisions`
- POST `/v1/work-orders/{workOrderId}/dependencies`
- DELETE `/v1/work-orders/{workOrderId}/dependencies/{dependencyId}`
- POST `/v1/projects/{projectId}/activate`

## 14. Authorization

Create/plan work requires:
- OpenFGA Project `can_create_work`;
- PostgreSQL current-source Project authority guard.

WorkOrder creation projects:
- Project relation;
- Site relation.

No assignment relation yet.

## 15. Windows

Planning Tree gains WorkOrder nodes/editor:
- WorkType selector;
- exact revision summary;
- title/scope/schedule/priority;
- instruction editor/preview;
- checklist preview;
- requirements preview;
- dependency editor;
- state DRAFT/PLANNED;
- clear "readiness not evaluated yet" state.

Do not show fake assignee/material availability.

## 16. Migration

`V0023__work_order_policy_binding__slice03.sql`.

Must be safe over V0022 and existing V0005 scaffold.

## 17. Tests

- same-tenant context enforced.
- WorkPackage FK/context enforced.
- CodePolicy allocation/collision/manual override.
- config revision binding exact.
- config supersession does not rewrite old WorkOrder.
- one current WorkPolicyBinding.
- instruction revision append-only.
- checklist materialized exactly once under idempotent replay.
- requirements materialized from bound revision.
- work dependency cycle rejected.
- stale WorkOrder update/plan rejected.
- unauthorized create/plan denied.
- ActivateProject refuses DRAFT/unbound included work.
- ActivateProject READY -> ACTIVE success.
- Windows render shows bound revision and no fake readiness.

## 18. Exit

A Project baseline can contain explainable PLANNED WorkOrders whose exact WorkType/policies/instructions/checklists remain reconstructable after future config changes.

`SLICE03_CONTRACT = FROZEN`
