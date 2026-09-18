# 02 — First-Slice API / Command / Read-Model Contracts

Status: **CONTRACT CANDIDATE v0.3 / FIRST-SLICE HTTP SEMANTICS CLOSED**

## Accepted Cross-Cutting Wire Rules

Locked technical:
- Ktor Client 3.5.2.
- Android OkHttp engine.
- JVM Desktop CIO engine.
- bearer OIDC access token.
- `X-Correlation-Id`.
- W3C `traceparent`.
- `Idempotency-Key` for retry-sensitive commands.
- `baseVersion` in concurrency-sensitive command body.
- JSON through shared typed DTOs.
- explicit commands, not unrestricted CRUD.
- binary files use reservation/direct upload/finalization.

Major API prefix `/v1` is accepted as current baseline.
Exact resource grammar is frozen here before bootstrap.

---

# Configuration API

Normal operating-policy changes use explicit configuration commands, not developer/database edits.

Command families:
- CreateDraftConfiguration
- UpdateDraftConfiguration
- ValidateDraftConfiguration
- ActivateConfigurationRevision
- SupersedeConfigurationRevision
- RetireConfiguration
- CloneConfigurationRevision

Required query/read models:
- ActiveConfigurationByCode
- ConfigurationRevisionHistory
- ConfigurationDependencyGraph
- ConfigurationUsageImpact
- DraftValidationResult
- ConfigurationCenterList
- WorkTypeConfigurationDetail

All config mutations:
- server-authorized,
- audited,
- baseVersion protected,
- idempotent where retry-sensitive.

Exact resource grammar is still to be frozen, but the capability boundary is now part of first production scope.

---

# Command Freeze Table

The earlier placeholder command table has been superseded by the v0.2 route/DTO section below.

Current command families are now structurally defined for:
- WorkOrder lifecycle,
- Asset custody,
- Evidence upload/finalize,
- Stock movement,
- typed Configuration revisions.

Remaining work is exact field/route normalization and error-code closure, not reality discovery.

---

# Query / Read Model Freeze Table

Read-model structural shapes are defined in the v0.2 section below:

- PMProjectCommandCenter
- TechnicianTodayItem
- TechnicianJobBundle
- WarehouseAssetPassport
- WarehouseCheckoutContext
- SupervisorReviewView
- PMWorkProgressItem

Remaining design work affects presentation/navigation and exact optional fields; authorization/redaction and core semantics are already contract-bound.

---

# Standard Error Families

Accepted semantic families:
- UNAUTHENTICATED
- REAUTH_REQUIRED
- PERMISSION_DENIED
- OBJECT_NOT_VISIBLE
- REJECTED_VALIDATION
- REJECTED_STATE
- VERSION_CONFLICT
- DUPLICATE_REPLAY
- FAILED_RETRYABLE
- FAILED_PERMANENT
- INTEGRATION_PENDING
- INTEGRATION_UNKNOWN

Before freeze, every command above must list exactly which errors it may return and the safe `details` schema.

---

# Conflict Contract

SPIKE-15 validated:
- stale `baseVersion` does not overwrite authoritative state,
- local intent/evidence remains,
- dependent local command can become BLOCKED_BY_CONFLICT.

First-slice freeze must define exact `conflictType` and `allowedRecoveryActions` per Work/Asset collision.

Never return a generic conflict with no recovery semantics to field UI.


---

# Route grammar candidate v0.2

Principles:
- /v1 major prefix.
- plural nouns for resources/read models.
- state-changing business actions use explicit command sub-routes.
- no unrestricted generic PATCH for lifecycle-critical objects.
- UUIDs in path where object identity is required.
- command bodies carry typed payload + operationId/baseVersion where relevant.

## Project / Work

- POST /v1/projects/{projectId}/work-orders — CreateWorkOrder
- GET /v1/projects/{projectId}/command-center — PMProjectCommandCenter
- GET /v1/projects/{projectId}/work-orders — scoped list/read model
- GET /v1/work-orders/{workOrderId} — authorized detail
- GET /v1/work-orders/{workOrderId}/job-bundle — TechnicianJobBundle
- POST /v1/work-orders/{workOrderId}/assign
- POST /v1/work-orders/{workOrderId}/start
- POST /v1/work-orders/{workOrderId}/block
- POST /v1/work-orders/{workOrderId}/resume
- POST /v1/work-orders/{workOrderId}/submit-completion
- POST /v1/work-orders/{workOrderId}/accept
- POST /v1/work-orders/{workOrderId}/request-rework
- POST /v1/work-orders/{workOrderId}/close
- POST /v1/work-orders/{workOrderId}/cancel
- GET /v1/field/today — TechnicianToday
- GET /v1/review/work-items — reviewer queue

## Asset / Warehouse

- GET /v1/assets/{assetId}/passport
- GET /v1/assets/by-tag/{opaqueTagCode}
- POST /v1/assets/{assetId}/reserve
- POST /v1/assets/{assetId}/checkout
- POST /v1/assets/{assetId}/return
- POST /v1/assets/{assetId}/transfer
- POST /v1/assets/{assetId}/report-damage
- POST /v1/assets/{assetId}/report-missing
- GET /v1/storage-locations
- GET /v1/warehouse/checkout-context?assetId=...
- POST /v1/stock/{stockItemId}/reserve
- POST /v1/stock/{stockItemId}/issue
- POST /v1/stock/{stockItemId}/consume
- POST /v1/stock/{stockItemId}/return
- POST /v1/stock/transfers
- POST /v1/stocktakes
- POST /v1/stocktakes/{stocktakeId}/counts

## Evidence

- POST /v1/evidence/upload-reservations
- POST /v1/evidence/{evidenceId}/finalize
- GET /v1/evidence/{evidenceId}
- POST /v1/evidence/{evidenceId}/download-reservation

## Configuration

Allowed family path values:
- work-types
- assignment-policies
- readiness-policies
- evidence-policies
- review-policies
- tracking-policies
- asset-types
- stock-categories
- storage-locations
- code-policies
- project-health-policies
- templates

Canonical route grammar:

- GET /v1/config/{family}
- POST /v1/config/{family}/drafts
- PUT /v1/config/{family}/drafts/{revisionId}
- POST /v1/config/{family}/drafts/{revisionId}/validate
- POST /v1/config/{family}/drafts/{revisionId}/activate
- GET /v1/config/{family}/revisions/{revisionId}
- POST /v1/config/{family}/revisions/{revisionId}/retire
- POST /v1/config/{family}/revisions/{revisionId}/clone
- GET /v1/config/{family}/revisions/{revisionId}/usage-impact
- GET /v1/config/{family}/codes/{code}/history

Rules:
- {family} is allow-listed by server, not arbitrary reflection/table routing.
- only DRAFT revision is editable.
- activation/supersession semantics follow configuration contract.
- activating a new revision supersedes the prior active revision for the same scope/code in the same command transaction/projection flow.

## Project / Site administration

These routes support the wider Project domain but are not required to be implemented before the first vertical's core Work execution if pilot data is seeded.

Canonical candidates:

- POST /v1/projects
- GET /v1/projects/{projectId}
- PUT /v1/projects/{projectId}/details
- POST /v1/projects/{projectId}/change-manager
- POST /v1/projects/{projectId}/start-kickoff
- POST /v1/projects/{projectId}/mark-ready
- POST /v1/projects/{projectId}/activate
- POST /v1/projects/{projectId}/put-on-hold
- POST /v1/projects/{projectId}/resume
- POST /v1/projects/{projectId}/start-handover
- POST /v1/projects/{projectId}/close

- POST /v1/sites
- GET /v1/sites/{siteId}
- PUT /v1/sites/{siteId}/details

- POST /v1/projects/{projectId}/sites
- GET /v1/projects/{projectId}/sites
- GET /v1/project-sites/{projectSiteId}
- POST /v1/project-sites/{projectSiteId}/activate
- POST /v1/project-sites/{projectSiteId}/put-on-hold
- POST /v1/project-sites/{projectSiteId}/resume
- POST /v1/project-sites/{projectSiteId}/complete
- POST /v1/project-sites/{projectSiteId}/close

Metadata PUT requests still carry baseVersion and do not bypass lifecycle commands.


---

# Common command fields

Every retry-sensitive command DTO contains:

- operationId: UUID
- baseVersion: Long?
- clientOccurredAt: Instant

Headers:
- Authorization: Bearer ...
- Idempotency-Key: operationId
- X-Correlation-Id
- traceparent

Native authenticated requests include:
- X-Client-Platform: android | windows
- X-Client-Version: semantic/build version
- X-Device-Installation-Id: opaque installation UUID

Rules:
- these headers support compatibility, diagnostics, rollout and offline/device correlation.
- they are not trusted authorization identity.
- server authentication/authorization still derives from token + HILTECH identity/device records.
- web/server-to-server clients use their own explicit client identity/metadata contract.

---

# Work command DTO candidates

## CreateWorkOrderRequest

- operationId
- siteId
- areaId?
- workPackageId?
- workTypeCode or active WorkTypeDefinition id
- humanCode? only when CodePolicy allows manual override
- title
- description?
- plannedStart?
- plannedEnd?
- priorityCode?
- instruction payload/template inputs
- initial assignment? optional if policy allows
- clientOccurredAt

Project id comes from route.

## AssignWorkRequest

- operationId
- baseVersion
- assignmentTargets: non-empty typed list
- plannedStart?
- plannedEnd?
- reason?
- clientOccurredAt

## ReviseWorkInstructionRequest

Route candidate:
POST /v1/work-orders/{workOrderId}/instruction-revisions

Fields:
- operationId
- baseVersion
- sourceInstructionTemplateId/revision?
- payloadSchemaVersion
- structuredPayload
- summaryText?
- changeReason
- clientOccurredAt

Result:
- WorkOrderCommandResult
- new instructionRevision/currentInstructionRevisionId

After ASSIGNED this command is online-authoritative and intentionally makes stale offline bundles conflict/reload.

## StartWorkRequest

- operationId
- baseVersion
- clientOccurredAt
- localSiteSessionRef?

## BlockWorkRequest

- operationId
- baseVersion
- blockerTypeCode
- severityCode?
- description
- clientOccurredAt

## ResumeWorkRequest

- operationId
- baseVersion
- blockerId?
- resolutionNote?
- clientOccurredAt

## SubmitWorkCompletionRequest

- operationId
- baseVersion
- evidenceIds
- measurementOrTestRefs
- materialUsageRefs
- completionNote?
- clientOccurredAt

## AcceptWorkRequest

- operationId
- baseVersion = exact submitted version
- reviewStepId?
- comment?
- clientOccurredAt

## RequestReworkRequest

- operationId
- baseVersion
- reasonCode?
- reason
- requiredCorrectionNotes?
- evidenceRefs?
- clientOccurredAt

## CancelWorkRequest

- operationId
- baseVersion
- reasonCode
- reason
- clientOccurredAt

## WorkOrderCommandResult

- workOrderId
- lifecycleState
- readinessState
- version
- policyBindingSummary
- appliedAt
- correlationId
- duplicateReplay: Boolean

---

# Asset command DTO candidates

## CheckoutAssetRequest

- operationId
- baseVersion
- recipientTargetType
- recipientTargetId
- projectId?
- siteId?
- workOrderId?
- destinationStorageLocationId?
- expectedReturnAt?
- conditionAtCheckout
- reasonCode?
- clientOccurredAt

## AssetCustodyCommandResult

- assetId
- lifecycleState
- assetVersion
- movementId
- currentStorageLocationId?
- currentCustodianTargetType?
- currentCustodianTargetId?
- projectId?
- siteId?
- workOrderId?
- expectedReturnAt?
- appliedAt
- correlationId
- duplicateReplay

Return and Transfer use the same exact-version/idempotent pattern.

---

# Evidence DTO candidates

## ReserveEvidenceUploadRequest

- operationId
- targetType
- targetId
- workOrderId?
- evidenceRequirementKey?
- evidenceTypeCode
- contentType
- sizeBytes
- expectedSha256
- clientOccurredAt
- baseVersion?

## ReserveEvidenceUploadResult

- evidenceId
- uploadSessionId
- uploadUrl
- requiredHeaders
- expiresAt
- maxSizeBytes
- correlationId

## FinalizeEvidenceRequest

- operationId
- uploadSessionId
- expectedSha256
- expectedSizeBytes

## EvidenceResult

- evidenceId
- storageState
- sha256
- sizeBytes
- contentType
- finalizedAt?
- version
- correlationId

---

# Standard list/read envelopes

List/read models use:
- items
- nextCursor: String?
- asOf: Instant
- correlationId

Cursor is opaque to clients.

Single business commands may return typed result DTOs directly instead of a generic data wrapper.

---

# Standard error envelope

Candidate exact shape:

- code: String
- message: String
- correlationId: String
- retryable: Boolean
- details: object?
- currentVersion: Long?
- conflict: ConflictPayload?

## ConflictPayload

- conflictType
- targetType
- targetId
- attemptedBaseVersion
- currentVersion
- safeCurrentState
- localWorkSafe: Boolean?
- allowedRecoveryActions: List<String>

Rules:
- safeCurrentState is authorization-filtered.
- external actors may receive OBJECT_NOT_VISIBLE instead of existence-revealing details.
- clients never parse free-text message to drive logic.

---

# HTTP status mapping candidate

- 200/201 — applied/read success.
- 202 — only for explicitly asynchronous server workflow.
- 400 — malformed/request validation.
- 401 — unauthenticated/token invalid.
- 403 — permission denied where existence disclosure is safe.
- 404 — not found/object hidden.
- 409 — VERSION_CONFLICT / state conflict / custody collision.
- 422 — domain validation/evidence/policy requirement not met.
- 429 — rate limited.
- 500 — safe internal failure.
- 503 — retryable service/integration unavailable.

---

# Read-model candidate shapes

## TechnicianTodayItem

- workOrderId/code
- WorkType code/name
- Project/Site safe labels
- lifecycleState/readinessState
- plannedStart/end
- priorityCode
- sync/freshness hint
- navigation/location availability flag
- blocker summary
- tool/material readiness summary

## TechnicianJobBundle

Uses the exact local contract in 04_ROOM_OFFLINE_SYNC.md.

## WarehouseAssetPassport

- Asset identity/type/model/serial
- lifecycle/condition
- calibration state/due
- current safe custody/location
- reservation context
- Project/Site/Work context where permitted
- tag identity
- version
- freshness/asOf
- acquisition cost excluded unless separate permission

## SupervisorReviewView

- WorkOrder identity
- exact submitted version
- WorkType
- instruction revision
- review policy step
- evidence requirement/result set
- measurements/tests
- relevant drawings/revisions
- assignment/context
- prior rework/review history
- allowed actions

## ProjectProgressSummary

- baselineVersion
- acceptedWeight: Decimal
- totalWeight: Decimal
- progressPercent: Decimal?
- asOf

## ProjectHealthSummary

- state: UNKNOWN / HEALTHY / ATTENTION / CRITICAL / ON_HOLD
- signals: list of typed safe signal summaries
- asOf

## PMProjectCommandCenter

- Project identity/state/health
- milestones/progress projection
- Work waiting/blocked/submitted/rework
- resource readiness
- Warehouse/material exceptions
- client/action exceptions
- activity/audit links
- finance subset only with permission

---

# API freeze items still open

No first-slice HTTP semantic decision remains open.

Post-Freeze Bootstrap verification:
- serialization option flags in production code.
- generated OpenAPI snapshot.
- REAUTH_REQUIRED integration test.
- cursor integration test.
- representative safe 4xx/5xx mappings.
- Android/Desktop DTO compatibility tests.

Current:
**first-slice API has stable route/DTO/error candidates sufficient to drive server/client skeleton generation.**


---

# Typed shared DTO details v0.2

## AssignmentTarget DTO

- type: USER / CREW / TEAM / SUBCONTRACTOR_ORGANIZATION
- id: UUID
- lead: Boolean = false

Rules:
- list must be non-empty when assigning.
- duplicate type+id targets rejected.
- policy decides allowed target types/cardinality.
- server validates eligibility/authorization.

## RequirementInstance DTO

- id
- family
- key
- label
- required
- satisfactionState
- sourceConfigId?
- sourceConfigRevision?
- satisfiedByRef?
- waived
- waiverRef?
- sortOrder

Clients render this; they do not recalculate authoritative readiness from hidden assumptions.

## Re-auth error details

For REAUTH_REQUIRED:

- requiredAuthenticationStrength: String
- maxAuthenticationAgeSeconds: Long?
- reasonCode: String
- retrySameOperationId: Boolean

The native client performs Keycloak re-auth and retries the same semantic command only when retrySameOperationId is true.

No password collection in HILTECH UI.

## Object visibility policy

First-slice candidate:

External users:
- unauthorized or out-of-scope object existence is hidden with HTTP 404 + OBJECT_NOT_VISIBLE.

Internal authenticated HILTECH users:
- 403 + PERMISSION_DENIED may be returned when object existence is already safe within their organization/context.
- cross-organization or highly sensitive existence may still use OBJECT_NOT_VISIBLE.

UI never infers object existence from timing/details.

## Cursor contract

Cursor is:
- opaque to clients,
- stateless server token candidate,
- versioned internally,
- bound to read-model/filter/order context,
- tamper-protected,
- contains/encodes stable seek keys + tie-breaker + asOf where required.

Clients:
- store/pass it only,
- never decode it,
- discard and restart pagination on CURSOR_INVALID/CURSOR_EXPIRED.

No Redis/server cursor session is required for the baseline.

## Typed client convention

Baseline:
- shared DTOs + thin manually maintained typed Ktor repository/client functions.
- no generated network client is required for first production slice.
- server OpenAPI/contract description may be generated for docs/testing, but generated client code is not the domain API source of truth.
- compile + contract tests prevent Android/Desktop drift.

## Breaking-change policy

Within /v1:
- prefer additive fields/endpoints.
- clients tolerate unknown response fields.
- do not repurpose field semantics.
- commands/read models carry explicit payload/schema version only when persisted/offline compatibility needs it.

Breaking incompatible public/native contract:
- new major API boundary or explicit migration/version path.
- old queued offline commands must be migrated/rejected explicitly, never misinterpreted.


---

# OpenAPI publication contract

After repository bootstrap:

- server emits OpenAPI 3.1 for /v1 HTTP routes.
- generated review snapshot path: contracts/http/hiltech-v1.openapi.yaml.
- snapshot is generated from production server DTO/controller contract and checked in CI for drift.
- manual edits to generated snapshot are forbidden.
- CI regenerates and fails if committed snapshot differs.
- breaking-change check runs against previous accepted /v1 snapshot.

The shared Ktor client remains manually typed/shared and is not generated from OpenAPI in first slice.

OpenAPI is:
- documentation,
- compatibility review artifact,
- test/tool input.

It is not a replacement for domain/configuration contracts.

---

# First-slice HTTP freeze decision

Pre-code semantics CLOSED for:
- route naming/prefix/action pattern.
- auth/idempotency/concurrency/correlation headers.
- native client metadata.
- Work/Asset/Evidence command DTOs.
- typed assignment/requirement DTOs.
- error/conflict/reauth envelopes.
- object visibility policy.
- cursor contract.
- Project progress/health read models.
- Configuration routes.
- Project/Site administration route pattern.
- additive /v1 compatibility.

Post-Freeze Bootstrap verification must implement and test these contracts.
