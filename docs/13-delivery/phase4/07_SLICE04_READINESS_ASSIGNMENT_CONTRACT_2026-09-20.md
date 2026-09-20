# Phase 4 / Slice 04 — Readiness + WorkAssignment

Date: 2026-09-20  
Status: **REALITY CLOSED / IMPLEMENTATION CONTRACT FROZEN / PRODUCTION CODE AUTHORIZED ONLY FOR SLICE 04**

## 1. Goal

Turn PLANNED Work into explainably ready/blocked and assigned work without hard-coded people.

Core questions:
- Is this WorkOrder assignable?
- What blocks it?
- Which targets are eligible?
- Who owns the next decision?

## 2. Assignment mode

AssignmentPolicy gains explicit execution mode:

- AUTO
- SUGGEST_CONFIRM
- MANUAL_ELIGIBLE

AUTO does not invent a scoring engine.

Phase-4 AUTO behavior:
- if exactly one eligible target satisfies the policy, system may assign it;
- if zero, work remains unassigned with typed reason;
- if more than one, system produces candidates and routes confirmation instead of making an arbitrary choice.

Future optimization/scoring requires a separate proven policy.

## 3. Target types

Supported contract shape:
- USER
- TEAM
- CREW
- SUBCONTRACTOR_ORGANIZATION

Enabled policy may restrict the set.

### USER
Resolves current active identity + Employee + WorkforceAssignment and required Certifications.

### TEAM
Uses Phase-1 Team truth. It is not duplicated in Work schema.

### CREW
Temporary execution grouping owned by Work:
- `work_crew`;
- `work_crew_member`;
- effective-dated members referencing Employee;
- optional Project scope;
- no organization-level authorization merely from crew membership.

### SUBCONTRACTOR_ORGANIZATION
Uses active Organization type SUBCONTRACTOR.

No external capability claim is fabricated when no authoritative subcontractor capability source exists.

## 4. Eligibility result

`EligibleTargetResult` includes:
- targetType/id;
- display label;
- eligible Boolean;
- reasonCodes[];
- sourceFreshness/asOf;
- required role/cert checks;
- current assignment conflict if authoritative policy knows one.

Reason examples:
- EMPLOYEE_NOT_ACTIVE
- IDENTITY_NOT_ACTIVE
- ORGANIZATION_MEMBERSHIP_NOT_CURRENT
- ROLE_REQUIREMENT_MISSING
- CERTIFICATION_MISSING
- CERTIFICATION_EXPIRED
- TEAM_INACTIVE
- TEAM_HAS_NO_ELIGIBLE_MEMBER
- CREW_INACTIVE
- SUBCONTRACTOR_INACTIVE
- CAPABILITY_SOURCE_UNAVAILABLE
- TARGET_TYPE_NOT_ALLOWED

No opaque score is authoritative in Slice 04.

## 5. Readiness execution

WorkOrder.readinessState is derived from current WorkRequirementInstance truth.

`EvaluateReadiness`:
- exact WorkOrder version;
- bound ReadinessPolicy;
- recomputes applicable current sources;
- updates requirement satisfaction states;
- writes typed blocker/explanation;
- emits work.readiness_changed only on meaningful change.

### Source behavior

ASSIGNEE:
- **pre-assignment readiness must not be circular**;
- before lifecycle ASSIGNED, this requirement means the policy's minimum executable eligible-target set exists now;
- after assignment, it also verifies the active assignment still resolves to an eligible current target;
- therefore `EvaluateReadiness` can produce READY before `AssignWork` without pretending an assignment already exists.

DEPENDENCY:
- predecessor WorkOrders satisfy the bound rule.

SITE_ACCESS:
- ProjectSite/Area context + explicit confirmation source when policy permits.

DRAWING_REVISION:
- satisfied only by authoritative current approved document revision if available.

CLIENT_PERMISSION:
- explicit current confirmation/external source only.

SAFETY_PPE:
- explicit source/manual confirmation only where policy permits.

MATERIAL:
- requirement exists but availability is unresolved/blocked until Phase 5 supplies authoritative truth.

ASSET_TOOL:
- requirement exists but availability is unresolved/blocked until Phase 5 supplies authoritative truth.

The system never marks MATERIAL/ASSET_TOOL satisfied merely because Phase 5 is not implemented.

## 6. Waiver behavior

If bound policy permits a waiver:
- explicit command/decision;
- authority policy check;
- reason when required;
- immutable audit/waiver reference;
- exact requirement version;
- does not bypass hard domain/security invariants.

Slice 04 may support readiness waiver only for manual/waivable requirement types already represented by policy. No generic "force ready" button.

## 7. WorkAssignment

Existing `work_assignment` is canonical history.

Assignment:
- never overwrites old row;
- one active logical target row at a time according policy constraints;
- reassignment ends/replaces old rows and inserts new rows;
- sourceOperationId unique;
- exact WorkOrder version;
- assignment history remains.

Lifecycle:
`PLANNED + readiness READY -> ASSIGNED`.

Project may be READY or ACTIVE according to current Project scheduling context; StartWork remains a Phase-6 execution concern and requires the later execution gate.

An explicit valid waiver may satisfy the policy path where allowed.

## 8. Authorization projection

OpenFGA WorkOrder relations expand safely for:
- assigned_user;
- assigned_team;
- assigned_crew member relation;
- assigned_subcontractor organization member where applicable.

PostgreSQL current source guards are mandatory.

Pending grant:
- deny until APPLIED.

Ended/replaced assignment:
- deny immediately even with stale FGA tuple.

Queued/offline commands in future Phase 6 reauthorize against current assignment.

## 9. Work Queue

Slice 04 projects only genuine next actions:
- assignment confirmation required;
- no eligible target;
- waiver/exception requiring authority;
- severe readiness blocker requiring Project authority.

Work Queue is not a second WorkOrder state database.

## 10. Routes

- POST `/v1/work-orders/{workOrderId}/evaluate-readiness`
- GET `/v1/work-orders/{workOrderId}/readiness`
- GET `/v1/work-orders/{workOrderId}/eligible-targets`
- POST `/v1/work-orders/{workOrderId}/assign`
- POST `/v1/work-orders/{workOrderId}/reassign`
- GET `/v1/projects/{projectId}/work-queue-context`

Optional waiver route only if the bound policy enables it:
- POST `/v1/work-orders/{workOrderId}/readiness/{requirementId}/waive`

## 11. Migration

`V0024__work_readiness_assignment__slice04.sql`.

Includes:
- assignment mode/config additions;
- readiness execution metadata needed by policy;
- WorkCrew tables;
- same-tenant/target indexes;
- assignment/current-source indexes;
- no Warehouse/Stock mutation.

## 12. Windows

WorkOrder inspector adds:
- readiness state;
- requirement list;
- exact blocker reasons;
- source/unavailable reason;
- eligible targets;
- why target eligible/ineligible;
- assignment mode;
- Assign/Reassign;
- current assignment/history;
- Waiting On.

Warehouse-dependent requirements visibly say authoritative availability is pending Phase 5; never green placeholder.

## 13. Tests

- lifecycle/readiness separation.
- ASSIGNEE readiness is satisfiable from an eligible-target set before assignment; no circular dependency.
- after assignment, loss of target eligibility makes the ASSIGNEE requirement fail on reevaluation.
- cannot assign BLOCKED work without valid policy waiver.
- AUTO exactly-one behavior.
- AUTO ambiguous set routes confirmation.
- MANUAL only permits eligible target.
- USER current People truth.
- expired certification denied.
- stale WorkforceAssignment denied.
- TEAM requires active/current eligible membership.
- CREW membership history.
- SUBCONTRACTOR type/status validation.
- capability-source-unavailable explained.
- material/tool requirement remains blocked without Phase 5.
- assignment replay idempotent.
- reassignment history preserved.
- pending FGA grant denies.
- stale FGA tuple after replacement denies immediately.
- unauthorized assign/reassign denied.
- Windows render proves readiness + target explanations.

## 14. Exit

System can explain whether Work is assignable, why it is blocked, what targets are eligible, and who owns the next action, without hard-coding one employee or fabricating resource truth.

`SLICE04_CONTRACT = FROZEN`
