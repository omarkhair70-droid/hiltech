# HILTECH unified staging

The integration source is PR [#242](https://github.com/omarkhair70-droid/hiltech/pull/242). A staging candidate consists of the exact source commit, the published backend digest and both native build artifacts. Use the existing HILTECH staging resources. The Compose files describe a separate reference topology.

## Build the native clients

Use JDK 21, the installed Android SDK and a Windows host. Set the two HTTPS endpoints from the same staging environment.

```powershell
.\gradlew.bat :apps:desktopApp:packageMsi :apps:androidApp:assembleDebug `
  "-Philtech.apiBaseUrl=$apiUrl" `
  "-Philtech.oidcIssuerUri=$issuerUri"
```

The MSI includes its connection settings. An environment variable already set for the Windows process takes precedence over the packaged value. The Android APK includes the same endpoints and uses the local debug signer, suitable for staging and developer devices. Updates to an existing Android installation require its signing certificate. Public distribution requires the release signing keys.

## Configure the operations profile

Set `SPRING_PROFILES_ACTIVE=operations` in the HILTECH API environment. The runtime profile provides defaults for assigned AssetTag lookup, personal issued stock, material usage reporting, completion and Executive Home. The authenticated server capabilities determine the corresponding native entry points. The server APIs still enforce current organization, role, assignment and transaction checks.

Omit existing per-feature overrides to inherit the profile defaults. An explicit `HILTECH_FIELD_*_ENABLED=false` remains effective and can be used for rollback of an individual workflow. Keep database, OIDC, OpenFGA and evidence settings in the private HILTECH API environment. The product profile needs no `SPRING_APPLICATION_JSON`.

For Executive Home, issued stock, material usage reporting, assigned AssetTag lookup, and engineering tests, updated clients read availability from the authenticated session bootstrap. Set those capabilities on the server and reopen the client session after deployment. Clients use their existing build configuration against servers that predate the capability response.

## Employee account setup

Keep the native OIDC issuer on the HTTPS gateway. Configure the API's account-setup service client with a private `HILTECH_KEYCLOAK_ADMIN_BASE_URI` when the gateway only routes public sign-in. Use the Keycloak service's private network alias and port for an internal HTTP connection. Store the service client identifier and secret in Coolify, scoped to managing users in the HILTECH staging realm.

Enable `HILTECH_KEYCLOAK_PROVISIONING_ENABLED` after testing the service client on that connection. Keep email delivery off until the realm's setup email is functional. The in-person handoff uses the existing recent-authentication check and displays the one-time password in the employee's account panel.

## Update and verify staging

1. Build the candidate through the unified staging workflow. Record the commit, SHA-256 and image digest returned by the workflow.
2. Compare migration checksums and establish a recoverable PostgreSQL backup for the staging deployment. Retain the currently deployed image digest and runtime configuration for rollback.
3. Update the immutable image of the existing HILTECH API and use the existing HTTPS gateway. Deploy only that application. Verify liveness, readiness, OIDC discovery and an actual signed-in native session.
4. Run at least one allowed and one denied operation under the appropriate staging roles. Verify file access and the status of the authorization projector with a real source-owned operation.
5. If the candidate fails, restore the previous API image and environment. If migrations changed, follow the database recovery procedure before rolling back application code.

The native staging packages and the operations profile have independent acceptance. Record device and role evidence in the [company acceptance register](HILTECH_COMPANY_END_TO_END_ACCEPTANCE_20261123.md).
