# SPIKE-08 — Keycloak Native OIDC

Status: RUNNING

## Candidate

Keycloak 26.7.4.

## Goal

Prove HILTECH can use standards-based native authentication on Android + Windows without embedding a password form inside the app.

## Native client model

One public OIDC client:
- client_id: hiltech-native
- Authorization Code flow enabled.
- PKCE S256 required.
- Direct Access Grant / password grant disabled.
- no client secret in Android or Windows binary.

Redirects under test:
- Android-style private-use URI: `com.hiltech.app:/oauth2redirect`
- Windows loopback URI: `http://127.0.0.1:<port>/callback`

## Scenarios

The CI test boots a real Keycloak server and proves:

1. OIDC discovery advertises authorization code + S256 PKCE.
2. Android-style browser authorization returns a code to the registered custom redirect.
3. code exchange succeeds only with the PKCE verifier.
4. access token belongs to the expected user/client.
5. refresh token works.
6. explicit logout invalidates subsequent refresh.
7. Windows loopback redirect completes the same native flow.
8. admin remote session revoke invalidates refresh capability.
9. an existing SSO browser session can skip login.
10. `prompt=login` forces a re-auth interaction.
11. WebAuthn registration required action exists.
12. WebAuthn passwordless registration required action exists.
13. native client remains public and direct password grant remains disabled.

## Security boundary

The test uses the Keycloak `admin-cli` password grant only for CI administration/bootstrap.

The HILTECH native client itself never uses Resource Owner Password Credentials / Direct Access Grant.

## Pass

ACCEPT if all scenarios above pass against real Keycloak 26.7.4.

## Not proven here

- real Android Custom Tabs integration.
- real Windows system browser + local HTTP callback listener.
- biometric/passkey ceremony on physical hardware.
- production HA/database/backup.
- federation with HILTECH Microsoft/Google/LDAP if later needed.
- step-up policy UX for specific finance actions.

Those remain implementation/integration work after auth architecture is accepted.

## Production status

Disposable identity proof only.
Not production identity infrastructure.
