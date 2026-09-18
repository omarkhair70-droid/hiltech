# 30 — Bootstrap Ktor Authenticated Command Transport

Date: 2026-09-19
Status: **IMPLEMENTED / CI VERIFICATION PENDING**

## Purpose

Implement the frozen native command transport boundary for durable replay without allowing generic CRUD or pushing authorization truth into the client.

Canonical source:
- `02_API_AND_READ_MODELS.md`
- `04_ROOM_OFFLINE_SYNC.md`
- `05_AUTHORIZATION_POLICY_TESTS.md`
- ADR-007 / ADR-008 / ADR-011.

## First offline route allow-list

The bootstrap transport sends only explicitly frozen offline-capable commands:

- `StartWork` → `POST /v1/work-orders/{id}/start`
- `BlockWork` → `POST /v1/work-orders/{id}/block`
- `ResumeWork` → `POST /v1/work-orders/{id}/resume`
- `SubmitWorkCompletion` → `POST /v1/work-orders/{id}/submit-completion`
- `ReportAssetDamage` → `POST /v1/assets/{id}/report-damage`
- `FinalizeEvidence` → `POST /v1/evidence/{id}/finalize`

Unknown command types fail locally as:
`UNSUPPORTED_OFFLINE_COMMAND`

They are never converted into generic PATCH/CRUD.

## Request contract

Every request carries:
- `Authorization: Bearer ...`
- `Idempotency-Key: operationId`
- `X-Correlation-Id`
- optional W3C `traceparent`
- `X-Client-Platform`
- `X-Client-Version`
- `X-Device-Installation-Id`

The durable queue remains the source of:
- operationId,
- baseVersion,
- clientOccurredAt,
- payload.

Transport merges these into the typed command JSON at replay time.

Client metadata is diagnostic/compatibility metadata only.
It is not trusted authorization identity.

## Response mapping

2xx:
- `Applied`
- preserves returned version/correlation
- honors server `duplicateReplay`.

409 with typed conflict:
- `Conflict`
- preserves safe current state + recovery actions.

429 / 5xx / retryable envelope:
- `Retryable`
- honors integer-seconds `Retry-After`.

401 `REAUTH_REQUIRED` and other non-retryable authorization/validation failures:
- stop automatic replay as terminal queue result,
- preserve original command/payload,
- explicit auth/user recovery can requeue the same semantic operationId later.

This avoids a hot-loop with an expired/insufficient token.

## Tests

Bootstrap contract tests verify:
- exact route,
- bearer/idempotency/correlation/native headers,
- command metadata merge,
- applied mapping,
- typed conflict mapping,
- retry-after mapping,
- reauth does not auto-retry,
- unknown command never reaches network.

## Deliberately not wired yet

The Android worker still does not construct production auth/runtime dependencies.

Next gate:
- Android runtime composition,
- token/session provider backed by Keycloak/OIDC integration boundary,
- installation-id/correlation/trace providers,
- replay result → WorkManager result mapping,
- explicit reauth notification/recovery hook.
