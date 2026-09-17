# Exact Object Specs — Approval, Inbox, Notifications, Sync & Audit

Status: DOMAIN DATA MODEL v0.1 / NOT TECH-SCHEMA-FROZEN

---

# ApprovalRequest

## Fields
- id
- subjectType
- subjectId
- subjectVersion
- policyKey
- requesterUserId
- state
- riskClass
- amount/currency — O
- reason
- createdAt
- dueAt — O
- completedAt — O
- version

## Invariants
- approval binds exact subjectVersion.
- subject change may supersede approval.
- request cannot self-authorize outside policy.

---

# ApprovalStep

## Fields
- id
- approvalRequestId
- sequence
- mode: SINGLE/ANY_OF/ALL_OF/QUORUM
- state
- requiredCount
- dueAt — O
- escalationPolicyRef — O

---

# ApprovalAssignment

## Fields
- id
- approvalStepId
- approverUserId
- delegatedFromUserId — O
- state
- assignedAt
- actedAt — O

---

# ApprovalDecision

## Fields
- id
- approvalAssignmentId
- decision: APPROVE/REJECT/REQUEST_CHANGE
- actorUserId
- decidedAt
- comment — O
- authenticationStrength
- deviceId — O
- subjectVersion
- auditRef

Append-only.

---

# InboxItem

## Fields
- id
- userId
- itemType
- sourceObjectRef
- priority
- state: UNREAD/READ/ACTED/DISMISSED/EXPIRED
- titleKey/textRef
- createdAt
- dueAt — O
- actionRef — O
- aggregationKey — O
- version

No sensitive business payload required in durable item if object can be fetched securely.

---

# Notification

## Fields
- id
- userId
- sourceInboxItemId — O
- eventRef
- class
- channelPolicy
- previewPolicy
- createdAt
- state

---

# NotificationDeliveryAttempt

## Fields
- id
- notificationId
- channel: PUSH/EMAIL/SMS/DESKTOP
- provider
- providerMessageId — O
- attemptedAt
- result
- failureCode — O
- deliveredAt — O
- readAt — O

Append-only attempts.

---

# NotificationPreference

## Fields
- id
- userId
- category
- channel
- enabled
- quietHours — O
- digestMode — O

Mandatory alerts may ignore opt-out according policy.

---

# DeviceOperation

## Purpose
Durable local/offline command representation.

## Fields
- operationId
- deviceId
- userId
- commandType
- targetRef
- baseVersion — O
- payloadRef/local serialized command
- clientOccurredAt
- createdAt
- state
- retryCount
- dependencyOperationIds
- lastErrorCode — O
- serverResultRef — O

Local storage object; server may persist idempotency record separately.

---

# SyncCursor

## Fields
- user/device/scope
- cursor/token
- lastPulledAt
- serverVersion/watermark
- expiresAt — O

---

# SyncConflict

## Fields
- id
- operationId
- targetRef
- conflictType
- localBaseVersion
- serverCurrentVersion
- currentServerStateSummary
- localIntentSummary
- allowedRecoveryActions
- createdAt
- resolvedAt/by — O
- resolution — O

---

# UploadSession

## Fields
- id
- user/device
- storageKey
- targetEvidence/documentRef
- fileName
- sizeBytes
- checksum
- state
- uploadProviderRef
- startedAt
- completedAt — O
- retryCount

Sensitive temporary credentials are never stored in this business object.

---

# AuditEvent

## Fields
- id
- occurredAt
- actorUserId — O
- actorType
- action
- objectType
- objectId
- objectVersion — O
- result
- reason — O
- requestCorrelationId — O
- deviceId — O
- sessionId — O
- classification
- semanticChangeSummary — O
- externalCorrelationId — O

Append-only / tamper-resistant strategy required.

Audit event payload should not duplicate sensitive object contents unnecessarily.

---

# Invariants

- ApprovalDecision immutable.
- Notification delivery state does not become business truth.
- SyncConflict never discards local intent silently.
- Upload completion requires checksum/metadata consistency.
- Audit event is not application log.
- Inbox item can resolve when underlying action completed elsewhere.

## Next
Technical spikes define exact persistence and API shape.
