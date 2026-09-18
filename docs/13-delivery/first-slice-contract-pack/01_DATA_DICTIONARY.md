# 01 — First-Slice Data Dictionary

Status: **CONTRACT CANDIDATE v0.2 / STRUCTURE SEPARATED FROM SEED DATA**

## Rule

Every production field must have:
- canonical owner,
- exact wire/storage type,
- nullability,
- lifecycle mutability,
- classification,
- source of truth,
- audit behavior,
- offline/cache behavior,
- reality evidence.

Do not copy every v0.1 object-spec field automatically into production.

---

# Configuration Core

Canonical schema:
`00_CONFIGURATION_POLICY_SCHEMAS.md`

Configuration aggregates referenced by first-slice domain data:
- WorkTypeDefinition
- AssignmentPolicy
- ReadinessPolicy
- EvidencePolicy
- ReviewPolicy
- FieldTrackingPolicy
- StorageLocationConfiguration
- AssetTypeDefinition
- StockItemCategoryDefinition
- ApprovalPolicy
- Role/Team/Delegation configuration
- typed templates

Common configuration identity:
- id: UUID
- organizationId: UUID
- code: String
- lifecycleState: DRAFT / ACTIVE / SUPERSEDED / RETIRED
- revisionNumber: Int
- effectiveFrom/effectiveTo: Instant?
- supersedesId: UUID?
- version: Long
- audit/activation metadata

Current HILTECH values are seed data; the schemas are production contracts.

---

# Identity / Organization Subset

| Field | Exact type | Null | Owner | Classification | Source of truth | Offline/cache | Evidence | Status |
|---|---|---:|---|---|---|---|---|---|
| UserIdentity.id | UUID | NO | Identity | INTERNAL | HILTECH | minimal | technical model | PROPOSED_FOR_REVIEW |
| UserIdentity.authSubject | String | NO | Identity | HIGHLY_RESTRICTED | Keycloak link | no UI cache | SPIKE-08 | LOCKED_TECHNICAL |
| UserIdentity.status | ACTIVE / LOCKED / REVOKED / PENDING candidate | NO | Identity | INTERNAL | HILTECH | session bootstrap | identity contract | CONTRACT_CANDIDATE |
| Organization | typed object: HILTECH / CLIENT / SUPPLIER / SUBCONTRACTOR / PARTNER / OTHER | NO | Organizations | INTERNAL/RESTRICTED by field | HILTECH | context subset only | organization object spec | CONTRACT_CANDIDATE |
| OrganizationMembership | typed membership with validity/state | NO | Organizations | INTERNAL | HILTECH | assigned context | identity/org contract | CONTRACT_CANDIDATE |
| Team | UUID/code/name/parent/manager/active/version | NO | People/Organizations | INTERNAL | HILTECH | assigned context | configurable operating model | CONTRACT_CANDIDATE |
| TeamMembership | team/employee/roleInTeam/validity | NO | People/Organizations | INTERNAL | HILTECH | assigned context | configurable operating model | CONTRACT_CANDIDATE |

---

# Project

Required decisions before freeze:

| Field | Exact type | Null | Owner | Classification | Source | Local cache | Evidence | Status |
|---|---|---:|---|---|---|---|---|---|
| id | UUID | NO | Projects | INTERNAL | HILTECH | YES | technical | CONTRACT_CANDIDATE |
| projectCode | String; exact max/pattern final-freeze item | NO | Projects | INTERNAL | configured/generated/manual policy | YES | project contract | CONTRACT_CANDIDATE |
| name | String | NO | Projects | INTERNAL | commercial/project creation | YES | project contract | CONTRACT_CANDIDATE |
| clientOrganizationId | UUID | NO | Projects | RESTRICTED | commercial/project handoff | YES-safe subset | organization/project contract | CONTRACT_CANDIDATE |
| lifecycleState | ProjectState enum candidate | NO | Projects | INTERNAL | Projects | YES | project contract | CONTRACT_CANDIDATE |
| projectManagerId | UUID | YES until policy requires active PM | Projects | INTERNAL | assignment/config | YES | project contract | CONTRACT_CANDIDATE |
| planned dates | LocalDate | YES | Projects | INTERNAL | project planning | YES | project contract | CONTRACT_CANDIDATE |
| version | Long | NO | Projects | INTERNAL | server | YES | SPIKE-15 pattern | LOCKED_TECHNICAL |

Real project data is used to validate terminology/coverage and seed initial values; it does not define the production schema by itself.

---

# Site / Area

First-slice structural contract:
- Site is durable physical/client location independent of a single Project.
- Site.id: UUID
- Site.clientOrganizationId: UUID
- Site.siteCode: String unique within client organization candidate
- Site.name: String
- address/location/timezone fields are optional and permission-scoped
- Site.status: ACTIVE / INACTIVE candidate
- ProjectSite joins Project ↔ Site and owns project-specific lifecycle/access/contacts/notes
- WorkOrder references Project + Site and ProjectSite where exact delivery context is needed
- Area/Room/Zone belongs to Site, recursive/acyclic/type-code driven
- version: Long on mutable aggregates

A real project/site validates terminology/coverage and supplies seed data; repeat projects/support/maintenance reuse the same Site identity.

---

# WorkOrder

Technically fixed semantics:
- authoritative server version,
- offline-capable Start/Block/Resume/Submit,
- online authoritative Assign/Accept/Cancel,
- stale version cannot overwrite server truth,
- required evidence blocks acceptance/submission according policy.

Exact production fields to freeze:

| Field group | Decision needed | Evidence | Status |
|---|---|---|---|
| identity/code | UUID + configurable human code/string | field/project seed | PROPOSED_FOR_REVIEW |
| project/site/area refs | UUID refs; required subset by work context/template | real job validates coverage | PROPOSED_FOR_REVIEW |
| workTypeDefinitionRef | config id + revision | configuration contract | CONTRACT_CANDIDATE |
| assignmentPolicyRef | config id + revision | configuration contract | CONTRACT_CANDIDATE |
| readinessPolicyRef | config id + revision | configuration contract | CONTRACT_CANDIDATE |
| evidencePolicyRef | config id + revision | configuration contract | CONTRACT_CANDIDATE |
| reviewPolicyRef | config id + revision | configuration contract | CONTRACT_CANDIDATE |
| trackingPolicyRef | config id + revision, optional | configuration contract | CONTRACT_CANDIDATE |
| title/instruction | typed/string fields + optional template revision | real job validates terminology | PROPOSED_FOR_REVIEW |
| lifecycleState | exact production enum from transition contract | transition review | PROPOSED_FOR_REVIEW |
| readinessState | derived state from bound readiness policy | configuration contract | CONTRACT_CANDIDATE |
| assignee target | typed USER/CREW/TEAM/SUBCONTRACTOR assignment | assignment policy | CONTRACT_CANDIDATE |
| reviewer relation | derived from bound ReviewPolicy | configuration contract | CONTRACT_CANDIDATE |
| planned/actual timestamps | exact meanings | field workflow | PROPOSED_FOR_REVIEW |
| priorityCode | configurable/master code | PM seed/config | CONTRACT_CANDIDATE |
| drawing/material/asset requirements | typed requirement refs/templates | work-type config | CONTRACT_CANDIDATE |
| instructionRevision | Long/typed revision ref | field revision flow | PROPOSED_FOR_REVIEW |
| policyBindingSnapshot | immutable config revision references | historical reproducibility | CONTRACT_CANDIDATE |
| version | Long optimistic lock | SPIKE-15 | LOCKED_TECHNICAL |

---

# Asset / Warehouse

Technically fixed:
- asset identity separate from tag,
- custody/location projection from append-only movement,
- one active custody,
- optimistic version/idempotency on checkout,
- no destructive movement history,
- calibration can block availability by policy.

Configuration-driven:
- AssetTypeDefinition,
- StockItemCategoryDefinition,
- units of measure,
- calibration requirement,
- storage locations,
- high-value/restricted handling policy.

Domain invariants remain fixed:
- unique physical identity for serialized assets,
- append-only movement/custody history,
- one active authoritative custody,
- no silent negative stock.

A walkthrough validates coverage and supplies initial seed/master data; it does not define permanent code enums.

---

# Evidence

Technically fixed:
- metadata authoritative in DB,
- binary in S3-compatible storage,
- SHA-256 identity,
- signed direct upload,
- server finalization verifies stored bytes,
- restricted evidence has no permanent public URL.

Configuration-driven:
- evidence type/category master data,
- mandatory evidence by WorkType through EvidencePolicy,
- allowed content types and count constraints,
- client visibility mode,
- reviewer requirement.

Provider/legal freeze items:
- maximum file-size policy,
- retention/legal hold,
- exact client sharing/download policy by classification.

---

# Global Technical Columns

Candidate baseline per authoritative mutable aggregate:

- `id`
- `version`
- `created_at`
- `created_by`
- `updated_at`

Do not mechanically add soft-delete or generic status columns to every table.
Deletion/retention must follow domain policy.
