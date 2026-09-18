# 06 — Files / Evidence Contract

Status: **CONTRACT CANDIDATE v0.1**
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

Freeze:
- bucket/container separation policy,
- opaque key generation,
- tenant/org partition if required,
- lifecycle/versioning,
- encryption,
- quarantine/scanning if required,
- retention/legal hold if applicable.

Provider remains open until provider/ops freeze.

---

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

Authorized short-lived download target or proxied stream.
Never a permanent public restricted URL.

---

# Persistence contract

First-slice server owns:
- evidence
- evidence_upload_session
- object/evidence link where needed

Object storage owns binary bytes.

DB never stores large binary evidence payload as normal OLTP row.

---

# Open items before final freeze

- exact object-storage provider.
- exact object-key partitioning.
- virus/malware scanning requirement for uploaded files.
- maximum file sizes per evidence type.
- retention/legal hold implementation.
- multipart threshold.
- exact download proxy vs signed URL policy by classification.

Current:
**evidence protocol and metadata are contract-ready; provider/retention closure remains.**
