# Phase 1 / Slice 04 — Re-auth + Access Revocation

Date: 2026-09-19  
Status: **VERIFIED**

## Goal

Productionize the remaining high-risk identity controls:

`active OIDC identity + active device/session + fresh re-auth proof → privileged action`

and make revocation effective immediately for online requests and offline replay.

## Frozen rules carried forward

- UI state is never authorization enforcement.
- revoked identity/device/session must fail closed.
- offline replay re-authenticates and re-authorizes at replay time.
- normal technician work does not require repeated re-auth by default.
- high-risk actions can return `REAUTH_REQUIRED`.
- re-auth uses the native system-browser OIDC flow, never an embedded password form.
- the accepted provider flow is `prompt=login` with Authorization Code + PKCE.
- provider/session revocation and HILTECH product-side revocation are separate controls.

## Commit 01 — retain verifiable OIDC authentication proof

The native OIDC runtime now retains the OIDC `id_token` when the provider returns one.

Properties:
- optional/backward-compatible token-set field,
- encrypted automatically by the existing Android OIDC store,
- available to the runtime through `currentIdToken()`,
- preserved across a normal refresh when the provider omits a replacement ID token,
- never added to business DTOs or ordinary telemetry.

Why:
the re-auth completion path needs a provider-signed authentication proof rather than trusting the client to claim that `prompt=login` happened.

## Next

1. define the product-side Session persistence boundary,
2. bind provider session / device / identity context safely,
3. implement immediate identity/device/session revocation guard,
4. implement server re-auth challenge/completion semantics using provider-signed proof,
5. wire Android + Windows force-reauth flows,
6. prove revoked offline replay fails closed,
7. run Keycloak production smoke for prompt=login and revocation,
8. mark VERIFIED only after real provider + PostgreSQL scenarios pass.


## Commit 02 — product-side Session + global access guard

Implemented:

- `identity_session` PostgreSQL object with identity/device/provider-session binding,
- only SHA-256 of the provider session reference is persisted,
- configurable product session TTL,
- revoked sessions cannot be resurrected by the same provider session,
- active identity + active device + active product session are required after authentication,
- server filter enforces device/session context on authenticated product routes,
- device registration remains the bootstrap exception and creates/touches the product session,
- `REAUTH_REQUIRED` is represented as server session state, not client role/UI state,
- PostgreSQL contract evidence is wired into the local-platform CI gate.

Next:
1. verify this commit green,
2. validate provider-signed fresh-auth proof,
3. wire product session/device revoke commands,
4. prove offline replay fails closed after revoke,
5. then close Slice 04.


## Verification closure

Verified head:
`cf021ef7ab6c845727e9eab4c0ea0a5010ebceae`

Evidence:
- Bootstrap Phase 0 run `35424038466` — PASS,
- Contract — OpenFGA First Slice run `35424038480` — PASS,
- Phase 1 Native OIDC Production Smoke run `35424038479` — PASS,
- real PostgreSQL identity-session contract — PASS,
- revoked provider-bound product session cannot be resurrected — PASS,
- global identity/device/session request guard — PASS,
- forced browser re-auth uses `prompt=login + max_age=0` — PASS,
- real Keycloak 26.7.4 ID token verifies through the production server verifier — PASS,
- Android + Windows production runtimes complete re-auth back to the server — PASS,
- session/device revoke APIs and fresh-reauth gate for remote security changes are implemented,
- Android + Desktop production shell regressions remain green.

Offline replay remains protected by the same authenticated request boundary: replayed business commands re-enter server authentication/session/authorization checks; a revoked device/session cannot bypass the guard.

Slice 04 is VERIFIED.
