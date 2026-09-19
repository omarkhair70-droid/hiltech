# Phase 1 / Slice 02 — Native OIDC Session Runtime

Date: 2026-09-19
Status: **IMPLEMENTING**

## Goal

Productionize the accepted SPIKE-08 native identity architecture for Android + Windows without embedding a password form inside HILTECH.

Target flow:

`System browser → Authorization Code + PKCE S256 → native callback → token exchange → refresh/session runtime → Phase 1 identity bootstrap → permission-safe shell`

## Frozen security properties

- public client: `hiltech-native`
- Authorization Code flow
- PKCE S256
- no client secret in native binaries
- no Direct Access Grant / password grant
- no default `offline_access`
- Android private-use redirect: `com.hiltech.app:/oauth2redirect`
- Windows loopback redirect: `http://127.0.0.1:<port>/callback`
- system browser, not embedded credential UI
- refresh is normal OIDC session continuity
- logout clears local session even when provider logout fails
- reconnect revalidates HILTECH identity/authority before authoritative offline replay

## Commit 01 — shared protocol/session core

Shared KMP runtime implements:
- OIDC discovery
- exact issuer validation
- authorization-code capability validation
- PKCE S256 capability validation
- secure-random state / nonce / verifier
- authorization URL construction
- callback state validation before code exchange
- code exchange
- refresh
- logout
- expiring access-token refresh
- token-store abstraction

The baseline scopes include `openid` and explicitly reject `offline_access`.

## Remaining in this slice

1. contract tests for the shared runtime,
2. Android system-browser launch + private-use callback,
3. Windows system-browser + loopback listener,
4. platform session storage boundary,
5. wire tokens into Android sync runtime,
6. register/touch device + call `/v1/me/bootstrap`,
7. replace the Phase 0 placeholder shell with signed-out / signing-in / loading / access-denied / signed-in states,
8. rendered/CI verification.
