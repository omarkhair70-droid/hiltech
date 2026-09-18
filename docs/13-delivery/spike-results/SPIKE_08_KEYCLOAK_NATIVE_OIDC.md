# SPIKE-08 Result — Keycloak Native OIDC

Date: 2026-09-18
Decision: **ACCEPT — NATIVE OIDC IDENTITY/SESSION ARCHITECTURE PASSED**

## Environment

- Keycloak 26.7.4
- HTTPS with disposable CI certificate
- Playwright / real Chromium for Windows system-browser behavior
- public OIDC native client

GitHub Actions run: 35307236838.

## Proven

### Native client security
- client is public.
- Authorization Code flow enabled.
- PKCE S256 supported and used.
- Direct Access Grant/password grant disabled for the HILTECH native client.
- no client secret is required in Android/Windows binaries.

### Android-style redirect
Private-use redirect:
`com.hiltech.app:/oauth2redirect`

Authorization → login → code → PKCE token exchange passed.

### Windows desktop redirect
Loopback redirect:
`http://127.0.0.1:<port>/callback`

A real Chromium browser completed the authorization and delivered the code to a local loopback HTTP listener.

### SSO + reauthentication
In the same browser context:
- second authorization reused the Keycloak SSO session.
- `prompt=login` stopped automatic reuse and required interactive credentials.
- re-auth produced a fresh authorization code and successful PKCE exchange.

### Session lifecycle
- refresh token flow passed.
- explicit logout invalidated the refresh token.
- remote admin session revoke invalidated refresh capability.

### Passkey path
Keycloak exposed:
- `webauthn-register`
- `webauthn-register-passwordless`

This proves path availability, not a physical-device passkey ceremony.

## Important Architecture Finding

HILTECH offline business capability is **not** tied to OIDC `offline_access`.

Separation:
- Room/local command queue preserves disconnected work.
- normal OIDC session/refresh handles identity lifetime.
- reconnect revalidates identity/authorization before authoritative replay.
- long-lived Keycloak offline tokens are not the baseline.

## Accepted Boundary

Keycloak:
- authentication,
- browser/native OIDC session,
- refresh/logout/session revoke,
- MFA/passkey capability.

OpenFGA:
- object/action relationship authorization.

HILTECH server:
- field-level filtering,
- workflow prerequisites,
- re-auth/MFA obligations,
- amount/version policies,
- offline replay authorization checks.

## Still Open

- physical Android Custom Tabs integration.
- real Windows app loopback listener integration.
- production Keycloak PostgreSQL/HA/backups.
- final MFA/passkey enrollment UX.
- federation if HILTECH later needs Microsoft/Google/LDAP.

## Production Status

Disposable identity evidence only.
