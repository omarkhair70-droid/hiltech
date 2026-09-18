# HILTECH Cross-Cutting Build Contracts

Status: **BUILD-CONTRACT CANDIDATE / PRE-FREEZE**
Date: 2026-09-18

Purpose:
Turn already-proven cross-cutting architecture into exact implementation-facing contracts without pretending domain schemas are frozen.

This document may be promoted to FROZEN only after SPIKE-15 validates the real HTTP/Ktor path and the first exact domain schemas exist.

---

# 1. Transport Boundary

Baseline:
- HTTPS only outside local development.
- authenticated requests use OIDC access token.
- server derives actor/roles/organization context; client claims are never trusted authority.
- business commands are explicit actions, not generic table CRUD.
- queries return task-specific authorized read models.

Exact base URL/version prefix remains to be locked during SPIKE-15/API freeze.

---

# 2. Standard Request Headers

Every authenticated application request may carry:

- Authorization: Bearer <access-token>
- X-Correlation-Id: stable request/workflow correlation identifier
- X-Client-Platform: android | windows
- X-Client-Version: application build/version
- X-Device-Id: opaque registered device identity where applicable

Retry-sensitive command requests additionally carry:

- Idempotency-Key: stable operationId generated before first submission

Concurrency-sensitive commands additionally carry one accepted representation of:

- baseVersion in command body, or
- If-Match equivalent

SPIKE-15 chooses the exact final wire representation; the semantics are already accepted.

---

# 3. Command Envelope

Logical shape:

```json
{
  "operationId": "opaque-stable-id",
  "targetId": "opaque-object-id",
  "baseVersion": 8,
  "clientOccurredAt": "2026-09-18T04:12:00Z",
  "payload": {}
}
```

Rules:
- operationId is stable across retries.
- actor/session/permissions come from authenticated server context.
- device identity is server/session validated where material.
- baseVersion is mandatory for collision-sensitive commands.
- payload never includes a trusted role/permission claim.
- offline capture time does not override authoritative ordering.

---

# 4. Command Result

Logical success shape:

```json
{
  "result": "ACCEPTED",
  "operationId": "...",
  "target": {"type":"WorkOrder","id":"..."},
  "currentVersion": 9,
  "state": "IN_PROGRESS",
  "updated": {},
  "correlationId": "..."
}
```

Allowed product result families include:
- ACCEPTED
- REJECTED_VALIDATION
- REJECTED_PERMISSION
- REJECTED_STATE
- VERSION_CONFLICT
- DUPLICATE_REPLAY
- INTEGRATION_PENDING
- INTEGRATION_UNKNOWN
- FAILED_RETRYABLE
- FAILED_PERMANENT
- REAUTH_REQUIRED

HTTP status and product result code are separate concerns.

---

# 5. Error Envelope

Every safe client-facing error uses:

```json
{
  "code": "VERSION_CONFLICT",
  "message": "Safe human-readable fallback",
  "messageKey": "work.version_conflict",
  "correlationId": "...",
  "target": {"type":"WorkOrder","id":"..."},
  "details": {}
}
```

Rules:
- code is stable/machine-readable.
- message contains no secret/internal stack detail.
- details are code-specific and explicitly safe.
- stack trace is never returned to client.
- unauthorized external caller may receive OBJECT_NOT_VISIBLE instead of existence-leaking detail.

---

# 6. Validation Error

Validation details are structured:

```json
{
  "code": "REJECTED_VALIDATION",
  "details": {
    "violations": [
      {"path":"amount","code":"MUST_BE_POSITIVE","params":{}}
    ]
  }
}
```

Client owns Arabic/English presentation from stable message keys/parameters unless a future localization layer is explicitly selected.

---

# 7. Version Conflict

Conflict response must include enough safe data to recover:

```json
{
  "code": "VERSION_CONFLICT",
  "target": {"type":"WorkOrder","id":"wo-42"},
  "details": {
    "attemptedBaseVersion": 7,
    "currentVersion": 8,
    "conflictType": "STALE_ASSIGNMENT",
    "currentState": "REASSIGNED",
    "allowedRecoveryActions": ["REVIEW_LOCAL_WORK","DISCARD_COMMAND"]
  }
}
```

Rules:
- no silent last-write-wins on critical objects.
- local intent/evidence remains preserved.
- dependent queued commands may become BLOCKED_BY_CONFLICT.

---

# 8. Idempotency

Server idempotency record conceptually binds:
- actor/security subject
- command type
- target
- operationId
- semantic result
- result target version
- created/expiry policy

Retry with same operationId must not repeat the business side effect.

Same operationId with materially different command identity/payload must be rejected as misuse/conflict, not treated as a new operation.

Retention duration is domain/risk dependent and remains schema policy.

---

# 9. Query Envelope / Pagination

Large mutable lists use cursor pagination.

Logical response:

```json
{
  "items": [],
  "nextCursor": "opaque-or-null",
  "asOf": "2026-09-18T04:12:00Z"
}
```

Rules:
- cursor is opaque.
- ordering is deterministic with stable tie-breaker.
- totalCount is optional, never required globally.
- filters/sorts are allow-listed per read model.
- no raw SQL-like query language exposed to clients.
- field-level authorization is enforced server-side.

---

# 10. IDs

Rules:
- domain IDs are opaque stable identifiers.
- human codes are separate fields.
- clients never infer authorization or ordering from ID value.
- exact UUID strategy remains data-freeze decision.

Examples:
- opaque id
- projectCode = PRJ-2026-0012
- assetCode = HT-A-000184

---

# 11. Money

Wire contract:

```json
{
  "amount": "18450.00",
  "currency": "EGP"
}
```

Rules:
- decimal string/exact decimal representation, never binary float.
- ISO 4217 currency.
- rounding belongs to explicit domain/accounting policy.

---

# 12. Time

Events/timestamps:
- ISO-8601 UTC instant.

Business date:
- explicit local date with no fake timezone.

Local schedules:
- IANA timezone where timezone changes meaning.

Do not infer timezone from backend host.

---

# 13. Binary Upload Reservation

Logical reservation request contains:
- target object/evidence context,
- file name/type,
- size,
- SHA-256,
- operationId/correlation where applicable.

Logical response contains:
- uploadSessionId,
- storage key opaque to client semantics,
- short-lived signed PUT target,
- required signed headers,
- expiry.

Finalization command contains:
- uploadSessionId,
- expected checksum/size identity.

Server finalization must independently verify stored bytes/metadata before READY.

Permanent public object URLs are forbidden for restricted evidence.

---

# 14. Sync Command Record

Local durable representation requires:
- operationId
- commandType
- target/object type + ID
- baseVersion optional/required by command class
- serialized payload/ref
- created/clientOccurredAt
- localSequence
- dependencyOperationIds
- queue state
- retryCount/lastAttempt
- lastResultCode
- serverVersion/result ref when available

Accepted semantic states currently include:
- PENDING
- RETRYABLE
- SYNCING
- APPLIED
- CONFLICT
- BLOCKED_BY_CONFLICT
- FAILED_TERMINAL

Exact persisted enum names may still be renamed before schema freeze; semantics are fixed by ADR-011.

---

# 15. Authentication / Re-auth

Identity:
- Keycloak native OIDC Authorization Code + PKCE.

When a sensitive command needs stronger/recent authentication:
- server returns REAUTH_REQUIRED with safe requirement metadata,
- client performs OIDC re-auth interaction,
- command retries using the same operationId if semantic retry is safe.

Password is never collected by HILTECH native UI.

---

# 16. Authorization

On every authoritative command/query:
1. validate identity/session,
2. resolve HILTECH subject/org context,
3. OpenFGA object/action check where applicable,
4. workflow/state/business-policy check,
5. field-level filtering/obligations,
6. execute or reject.

OpenFGA does not replace amount thresholds, state prerequisites, field redaction, MFA/re-auth obligations or audit.

---

# 17. Observability

Every request/command path preserves:
- W3C trace context,
- correlationId,
- operationId where relevant,
- module/command/error code safe dimensions.

Never emit raw command payload, salary, bank details, token, national ID or evidence contents into telemetry by default.

---

# 18. Realtime

Realtime event is a hint, not authoritative business payload.

Example hint:
- object type/ID
- version
- event class
- correlation where useful

Client reconciles/fetches through normal permission-safe query/sync path.

---

# 19. Contract Tests Required Before Freeze

SPIKE-15 / final API contract tests must prove:
- Android + Windows shared Ktor contract.
- auth token attachment.
- correlation propagation.
- operationId/idempotency propagation.
- command success.
- duplicate replay.
- stale version conflict.
- permission denied/object hidden.
- REAUTH_REQUIRED path.
- retryable network failure mapping.
- safe server error mapping.
- cursor query.
- binary upload reservation/finalization.
- offline replay.

---

# What Remains Unfrozen

- exact URL grammar/base prefix.
- exact serialization library/options.
- exact Ktor engine choices per platform.
- exact generated/manual API client convention.
- UUID representation.
- exact pagination cursor encoding.
- per-domain request/response schemas.
- migration/schema persistence.
- API breaking-change policy.

Those are intentionally resolved by SPIKE-15 + exact schema/domain freeze, not guessed here.