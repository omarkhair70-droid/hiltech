# 31 — Bootstrap Android Sync Runtime Wiring

Date: 2026-09-19
Status: **IMPLEMENTED / CI VERIFICATION PENDING**

## Purpose

Wire the already-verified Room queue, replay engine and Ktor transport into the Android process without inventing a fake authentication implementation.

Canonical source:
- `04_ROOM_OFFLINE_SYNC.md`
- ADR-007 / ADR-008 / ADR-011
- Bootstrap gates 28–30.

## Process composition

`HiltechApplication` owns app-scoped:
- Room database,
- Ktor Android/OkHttp client,
- stable installation ID,
- Android sync runtime.

The installation ID is app-private device metadata, not an authentication secret.

The Android manifest now declares:
- `android.permission.INTERNET`
- `HiltechApplication` as the process Application.

## API endpoint configuration

The Android build exposes:
`BuildConfig.HILTECH_API_BASE_URL`

It is sourced from the Gradle property:
`hiltech.apiBaseUrl`

There is no hardcoded production host in bootstrap code.
Blank configuration fails closed as:
`SYNC_RUNTIME_NOT_CONFIGURED`.

## Authentication boundary

Bootstrap intentionally does **not** store tokens in Room or ordinary SharedPreferences.

`AndroidSessionTokenProvider` is the runtime boundary for the next Keycloak/OIDC gate.

The temporary bootstrap provider returns no token.
Therefore the worker:
- does not send anonymous requests,
- does not consume/mutate queued intent,
- returns `REAUTH_REQUIRED` until the Keycloak secure-session adapter is installed.

This matches the frozen rule that access/refresh secrets stay in platform secure credential storage, never normal Room rows.

## WorkManager mapping

`HiltechSyncWorker` now executes the real Android sync runtime.

Result mapping:
- fully processed/no retry due → WorkManager success,
- retryable command or future durable retry → WorkManager retry,
- missing/invalid session → failure with `REAUTH_REQUIRED`,
- missing API configuration → failure with `SYNC_RUNTIME_NOT_CONFIGURED`.

The worker no longer returns the previous placeholder `SYNC_ENGINE_NOT_WIRED`.

## Durable retry safety

Room remains authoritative for `nextRetryAt`.

The runtime also checks whether a future RETRYABLE command remains.
This prevents an early WorkManager retry from observing zero currently-due commands and incorrectly declaring the queue complete.

## Trace boundary

Correlation IDs are generated per request now.

W3C `traceparent` remains an explicit provider boundary and is currently null until the observability gate installs the production tracing context.

## Next gate

Keycloak/OIDC runtime adapter:
- Authorization Code + PKCE S256,
- secure credential/session storage boundary,
- refresh/logout/revoke,
- explicit re-auth,
- recovery of preserved `REAUTH_REQUIRED` commands using the same semantic operationId.
