# ADR-008 — Authentication / Native OIDC

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH needs one identity system across Android and Windows without embedding passwords inside native apps.

Requirements include:
- standards-based system-browser login,
- session refresh/logout,
- remote session revoke,
- reauthentication for sensitive actions,
- future passkey/WebAuthn path.

## Decision

Use **Keycloak + OpenID Connect Authorization Code with PKCE** as the primary HILTECH identity direction.

Native client rules:
- public client,
- PKCE S256,
- no client secret in app binaries,
- no Direct Access Grant/password grant,
- Android uses a registered private-use redirect,
- Windows uses loopback redirect through system browser,
- normal refresh/session semantics.

Keycloak `offline_access` tokens are not the default mechanism for offline HILTECH work.

## Evidence

SPIKE-08 / GitHub Actions run 35307236838 on Keycloak 26.7.4.

Passed:
- Android private-use redirect + PKCE.
- Windows loopback redirect through real Chromium.
- browser SSO reuse.
- prompt=login credential reauthentication.
- refresh.
- logout invalidation.
- remote session revoke.
- WebAuthn/passwordless registration path.
- native password grant disabled.

## Separation of Concerns

Disconnected business work:
- Room/local command queue,
- later authoritative replay.

Identity:
- Keycloak OIDC session/refresh.

Authorization:
- OpenFGA object/action relationships,
- server-side field/workflow/security obligations.

## Consequences

Positive:
- no embedded-password native anti-pattern.
- standards-based browser flow on both target platforms.
- centralized session revoke.
- credible MFA/passkey evolution path.

Costs:
- Keycloak becomes security-critical infrastructure.
- production HA/database/backup/upgrade procedures are mandatory.
- redirect registration and loopback listener hardening require careful implementation.

## Revisit Triggers

Revisit if:
- production identity operations become disproportionate,
- required enterprise federation cannot be met,
- native-browser integration reveals a platform blocker,
- organization chooses a managed IdP with materially better operational fit.
