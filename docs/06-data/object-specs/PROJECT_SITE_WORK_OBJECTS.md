# Exact Object Specs — Project, Site, Work

Status: DOMAIN DATA MODEL v0.2 / PRE-FREEZE / CONTRACT PACK CANONICAL

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
Canonical durable physical/client location that can survive across multiple projects, support cases, warranty, maintenance, and managed-service relationships.

## Fields
- id: UUID — R
- clientOrganizationId: UUID — R
- siteCode: String — R, human-facing unique within client organization candidate
- name: String — R
- addressText: String — O — RESTRICTED
- latitude: Decimal — O — RESTRICTED
- longitude: Decimal — O — RESTRICTED
- timezone: IANA TZ — O
- status: ACTIVE/INACTIVE candidate — R
- createdAt/by
- updatedAt
- version: Long — R

## Invariants
- Site identity is not recreated merely because a new Project starts at the same physical/client location.
- geolocation visibility is permission-scoped.
- support/maintenance can reference Site after Project closure.

## Offline
Assigned field users may cache the safe Site subset required by active work.

---

# ProjectSite

## Purpose
Project-specific association between Project and canonical Site.

## Fields
- id: UUID — R
- projectId: UUID — R
- siteId: UUID — R
- projectSiteCode: String — O
- lifecycleState: PLANNED/ACTIVE/ON_HOLD/COMPLETED/CLOSED candidate
- accessInstructions: Text — O — RESTRICTED
- projectSpecificNotes: Text — O — INTERNAL/RESTRICTED
- activeFrom: Instant — O
- activeUntil: Instant — O
- version: Long — R

## Relationships
- project-specific contacts
- work
- documents
- project/site storage context

## Invariants
- unique Project + Site association by default.
- access instructions never exposed to unauthorized users.
- closing ProjectSite does not delete canonical Site history.

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
Executable unit assigned to internal/external worker target(s), bound to versioned WorkType/policy configuration.

## State dimensions
- lifecycleState: DRAFT / PLANNED / ASSIGNED / IN_PROGRESS / BLOCKED / SUBMITTED_FOR_REVIEW / REWORK_REQUIRED / ACCEPTED / CLOSED / CANCELLED
- readinessState: NOT_EVALUATED / READY / BLOCKED — derived from bound ReadinessPolicy

Lifecycle and readiness are independent.

## Core fields
- id: UUID — R
- workOrderCode: String — R
- projectId: UUID — R
- siteId: UUID — R
- projectSiteId: UUID — O
- areaId: UUID — O
- workPackageId: UUID — O
- title: String — R
- description: Text — O
- lifecycleState — R
- readinessState — C
- plannedStart/end — O
- actualStart — O
- submittedAt — O
- acceptedAt — O
- closedAt — O
- priorityCode — R
- instructionRevision — R
- instructionRef/payload
- version: Long — R
- createdAt/by
- updatedAt

## Policy binding
WorkOrder binds revisions for:
- WorkTypeDefinition
- AssignmentPolicy
- ReadinessPolicy
- EvidencePolicy
- ReviewPolicy
- FieldTrackingPolicy — O
- checklist/instruction templates — O

Exact binding contract:
`docs/13-delivery/first-slice-contract-pack/11_PROJECT_SITE_WORK_CONTRACT.md`.

## Assignment
Assignments are first-class WorkAssignment history records targeting:
- USER
- CREW
- TEAM
- SUBCONTRACTOR_ORGANIZATION

Do not use assignedUserIds/assignedTeamId columns as the authoritative long-term assignment model.

## Invariants
- belongs to a valid non-terminal Project/ProjectSite context.
- at least one eligible active executor before ASSIGNED.
- readiness READY required before normal assignment/start unless an explicit authorized waiver path exists.
- cannot ACCEPT without bound Evidence/Review requirements.
- stale offline completion cannot overwrite cancellation/reassignment/newer version.
- acceptedAt only set through exact-version AcceptWork.
- policy/history needed to explain completed work is preserved.

## Audit
Assignment/reassignment, lifecycle transitions, policy binding, evidence acceptance/rework, instruction revision, conflict resolution.

## Offline
Assigned field users cache full authorized Job Bundle.
Start/Block/Resume/Evidence/Submit may queue offline.
Assign/Accept/Cancel remain online-authoritative by default.

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

## Canonical implementation bridge

First-slice implementation contracts now exist in:
- `docs/13-delivery/first-slice-contract-pack/11_PROJECT_SITE_WORK_CONTRACT.md`
- `02_API_AND_READ_MODELS.md`
- `03_POSTGRES_FLYWAY_JOOQ.md`
- `04_ROOM_OFFLINE_SYNC.md`
- `05_AUTHORIZATION_POLICY_TESTS.md`

Reality validation now checks structural coverage and seeds configuration; it no longer blocks by requiring today's mutable operating choices to be hard-coded.
