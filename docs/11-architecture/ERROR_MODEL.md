# HILTECH Error Model

Status: PRODUCT / ARCHITECTURE MODEL v0.1

## Objective
Users should understand what happened and what they can do next.

Do not expose infrastructure errors directly as product UX.

---

# Error Families

## AUTHENTICATION
- UNAUTHENTICATED
- SESSION_EXPIRED
- DEVICE_REVOKED
- REAUTH_REQUIRED
- MFA_REQUIRED

## AUTHORIZATION
- PERMISSION_DENIED
- OBJECT_NOT_VISIBLE
- ACTION_NOT_ALLOWED_IN_CONTEXT

## VALIDATION
- REQUIRED_FIELD
- INVALID_VALUE
- INVALID_STATE_TRANSITION
- MISSING_REQUIRED_EVIDENCE
- INVALID_QUANTITY
- DUPLICATE_IDENTIFIER

## CONCURRENCY / SYNC
- VERSION_CONFLICT
- OFFLINE_PENDING
- SYNC_CONFLICT
- OPERATION_SUPERSEDED
- DUPLICATE_REPLAY
- DEPENDENCY_NOT_SYNCED

## BUSINESS STATE
- RESOURCE_UNAVAILABLE
- ASSET_ALREADY_CHECKED_OUT
- STOCK_INSUFFICIENT
- CALIBRATION_EXPIRED
- WORK_BLOCKED
- APPROVAL_REQUIRED
- APPROVAL_SUPERSEDED
- OBJECT_CLOSED

## INTEGRATION
- INTEGRATION_UNAVAILABLE
- INTEGRATION_AUTH_EXPIRED
- INTEGRATION_REJECTED
- INTEGRATION_RATE_LIMITED
- INTEGRATION_UNKNOWN_OUTCOME

## FILE / DOCUMENT
- UPLOAD_FAILED
- CHECKSUM_MISMATCH
- FILE_TOO_LARGE
- UNSUPPORTED_FILE
- DOCUMENT_SUPERSEDED
- DOWNLOAD_UNAVAILABLE

## FINANCIAL
- PAYMENT_FAILED
- PAYMENT_UNKNOWN
- PAYMENT_RETURNED
- INVOICE_MISMATCH
- RECONCILIATION_REQUIRED

## SYSTEM
- TEMPORARY_UNAVAILABLE
- INTERNAL_ERROR
- MAINTENANCE_MODE
- FEATURE_DISABLED

---

# UX Contract

Every surfaced error should answer where possible:
1. What happened?
2. Is my work safe?
3. What is current truth?
4. What can I do now?
5. Does someone else need to act?

Example:

Bad:
"409 Conflict"

Good:
"This Fluke was checked out by another user while you were offline. Your scan is saved; choose another unit or ask the warehouse to transfer custody."

---

# Retry Policy

Retryable:
- transient network.
- provider temporary unavailable.
- safe idempotent command.

Not blindly retryable:
- validation.
- permission.
- version conflict.
- payment unknown outcome.
- stock/asset conflict.

---

# Logging

Client sees safe error code/message.
Server observability stores technical diagnostic correlated by correlationId.

No sensitive data in error body.

---

# Localization

Machine code stable.
Presentation message localizable Arabic/English.

Avoid parsing localized strings for logic.

---

# Support Diagnostics

Selected errors may expose user-safe support code:
HIL-XXXX

Support/admin can correlate to trace/log without revealing infrastructure details.

## Freeze Gate
Map every first implementation command to expected errors and test each recovery path.
