# 00 — Configuration / Policy Schemas

Status: **PRE-FREEZE TEMPLATE**

## Goal

Freeze the typed/versioned configuration objects that let HILTECH change legitimate operating policy without code changes.

This file comes before the exact Data Dictionary/API freeze because many domain fields reference configuration rather than hard-coded enums/users.

---

# Core Rule

Freeze:
- configuration schema,
- allowed condition/action types,
- versioning,
- effective dates,
- audit,
- authorization to edit configuration,
- policy evaluation semantics,
- historical binding rules.

Do not freeze:
- today's approver name,
- today's technician count,
- one permanent WorkType list,
- one permanent evidence checklist,
- one permanent storage-location list,
- one permanent tracking cadence.

Those are initial configuration/seed data.

---

# WorkTypeDefinition

Candidate fields:

- id
- code
- name
- description
- active
- assignmentMode
- readinessPolicyId
- evidencePolicyId
- reviewPolicyId
- trackingPolicyId — optional
- materialRequirementTemplateId — optional
- assetRequirementTemplateId — optional
- checklistTemplateId — optional
- instructionTemplateId — optional
- completionPolicyId
- effectiveFrom
- effectiveTo — optional
- version

Rules:
- WorkOrder stores/binds the relevant WorkType/policy versions where historical correctness requires it.
- WorkType changes do not retroactively rewrite completed WorkOrders.

---

# ReadinessPolicy

Configurable requirement entries:

- key/type
- mandatory
- satisfactionSource
- manualConfirmationAllowed
- waiverAllowed
- waiverAuthorityPolicy
- reasonRequired
- effective dates
- version

Typed requirement families may include:
- ASSIGNEE
- MATERIAL
- ASSET_TOOL
- DRAWING_REVISION
- SITE_ACCESS
- DEPENDENCY
- CLIENT_PERMISSION
- SAFETY_PPE
- CUSTOM_TYPED_EXTENSION

No arbitrary production script execution.

---

# EvidencePolicy

Each requirement can define:

- evidenceType
- required stage: BEFORE_START / BEFORE_SUBMIT / BEFORE_ACCEPT
- minCount / maxCount
- allowed content types
- measurement/test schema ref
- offline capture allowed
- capture source
- reviewer requirement
- retention/classification
- client visibility
- version

Evidence types are extensible master/config data where possible.

The protocol invariant remains server-side checksum/finalize before READY.

---

# ReviewPolicy

Configures:

- eligible reviewer relationship(s)
- sequential / parallel / any-of / quorum if required
- engineer/supervisor/PM/client stage distinctions
- reason required for rework/reject
- evidence visibility requirements
- re-auth/MFA obligation
- escalation
- effective dates
- version

ApprovalPolicy may be reused where formal approval semantics apply.

---

# AssignmentPolicy / Crew Model

Configures allowed execution shape:

- named user
- crew/team
- engineer + technicians
- technician pair
- subcontractor
- mixed/other explicitly supported form

Must support small HILTECH crews without requiring fixed department hierarchy.

---

# FieldTrackingPolicy

Configures:

- enabled
- trigger
- stop condition
- mode: NAVIGATION / ARRIVAL_PROOF / ACTIVE_SITE_PRESENCE
- sample interval/frequency
- required accuracy class
- offline buffering
- viewers/relationships
- retention
- employee notice/consent rule where required
- version

Tracking policy is scoped to work/site context and must not imply permanent employee surveillance.

---

# Storage Configuration

Master/configuration objects should support:

- MAIN_WAREHOUSE
- PROJECT_STORAGE
- SITE_STORAGE
- nested storage zones only when useful

Configurable:
- code/name
- project/site association
- temporary/permanent
- active dates
- responsible relationship
- allowed item classes
- access notes
- version

Do not require every remote material location to become a full Warehouse.

---

# Asset / Stock Master Configuration

Configurable master data:

- AssetType
- StockItemCategory
- unit of measure
- serialized flag
- lot tracking flag
- calibrationRequired
- maintenance/warranty defaults
- high-value/restricted flag/policy
- QR/barcode/tag type
- reorder settings
- active/version

Hard invariants stay in code/domain.

---

# Role / Team / Delegation

Initial people/org structure is configuration data.

Freeze schemas for:

- RoleDefinition
- Team
- TeamMembership
- OrganizationMembership
- Delegation
- relationship assignment

OpenFGA model + application policy must consume these safely.

Do not make employee titles directly equal permissions.

---

# ApprovalPolicy

Already modeled in:
- `docs/05-workflows/APPROVAL_POLICY_MODEL.md`
- `docs/05-workflows/APPROVAL_SYSTEM.md`

Freeze integration with first-slice actions, not a hard-coded user.

---

# Notification / Escalation Policy

Configurable:
- event/action class
- recipient relationship
- reminder cadence
- escalation path
- digest vs immediate
- quiet/suppression rules
- effective version

Never make escalation equal auto-approval.

---

# Configuration Versioning Rules

Every important config object should support:

- DRAFT / ACTIVE / SUPERSEDED / RETIRED semantics where relevant
- version
- effectiveFrom/effectiveTo
- created/changed by
- reason for sensitive changes
- audit history

Historical business actions bind the policy/config version necessary to explain/reproduce the decision.

---

# Configuration Authorization

Must freeze who may:
- view configuration,
- draft,
- activate,
- supersede,
- emergency-disable,
- delegate configuration authority.

Critical config changes may require:
- approval,
- re-auth,
- reason,
- effective future date.

---

# Admin / Configuration Center UI Contract

The product needs authorized administration surfaces for:

- Work Types
- Readiness
- Evidence
- Review/Acceptance
- Approval Policies
- Teams/Roles/Delegations
- Warehouses/Site Storage
- Asset/Stock master data
- Tracking
- Notification/Escalation
- Templates/Checklists

Configuration UI is part of production product scope, not a developer-only database edit.

---

# Required Configuration Tests

- activate new policy version
- old WorkOrder remains bound to old policy where required
- new WorkOrder uses active policy
- invalid policy combination rejected
- unauthorized config edit denied
- expired delegation ignored
- policy rollback/supersession audit
- config change while device offline
- offline replay re-evaluates current authority safely
- disabling tracking stops future collection according policy
- adding SiteStorage requires no code deployment
- adding WorkType requires no code deployment
- changing EvidencePolicy requires no code deployment

---

# Freeze Outcome

This file is ready when normal HILTECH operating-policy changes can be expressed through typed audited configuration without:
- source-code edits,
- DB ad-hoc edits,
- arbitrary scripting,
- weakening non-configurable invariants.
