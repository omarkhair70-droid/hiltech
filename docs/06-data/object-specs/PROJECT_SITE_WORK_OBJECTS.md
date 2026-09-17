# Exact Object Specs — Project, Site, Work

Status: DOMAIN DATA MODEL v0.1 / NOT SCHEMA-FROZEN

Field notation:
- R = required
- O = optional
- C = computed/derived
- S = sensitive classification note

---

# Project

## Purpose
Canonical delivery object created from won commercial work or authorized internal project creation.

## Core fields
- id: UUID — R
- projectCode: String — R, human-facing unique
- name: String — R
- clientOrganizationId: UUID — R
- sourceOpportunityId: UUID — O
- contractId: UUID — O
- clientPoId: UUID — O
- lifecycleState: ProjectState — R
- healthState: ProjectHealth — C
- projectManagerId: UUID — O/R by active state
- startDatePlanned: LocalDate — O
- endDatePlanned: LocalDate — O
- startDateActual: Instant — O
- endDateActual: Instant — O
- baselineVersion: Int — R
- currency: ISO4217 — O
- internalBudgetAmount: Decimal — O — RESTRICTED
- sellValueAmount: Decimal — O — RESTRICTED
- paymentTermsRef: String/ObjectRef — O — RESTRICTED
- createdAt: Instant — R
- createdBy: UUID — R
- updatedAt: Instant — R
- version: Long — R optimistic lock

## Relationships
- client organization
- contacts
- sites
- milestones
- work packages/orders
- documents
- issues/changes
- invoices/receivables
- handover
- maintenance/warranty

## Invariants
- projectCode unique.
- ACTIVE requires projectManagerId unless policy explicitly allows temporary unassigned.
- baseline changes after activation must be versioned and use change/variation path where commercial/scope impact exists.
- CLOSED cannot have unresolved mandatory closure blockers.
- clientOrganizationId cannot change casually after award; requires controlled correction/migration.
- internalBudgetAmount never client-visible by default.

## Audit
All lifecycle transitions, baseline changes, PM changes, client relation changes.

## Offline
Cached read allowed.
Lifecycle/baseline authoritative changes online.

---

# Site

## Purpose
Physical/client location where project/service work occurs.

## Fields
- id: UUID — R
- projectId: UUID — R
- siteCode: String — R unique within project
- name: String — R
- clientSiteRef: String — O
- addressText: String — O — RESTRICTED
- latitude: Decimal — O — RESTRICTED
- longitude: Decimal — O — RESTRICTED
- accessInstructions: Text — O — RESTRICTED
- contactIds: [UUID] — O
- lifecycleState: SiteState — R
- timezone: IANA TZ — O
- notes: Text — O — INTERNAL/RESTRICTED
- version: Long — R

## Relationships
- areas/zones
- work
- assets
- documents
- support tickets
- maintenance visits

## Invariants
- site belongs to exactly one project/client context in delivery model unless later generalized into reusable client sites.
- geolocation visibility permission-scoped.
- access instructions never exposed to unauthorized external users.

## Offline
Assigned field users may cache site/access context.

---

# Area / Room / Zone

## Purpose
Optional physical hierarchy below site.

## Fields
- id
- siteId
- parentAreaId — O
- type — ROOM/FLOOR/RACK_ROOM/ZONE/etc.
- code
- name
- sequence/order
- restrictedAccessFlag
- version

## Invariants
No cyclic parent hierarchy.

---

# Milestone

## Fields
- id
- projectId
- code
- name
- state
- plannedDate
- actualDate
- weight/measure — O
- clientVisible: Boolean
- billingMilestoneRef — O
- acceptanceRequirement
- version

## Invariants
Progress derived/controlled; avoid arbitrary manual percentage if source work exists.

---

# WorkPackage

## Fields
- id
- projectId
- siteId — O
- milestoneId — O
- code
- name
- description
- ownerUserId/teamId
- state
- plannedStart/end
- dependencyRefs
- version

---

# WorkOrder

## Purpose
Executable unit assigned to field/internal/external worker(s).

## Fields
- id: UUID — R
- workOrderCode: String — R
- projectId: UUID — R
- siteId: UUID — R
- areaId: UUID — O
- workPackageId: UUID — O
- title: String — R
- description: Text — O
- lifecycleState: WorkOrderState — R
- readinessState: ReadinessState — C
- assignedUserIds: [UUID] — O
- assignedTeamId: UUID — O
- assignedSubcontractorOrgId: UUID — O
- supervisorId: UUID — O
- engineerId: UUID — O
- plannedStart: Instant — O
- plannedEnd: Instant — O
- actualStart: Instant — O
- submittedAt: Instant — O
- acceptedAt: Instant — O
- priority: Priority — R
- requiredEvidencePolicyId: UUID/ObjectRef — O
- drawingRevisionRefs: [Ref] — O
- requiredMaterialRefs: [Requirement] — O
- requiredAssetRefs: [Requirement] — O
- accessRequirement: String/ObjectRef — O
- baseInstructionVersion: Int — R
- version: Long — R

## Invariants
- must belong to active/non-terminal project.
- at least one executor before ASSIGNED.
- cannot ACCEPT without required evidence/technical checks.
- stale offline completion cannot overwrite cancelled/reassigned state.
- acceptedAt only set through AcceptWork transition.

## Audit
Assignment, state changes, evidence acceptance/rework, instruction revision changes.

## Offline
Assigned field users cache full job bundle.
Start/block/evidence/submit may queue offline.

---

# Task

## Purpose
Optional finer unit under WorkOrder.

## Fields
- id
- workOrderId
- title
- description
- assigneeId — O
- state
- order
- mandatory
- evidenceRequirement
- estimatedDuration — O
- version

## Invariants
WorkOrder completion policy determines whether all mandatory tasks must be accepted/completed.

---

# Blocker

## Fields
- id
- workOrder/project/site context
- type
- severity
- description
- createdBy
- ownerUser/team
- state
- resolvedAt
- resolution
- clientVisible flag
- version

## Types candidate
MATERIAL / EQUIPMENT / ACCESS / DRAWING / PEOPLE / TECHNICAL / CLIENT / SUPPLIER / OTHER

---

# Change / Variation

## Fields
- id
- projectId
- sourceIssueId — O
- title
- description
- technicalImpact
- scheduleImpact
- internalCostImpact — RESTRICTED
- clientPriceImpact — RESTRICTED
- currency
- versionNumber
- state
- internalApprovalRef
- clientApprovalRef
- effectiveBaselineVersion
- createdAt/by

## Invariants
Approved variation applies only to exact version.
Project baseline update references accepted variation.

---

# Data Classification Summary

Project basic identity: INTERNAL/RESTRICTED
Site/access: RESTRICTED
Internal cost/commercial: RESTRICTED
Field instruction/evidence: INTERNAL/RESTRICTED
Client-visible subset explicitly marked.

## Next
Translate these specs into:
- API contracts,
- DB schema,
- local cache schema,
- permission rules,
after reality validation.
