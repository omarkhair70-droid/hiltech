# HILTECH API Conventions

Status: ARCHITECTURE MODEL v0.2 / FIRST-SLICE CONTRACT CANDIDATE LINKED

## Objective
Define consistent application/API behavior before endpoint implementation.

Exact cross-cutting pre-freeze contract candidate:
`CROSS_CUTTING_BUILD_CONTRACTS.md`

This file remains the architectural rationale; the linked contract file is the implementation-facing consolidation.

The API is not generic CRUD over database tables.
It exposes:
- queries for authorized views,
- commands for meaningful state changes,
- upload/download flows,
- realtime hints where useful.

---

# 1. Resource and Command Split

## Query examples
GET /projects/{id}
GET /projects?state=ACTIVE
GET /assets/{id}
GET /work-orders/{id}
GET /payroll-runs/{id}

## Command examples
POST /work-orders/{id}/commands/start
POST /work-orders/{id}/commands/submit-completion
POST /assets/{id}/commands/checkout
POST /payroll-runs/{id}/commands/request-approval
POST /payments/{id}/commands/approve

First-slice production route candidates are now defined in:
`docs/13-delivery/first-slice-contract-pack/02_API_AND_READ_MODELS.md`.

The accepted pattern is `/v1` + plural resource/read-model routes + explicit action sub-routes for lifecycle-critical commands.

---

# 2. Request Identity

Every request receives:
- request/correlation ID
- authenticated subject from server security context
- device/app metadata where relevant

Clients do not send trusted role/permission claims.

---

# 3. Idempotency

Required for retry-sensitive commands.

Accepted header:
`Idempotency-Key`

Server stores/recognizes:
- actor
- command type
- target
- key
- result

Duplicate replay returns same semantic outcome where safe.

---

# 4. Optimistic Concurrency

Concurrency-sensitive commands carry expected/base version.

Accepted current wire baseline:
explicit `baseVersion` in the command body.

A future HTTP `If-Match` mapping may be added only if it improves a specific public/API boundary; it is not required by the current native client contract.

On stale version:
return VERSION_CONFLICT with:
- target ID
- attempted base version
- current version
- safe summary/current state
- recovery action hints

Never silently last-write-wins on critical objects.

---

# 5. Pagination

Prefer cursor-based pagination for large changing lists.

Response:
- items
- nextCursor
- optional totalCount only where cheap/needed

Avoid requiring exact counts for every screen.

---

# 6. Filtering / Sorting

Use explicit allowed fields/operators per endpoint/read model.

Do not expose raw SQL-like expressions.

Examples:
state
projectId
ownerId
dueBefore
updatedAfter

Sort:
stable and deterministic with ID tie-breaker.

---

# 7. Sparse / Field Selection

Only if proven useful.

Field-level authorization always server enforced regardless of requested fields.

---

# 8. Error Envelope

All product/API errors use stable machine code plus safe message/context.

Example:

{
  "code": "VERSION_CONFLICT",
  "message": "This work order changed while you were offline.",
  "correlationId": "...",
  "target": {"type":"WorkOrder","id":"..."},
  "details": {...safe structured data...}
}

No stack traces or secrets to clients.

---

# 9. Validation Errors

Return field/action validation in structured form:
- field/path
- code
- message key
- safe parameters

Support Arabic/English presentation client-side or via localized message layer.

---

# 10. Authorization Errors

Distinguish product semantics carefully:
- UNAUTHENTICATED
- REAUTH_REQUIRED
- PERMISSION_DENIED
- OBJECT_NOT_VISIBLE

For external users, avoid leaking existence of unauthorized object when needed.

---

# 11. Re-authentication

Critical endpoint can return:
REAUTH_REQUIRED

with required authentication strength/action.

Client performs IdP flow then retries command with same idempotency strategy where safe.

---

# 12. External Integration Results

Never map all external failures to generic 500.

Examples:
- INTEGRATION_UNAVAILABLE
- INTEGRATION_REJECTED
- INTEGRATION_UNKNOWN_OUTCOME
- INTEGRATION_AUTH_EXPIRED

Unknown outcome must trigger reconciliation path.

---

# 13. Uploads

Large evidence/documents:
1. create/request upload session.
2. receive signed/provider upload target.
3. upload binary directly.
4. confirm/finalize metadata.
5. server validates checksum/ownership.

Avoid pushing large binaries through normal JSON command endpoint.

---

# 14. Download / Document Access

Authorized request returns:
- metadata,
- signed short-lived URL/stream token,
or server-controlled stream.

Never expose permanent public object-storage URLs for restricted documents.

---

# 15. Realtime

Realtime channel can notify:
- object changed,
- inbox changed,
- approval assigned,
- sync hint.

Message is a hint/reference, not full authoritative truth.

Client fetches/reconciles through normal data path.

---

# 16. API Versioning

Prefer additive evolution and stable contracts.

Accepted first-slice policy:
- URL major version `/v1`.
- additive compatible evolution within v1.
- never repurpose an existing field's meaning.
- persisted/offline payloads carry explicit payloadVersion where compatibility requires.
- incompatible native/public contract requires explicit major/migration path.

Do not version internal module methods like public internet APIs unnecessarily.

---

# 17. Date / Time

Transport:
ISO-8601 / UTC instants for events.

Business local dates:
explicit LocalDate semantics.

Timezone:
IANA identifier when local schedule meaning matters.

Never infer timezone from server location.

---

# 18. Money

Transport:
- amount as decimal string or exact decimal representation.
- currency ISO 4217.

Never float.

---

# 19. IDs

Opaque stable identifiers.
Physical domain/config IDs use UUID.
Human codes remain separate/configurable.

Example:
id = UUID
projectCode = PRJ-2026-0012

Clients do not infer authorization/ordering from ID.

---

# 20. Query Models

Response models should be task/surface appropriate.

Do not send full Project aggregate to every list.

Examples:
ProjectListItem
ProjectExecutiveSummary
ProjectDetail
TechnicianJobBundle
AssetPassport
PayrollApprovalView

Query/read model may aggregate authorized fields from multiple modules through explicit query layer/projection.

---

# 21. Command Response

Return:
- result state
- target current version
- relevant updated object/read model
- emitted action IDs if useful
- pending external state where applicable

Avoid forcing immediate full-page refetch if safe result can update local DB.

---

# 22. Observability

Every API request logs/traces:
- correlation ID
- route/command
- latency
- result code
- module
without sensitive payload.

## Freeze Gate

Validated:
- [x] native auth architecture — Keycloak/OIDC PKCE.
- [x] shared client transport — Ktor Client 3.5.2.
- [x] Android/Windows shared DTO/header/result path.
- [x] bearer-token attachment.
- [x] Idempotency-Key propagation.
- [x] explicit baseVersion conflict path.
- [x] X-Correlation-Id + W3C traceparent.
- [x] direct binary upload/finalization pattern.
- [x] offline replay / duplicate replay / stale conflict.

Still required:
- [x] first-slice endpoint + request/response candidates.
- [ ] exact Kotlinx Serialization code option flags.
- [ ] API schema publication/OpenAPI convention.
- [x] additive /v1 compatibility policy.
- [x] opaque stateless cursor contract.
- [x] thin manual shared typed-client convention.
- [ ] REAUTH_REQUIRED representative contract test.
- [ ] production-shaped retryable/safe 4xx/5xx contract tests.
- [ ] contract tests for final frozen first-slice schemas.

Canonical implementation-facing detail:
`CROSS_CUTTING_BUILD_CONTRACTS.md`.
