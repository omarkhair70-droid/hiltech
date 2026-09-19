# Phase 1 / Slice 02 — Native OIDC Session Runtime

Date: 2026-09-19
Status: **VERIFIED**

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

## Implementation / verification state

Implemented:
1. shared OIDC protocol/session contract tests,
2. Android system-browser launch + private-use callback,
3. Windows system-browser + loopback listener,
4. platform session storage boundaries,
5. Android sync token wiring,
6. device register/touch + `/v1/me/bootstrap`,
7. signed-out / working / access-denied / signed-in permission-safe shell states.

CI evidence:
- GitHub Actions run `35418927238` — **PASS**,
- tested head: `2c0a29dadbbe2ac2d1670b66bad29e27e82dfcd7`,
- shared tests: PASS,
- Android debug build: PASS,
- Desktop compile: PASS,
- Server tests: PASS,
- database / local-platform / evidence / dependency / Terraform / supply-chain contracts: PASS.

Verification closure:
- GitHub Actions run `35420251296` — **PASS** on head `9985065b633ff86bd1f8f9c831c1c2ef1609f3aa`.
- Keycloak 26.7.4 HTTPS provider smoke: PASS.
- Android private-use redirect through the production OIDC session core: PASS.
- Windows loopback redirect through real Chromium and the production OIDC session core: PASS.
- production Android APK callback routing + fail-closed callback state: PASS on API 36 emulator.
- production Android shell rendered evidence: PASS.
- production Desktop shell rendered evidence: PASS.
- rendered evidence artifacts were uploaded by the workflow.

Slice 02 is therefore VERIFIED.


## Android production wiring

Android now:
- launches Authorization Code + PKCE through the system browser,
- receives only `com.hiltech.app:/oauth2redirect`,
- stores refresh/session data and pending PKCE attempt encrypted through Android Keystore AES-GCM,
- registers/touches the installation before identity bootstrap,
- feeds the same refreshed access token into WorkManager offline replay,
- renders signed-out / working / access-denied / signed-in identity-aware shell states.

Runtime configuration:
- `hiltech.apiBaseUrl`
- `hiltech.oidcIssuerUri`
- `hiltech.oidcClientId` (default `hiltech-native`)

Blank API/OIDC configuration fails closed in the shell.


## Windows production wiring

Windows Desktop now:
- binds an ephemeral callback listener explicitly to `127.0.0.1`,
- creates `http://127.0.0.1:<port>/callback` per sign-in attempt,
- opens the authorization URL in the system browser,
- returns a minimal local completion page,
- validates state/code through the shared PKCE runtime,
- registers/touches the Windows installation before identity bootstrap,
- intentionally keeps OIDC tokens in memory only.

The Desktop client does not write refresh tokens to Preferences or other plaintext local storage. After process restart, the user re-enters the browser flow; Keycloak browser SSO can complete without embedding credentials in HILTECH.

Blank API/OIDC configuration is represented as a fail-closed shell state on both Android and Windows.
