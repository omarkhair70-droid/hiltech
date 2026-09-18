# 00 — Configuration / Policy Schemas

Status: **CONTRACT CANDIDATE v0.2 / CORE SCOPE DECISIONS CLOSED**
Date: 2026-09-18

## Purpose

Freeze the typed/versioned configuration objects that let HILTECH change legitimate operating policy without source-code changes.

This contract is deliberately scale-neutral:
- today's headcount is seed data,
- today's approvers are seed data,
- today's WorkTypes are seed data,
- today's storage locations are seed data.

The schema must continue working as HILTECH adds people, teams, projects, branches, warehouses/site stores, clients, partners, subcontractors and operating rules.

---

# 1. Global Configuration Contract

## IDs / Time / Version

Accepted baseline:
- domain/config IDs use UUID physical representation.
- server-owned business objects receive server-generated UUIDs.
- client-created retry operation IDs are client-generated UUIDs.
- clients never infer time/order/authorization from UUID value.
- optimistic aggregate version: Long, starting at 1.
- timestamps: UTC Instant.
- effective business dates/times: Instant unless a domain-specific LocalDate is explicitly required.
- human/business code: stable String, unique within its declared scope.
- mutable configuration is never identified only by display name.

## Configuration scope

First-slice scope model:
- SYSTEM — platform-managed built-in configuration/schema definitions.
- ORGANIZATION — operating configuration owned by one Organization.

Fields:
- scopeType: SYSTEM / ORGANIZATION
- scopeOrganizationId: UUID? required when ORGANIZATION

Rules:
- normal HILTECH operating policies are ORGANIZATION scoped.
- external Client/Supplier organizations do not inherit HILTECH operating config unless an explicit product feature later permits it.
- SYSTEM configuration cannot be casually edited by organization admins.
- future additional scope types require contract/ADR extension; do not overload scope IDs.

## Configuration lifecycle

State enum:

- DRAFT
- ACTIVE
- SUPERSEDED
- RETIRED

Rules:
1. DRAFT is editable by authorized configuration administrators.
2. ACTIVE is immutable in-place for material policy semantics.
3. A material change creates a new version/revision and supersedes the prior ACTIVE definition.
4. RETIRED cannot be selected for new work.
5. Historical WorkOrders/Approvals keep the configuration revision needed to explain their behavior.
6. Deleting historical ACTIVE/SUPERSEDED policy revisions is not a normal operation.
7. Activation/supersession/retirement is audited.

## Common configuration metadata

All first-slice configuration aggregates use equivalent metadata:

- id: UUID
- scopeType: SYSTEM / ORGANIZATION
- scopeOrganizationId: UUID?
- code: String
- name: String
- description: String? 
- lifecycleState: ConfigLifecycleState
- revisionNumber: Int
- effectiveFrom: Instant?
- effectiveTo: Instant?
- createdAt: Instant
- createdByUserId: UUID
- activatedAt: Instant?
- activatedByUserId: UUID?
- supersedesId: UUID?
- changeReason: String?
- version: Long

Invariants:
- `organizationId + code + revisionNumber` unique.
- at most one ACTIVE/effective revision for the same config code at one instant.
- `effectiveTo > effectiveFrom` when both exist.
- supersession graph is acyclic.
- configuration change does not retroactively mutate already-completed business history.

---

# 2. WorkTypeDefinition

## Purpose

Defines a repeatable class of field work without making each work type a compile-time enum.

Examples of seed data can later include:
- rack installation,
- cable pulling,
- fiber testing,
- maintenance visit.

Examples are not hard-coded product limits.

## Fields

- id: UUID
- organizationId: UUID
- code: String
- name: String
- description: String?
- lifecycleState: ConfigLifecycleState
- revisionNumber: Int
- assignmentPolicyId: UUID
- readinessPolicyId: UUID
- evidencePolicyId: UUID
- reviewPolicyId: UUID
- trackingPolicyId: UUID?
- assetRequirementTemplateId: UUID?
- materialRequirementTemplateId: UUID?
- checklistTemplateId: UUID?
- instructionTemplateId: UUID?
- completionPolicyId: UUID?
- defaultPriorityCode: String?
- effectiveFrom: Instant?
- effectiveTo: Instant?
- supersedesId: UUID?
- createdAt/by
- activatedAt/by
- changeReason
- version: Long

## Invariants

- referenced policies must belong to the same HILTECH organization/scope unless explicitly global/system-managed.
- only ACTIVE referenced policy revisions can be selected for newly created WorkOrders.
- a WorkOrder binds the WorkType revision and policy revisions used when the WorkOrder becomes planned/assigned, according to the exact Work contract.
- changing WorkTypeDefinition does not rewrite existing WorkOrders.

---

# 3. AssignmentPolicy

## Purpose

Defines who/what may execute a WorkOrder without hard-coding current crew shape.

## AssignmentTargetType

- USER
- CREW
- TEAM
- SUBCONTRACTOR_ORGANIZATION

The policy stores allowed target types as a non-empty set.

## Fields

- common configuration metadata
- allowedTargetTypes: Set<AssignmentTargetType>
- minAssignees: Int?
- maxAssignees: Int?
- leadRequired: Boolean
- leadRelationshipType: String?
- requiredRoleCodes: Set<String>
- requiredSkillOrCertificationCodes: Set<String>
- allowCrossTeamAssignment: Boolean
- allowExternalSubcontractor: Boolean
- reassignmentRequiresReason: Boolean
- reassignmentApprovalPolicyId: UUID?
- assignmentMustBeOnline: Boolean

## Invariants

- min/max values are valid when present.
- policy cannot authorize an assignee who fails server-side authorization/eligibility.
- assignment target shape is policy; authorization is still server-enforced.
- current crew count is not schema.

---

# 4. ReadinessPolicy

## Purpose

Defines what must be true before work can start.

## ReadinessRequirementType

Built-in typed families:
- ASSIGNEE
- MATERIAL
- ASSET_TOOL
- DRAWING_REVISION
- SITE_ACCESS
- DEPENDENCY
- CLIENT_PERMISSION
- SAFETY_PPE

Extension mechanism:
- CUSTOM_TYPED_REQUIREMENT is **not enabled in the first production slice**.
- adding a new requirement semantic requires an explicit typed code/schema extension.
- no arbitrary script execution.

## ReadinessRequirement

- id: UUID
- requirementType
- key: String
- label: String
- mandatory: Boolean
- satisfactionSource: DERIVED / MANUAL_CONFIRMATION / EXTERNAL_INTEGRATION / HYBRID
- manualConfirmationAllowed: Boolean
- waiverAllowed: Boolean
- waiverAuthorityPolicyId: UUID?
- waiverReasonRequired: Boolean
- blocksStartWork: Boolean
- sortOrder: Int

## ReadinessPolicy fields

- common configuration metadata
- requirements: ordered non-empty List<ReadinessRequirement>
- allMandatoryMustPass: Boolean = true
- reEvaluateOnRelevantChange: Boolean = true

## Invariants

- a mandatory blocking requirement cannot be silently ignored.
- waiver is explicit, authorized, reasoned when policy requires, and audited.
- derived readiness is recomputed from authoritative truth.
- policy may alter requirements; it may not bypass hard domain invariants.

---

# 5. EvidencePolicy

## Purpose

Defines proof required by WorkType without making photo/test/signature rules global.

## EvidenceStage

- BEFORE_START
- BEFORE_SUBMIT
- BEFORE_ACCEPT

## EvidenceCaptureSource

- CAMERA
- FILE_UPLOAD
- FORM
- MEASUREMENT
- TEST_IMPORT
- SIGNATURE
- SCAN
- OTHER_TYPED

## EvidenceRequirement

- id: UUID
- key: String
- label: String
- stage: EvidenceStage
- evidenceTypeCode: String
- minCount: Int
- maxCount: Int?
- allowedContentTypes: Set<String>
- captureSources: Set<EvidenceCaptureSource>
- offlineCaptureAllowed: Boolean
- measurementSchemaRef: UUID?
- reviewerRelationshipCode: String?
- classificationCode: String
- retentionPolicyCode: String
- clientVisibilityMode: INTERNAL_ONLY / CLIENT_VISIBLE_IF_AUTHORIZED / CLIENT_REQUIRED
- sortOrder: Int

## EvidencePolicy fields

- common configuration metadata
- requirements: List<EvidenceRequirement>
- requireAllMandatoryBeforeSubmit: Boolean
- requireAllAcceptanceEvidenceBeforeAccept: Boolean

## Invariants

- minCount >= 0.
- maxCount >= minCount when present.
- binary evidence cannot become READY until authoritative finalize/checksum succeeds.
- changing EvidencePolicy does not invalidate previously accepted historical work unless a separate explicit compliance rule says so.
- client visibility never bypasses object/organization authorization.

---

# 6. ReviewPolicy

## Purpose

Defines who reviews/accepts submitted work.

## ReviewMode

- ANY_ONE
- ALL
- SEQUENTIAL
- QUORUM

## ReviewerSelectorType

- RELATIONSHIP
- ROLE
- TEAM
- SPECIFIC_USER
- SUBJECT_MANAGER_CHAIN
- CLIENT_RELATIONSHIP

Specific user is allowed as configuration only; never compile-time code.

## ReviewStep

- id: UUID
- sequence: Int
- selectorType
- selectorValue: String/UUID
- quorumCount: Int?
- reauthRequired: Boolean
- reasonRequiredOnRework: Boolean
- reasonRequiredOnReject: Boolean
- evidenceVisibilityMode: String
- escalationPolicyId: UUID?

## ReviewPolicy fields

- common configuration metadata
- mode: ReviewMode
- steps: ordered non-empty List<ReviewStep>
- bindExactSubmittedVersion: Boolean = true
- clientAcceptanceSeparate: Boolean
- allowDelegation: Boolean

## Invariants

- acceptance binds exact submitted WorkOrder version.
- a materially changed WorkOrder must be reviewed again.
- reviewer eligibility is evaluated server-side at decision time.
- policy cannot turn a denied actor into an authorized actor without a valid relationship/permission model.

---

# 7. FieldTrackingPolicy

## Purpose

Controls site navigation/arrival/presence behavior without hard-coding permanent employee tracking.

## TrackingMode

- NAVIGATION_ONLY
- ARRIVAL_PROOF
- ACTIVE_SITE_PRESENCE

## TrackingTrigger

- MANUAL
- SITE_VISIT_START
- START_WORK

## TrackingStop

- MANUAL_END
- SITE_VISIT_END
- SUBMIT_WORK
- TIMEOUT

## Fields

- common configuration metadata
- enabled: Boolean
- mode: TrackingMode
- trigger: TrackingTrigger
- stopCondition: TrackingStop
- sampleIntervalSeconds: Int?
- requiredAccuracyMeters: Int?
- offlineBufferingAllowed: Boolean
- viewerRelationshipCodes: Set<String>
- retentionDays: Int?
- requireUserNotice: Boolean
- requireExplicitUserActionToStart: Boolean
- maxContinuousDurationMinutes: Int?

## Invariants

- disabled policy collects no new tracking samples.
- tracking is bound to explicit operational context.
- retention and visibility are enforced server-side.
- location data classification is RESTRICTED/HIGHLY_RESTRICTED according to exact usage.
- tracking configuration cannot silently become permanent all-day surveillance.

---

# 8. StorageLocationConfiguration

## Purpose

Represent HQ warehouse, future warehouses, project stores and temporary site storage through one scalable model.

## StorageKind

- MAIN_WAREHOUSE
- WAREHOUSE
- PROJECT_STORAGE
- SITE_STORAGE
- OTHER_TYPED

Do not encode today's number of warehouses in schema.

## Fields

- id: UUID
- organizationId: UUID
- code: String
- name: String
- kind: StorageKind
- parentStorageLocationId: UUID?
- warehouseId: UUID?
- projectId: UUID?
- siteId: UUID?
- temporary: Boolean
- activeFrom: Instant?
- activeUntil: Instant?
- responsibleRelationshipCode: String?
- restrictedAccess: Boolean
- addressOrLocationRef: UUID/String?
- notes: String?
- version: Long
- createdAt/by
- retiredAt/by

## Invariants

- parent hierarchy is acyclic.
- Project/Site storage must reference valid context when its kind requires it.
- retiring a location does not erase movement history.
- inventory/custody cannot point to a nonexistent/retired location for new movement.

Note:
Physical storage-location master data is business configuration/master data, but stock/custody movements remain authoritative domain records.

---

# 9. AssetTypeDefinition

## Purpose

Configure asset behavior by class without hard-coded tool lists.

## Fields

- common configuration metadata
- code
- name
- serializedRequired: Boolean
- calibrationRequired: Boolean
- calibrationPolicyRef: UUID?
- maintenancePolicyRef: UUID?
- highValueOrRestricted: Boolean
- restrictedHandlingPolicyRef: UUID?
- allowedTagTypes: Set<String>
- defaultOwnershipTypeCode: String?
- expectedAccessoriesTemplateId: UUID?

## Invariants

- one physical serialized asset retains unique authoritative identity.
- calibration-required type cannot make expired calibration magically available.
- category changes do not erase asset history.

---

# 10. StockItemCategoryDefinition

## Fields

- common configuration metadata
- code
- name
- defaultUnitOfMeasureCode: String
- allowedUnitOfMeasureCodes: Set<String>
- lotTrackingAllowed: Boolean
- serialTrackingAllowed: Boolean
- reorderPolicyRef: UUID?
- restrictedHandlingPolicyRef: UUID?

Units themselves are master data, not source-code enums where avoidable.

Hard invariant:
authoritative stock cannot silently become negative.

---

# 11. Organization / Role / Team / Delegation Configuration

Existing canonical objects:
- Organization
- OrganizationMembership
- RoleDefinition
- Team
- TeamMembership
- Delegation

Canonical source:
`docs/06-data/object-specs/IDENTITY_ORGANIZATION_OBJECTS.md`.

Freeze rule:
- current staff names/counts/teams are seed data.
- role/team structure can grow without schema rewrite.
- title/role label alone is never authorization truth.
- OpenFGA/application policy derives effective authority from explicit relationships/policy.
- delegation is scoped, time-bounded, auditable, and cannot expand authority beyond delegator.

---

# 12. ApprovalPolicy

Canonical model:
- `docs/05-workflows/APPROVAL_POLICY_MODEL.md`
- `docs/05-workflows/APPROVAL_SYSTEM.md`

Integration contract:
- subject type + subject version
- deterministic conditions
- versioned policy
- typed approver selectors
- sequential/parallel/quorum semantics
- re-auth/reason obligations
- delegation/escalation
- audit

Do not hard-code Mohamed/Ahmed/current threshold values.

Current policy values are seed/configuration data.

---

# 13. Notification / Escalation Policy

## Fields

- common configuration metadata
- eventOrActionType: String
- recipientSelectors: List<TypedSelector>
- deliveryClasses: Set<IN_APP/PUSH/EMAIL/SMS/DESKTOP>
- firstReminderAfterSeconds: Long?
- repeatEverySeconds: Long?
- maxReminderCount: Int?
- escalationPolicyRef: UUID?
- aggregateIntoDigest: Boolean
- suppressActorSelfNotification: Boolean
- quietHoursPolicyRef: UUID?

Invariant:
escalation never means automatic approval.

---

# 14. Template Definitions

Typed templates may support:
- ChecklistTemplate
- InstructionTemplate
- AssetRequirementTemplate
- MaterialRequirementTemplate
- CompletionPolicy

Templates are configuration, but their typed schema is frozen before production.

No arbitrary executable code/scripts inside templates.

---

## Work policy binding lifecycle

- DRAFT/PLANNED WorkOrder may be explicitly rebound to newer policy revisions through a controlled RebindWorkPolicies command.
- every rebind writes binding history.
- once WorkOrder becomes ASSIGNED, the bound WorkType/Assignment/Readiness/Evidence/Review revisions are immutable for that execution cycle unless a dedicated reopen/replan workflow explicitly creates a new binding version.
- configuration activation never silently rewrites an assigned/in-progress/submitted/accepted WorkOrder.

# 15. Policy Binding To WorkOrder

The production WorkOrder contract must store enough immutable references to explain the rules applied to that work.

Candidate binding snapshot:

- workTypeDefinitionId + revision
- assignmentPolicyId + revision
- readinessPolicyId + revision
- evidencePolicyId + revision
- reviewPolicyId + revision
- trackingPolicyId + revision? 
- instructionTemplateId + revision?
- checklistTemplateId + revision?

Exact storage shape may be normalized references or a compact policy-binding record, but historical reproducibility is mandatory.

Rule:
A later config activation affects future/re-evaluated work according to explicit policy; it does not silently rewrite the meaning of already accepted historical work.

---

# 16. Configuration Commands

First production API must support explicit configuration commands rather than direct table CRUD.

Required command families:

- CreateDraftConfiguration
- UpdateDraftConfiguration
- ValidateDraftConfiguration
- ActivateConfigurationRevision
- SupersedeConfigurationRevision
- RetireConfiguration
- CloneConfigurationRevision
- AssignConfigurationToScope / bind where applicable

Critical activation/supersession may require approval/re-auth according to policy.

Every command:
- uses operationId/idempotency where retry-sensitive,
- uses baseVersion,
- is server-authorized,
- is audited.

---

# 17. Configuration Queries / Read Models

Required:
- ActiveConfigurationByCode
- ConfigurationRevisionHistory
- ConfigurationDependencyGraph
- ConfigurationUsageImpact
- DraftValidationResult
- ConfigurationCenterList
- WorkTypeConfigurationDetail

Before activation UI should be able to answer:
- what will change,
- what depends on this policy,
- whether current WorkTypes reference it,
- whether activation is valid.

---

# 18. Configuration Validation

Server validation must reject at minimum:

- missing referenced config,
- cross-organization invalid reference,
- cyclic supersession,
- invalid effective range,
- invalid min/max values,
- review quorum larger than eligible step set,
- readiness waiver without authority policy when required,
- EvidencePolicy impossible count/content constraints,
- tracking policy missing stop/retention safety where required,
- storage hierarchy cycle,
- duplicate active code revision,
- activation of draft with unresolved dependencies.

No generic "save any JSON" policy engine.

---

# 19. Configuration Authorization

Required action families:

- config.view
- config.create_draft
- config.edit_draft
- config.validate
- config.activate
- config.supersede
- config.retire
- config.view_history
- config.export
- config.seed/import

Rules:
- deny by default.
- activation is more privileged than draft editing.
- sensitive policy families may require separate authority.
- configuration access itself is audited.
- configuration changes cannot bypass hard domain invariants.

---

# 20. Required Contract Tests

Must pass before FIRST_SLICE_FREEZE:

- create WorkType draft.
- invalid draft rejected.
- activate WorkType revision.
- new WorkOrder binds active revision.
- activate new EvidencePolicy revision without code deploy.
- old accepted WorkOrder retains old evidence policy history.
- new WorkOrder uses new evidence policy.
- unauthorized config edit denied.
- unauthorized activation denied.
- expired delegation denied.
- invalid storage hierarchy denied.
- add SiteStorage without deployment.
- add WorkType without deployment.
- change ReviewPolicy without deployment.
- disable tracking policy and prove no new tracking session starts.
- stale config update returns VERSION_CONFLICT.
- duplicate activation operation is idempotent.
- config audit reconstructs who/what/when/old/new version.
- offline WorkOrder replay re-evaluates current authority while preserving bound historical policy context.
- scale test creates many teams/work types/storage locations without schema change.

---

# 21. What Is Still Open

This candidate is structurally strong enough to drive DB/API design.

Still to settle before final freeze:
- exact retention/legal rules for active location tracking before enabling that mode.
- exact Configuration Center visual design.

Closed:
- code/name/description bounds inherit cross-cutting baseline unless stricter.
- JSON v1 draft-only import/export/seed contract.
- activation sensitivity model.

Closed:
- SYSTEM/ORGANIZATION scope mechanism.
- WorkOrder policy-binding lifecycle.
- no arbitrary/custom requirement extension in first slice.
- activation can invoke ApprovalPolicy/re-auth by family without hard-coding today's approver.

These are **contract closure items**, not reasons to rediscover HILTECH's whole operating model.

---

# Freeze Outcome

Current: **CONTRACT CANDIDATE v0.1**

Next:
1. reflect these objects in Data Dictionary,
2. define PostgreSQL/Flyway table ownership,
3. define API DTO/command/read-model shapes,
4. define OpenFGA/application authorization for Configuration Center,
5. define representative Admin UI,
6. run contract consistency review,
7. mark configuration contract FROZEN when remaining closure items are resolved.


---

# 22. Import / Export / Seed Contract

Runtime/admin interchange baseline:
- UTF-8 JSON.
- top-level schemaVersion.
- explicit configuration family.
- scopeType/scopeOrganizationId.
- code/name.
- revision payload.
- dependency references by stable config code/id.
- no secrets/tokens/credentials.

Import behavior:
1. parse + schema validate.
2. resolve dependencies.
3. create DRAFT revisions only.
4. never auto-activate.
5. show diff/validation/usage impact.
6. authorized user explicitly activates through normal command/approval/re-auth path.

Export:
- can include ACTIVE + historical metadata according permission.
- sensitive integration secrets are references/redacted, never exported plaintext.

Git/dev fixtures may use YAML for human readability, but production configuration import/export contract is JSON v1.

---

# 23. Activation Control

Every configuration family declares activation sensitivity:

- STANDARD — config_activator permission sufficient.
- REAUTH — config_activator + recent authentication.
- APPROVAL — config_activator + configured ApprovalPolicy decision.
- REAUTH_AND_APPROVAL — both.

The engine supports all four.
Which current HILTECH family uses which class is seed/policy configuration.

Critical security/authorization/integration configuration may not use weaker activation than its system-defined minimum.
