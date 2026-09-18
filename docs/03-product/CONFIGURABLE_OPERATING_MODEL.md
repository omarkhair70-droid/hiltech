# HILTECH Configurable Operating Model

Status: **PRODUCT / ARCHITECTURE PRINCIPLE — PRE-FREEZE**
Date: 2026-09-18

## Principle

HILTECH OS must not hard-code today's company answers when those answers are legitimate operating policy.

Production freeze should lock:

- the objects,
- policy/configuration schemas,
- invariants,
- audit/version rules,
- permission boundaries,
- offline/error semantics,
- tests,

while HILTECH administrators configure the current operating choices inside the product.

The system should adapt to the company without requiring a code change for normal policy changes.

---

# 1. Configuration vs Code

## Configuration / admin-controlled

Examples:
- who can approve a class of action,
- approval thresholds,
- which reviewer accepts a WorkType,
- technician vs crew assignment mode,
- required evidence by WorkType,
- readiness checks by WorkType,
- whether a site visit requires location/presence tracking,
- tracking start/stop/frequency/visibility policy,
- notification/escalation cadence,
- warehouse/storage locations,
- temporary Project/Site storage,
- tool/asset categories,
- stock units/categories,
- calibration-required asset classes,
- project/work priority values where safe,
- role/team membership,
- temporary delegation,
- work templates/checklists,
- client-visible evidence/update policy,
- default handover checklist,
- project/site templates.

These are versioned business configuration, not compile-time logic.

## Code / non-configurable invariant

Examples:
- server enforces authorization.
- deny-by-default remains enforced.
- critical approvals bind an exact subject version.
- stale offline command cannot overwrite newer authoritative state.
- idempotency cannot be disabled for retry-sensitive critical commands.
- audit/history for critical actions cannot be silently removed.
- one physical Asset cannot have two authoritative active custodies.
- stock cannot silently go negative.
- Evidence cannot become READY until server-side finalize/checksum validation succeeds.
- restricted binary objects do not get permanent public URLs.
- payment UNKNOWN outcome cannot be blindly retried as a new payment.
- authorization is re-evaluated when offline commands replay.
- configuration changes themselves are audited/versioned.

These remain domain/platform laws.

---

# 2. First-Slice Configuration Objects

The first production slice should support typed configuration objects rather than generic free-form scripts.

## WorkTypeDefinition

Defines a repeatable type of field work.

Candidate fields:
- id / code / name
- active
- assignmentMode: USER / CREW / TEAM / SUBCONTRACTOR / allowed combinations
- readinessPolicyRef
- evidencePolicyRef
- reviewPolicyRef
- trackingPolicyRef — optional
- assetRequirementTemplateRef — optional
- materialRequirementTemplateRef — optional
- checklistTemplateRef — optional
- instructionTemplateRef — optional
- completionPolicyRef
- effectiveFrom/effectiveTo
- version

Examples can later include:
- data-center rack installation,
- cable pulling,
- fiber testing,
- maintenance visit.

Examples are seed data, not hard-coded enums.

---

## ReadinessPolicy

Configures which dimensions block starting work.

Candidate dimensions:
- assignee
- material
- asset/tool
- drawing/revision
- site access
- dependency
- client permission
- safety/PPE
- custom typed requirement

Policy specifies:
- mandatory/optional,
- how satisfaction is derived,
- whether manual confirmation is allowed,
- who can waive/override,
- whether override needs approval/reason.

---

## EvidencePolicy

Configures evidence required before submission/acceptance.

Candidate evidence requirement:
- type
- minimum/maximum count
- required file/content types
- measurement/test schema ref
- capture source
- offline allowed
- reviewer role/relationship
- required before Submit vs required before Accept
- retention/classification
- client visibility
- version

Photos/OTDR/Fluke/signature/checklist are configured requirements, not globally mandatory rules.

---

## ReviewPolicy

Configures:
- who may review,
- engineer vs supervisor vs PM relationship,
- sequential/parallel review where needed,
- rework reason/evidence requirements,
- whether client acceptance is a later separate stage,
- re-auth/approval obligations.

Do not hard-code “Supervisor always accepts”.

---

## FieldTrackingPolicy

Configures scoped operational location/presence behavior.

Candidate:
- enabled
- trigger: explicit site visit / StartWork / manual
- stop: EndVisit / Submit / timeout
- mode: navigation-only / arrival proof / active presence
- sample frequency
- precision level
- offline buffering
- visible to which relationships
- retention
- employee notice/consent policy where required
- version

Tracking is never assumed to be permanent all-day surveillance.

---

## StorageLocation Model

Supports:
- MAIN_WAREHOUSE,
- PROJECT_STORAGE,
- SITE_STORAGE,
- VEHICLE/CREW_CUSTODY only if validated,
- nested zone/shelf/bin where useful.

Temporary Site/Project storage can be created/configured without adding a full warehouse module instance for every remote project.

---

## Assignment / Crew Configuration

Supports:
- named user,
- crew/team of arbitrary supported size,
- engineer + technicians,
- technician pair,
- department/team-based assignment where useful,
- subcontractor/external organization assignment where applicable.

Current HILTECH staff shape is seed/operating data, not a schema or scale constraint.

The same model must support future hiring, new teams, more supervisors/engineers, additional projects and organizational growth without code changes.

---

# 3. Configuration Lifecycle

Every important policy/config object must support:

- DRAFT
- ACTIVE
- SUPERSEDED / RETIRED
- effective dates where useful
- version
- created/changed by
- reason where critical
- audit history

Existing WorkOrders/Approvals must bind the policy version they were evaluated against when correctness requires historical reproducibility.

Changing tomorrow's EvidencePolicy must not silently rewrite yesterday's accepted WorkOrder history.

---

# 4. Configuration Center

HILTECH OS needs an authorized administration surface.

Candidate areas:
- People / Teams / Roles
- Work Types
- Readiness Policies
- Evidence Policies
- Review / Acceptance Policies
- Approval Policies
- Warehouses / Site Storage
- Asset / Stock Categories
- Tracking Policies
- Notifications / Escalations
- Templates / Checklists
- Delegations
- Integration settings
- feature flags / operational kill switches where appropriate

Configuration access is itself permissioned and audited.

---

# 5. What Reality Validation Is For Now

Reality validation is no longer used to hard-code today's answer into production.

It is used to:

1. prove the configuration model can express real HILTECH work,
2. identify missing policy dimensions,
3. provide sensible initial seed configuration,
4. choose pilot WorkTypes/assets/storage locations,
5. find non-configurable legal/safety/integration constraints,
6. validate representative UI and operational terminology.

A real project/job is a **configuration/model validation fixture**, not a requirement that all future projects follow exactly the same rules.

---

# 6. First Production Freeze Consequence

Before repository bootstrap we must freeze:

- typed configuration schemas,
- policy evaluation semantics,
- policy version binding,
- audit/effective-date behavior,
- invariant boundaries,
- admin authorization,
- representative configuration UI,
- contract tests proving config changes alter behavior safely.

We do **not** need to freeze:

- Mohamed as a hard-coded approver,
- exact current technician count,
- one permanent list of WorkTypes,
- one permanent evidence checklist,
- one permanent reviewer role,
- one permanent warehouse/site list,
- one permanent tracking cadence.

Those become initial tenant/company configuration.

---

# 7. Product Goal

Production should be a stable HILTECH operating engine whose business behavior can evolve through controlled configuration without routine code changes.

That is the intended meaning of:

**one HILTECH OS, configurable to HILTECH reality, with hard invariants where truth/security/accountability require them.**
