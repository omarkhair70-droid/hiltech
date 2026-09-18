# 06 — Files / Evidence Contract

Status: **CONTRACT CANDIDATE v0.2 / STORAGE SECURITY CONTRACT CLOSED**
Date: 2026-09-18

## Locked Technical Protocol

- binary content is outside normal JSON command payload.
- server reserves upload.
- client uploads directly to short-lived S3-compatible target.
- expected SHA-256 is part of contract.
- finalize verifies stored bytes/metadata independently.
- restricted evidence has no permanent public URL.
- retry after failed/corrupt upload is safe.
- local evidence persists until authoritative finalize or explicit discard.

---

# Evidence Policy Binding

Evidence requirements are versioned configuration through `EvidencePolicy`.

A WorkOrder binds the EvidencePolicy revision required for historical reproducibility.

For each WorkType, seed/configuration may define:

| Config field | Meaning |
|---|---|
| evidenceTypeCode | typed evidence class |
| stage | BEFORE_START / BEFORE_SUBMIT / BEFORE_ACCEPT |
| minCount / maxCount | cardinality |
| captureSources | CAMERA / FILE_UPLOAD / FORM / MEASUREMENT / TEST_IMPORT / SIGNATURE / SCAN / OTHER_TYPED |
| offlineCaptureAllowed | whether local capture is permitted |
| allowedContentTypes | MIME/content restrictions |
| measurementSchemaRef | typed test/measurement contract |
| classificationCode | security/data classification |
| retentionPolicyCode | retention rule |
| clientVisibilityMode | internal / client-authorized / client-required |
| reviewerRelationshipCode | optional review requirement |

Do not make GPS/photo/signature universally mandatory without real work/site justification.

---

# Evidence Metadata Contract

Candidate exact semantic fields:
- evidenceId: UUID
- organizationId: UUID
- targetType: String
- targetId: UUID
- workOrderId: UUID?
- evidenceRequirementKey: String?
- evidencePolicyId: UUID?
- evidencePolicyRevision: Int?
- evidenceTypeCode: String
- contentType: String
- originalFileName: String?
- sizeBytes: Long
- sha256: String
- capturedAt: Instant
- clientOccurredAt: Instant?
- capturedByUserId: UUID
- sourceDeviceId: UUID/String?
- instructionRevision: Int/Long?
- workOrderVersionAtCapture: Long?
- storageState: EvidenceStorageState
- objectKeyRef: String?
- finalizedAt: Instant?
- classificationCode: String
- clientVisibilityMode: String
- supersedesEvidenceId: UUID?
- version: Long
- createdAt: Instant

EvidenceStorageState candidate:
- RESERVED
- UPLOADED_UNVERIFIED
- READY
- REJECTED
- RETIRED/SUPERSEDED where needed.

LOCAL_ONLY/UPLOADING/RETRYABLE remain client-local states, not authoritative server evidence states.

Every field needs a retention/security justification.

---

# Storage Key Rule

Object-storage key is infrastructure identity, not business meaning exposed to client.

First-slice provider-neutral contract:
- one private evidence bucket/container per environment is sufficient; tenant isolation uses an opaque organization prefix + server authorization.
- object key form: org/{organizationId}/evidence/{evidenceId}/objects/{objectVersionId}.
- no client filename, person name, project name or sensitive business text in object key.
- bucket/container is private; public ACL/access is disabled.
- server-side encryption at rest is required; exact KMS/provider mode is selected with the provider.
- object versioning is enabled where the provider supports it without breaking retention/deletion policy.
- lifecycle deletion is disabled by default until a formal RetentionPolicy is activated.

Provider remains open until provider/ops freeze.

---

## First-slice size / upload rule

- system maximum individual evidence object: 16 MiB.
- EvidencePolicy may set a lower limit.
- first production slice uses one signed PUT/upload request; no multipart upload.
- if a verified WorkType later needs files >16 MiB, multipart becomes an explicit extension with its own contract tests.

## Security scan rule

Evidence requirement carries a security scan class:

- GENERATED_TRUSTED_FORMAT — app-generated form/measurement or server-generated artifact; signature/schema validation, no malware scanner required.
- NATIVE_MEDIA — camera/native media; content-signature/MIME validation required; scanner may be enabled by deployment policy.
- ARBITRARY_FILE — user-selected/imported file; security scan/quarantine required before READY.

Server-authoritative state can include:
- RESERVED
- UPLOADED_UNVERIFIED
- QUARANTINED
- READY
- REJECTED

ARBITRARY_FILE cannot become READY until security scan result PASS.

Executable/script/archive content is denied in first-slice EvidencePolicy unless an explicit typed use case is added.

# Upload State Machine

## Client-local
- LOCAL_READY
- RESERVATION_REQUIRED
- RESERVED
- UPLOADING
- UPLOADED_UNVERIFIED
- RETRYABLE
- READY
- REJECTED
- DISCARDED_EXPLICITLY

## Server-authoritative
- RESERVED
- UPLOADED_UNVERIFIED
- QUARANTINED
- READY
- REJECTED

READY is server-authoritative only.

Corrupt/truncated content can never become READY.

---

# Required Tests

- correct upload/finalize,
- corrupt/truncated upload rejected,
- retry succeeds,
- unauthorized reservation denied,
- unauthorized download denied,
- expired signed target,
- local evidence survives process death,
- local evidence survives sync conflict,
- file type/size validation,
- checksum mismatch,
- duplicate finalize/idempotency,
- work cancellation/reassignment while evidence is pending.


---

# API contract

## ReserveEvidenceUpload

Input:
- operationId
- targetType/targetId
- workOrderId?
- evidenceRequirementKey?
- evidenceTypeCode
- contentType
- sizeBytes
- expectedSha256
- clientOccurredAt
- baseVersion/context version where required

Output:
- evidenceId
- uploadSessionId
- signed upload target
- required upload headers
- expiresAt
- max size/constraints
- correlationId

## FinalizeEvidence

Input:
- operationId
- evidenceId
- uploadSessionId
- expectedSha256
- expectedSizeBytes

Server independently verifies stored object.

Output:
- authoritative evidence metadata
- READY only after successful verification.

## Download

Never a permanent public restricted URL.

## Download contract by classification

- INTERNAL / RESTRICTED: after server authorization, return a signed private download target with maximum 5-minute expiry.
- HIGHLY_RESTRICTED: stream/proxy through the authenticated HILTECH API; do not issue a reusable direct object URL.
- CLIENT_VISIBLE_IF_AUTHORIZED: same signed-target rule after explicit client object/field authorization.
- every download decision re-checks current authorization/classification.

Signed URL expiry is a delivery mechanism, not authorization persistence.

---

## Retention baseline

First production slice:
- authoritative READY evidence has no automatic deletion by default.
- RetentionPolicy must be explicitly activated before automated expiry/deletion is enabled.
- legal/contractual hold, when later introduced, overrides normal deletion.
- superseded evidence remains historically linked until retention policy permits disposal.

This avoids inventing a legal retention period.

# Persistence contract

First-slice server owns:
- evidence
- evidence_upload_session
- object/evidence link where needed

Object storage owns binary bytes.

DB never stores large binary evidence payload as normal OLTP row.

---

# Open items before final freeze

- exact production S3-compatible provider/KMS.
- exact malware-scanner product/service for ARBITRARY_FILE.
- provider lifecycle/versioning/backup settings.
- future formal retention/legal-hold policy if HILTECH/client contracts require automated deletion/hold.

Closed:
- opaque object-key partitioning.
- 16 MiB first-slice system max.
- no multipart in first slice.
- quarantine/security-scan classes.
- private encryption-at-rest requirement.
- classification-based download delivery.
- no automatic evidence deletion by default.

Current:
**evidence application/storage contract is structurally closed; only provider/security-service operations remain.**
