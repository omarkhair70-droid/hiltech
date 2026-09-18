# 11 — Project / Site / Work Contract

Status: **CONTRACT CANDIDATE v0.1**
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

# 3. Site

## Structural decision

For the first production slice, Site is a Project delivery context:

- Site.projectId is required.
- siteCode is unique within Project.

The existing question of reusable ClientSite identity remains a broader-domain freeze item.

To avoid future rewrite pressure:
- references to a client-owned physical location may use `clientSiteRef` / future canonical external-location link,
- Project Site remains the delivery-context object.

If reality proves reusable client-site identity is required before production freeze, introduce a separate canonical ClientSite/Facility identity rather than making Project Site ambiguous.

## Site fields

- id: UUID
- projectId: UUID
- siteCode: String
- name: String
- clientSiteRef: String/UUID?
- addressText: String? RESTRICTED
- latitude: Decimal? RESTRICTED
- longitude: Decimal? RESTRICTED
- accessInstructions: Text? RESTRICTED
- contactIds: List<UUID>
- lifecycleState: SiteState
- timezone: IANA timezone?
- notes: Text? INTERNAL/RESTRICTED
- version: Long

## SiteState

Exact enum remains a closure item because current source docs do not define a complete transition table.

Minimum semantic requirements:
- active/usable vs inactive/closed distinction,
- state cannot be inferred only from Project state,
- field bundle includes only valid authorized site context.

Do not fabricate a final SiteState enum before the lifecycle is explicitly reviewed.

## Area / Room / Zone

- id: UUID
- siteId: UUID
- parentAreaId: UUID?
- typeCode: configurable/master code
- code: String
- name: String
- sequence: Int?
- restrictedAccess: Boolean
- version: Long

Invariant:
no cyclic hierarchy.

Type is configurable rather than fixed forever to ROOM/FLOOR/RACK_ROOM/ZONE.

---

# 4. WorkType / policy binding

Every WorkOrder binds a versioned configuration context.

## WorkPolicyBinding

Candidate immutable value/object:

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
- bindingCreatedAt: Instant

The exact persistence may be normalized rows or columns/JSON hybrid, but these semantics are required.

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
- currentInstructionRef/payload
- version: Long
- createdAt/by
- updatedAt

Assignments are not represented only by `assignedUserIds` + `assignedTeamId` columns.

Use typed assignment records.

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

Candidate principle:

Project/milestone progress derives from accepted WorkOrders where a configured progress model exists.

Do not let manual percentage silently override structured accepted truth.

Still to freeze:
- work weighting.
- milestone aggregation.
- treatment of rework/reopen.
- non-WorkOrder milestones.

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

- exact SiteState enum.
- reusable ClientSite/Facility identity decision.
- exact projectCode/workOrderCode generation rules.
- exact string lengths.
- exact ProjectHealth model.
- exact progress weighting model.
- exact typed instruction/checklist persistence.
- exact Project/Site field subset from representative real project.
- exact policy binding DB representation.

Current decision:
**Project/Site/Work domain is structurally contract-ready; remaining items are narrow freeze-closure decisions, not domain rediscovery.**
