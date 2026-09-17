# Exact Object Specs — Support, Documents & Security

Status: DOMAIN DATA MODEL v0.1 / NOT VENDOR/LEGAL-FROZEN

---

# SupportTicket

## Fields
- id
- ticketCode
- clientOrganizationId
- requesterUser/contactId
- projectId — O
- siteId — O
- assetId — O
- category
- severity
- priority
- coverageDecisionId — O
- lifecycleState
- ownerUser/team
- title
- description
- clientVisibleSummary
- createdAt
- firstResponseAt — O
- resolvedAt — O
- closedAt — O
- version

## Invariants
- client only sees allowed ticket content.
- internal notes separate from client communications.
- SLA state separate from lifecycle.
- reopen preserves prior resolution history.

---

# SLAInstance

## Fields
- id
- supportTicketId
- serviceContractId
- responseTargetAt
- resolutionTargetAt
- pauseState
- pauseReason
- breachedAt — O
- state
- calculationVersion

Derived from contract, not arbitrary.

---

# MaintenanceVisit

## Fields
- id
- serviceContractId
- siteId
- scheduledAt
- assignedUsers/team
- checklistTemplateVersion
- lifecycleState
- startedAt
- completedAt
- findingCount
- evidenceRefs
- version

---

# Finding

## Fields
- id
- maintenanceVisitId
- asset/site context
- severity
- type
- description
- evidenceRefs
- recommendedAction
- resultingTicket/work/quote refs
- state

---

# Document

## Fields
- id
- documentCode — O
- documentType
- title
- classification
- ownerOrganizationId
- createdBy
- currentVersionId
- retentionPolicyRef
- state
- createdAt
- version

---

# DocumentVersion

## Fields
- id
- documentId
- versionNumber
- storageObjectId
- filename
- mimeType
- sizeBytes
- checksum
- createdAt/by
- approvalState
- approvedAt/by — O
- supersedesVersionId — O

## Invariants
- approved version binary immutable.
- currentVersionId points to valid version.
- deleted/superseded version retained according retention.

---

# ObjectDocumentLink

## Fields
- id
- documentId
- targetType
- targetId
- relationshipType
- clientVisible
- requiredForHandover
- createdAt/by

Allows one document to belong to relevant contexts without copies.

---

# Evidence

## Fields
- id
- evidenceType
- targetType/id
- workOrderId — O
- siteId — O
- assetId — O
- testId — O
- capturedBy
- capturedAt
- deviceId — O
- localOperationId — O
- storageObjectId
- checksum
- syncState
- classification
- metadataJson limited
- version

## Invariants
- evidence linked to business context.
- local capture survives until upload acknowledged.
- no orphan upload considered valid evidence.

---

# HandoverPackage

## Fields
- id
- projectId
- versionNumber
- state
- generatedAt
- generatedBy
- requiredArtifactPolicyVersion
- missingArtifactCount
- clientReviewState
- acceptedAt — O
- documentRefs

---

# Facility

## Fields
- id
- code
- name
- address/location — RESTRICTED
- active

---

# SecurityZone

## Fields
- id
- facilityId
- parentZoneId — O
- code
- name
- classification
- restricted

---

# Camera

## Fields
- id
- facilityId
- zoneId
- name
- vendor/model — O
- integrationConnectionId
- externalCameraId
- active
- streamCapability
- recordingCapability
- healthState
- lastSeenAt

Classification: HIGHLY_RESTRICTED.

No credentials stored in normal object.

---

# Door / AccessPoint

## Fields
- id
- facilityId
- zoneId
- name
- integrationConnectionId
- externalDoorId
- healthState
- active

---

# AccessEntitlement

## Fields
- id
- subjectUser/employeeId
- zone/door scope
- validFrom
- validUntil — O
- sourcePolicy/approval
- state
- credentialRef — external/secret boundary

Classification: HIGHLY_RESTRICTED.

---

# AccessEvent

## Fields
- id
- accessPointId
- subjectRef — O
- externalCredentialRefRedacted — O
- result: GRANTED/DENIED/UNKNOWN
- occurredAt
- providerEventId
- reasonCode
- correlationRefs

Append-only.
Classification: HIGHLY_RESTRICTED.

---

# SecurityIncident

## Fields
- id
- type
- severity
- facility/zone
- relatedAccessEventRefs
- relatedCameraEventRefs
- relatedAssetIncidentRefs
- description
- state
- owner
- resolution
- createdAt/resolvedAt

No automatic blame inference.

---

# IntegrationConnection

## Fields
- id
- type
- provider/vendor
- displayName
- configReference
- secretReference — SECRET, external secret store
- healthState
- lastSuccessAt
- lastFailureAt
- active

---

# Data Classification Summary

Support: RESTRICTED
Documents: classification varies
Evidence: INTERNAL/RESTRICTED
Camera/access: HIGHLY_RESTRICTED
Integration secret: SECRET

## Next
Vendor inventory + privacy/security validation required before schema freeze.
