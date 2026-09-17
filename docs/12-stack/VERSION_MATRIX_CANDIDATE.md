# HILTECH Candidate Version Matrix

Status: RESEARCH SNAPSHOT — 2026-09-18
NOT a build lockfile. Versions must be rechecked at technical freeze.

| Technology | Current researched release/state | HILTECH status |
|---|---|---|
| Kotlin | 2.4.20 stable | Leading |
| Kotlin Multiplatform | Android/iOS/Desktop stable | Leading |
| Compose Multiplatform | 1.12.0 stable; Android/iOS/Desktop stable | Leading |
| Material3 Adaptive Android | 1.3.0 stable; 1.4 alpha ongoing | Use stable line |
| Room | 2.8.5 stable, KMP supported | Leading local DB baseline |
| WorkManager | 2.11.2 stable; 2.12 RC exists | Android leading |
| Ktor | 3.5.2 | Leading client networking |
| Spring Boot | 4.1.1 | Leading backend |
| Spring Modulith | 2.1.1 | Leading modular-monolith tooling |
| PostgreSQL | 18.6 | Leading database |
| jOOQ | 3.21.8 | Leading SQL candidate |
| Flyway | active 12/13-era product line; exact stable library pin TBD | Leading migration candidate |
| Keycloak | current latest docs support passkeys/WebAuthn | Leading auth candidate |
| OpenFGA | current schema/model docs 1.1 | Leading authorization candidate |
| Temporal | current platform/Java SDK supported | Conditional |
| OpenTelemetry Java | API/SDK stable; docs reference 1.66.0 | Leading server observability |
| OpenTelemetry Kotlin | Development | Do not baseline client directly |
| Redis | current 8.x line; 8.10 docs | Conditional |
| Flutter | stable mobile/desktop/web | Fallback client candidate |
| Electron | 44 current Aug 2026 | Desktop fallback benchmark |

## Rules
- Pin stable releases unless a pre-release feature is explicitly justified by an ADR.
- Every dependency gets an owner and upgrade policy.
- Security patch cadence overrides aesthetic desire to remain on an old version.
- Version freeze happens immediately before repository implementation bootstrap, not weeks earlier.
