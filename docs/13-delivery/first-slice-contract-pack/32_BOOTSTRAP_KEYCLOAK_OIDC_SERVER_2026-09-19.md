# 32 — Bootstrap Keycloak / OIDC Server Adapter

Date: 2026-09-19
Status: **PASS / BOOTSTRAP VERIFIED**

## Purpose

Materialize the accepted Keycloak/OIDC identity boundary on the production Spring Boot server without embedding secrets or turning token claims into business authorization.

Canonical source:
- ADR-008 — Keycloak Native OIDC,
- `05_AUTHORIZATION_POLICY_TESTS.md`,
- accepted SPIKE-08 / SPIKE-15.

## Server security posture

Default bootstrap behavior is fail-closed.

When:
`hiltech.identity.oidc.enabled=false`

the server:
- permits only Actuator health,
- denies every other HTTP request,
- disables form login,
- disables HTTP Basic,
- uses stateless security,
- does not create a generated-password fallback.

When OIDC is enabled:
- issuer URI is mandatory,
- JWT signature/time/issuer validation is installed,
- all non-health endpoints require an authenticated bearer token.

Optional audience validation is supported through:
`HILTECH_OIDC_AUDIENCE`

Audience is intentionally not invented as a mandatory first-slice contract because the frozen API/ADR requires a bearer OIDC access token but does not freeze an `aud` value.

## Configuration

Environment-backed properties:

- `HILTECH_OIDC_ENABLED`
- `HILTECH_OIDC_ISSUER_URI`
- `HILTECH_OIDC_AUDIENCE` (optional)

No Keycloak client secret is present in server/native bootstrap code.

## Product identity boundary

`OidcSubjectResolver` extracts only:
- issuer,
- subject (`sub`).

That pair is the external identity key used to resolve HILTECH `user_identity`.

Role/title claims are deliberately not treated as business authorization.

Authorization remains:
1. authenticated subject/session,
2. HILTECH identity/device/org context,
3. OpenFGA relationship/action,
4. domain/application obligations.

## Native client contract remains

The accepted native model is unchanged:
- Authorization Code,
- PKCE S256,
- public client,
- system browser,
- no password grant,
- no client secret in app binaries,
- normal refresh/session semantics,
- `offline_access` is not the default offline-work mechanism.

The Android secure token/session implementation is still a separate bootstrap gate; this server adapter does not fake it.

## Verification

Unit tests prove:
- disabled bootstrap does not require a production issuer,
- enabled OIDC cannot exist without an issuer,
- audience is optional unless explicitly configured,
- identity resolution is issuer + subject,
- missing subject fails closed.

CI verification completed successfully.

Run `35407526674` (#22) proved:
- Shared tests PASS,
- committed Room schema PASS,
- Android debug build PASS,
- Desktop compile PASS,
- Server tests PASS,
- PostgreSQL migrations/constraints PASS,
- jOOQ generation/verification PASS,
- server compile against generated jOOQ PASS.

## Next

- Android secure OIDC session/token adapter,
- OpenFGA HTTP adapter with pinned store/model IDs,
- authorization projection/outbox worker.
