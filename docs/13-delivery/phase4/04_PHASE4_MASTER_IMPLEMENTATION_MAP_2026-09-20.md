# Phase 4 — Full Implementation Map

Date: 2026-09-20  
Status: **FROZEN PLANNING / NO PRODUCTION CODE IN THIS CHANGE**

## 1. Purpose

Freeze the remaining Phase 4 implementation before handing execution to Codex.

Slice 01 is already VERIFIED / MERGED. This map closes the intended implementation order for Slice 02 through Slice 06 so implementation agents do not redesign the product one slice at a time.

Canonical Phase 4 chain:

`Slice 01 Project + Site Core`
→ `Slice 02 Project Planning Structure`
→ `Slice 03 WorkOrder + WorkType / Policy Binding`
→ `Slice 04 Readiness + WorkAssignment`
→ `Slice 05 Review / Rework / Accepted Progress / Project Health`
→ `Slice 06 Basic Assigned Work Mobile Read / Phase 5–6 Handoff`

## 2. Source-of-truth inputs

The implementation must remain compatible with:

- `docs/13-delivery/phase4/00_PHASE4_OWNER_ENTRY_REALITY_RECONSTRUCTION_2026-09-20.md`
- `docs/13-delivery/phase4/01_PHASE4_SCOPE_AND_SLICE_PLAN_2026-09-20.md`
- `docs/13-delivery/phase4/02_PROJECT_SITE_CORE_REALITY_AND_IMPLEMENTATION_CONTRACT_2026-09-20.md`
- `docs/13-delivery/phase4/03_PROJECT_SITE_CORE_FINAL_GAP_REVIEW_2026-09-20.md`
- `docs/13-delivery/first-slice-contract-pack/11_PROJECT_SITE_WORK_CONTRACT.md`
- `docs/13-delivery/first-slice-contract-pack/00_CONFIGURATION_POLICY_SCHEMAS.md`
- `docs/13-delivery/first-slice-contract-pack/02_API_AND_READ_MODELS.md`
- `docs/13-delivery/first-slice-contract-pack/05_AUTHORIZATION_POLICY_TESTS.md`
- `docs/13-delivery/first-slice-contract-pack/15_AUTHORIZATION_CONSISTENCY_CONTRACT.md`
- `docs/13-delivery/first-slice-contract-pack/16_DATABASE_DDL_CONSTRAINT_CONTRACT.md`
- `docs/05-workflows/PROJECT_LIFECYCLE.md`
- `docs/06-data/object-specs/PROJECT_SITE_WORK_OBJECTS.md`
- `docs/06-data/transition-tables/PROJECT_AND_WORK_TRANSITIONS.md`
- `docs/10-design/wireflows/PROJECT_CONTROL_WIREFLOW.md`
- `docs/10-design/wireflows/FIELD_OFFLINE_WIREFLOW.md`

## 3. Cross-slice invariants

These are not design questions for implementation agents.

1. PostgreSQL is authoritative business truth.
2. OpenFGA is authorization projection/decision support, not business source truth.
3. Relationship-changing commands commit PostgreSQL + projection intent together and fail closed during projection lag.
4. No authorization from job title, role label, membership type, or a named person.
5. Project responsibility remains effective-dated Employee/Team history from Slice 01.
6. Site is durable/reusable. ProjectSite owns project-specific Site context.
7. WorkOrder lifecycle and readiness are separate dimensions.
8. WorkAssignment is append/history, never one mutable assignee column.
9. WorkType/policies/instructions/checklists are revision-bound and historical work remains explainable.
10. Operational progress is accepted included WorkOrder weight, never subjective manual percentage.
11. ProjectHealth is derived and explainable; non-healthy state must expose source signals.
12. Phase 4 may represent material/tool requirements but must not fabricate availability, reservation, custody, stock, issue, return or consumption before Phase 5.
13. Phase 4 may expose assigned-work Android read context but must not implement the Phase 6 offline execution state machine.
14. No direct cross-module table mutation. Projects owns project planning. Work owns executable work. People/Teams remain Phase 3/1 truth.
15. Every retry-sensitive command uses operationId + Idempotency-Key + baseVersion where an existing target is changed.
16. Every Slice closes on the exact tested head only.

## 4. Module ownership

### projects module

Owns:
- Project / ProjectSite / Site behavior from Slice 01;
- Area behavior;
- Milestone;
- WorkPackage;
- planning dependency graph;
- Project planning baseline gate;
- Project lifecycle through READY in Slice 02;
- Project progress/health projections in Slice 05 as consumer projections of Work events.

### work module

Owns:
- WorkOrder;
- WorkPolicyBinding;
- WorkInstructionRevision;
- WorkChecklistItemInstance;
- WorkRequirementInstance;
- WorkAssignment;
- WorkCrew when temporary execution grouping is needed;
- WorkBlocker;
- WorkReviewDecision;
- Work lifecycle/readiness behavior.

### configuration source

Existing `config_revision` + typed children remain source for:
- WorkTypeDefinition;
- AssignmentPolicy;
- ReadinessPolicy;
- EvidencePolicy;
- ReviewPolicy;
- FieldTrackingPolicy;
- templates;
- CodePolicy;
- ProjectHealthPolicy.

Phase 4 may add typed columns/child rows needed by the already-frozen policy contracts, but must not build an unrelated configuration product.

## 5. Forward migration map

Never edit V0001–V0021.

Planned forward migrations:

- `V0022__projects_planning__slice02.sql`
  - Area tenant hardening;
  - Milestone;
  - WorkPackage;
  - normalized planning dependencies;
  - indexes/constraints needed for planning and READY gate.

- `V0023__work_order_policy_binding__slice03.sql`
  - harden existing V0005 Work tables;
  - WorkOrder tenant/baseline consistency;
  - WorkPackage FK;
  - optional WorkTask planning table;
  - WorkOrder dependency structure;
  - active WorkPolicyBinding uniqueness/indexes;
  - typed instruction/checklist/runtime invariants;
  - configuration extensions required to bind the documented WorkType contract;
  - representative synthetic config seed only where needed for deterministic proof.

- `V0024__work_readiness_assignment__slice04.sql`
  - AssignmentPolicy execution-mode fields;
  - readiness requirement execution metadata;
  - temporary WorkCrew + membership when CREW target is enabled;
  - assignment/readiness indexes and source guards;
  - no Warehouse stock tables changed.

- `V0025__work_review_progress_health__slice05.sql`
  - ReviewPolicy normalized decision semantics required by production;
  - health signal/projection support;
  - progress recomputation support/indexes;
  - no commercial claim tables.

Slice 06 is read/UI/projection work. It must not create an empty migration. A V0026 migration is permitted only if implementation proves a real persistence invariant absent from V0022–V0025.

## 6. Lifecycle ownership by Slice

### Slice 01 — complete
Project:
`DRAFT -> KICKOFF -> PLANNING`

### Slice 02
Project:
`PLANNING -> READY`

MarkReady freezes the initial planning baseline boundary. Planning mutation commands are denied after READY in Phase 4 unless a later explicit baseline/change path owns the mutation.

### Slice 03
Project:
`READY -> ACTIVE`

ActivateProject requires:
- current Project responsibility;
- a frozen planning structure;
- at least one included PLANNED WorkOrder;
- no included baseline WorkOrder left DRAFT;
- all included WorkOrders have current policy binding + instruction revision;
- exact current baseline/version.

WorkOrder:
`DRAFT -> PLANNED`

### Slice 04
WorkOrder:
`PLANNED -> ASSIGNED`

Readiness:
`NOT_EVALUATED -> READY | BLOCKED` independently of lifecycle.

### Slice 05
Production review boundary:
`SUBMITTED_FOR_REVIEW -> ACCEPTED | REWORK_REQUIRED`

Project:
`ACTIVE <-> ON_HOLD` may be enabled here because health/readiness exception management now exists.

Phase 6 remains owner of normal technician execution transitions:
`ASSIGNED -> IN_PROGRESS -> BLOCKED/RESUMED -> SUBMITTED_FOR_REVIEW`.

### Slice 06
No new authoritative execution lifecycle transition.

## 7. API ownership map

### Slice 02

- GET `/v1/projects/{projectId}/plan`
- POST `/v1/projects/{projectId}/areas`
- PUT `/v1/areas/{areaId}`
- POST `/v1/projects/{projectId}/milestones`
- PUT `/v1/milestones/{milestoneId}`
- POST `/v1/projects/{projectId}/work-packages`
- PUT `/v1/work-packages/{workPackageId}`
- POST `/v1/projects/{projectId}/plan-dependencies`
- DELETE `/v1/projects/{projectId}/plan-dependencies/{dependencyId}`
- POST `/v1/projects/{projectId}/mark-ready`

### Slice 03

- POST `/v1/projects/{projectId}/work-orders`
- PUT `/v1/work-orders/{workOrderId}/details`
- POST `/v1/work-orders/{workOrderId}/plan`
- POST `/v1/work-orders/{workOrderId}/instruction-revisions`
- POST `/v1/work-orders/{workOrderId}/tasks`
- PUT `/v1/work-tasks/{taskId}`
- POST `/v1/work-orders/{workOrderId}/dependencies`
- DELETE `/v1/work-orders/{workOrderId}/dependencies/{dependencyId}`
- GET `/v1/projects/{projectId}/work-orders`
- GET `/v1/work-orders/{workOrderId}`
- POST `/v1/projects/{projectId}/activate`

### Slice 04

- POST `/v1/work-orders/{workOrderId}/evaluate-readiness`
- GET `/v1/work-orders/{workOrderId}/readiness`
- GET `/v1/work-orders/{workOrderId}/eligible-targets`
- POST `/v1/work-orders/{workOrderId}/assign`
- POST `/v1/work-orders/{workOrderId}/reassign`
- GET `/v1/projects/{projectId}/work-queue-context`

### Slice 05

- GET `/v1/review/work-items`
- POST `/v1/work-orders/{workOrderId}/accept`
- POST `/v1/work-orders/{workOrderId}/request-rework`
- GET `/v1/projects/{projectId}/command-center`
- GET `/v1/projects/{projectId}/progress`
- GET `/v1/projects/{projectId}/health`
- POST `/v1/projects/{projectId}/put-on-hold`
- POST `/v1/projects/{projectId}/resume`

There is no Phase-4 production endpoint to fake technician submission. Slice 05 integration tests may seed an authoritative submitted fixture directly.

### Slice 06

- GET `/v1/field/today`
- GET `/v1/work-orders/{workOrderId}/job-bundle`

The Slice-06 job bundle is read-only execution context. It contains no offline command queue and no false "completed/synced" state.

## 8. Authorization map

Existing Project relationships remain authoritative.

Slice 02:
- planning read inherits Project can_view.
- planning mutation requires Project can_manage.
- no Area/Milestone/WorkPackage title implies permission.

Slice 03:
- create/plan work requires `project.can_create_work` plus PostgreSQL current-source guard.
- WorkOrder projects Project + Site relation to OpenFGA.
- no WorkOrder authority is granted from display role strings.

Slice 04:
- assignment requires `project.can_assign_work`.
- target eligibility is a business-source check in PostgreSQL before OpenFGA assignment relation projection.
- USER, TEAM, CREW and SUBCONTRACTOR_ORGANIZATION use explicit current-source resolvers.
- pending assignment grants deny until applied.
- ended/replaced assignment denies immediately from PostgreSQL even if stale tuple exists.

Slice 05:
- review eligibility derives from bound ReviewPolicy + Project relationship + current source guard.
- exact submitted version is mandatory.
- pending reviewer grants fail closed.

Slice 06:
- assigned-work read requires current assignment and Project/Site access.
- expired/replaced assignment disappears immediately from Today even if projection cleanup lags.

## 9. Assignment target source truth

### USER

A USER execution target is eligible only when it resolves to:
- current active UserIdentity;
- current HILTECH organization membership;
- active/preboarding Employee as policy allows;
- current WorkforceAssignment;
- required role codes;
- verified, unexpired Certification when the bound policy requires that code.

The existing `required_skill_or_certification_codes` field does not permit invented skill truth. Until a skill source exists, only authoritative Certification codes satisfy capability requirements.

### TEAM

TEAM must be an active Team in the same HILTECH organization and must have at least one current eligible executable member for policies requiring executable capacity.

### CREW

CREW is a temporary operational execution group, not a replacement for Phase-1 Team structure.

Slice 04 may create Work-owned `work_crew` / `work_crew_member`:
- organization-scoped;
- optional Project scope;
- effective-dated;
- members reference current Employee;
- no new organizational permission is implied merely by crew membership.

### SUBCONTRACTOR_ORGANIZATION

Target must be an ACTIVE `organization.organization_type='SUBCONTRACTOR'`.

If AssignmentPolicy requires internal role/certification evidence that HILTECH cannot authoritatively verify for the external organization, eligibility is not fabricated. The target is ineligible with an explainable source-unavailable reason until a later supplier/subcontractor capability source exists.

## 10. Readiness integration truth

Phase 4 may evaluate only sources it actually owns/has.

- ASSIGNEE: Phase 3 People + Slice 04 assignment.
- DEPENDENCY: Work dependency state.
- SITE_ACCESS: explicit current ProjectSite/Area/manual confirmation when policy allows.
- DRAWING_REVISION: only when an authoritative current document revision exists; otherwise unresolved.
- CLIENT_PERMISSION: explicit manual/external-confirmation source when configured.
- SAFETY_PPE: only explicit configured/manual source.
- MATERIAL: requirement can exist, availability remains unresolved/blocked until Phase 5 authoritative source exists.
- ASSET_TOOL: requirement can exist, availability remains unresolved/blocked until Phase 5 authoritative source exists.

No "available" placeholder is allowed.

## 11. Baseline semantics

Phase 4 initial baseline is intentionally simple and safe.

- Project.baselineVersion remains the authoritative baseline number.
- Slice 02 planning rows are editable only while Project is PLANNING.
- MarkProjectReady validates the planning graph and freezes the initial baseline boundary.
- Slice 03 WorkOrders created for the READY baseline store `baseline_version = Project.baselineVersion`.
- ActivateProject requires all included current-baseline WorkOrders to be PLANNED/bound and no included DRAFT work.
- After ACTIVE, Phase 4 does not expose generic plan mutation that silently changes the denominator/scope.
- Future approved variation/change flow owns baseline increment and baseline-changing scope. No fake variation path is introduced here.

This provides baseline-version safety without inventing a mutable shadow baseline.

## 12. Windows surfaces

Slice 02:
- Project Planning Tree: Sites/Areas, Milestones, WorkPackages, dependency inspector.
- exact stale-version UX.
- no WorkOrder controls yet.

Slice 03:
- Work planning editor inside the planning tree.
- WorkType + bound revision summary.
- instruction/checklist preview.
- no readiness/assignment claims yet.

Slice 04:
- readiness panel with exact blocker reasons.
- eligible target explanation.
- assign/reassign.
- Waiting On / current owner.

Slice 05:
- PM Command Center.
- progress from accepted included WorkOrders.
- explainable health.
- review/rework queue.
- no fabricated commercial/warehouse signals.

Slice 06:
- Android Today / Assigned Work.
- Arabic-first RTL/adaptive.
- read-only authoritative work context.

## 13. Required CI gates

Each production Slice must add its own dedicated workflow and also be included in Bootstrap.

Required exact-head proof per Slice:
- database migration/constraint contract;
- server domain contract;
- authorization/OpenFGA contract where relations change;
- shared DTO/client contract;
- Windows render where Windows UI changes;
- Android render in Slice 06;
- existing Phase 0–3 regression workflows that trigger;
- post-merge Bootstrap.

No rerun may be used to hide deterministic product failure. Infrastructure/render flake may be rerun only after log diagnosis shows no product defect.

## 14. Slice merge discipline

For every Slice:
1. create dedicated branch from current main;
2. implement only that Slice;
3. keep PR Draft while incomplete;
4. diagnose failures from logs;
5. all required gates green on one exact head;
6. final gap review against frozen Slice contract;
7. if a gap exists, fix and rerun exact head;
8. mark Ready;
9. merge exact tested head;
10. verify post-merge Bootstrap;
11. update program status;
12. proceed to next Slice.

## 15. Phase 4 stop conditions

Implementation agent must stop for human/reality review only if:
- repository contracts contradict one another in a way that changes business meaning;
- an authoritative source object required for a hard invariant does not exist and adding one would cross a phase boundary;
- a security/privacy requirement cannot be satisfied from current architecture;
- implementation would require fabricating Warehouse, field execution, commercial, payroll or legal truth.

Normal coding difficulty is not a stop condition.

## 16. Completion target

After Slice 06, Phase 4 means:

- Project/Site identity works;
- planning structure works;
- WorkOrder planning is revision-bound;
- readiness/eligibility/assignment are explainable;
- review/rework/progress/health foundations work;
- Windows PM can drill from Project to source work/blocker/action;
- Android assignee can read the same authoritative assigned context;
- Phase 5 and Phase 6 integration seams are explicit and honest.

It does **not** mean stock/custody is complete or field work can execute offline.

`PHASE4_MASTER_PLAN = FROZEN`
