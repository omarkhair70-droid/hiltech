# 11 — Project / Site / Work Contract

Status: **CONTRACT CANDIDATE v0.3 / CORE PROJECT-WORK DECISIONS CLOSED**
Date: 2026-09-18

## Purpose

Convert the existing Project / Site / Work object specs, lifecycle, command catalog and configuration model into one implementation-facing contract for the first production slice.

Canonical sources:
- `docs/06-data/object-specs/PROJECT_SITE_WORK_OBJECTS.md`
- `docs/06-data/transition-tables/PROJECT_AND_WORK_TRANSITIONS.md`
- `docs/05-workflows/PROJECT_LIFECYCLE.md`
- `00_CONFIGURATION_POLICY_SCHEMAS.md`
- `04_ROOM_OFFLINE_SYNC.md`
- `05_AUTHORIZATION_POLICY_TESTS.md`

---

# 1. Critical normalization: lifecycle != readiness

The earlier transition model mixed WorkOrder lifecycle and readiness by using READY/BLOCKED as transition destinations after readiness evaluation.

The production contract separates them.

## WorkOrderLifecycleState

Candidate frozen enum:

- DRAFT
- PLANNED
- ASSIGNED
- IN_PROGRESS
- BLOCKED
- SUBMITTED_FOR_REVIEW
- REWORK_REQUIRED
- ACCEPTED
- CLOSED
- CANCELLED

## WorkReadinessState

Derived/config-driven:

- NOT_EVALUATED
- READY
- BLOCKED

Readiness is computed from the bound `ReadinessPolicy` and authoritative requirement satisfaction.

Rules:
- PLANNED + READY means assignable.
- PLANNED + BLOCKED means not normally assignable unless an explicit authorized waiver/override path exists.
- ASSIGNED can later become operationally blocked and move lifecycle to BLOCKED; this is distinct from pre-start readiness.
- readiness changes do not silently rewrite lifecycle.
- lifecycle transitions remain commands/audited events.

This separation must be reflected in DB/API/Room/UI/tests.

---

# 2. Project

## ProjectState

Candidate enum from the existing lifecycle:

- DRAFT
- KICKOFF
- PLANNING
- READY
- ACTIVE
- ON_HOLD
- DELIVERY_REVIEW
- HANDOVER
- DELIVERED
- CLOSED

A separate cancellation/archive path may be added only if actual project reality requires it; do not invent one now.

## Project fields

- id: UUID
- projectCode: String
- name: String
- clientOrganizationId: UUID
- sourceOpportunityId: UUID?
- contractId: UUID?
- clientPoId: UUID?
- lifecycleState: ProjectState
- healthState: derived ProjectHealth
- projectManagerId: UUID?
- startDatePlanned: LocalDate?
- endDatePlanned: LocalDate?
- startDateActual: Instant?
- endDateActual: Instant?
- baselineVersion: Int
- currencyCode: ISO-4217 String?
- internalBudgetAmount: Decimal? RESTRICTED
- sellValueAmount: Decimal? RESTRICTED
- paymentTermsRef: typed ref?
- createdAt: Instant
- createdBy: UUID
- updatedAt: Instant
- version: Long

## Project invariants

- projectCode unique in HILTECH organization scope.
- ACTIVE normally requires Project Manager unless an explicit project policy allows temporary unassigned state.
- baseline changes after activation are versioned.
- commercial/scope-impacting baseline changes use structured change/variation path.
- CLOSED requires mandatory closure blockers resolved.
- clientOrganizationId change after award is controlled correction/migration, not casual edit.
- finance/commercial fields are separately permissioned.

## Project commands — candidate

- CreateProjectFromAward
- CreateInternalProject where policy allows
- StartKickoff
- CompleteKickoff
- MarkProjectReady
- ActivateProject
- PutProjectOnHold
- ResumeProject
- StartDeliveryReview
- StartHandover
- AcceptDelivery
- CloseProject
- ChangeProjectManager
- ApplyApprovedVariation / UpdateBaseline through controlled flow

All lifecycle-changing commands are online-authoritative.

---

# 3. Site / ProjectSite

## Structural decision

A physical/client Site must survive beyond one delivery project because the same location can later participate in:
- another project,
- support,
- warranty,
- maintenance,
- managed service,
- asset history.

Therefore production separates:

### Site
Canonical durable physical/client location.

### ProjectSite
Association between one Project and one Site with project-specific delivery context.

This preserves one physical/site truth while allowing many project/service relationships over time.

## Site fields

- id: UUID
- clientOrganizationId: UUID
- siteCode: String
- name: String
- addressText: String? RESTRICTED
- latitude: Decimal? RESTRICTED
- longitude: Decimal? RESTRICTED
- timezone: IANA timezone?
- status: ACTIVE / INACTIVE
- createdAt/by
- updatedAt
- version: Long

Unique:
- clientOrganizationId + siteCode.

Site itself does not carry project-specific access instructions that may change by contract/project.

## ProjectSite fields

- id: UUID
- projectId: UUID
- siteId: UUID
- projectSiteCode: String?
- lifecycleState: ProjectSiteState
- accessInstructions: Text? RESTRICTED
- projectContactIds / link table
- projectSpecificNotes: Text?
- activeFrom: Instant?
- activeUntil: Instant?
- version: Long

Unique:
- projectId + siteId unless a rare explicit multi-association case is justified.

## ProjectSiteState

Frozen first-slice enum:
- PLANNED
- ACTIVE
- ON_HOLD
- COMPLETED
- CLOSED

This state describes the site's participation in a Project, not whether the physical Site still exists.

## Area / Room / Zone

Area belongs to Site.

- id: UUID
- siteId: UUID
- parentAreaId: UUID?
- typeCode: configurable/master code
- code: String
- name: String
- sequence: Int?
- restrictedAccess: Boolean
- version: Long

Project-specific scope can reference Areas through Work/ProjectSite context.

Invariant:
no cyclic hierarchy.

## Consequence

WorkOrder stores:
- projectId
- siteId
- projectSiteId where needed for exact project-specific context
- areaId optional

Support/Maintenance can reference the same Site after Project closure without duplicating physical identity.

# 4. WorkType / policy binding

Every WorkOrder binds a versioned configuration context.

## WorkPolicyBinding

Versioned binding-history aggregate:

- workTypeDefinitionId: UUID
- workTypeRevision: Int
- assignmentPolicyId: UUID
- assignmentPolicyRevision: Int
- readinessPolicyId: UUID
- readinessPolicyRevision: Int
- evidencePolicyId: UUID
- evidencePolicyRevision: Int
- reviewPolicyId: UUID
- reviewPolicyRevision: Int
- trackingPolicyId: UUID?
- trackingPolicyRevision: Int?
- checklistTemplateId: UUID?
- checklistTemplateRevision: Int?
- instructionTemplateId: UUID?
- instructionTemplateRevision: Int?
- bindingRevision: Int
- bindingCreatedAt: Instant
- bindingCreatedBy: UUID
- supersededAt: Instant?
- supersededByBindingId: UUID?
- rebindReason: String?

Persistence decision:
- normalized `work_policy_binding` history table.
- one current unsuperseded binding per WorkOrder.
- WorkOrder stores `currentPolicyBindingId`.
- unique (workOrderId, bindingRevision).
- prior bindings are never overwritten.

---

# 5. WorkOrder

## Fields

- id: UUID
- workOrderCode: String
- projectId: UUID
- siteId: UUID
- areaId: UUID?
- workPackageId: UUID?
- title: String
- description: Text?
- lifecycleState: WorkOrderLifecycleState
- readinessState: WorkReadinessState derived
- policyBinding: WorkPolicyBinding
- plannedStart: Instant?
- plannedEnd: Instant?
- actualStart: Instant?
- submittedAt: Instant?
- acceptedAt: Instant?
- closedAt: Instant?
- priorityCode: String/config value
- instructionRevision: Int/Long
- currentInstructionRevisionId: UUID
- version: Long
- createdAt/by
- updatedAt

Assignments are not represented only by `assignedUserIds` + `assignedTeamId` columns.

Use typed assignment records.

---

# 5A. Human code generation

Project and WorkOrder UUIDs are identity.

Human codes are allocated by active `CodePolicy`.

First-slice rules:
- Project code sequence default scope: ORGANIZATION.
- WorkOrder code sequence default scope: PROJECT.
- actual prefix/year/padding/reset behavior comes from active CodePolicy.
- server allocates generated sequences transactionally.
- CreateProject/CreateWorkOrder may accept an explicit human code only when CodePolicy.manualOverrideAllowed = true.
- code collision returns validation/conflict; server never silently renames historical codes.
- changing CodePolicy affects future allocations only.

No fixed HILTECH prefix is compiled into code.

---

# 5B. Work instruction revisions

`WorkInstructionRevision` is append-only/versioned.

Fields:
- id: UUID
- workOrderId: UUID
- instructionRevision: Int
- sourceInstructionTemplateId: UUID?
- sourceInstructionTemplateRevision: Int?
- payloadSchemaVersion: Int
- structuredPayloadJson: schema-validated JSON
- summaryText: String?
- createdAt
- createdBy
- supersedesInstructionRevisionId: UUID?
- changeReason: String?
- correlationId

Rules:
- unique (workOrderId, instructionRevision).
- WorkOrder.currentInstructionRevisionId points to latest authoritative revision.
- after ASSIGNED, material instruction revision is an explicit online-authoritative command that increments WorkOrder version and makes old offline bundles stale.
- stale offline Start/Submit cannot apply against a newer material instruction/WorkOrder version.
- prior instruction revisions remain readable in audit/history.

---

# 5C. Checklist materialization

ChecklistTemplate activation does not make runtime completion live only in config JSON.

When a WorkOrder binds/materializes a checklist, create `WorkChecklistItemInstance` rows:

- id
- workOrderId
- sourceTemplateId/revision
- itemKey
- label
- required
- sortOrder
- completionState: PENDING / COMPLETE / NOT_APPLICABLE
- completedAt/by?
- evidenceRequirementKey?
- notes?
- version

This enables offline completion, query, audit and review without parsing an opaque template blob.

---

# 6. WorkAssignment

## AssignmentTargetType

- USER
- CREW
- TEAM
- SUBCONTRACTOR_ORGANIZATION

## Candidate fields

- id: UUID
- workOrderId: UUID
- targetType
- targetId: UUID
- lead: Boolean
- assignedAt: Instant
- assignedBy: UUID
- validFrom: Instant
- validUntil: Instant?
- state: ACTIVE / ENDED / REPLACED
- sourceOperationId: UUID
- version: Long?

Rules:
- at least one ACTIVE executor before lifecycle ASSIGNED.
- assignment history is preserved.
- reassignment does not overwrite history.
- exact eligibility is evaluated using AssignmentPolicy + authorization.
- user/team/crew/subcontractor assignment can coexist only if policy permits.

---

# 7. Work requirements

The WorkOrder should materialize/evaluate requirement instances from configuration rather than embed a permanent universal checklist.

Candidate requirement instance families:

- ReadinessRequirementInstance
- EvidenceRequirementInstance
- AssetRequirementInstance
- MaterialRequirementInstance
- Document/RevisionRequirementInstance

Each requirement instance keeps:
- source policy/template revision.
- requirement key.
- current satisfaction state.
- authoritative evidence/object refs.
- waiver/override record if applicable.
- audit.

This lets future WorkTypes introduce different requirements without schema redesign.

---

# 8. Work lifecycle commands

## PlanWork

DRAFT → PLANNED

Requires:
- Project/Site valid.
- WorkType/policy bindings resolvable.
- scope/instruction present.

Online-authoritative.

## EvaluateReadiness

Does not change lifecycle by itself.

Recomputes:
`readinessState = READY | BLOCKED`

Triggered by relevant changes:
- assignment eligibility,
- material reservation,
- asset/tool availability,
- document revision,
- access,
- dependency,
- configured requirements.

## AssignWork

PLANNED → ASSIGNED

Requires:
- readiness READY unless explicit authorized waiver path.
- AssignmentPolicy satisfied.
- executor target eligible.
- baseVersion current.

Online-authoritative.

## StartWork

ASSIGNED → IN_PROGRESS

Requires:
- actor currently assigned/authorized.
- mandatory start requirements satisfied.
- work not cancelled/reassigned.
- baseVersion.

Offline-capable typed command.

## BlockWork

IN_PROGRESS → BLOCKED

Requires:
- typed blocker/reason according policy.

Offline-capable.

## ResumeWork

BLOCKED → IN_PROGRESS

Requires:
- blocker resolved/waived according policy.

Offline-capable; server may reject stale state.

## SubmitWorkCompletion

IN_PROGRESS → SUBMITTED_FOR_REVIEW

Requires:
- EvidencePolicy BEFORE_SUBMIT requirements satisfied.
- required measurements/tests/material declarations satisfied.
- unresolved blocker policy satisfied.
- exact bound policy context.

Offline-capable after evidence dependencies.

## AcceptWork

SUBMITTED_FOR_REVIEW → ACCEPTED

Requires:
- actor selected/eligible under bound ReviewPolicy.
- exact submitted version.
- EvidencePolicy BEFORE_ACCEPT requirements satisfied.
- technical checks satisfied.
- no material unseen change.

Online-authoritative.

## RequestRework

SUBMITTED_FOR_REVIEW → REWORK_REQUIRED

Requires:
- eligible reviewer.
- reason/evidence obligations.

Online-authoritative.

## ResumeRework

REWORK_REQUIRED → IN_PROGRESS

Assigned executor/supervisor under policy.
Offline-capable.

## CloseWork

ACCEPTED → CLOSED

Requires:
- post-acceptance obligations complete.

Authority:
system/policy/PM according configured rule.

Online-authoritative.

## CancelWork

Any non-terminal applicable state → CANCELLED

Requires:
- cancellation authority.
- reason.
- impact handling.

Online-authoritative.

---

# 9. Work Blocker

Candidate fields:

- id: UUID
- workOrderId
- projectId/siteId derived/context
- blockerTypeCode: configurable/master code
- severityCode
- description
- createdBy
- ownerTargetType/id?
- state: OPEN / RESOLVED / WAIVED
- resolvedAt?
- resolution?
- clientVisible: Boolean
- waiverAuthorityRef?
- version: Long

Do not make MATERIAL/EQUIPMENT/ACCESS/etc. the only future blocker types; use typed/configurable master codes with known built-in semantics where automation needs them.

---

# 10. Work review history

Review/rework decisions are append/audit history, not only current state.

Candidate `WorkReviewDecision`:

- id
- workOrderId
- submittedWorkVersion
- reviewPolicyId/revision
- reviewStepId
- reviewerUserId
- decision: ACCEPT / REWORK / REJECT/TECHNICAL_HOLD if policy supports
- reason
- evidenceRefs
- decidedAt
- delegationContext?
- audit/correlation

Accepted WorkOrder points to the decision/version that produced acceptance.

---

# 11. Offline bundle contract

TechnicianJobBundle must include:

- WorkOrder identity/state/version.
- Project/Site safe context.
- assignment.
- WorkPolicyBinding revisions.
- instruction revision.
- readiness requirement instances.
- evidence requirement instances.
- required asset/material refs.
- approved/current document refs + revisions.
- configured tracking subset when enabled.
- freshness/version metadata.

It must exclude unrelated finance/commercial/security/private data.

---

# 12. Project progress

## First-slice progress contract

Numeric operational progress derives from **ACCEPTED WorkOrders only**.

Each WorkOrder stores:
- countsTowardProjectProgress: Boolean
- progressWeight: Decimal(20,6), defaulted from bound WorkTypeDefinition but snapshotted on the WorkOrder/baseline.

For a baseline scope:

`progress = acceptedIncludedWeight / totalIncludedBaselineWeight × 100`

Rules:
- CANCELLED or explicitly baseline-excluded work contributes neither numerator nor denominator.
- scope addition/removal that changes denominator must occur through controlled baseline/variation change and increments baselineVersion.
- IN_PROGRESS/SUBMITTED/REWORK work contributes 0 to accepted progress in the first slice; no subjective partial-completion percentage.
- ACCEPTED contributes full snapshotted weight.
- an already ACCEPTED WorkOrder is not casually reopened in first slice; correction/new scope uses controlled rework/change/new WorkOrder path.
- Milestone progress uses the same weight rule over its included WorkOrders when a WorkOrder→Milestone relation exists.
- non-WorkOrder milestone/status items are shown as state/checkpoints and do not silently alter numeric work progress.

The UI may show additional operational indicators, but manual percent is not authoritative truth.

## ProjectHealth

Derived projection:
- UNKNOWN
- HEALTHY
- ATTENTION
- CRITICAL
- ON_HOLD

Rules:
- ON_HOLD project lifecycle overrides to ON_HOLD.
- otherwise active typed health signals are evaluated under bound/active ProjectHealthPolicy.
- every non-HEALTHY result exposes contributing signal codes/objects.
- ProjectHealth is not a stored editable manager opinion.
- finance/commercial risk may later contribute only through separately permissioned signals.

First-slice signals can derive from:
- overdue included WorkOrders,
- BLOCKED WorkOrders,
- rework backlog,
- resource-readiness failures,
- milestone delay,
- explicit client-action-required blockers.

---

# 13. Events

Minimum first-slice domain events:

Project:
- project.created
- project.kickoff_started
- project.kickoff_completed
- project.ready
- project.activated
- project.put_on_hold
- project.resumed
- project.delivery_review_started
- handover.started
- project.delivery_completed
- project.closed
- project.manager_changed
- project.baseline_changed

Work:
- work.created/planned
- work.readiness_changed
- work.assigned
- work.reassigned
- work.started
- work.blocked
- work.resumed
- work.evidence_finalized
- work.submitted_for_review
- work.reviewed
- work.accepted
- rework.requested
- work.rework_started
- work.closed
- work.cancelled

Event payloads must include IDs/version/correlation and only safe minimal data.

---

# 14. Authorization hooks

Project actions use project relationships/policy.

Work actions use:
- Project context,
- active WorkAssignment,
- ReviewPolicy,
- configured authority,
- OpenFGA relationship,
- application obligations.

No direct `if (role == PM)` as final authorization logic.

---

# 15. DB ownership

Server modules:

## projects
Owns:
- project
- site
- area
- milestone
- work_package

## work
Owns:
- work_order
- work_policy_binding
- work_assignment
- work_requirement instances
- blocker
- review/rework history

No cross-module direct table mutation.

Project progress projection may consume Work events.

---

# 16. Required tests

Project:
- projectCode uniqueness.
- invalid lifecycle transition.
- active PM requirement policy.
- close blockers.
- baseline version safety.
- sensitive field projection.

Work:
- lifecycle/readiness separation.
- cannot Assign when readiness blocked without valid waiver.
- AssignmentPolicy eligibility.
- StartWork stale conflict.
- reassignment history.
- offline Start/Block/Resume/Submit.
- EvidencePolicy before submit.
- ReviewPolicy exact-version acceptance.
- stale review rejected.
- rework history preserved.
- cancel vs offline completion conflict.
- policy revision binding preserved.
- unauthorized actor deny.
- field-level technician projection.
- old WorkOrder still explainable after WorkType/policy supersession.

---

# 17. Open items before final freeze

No broad Project/Site/Work structural decision remains.

Closed in v0.3:
- Site status and ProjectSite lifecycle.
- CodePolicy-driven Project/WorkOrder codes.
- ProjectHealth explainable signal model.
- accepted-weight progress formula.
- typed instruction revision persistence.
- materialized checklist item instances.
- representative field subset structurally validated by internal fixtures.
- versioned normalized WorkPolicyBinding history representation.

Still required outside domain structure:
- final physical DDL/indexes.
- final API route/schema normalization.
- visual/RTL proof.
- provider/ops and contract-test closure.

Current decision:
**Project/Site/Work core domain contract is structurally closed for first-slice freeze.**
