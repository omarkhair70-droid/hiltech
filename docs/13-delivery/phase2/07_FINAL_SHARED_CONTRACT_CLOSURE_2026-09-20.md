# Phase 2 / Slice 07 — Final Shared Contract Closure

Date: 2026-09-20  
Status: **IMPLEMENTATION AUTHORIZED / TECHNICAL CONTRACT FROZEN**

## Why this slice exists

After Slice 06 merged and post-merge Bootstrap passed, the remaining Phase 2 master-order items were reviewed against production code rather than treated as four new feature slices.

The review found:
- **Error Model** — materially implemented by Slice 01 and inherited tests.
- **API Conventions** — materially implemented by Slice 01 plus later real endpoints.
- **IDs / versioning** — materially implemented by command/idempotency/version contracts and later domain foundations.
- **Read-model / query foundation** — materially proven by Activity and Inbox cursor/query implementations.

Two cross-cutting verification gaps remain explicit in the frozen API contracts:

1. generated OpenAPI publication / drift detection;
2. REAUTH_REQUIRED retry of the same semantic command using the same operation/idempotency identity.

This slice closes only those two gaps.

## Canonical sources

- `docs/13-delivery/IMPLEMENTATION_ORDER.md`
- `docs/11-architecture/API_CONVENTIONS.md`
- `docs/11-architecture/ERROR_MODEL.md`
- `docs/11-architecture/ID_VERSIONING_CONVENTIONS.md`
- `docs/11-architecture/READ_MODEL_ARCHITECTURE.md`
- `docs/11-architecture/CROSS_CUTTING_BUILD_CONTRACTS.md`
- `docs/13-delivery/first-slice-contract-pack/02_API_AND_READ_MODELS.md`
- `docs/13-delivery/phase2/01_SHARED_HTTP_COMMAND_RUNTIME_SLICE_2026-09-19.md`

## 1. OpenAPI publication contract

The server must expose generated OpenAPI for production Spring MVC controllers.

Implementation:
- use the Spring Boot 4 compatible `springdoc-openapi` WebMVC API module only;
- do not add Swagger UI to production scope;
- generate from production controller / DTO mappings;
- normalize output deterministically for review;
- commit `contracts/http/hiltech-v1.openapi.json`;
- CI regenerates and fails when the committed snapshot drifts;
- manual editing of the generated snapshot is forbidden.

The snapshot is a compatibility/review artifact. It does not become the domain source of truth.

Minimum route coverage includes the currently shipped `/v1` controller surfaces:
- identity/bootstrap/device/session/reauth;
- evidence;
- activity;
- approvals;
- work queue;
- inbox.

## 2. REAUTH_REQUIRED same-operation retry

The shared HTTP client may expose an explicit reauthentication-aware request helper.

Rules:
- it is opt-in; normal `request` behavior remains unchanged;
- first `REAUTH_REQUIRED` invokes a caller-supplied reauthentication callback;
- the request is retried **once only**;
- the retry reuses the exact request method/path/body/options;
- therefore the same `Idempotency-Key` / operation identity is preserved;
- the access-token provider is called again after reauthentication so the retry can use fresh credentials;
- a second `REAUTH_REQUIRED` is returned to the caller; no loop;
- non-read/state-changing requests without an idempotency key must not be auto-replayed by this helper;
- cancellation is never swallowed;
- no password is collected by HILTECH.

HTTP fallback mapping must recognize status 428 as `REAUTH_REQUIRED` even if a malformed/missing body prevents envelope decoding.

## 3. Serialization closure

The shared production JSON client configuration is frozen as:
- `ignoreUnknownKeys = true`;
- `explicitNulls = false`.

This preserves additive `/v1` compatibility.

Do not introduce a second conflicting JSON wire configuration in this slice.

## Explicit non-goals

Do not implement:
- new business endpoints;
- generated network clients;
- Swagger UI;
- API gateway;
- GraphQL;
- generic CRUD;
- new cursor system;
- new ID format;
- new error taxonomy;
- automatic reauth on every request without caller intent;
- retry loops/backoff framework;
- Phase 3 People features.

## Required evidence for VERIFIED

1. Spring Boot 4 compatible springdoc API dependency is pinned.
2. production controller routes generate OpenAPI successfully.
3. normalized OpenAPI snapshot is committed at `contracts/http/hiltech-v1.openapi.json`.
4. CI regeneration matches the committed snapshot exactly.
5. snapshot contains representative current `/v1` routes for identity, evidence, activity, approvals, work queue and inbox.
6. no Swagger UI dependency/surface is required.
7. shared client REAUTH_REQUIRED helper retries at most once.
8. same idempotency key is observed before and after reauth.
9. fresh access token is observed on retry.
10. a second REAUTH_REQUIRED does not loop.
11. state-changing request without idempotency identity is not auto-replayed.
12. malformed 428 fallback maps to REAUTH_REQUIRED.
13. existing safe 4xx/5xx and network retryability tests remain green.
14. Activity/Inbox cursor regressions remain green.
15. Slice 01–06 inherited regressions remain green.
16. Phase 1 OIDC production smoke remains green.
17. Bootstrap Phase 0 is green on the exact closure head.

## Closure rule

After exact-head verification:
- mark Slice 07 VERIFIED;
- merge exact tested head;
- run post-merge Bootstrap on `main`;
- run a final Phase 2 checklist against the master order.

If all master-order shared capabilities are satisfied by evidence, mark **Phase 2 VERIFIED / COMPLETE** and move next to Phase 3 contract closure rather than inventing another shared slice.

## Contract conclusion

**IMPLEMENTATION AUTHORIZED for the final shared contract closure only.**
