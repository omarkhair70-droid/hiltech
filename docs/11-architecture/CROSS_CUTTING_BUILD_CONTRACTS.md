# HILTECH Cross-Cutting Build Contracts

Status: **SPIKE-VALIDATED BUILD-CONTRACT CANDIDATE / PRE-FREEZE**
Date: 2026-09-18

Purpose:
Turn already-proven cross-cutting architecture into exact implementation-facing contracts without pretending domain schemas are frozen.

SPIKE-15 has validated the real HTTP/Ktor path. This document may be promoted to FROZEN only after the first exact production domain schemas/contracts exist and remaining contract-test gaps are closed.

---

# 1. Transport Boundary

Accepted baseline:
- Ktor Client 3.5.2 is the shared client HTTP boundary — ADR-007.
- Android engine: OkHttp.
- JVM Desktop engine: CIO.
- shared code owns DTOs, serialization, request headers, result mapping and sync transport.
- engine-specific APIs do not leak into UI/domain layers.
- HTTPS only outside local development.
- authenticated requests use OIDC access token.
- server derives actor/roles/organization context; client claims are never trusted authority.
- business commands are explicit actions, not generic table CRUD.
- queries return task-specific authorized read models.

First-slice API freeze now accepts `/v1` with explicit resource/action routes documented in `docs/13-delivery/first-slice-contract-pack/02_API_AND_READ_MODELS.md`.

---

# 2. Standard Request Headers

Every authenticated application request may carry:

- Authorization: Bearer <access-token>
- X-Correlation-Id: stable request/workflow correlation identifier
- X-Client-Platform: android | windows
- X-Client-Version: application build/version
- X-Device-Id: opaque registered device identity where applicable

Retry-sensitive command requests carry:

- `Idempotency-Key`: stable operationId generated before first submission.

Concurrency-sensitive commands carry:

- `baseVersion` in the command body.

SPIKE-15 validated this representation end to end.

Trace/correlation representation validated by SPIKE-15:
- `X-Correlation-Id`
- W3C `traceparent`

The optional `X-Client-Platform`, `X-Client-Version`, and `X-Device-Id` headers remain production-contract candidates and must only be required where the server actually uses them.

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

## Cursor implementation
First-slice baseline is a stateless, tamper-protected opaque seek cursor bound to:
- read-model identity,
- normalized filter/order contract,
- stable seek values + tie-breaker,
- asOf/version context where needed.

No Redis/server cursor session is required.
Invalid/expired cursor returns a typed restart-pagination result.

---

# 10. IDs

Rules:
- domain IDs are opaque stable identifiers.
- human codes are separate fields.
- clients never infer authorization or ordering from ID value.
- physical domain/config identifiers use UUID.
- server-created business IDs are server-generated UUIDs.
- client-generated retry operation IDs are UUIDs.
- Room stores UUIDs as canonical lowercase TEXT for KMP portability.

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

## Validated by SPIKE-15

- [x] Android + Windows shared Ktor contract.
- [x] auth token attachment.
- [x] correlation + W3C trace propagation.
- [x] operationId/idempotency propagation.
- [x] command success.
- [x] duplicate replay.
- [x] stale version conflict.
- [x] server-side permission deny.
- [x] binary upload reservation/upload/finalization.
- [x] offline replay after process death/reconnect.
- [x] dependent command blocking after conflict.
- [x] authoritative state preserved after stale conflict.

Evidence:
- E2E run 35323209954.
- Room regression 35323209973.
- Offline queue regression 35323209948.
- WorkManager regression 35323209949.

## Still Required Before Contract Freeze

- [ ] REAUTH_REQUIRED end-to-end command retry path.
- [ ] retryable network failure mapping through production-shaped HTTP error envelope.
- [ ] safe server error mapping across representative 4xx/5xx families.
- [ ] cursor query contract implementation test on a production-shaped read model.
- [x] object-hidden vs permission-denied policy defined.
- [x] first-slice Work/Asset/Evidence request/response contract candidates defined.

---

# What Remains Unfrozen

- final route wording normalization for a few configuration/project admin endpoints.
- exact Kotlinx Serialization option flags in code.
- final physical migration/schema DDL.
- production retry/backoff constants by command/query class.
- optional device/client metadata headers only where server logic consumes them.

Ktor Client 3.5.2 and Android OkHttp / JVM Desktop CIO engine choices are no longer open.

The remaining items are resolved by exact schema/domain contract freeze, not guessed from spike code.

---

# 20. Serialization / Typed Client

First-slice baseline:
- kotlinx.serialization for shared wire DTOs.
- JSON transport.
- response decoding tolerates unknown additive fields.
- request/command DTOs remain strongly typed.
- persisted offline command payload has explicit payloadVersion.
- Android/Desktop use thin manually maintained shared Ktor repository/client functions.
- generated network client is not required for first slice.
- OpenAPI/contract output may be generated for docs/testing, but it is not domain source of truth.

---

# 21. API Visibility / Versioning

External unauthorized/out-of-scope object:
- HTTP 404 + OBJECT_NOT_VISIBLE.

Internal HILTECH user where object existence is already safe:
- HTTP 403 + PERMISSION_DENIED may be used.

Within /v1:
- additive evolution preferred.
- unknown response fields tolerated.
- field semantic repurpose is forbidden.
- persisted/offline payload compatibility uses explicit payloadVersion/migration.

---

# 22. String / Text Bounds — First-Slice Baseline

Unless a domain contract specifies stricter:

- machine/business code: max 64 Unicode characters; expected canonical ASCII-safe form for generated codes.
- short display name: max 200.
- UI title/label: max 240.
- description/reason/note submitted through normal command fields: max 4000.
- external/provider opaque ID: max 255.
- correlation/idempotency textual representation: max 128.
- MIME/content type: max 127.

Large documents/evidence are file/document objects, not giant text fields.

Exact database column types may use TEXT while server validation enforces these product bounds where appropriate.
