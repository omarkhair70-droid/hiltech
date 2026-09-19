# Phase 2 / Slice 01 — Shared HTTP / Command Runtime

Date: 2026-09-19  
Status: **CONTRACTED / IMPLEMENTATION AUTHORIZED**

## Why this is first

Phase 2 contains several shared capabilities, but they are not equally implementation-ready.

The evidence/object-storage protocol is structurally closed and its S3-compatible adapter is already verified. However, an authoritative Evidence API must authorize against the real target object/action. Project/Work target authority belongs to later business-domain phases and must not be replaced with a broad organization-member shortcut.

Approval and notification models are still explicitly research/domain-model candidates and are not technical-schema frozen.

The cross-cutting HTTP/command runtime has no later-domain dependency and is already spike-validated. Closing it first gives every later Phase 2/3+ vertical one production-grade request, idempotency, error and client boundary.

## Canonical sources

Read before implementation:
- `AGENTS.md`
- `docs/00-program/CURRENT_PROGRAM_STATUS.md`
- `docs/13-delivery/IMPLEMENTATION_ORDER.md`
- `docs/11-architecture/CROSS_CUTTING_BUILD_CONTRACTS.md`
- `docs/11-architecture/API_CONVENTIONS.md`
- `docs/11-architecture/ERROR_MODEL.md`
- `docs/11-architecture/ID_VERSIONING_CONVENTIONS.md`
- `docs/12-stack/FINAL_STACK.md`

Existing production foundations to reuse:
- `shared/core/.../network/ApiContracts.kt`
- `shared/core/.../network/HiltechHttpClient.kt`
- `database/migrations/V0002__platform__foundations.sql` → `idempotent_operation`
- Phase 1 active identity/device/session request guard
- Phase 1 audit writer
- OpenTelemetry safe telemetry runtime
- shared Ktor Android/Windows transport

Do not duplicate these foundations.

## Scope

### 1. Canonical request context

Create one server request-context boundary that normalizes:
- authenticated identity/session context where applicable,
- `X-Correlation-Id`,
- W3C trace context,
- registered device context where applicable,
- `Idempotency-Key` for retry-sensitive commands.

Rules:
- correlation IDs are safe bounded values,
- missing correlation ID is generated server-side,
- roles/permission claims are never trusted from client headers,
- no raw access token or sensitive payload enters logs/telemetry.

### 2. Standard product error envelope

Promote the existing shared `ErrorEnvelope` into the canonical server/client wire contract.

Representative families to prove:
- malformed/validation 400,
- unauthenticated 401,
- permission denied 403,
- hidden object 404,
- version/state conflict 409,
- domain obligation/validation 422,
- rate limit 429 where applicable,
- safe internal failure 500,
- retryable dependency/service unavailable 503,
- `REAUTH_REQUIRED` using the already-verified Phase 1 re-auth flow.

Requirements:
- stable machine code,
- safe fallback message,
- correlation ID,
- retryable flag,
- optional safe target/details/conflict payload,
- no stack trace, SQL/provider text, token, secret or sensitive business payload.

Do not keep identity-only error envelopes as a parallel long-term convention.

### 3. Idempotent command execution

Implement a reusable server boundary over the existing `idempotent_operation` table.

Conceptual binding:
- operationId / Idempotency-Key,
- actor identity,
- command type,
- target type/id where present,
- request fingerprint,
- state,
- semantic result,
- correlation ID.

Required behavior:
- first submission executes once,
- same actor + same operation + same semantic request returns the prior semantic result,
- materially different request using the same operation ID is rejected,
- another actor cannot reuse an operation ID owned by another actor,
- a timeout/retry cannot duplicate the business side effect,
- unfinished/failed records have explicit safe behavior; never silently re-run an ambiguous applied effect,
- storage/result payload is safe and bounded.

Do not invent a second idempotency table.

### 4. Shared typed client HTTP boundary

Create/reuse a thin shared Ktor request layer used by Android and Windows.

It owns, as applicable:
- bearer token attachment,
- device installation context,
- correlation ID,
- idempotency header,
- JSON serialization,
- canonical error decoding,
- retryable vs terminal classification.

Engine-specific OkHttp/CIO APIs must not leak above the platform client factory.

Existing Phase 1 identity client behavior may be migrated to the shared boundary only if regressions remain green. Avoid a large unrelated rewrite.

### 5. Observability

Every representative command path must preserve safe dimensions:
- correlationId,
- operationId,
- command type,
- object type when safe,
- result/error code,
- module.

No command payload, ID token, access token, evidence bytes, salary/bank/private data in telemetry.

## Deliberate non-scope

Not in Slice 01:
- Evidence business reserve/finalize endpoints,
- Project/Work/Asset business commands,
- approval engine,
- Inbox,
- push/email/SMS providers,
- search,
- generic CRUD framework,
- Redis,
- new microservice,
- new API gateway,
- new auth architecture.

## Phase 2 ordering after this slice

Working order, subject to each slice's own contract check:

1. Shared HTTP / Command Runtime — **this slice**.
2. Evidence Metadata + Upload/Finalize lifecycle.
   - reuse V0007 evidence tables,
   - reuse verified object-storage adapter,
   - require target-specific authorization port,
   - fail closed for unsupported target types,
   - never broaden authority merely to make the shared foundation callable.
3. Activity event / audit shared projection closure.
4. Approval engine only after its policy/reality gaps are closed.
5. Inbox / Work Queue.
6. Notification abstraction after channel/urgency/privacy policy closure.
7. Read-model/cursor foundation may be pulled earlier when a real production-shaped query needs it.

This ordering does not rewrite `IMPLEMENTATION_ORDER.md`; it is the Phase 2 vertical-slice decomposition derived from current contract readiness.

## Required tests / evidence

Minimum closure:
- real PostgreSQL idempotency contract,
- concurrent duplicate command proves one side effect,
- exact duplicate returns prior semantic result,
- same operation ID + changed fingerprint rejects,
- cross-actor operation reuse rejects,
- safe 400/401/403/404/409/422/500/503 envelopes,
- correlation ID preserved server ↔ shared client,
- retryable mapping proved through production-shaped Ktor HTTP,
- re-auth-required mapping does not break Phase 1 flow,
- Android shared-client compile/tests,
- Windows shared-client compile/tests,
- server regression,
- Phase 1 regression gates remain green,
- telemetry hygiene assertion.

## Verification gate

Mark Slice 01 VERIFIED only when:
1. the exact tested head is green,
2. real PostgreSQL idempotency evidence passes,
3. shared Android/Windows client contract passes,
4. representative error/retry behavior passes,
5. Phase 1 auth/session/OpenFGA regressions remain green,
6. status/evidence docs identify the exact canonical run/head.

Do not merge the final Phase 2 PR merely because this first slice is verified unless the intended PR scope is explicitly closed.
