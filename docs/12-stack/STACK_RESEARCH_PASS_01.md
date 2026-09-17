# HILTECH Stack Research — Pass 01

Status: RESEARCHING / TECHNOLOGY CANDIDATES
Research date: 2026-09-18

This pass evaluates current production-grade technology candidates against HILTECH requirements. It does not freeze FINAL_STACK.

---

# 1. Client Platform Decision Space

## Candidate A — Kotlin Multiplatform + Compose Multiplatform

Current official state:
- Kotlin Multiplatform core: Stable on Android, iOS, Desktop/JVM and Server/JVM.
- Compose Multiplatform UI: Stable on Android, iOS and Desktop; web/Wasm remains Beta.
- Current Kotlin release: 2.4.20.
- Compose Multiplatform stable release observed: 1.12.0.
- Native desktop packaging supports Windows MSI/EXE, macOS DMG/PKG and Linux DEB/RPM through Compose tooling.

Why it fits HILTECH:
- Android-first without giving up Windows desktop.
- shared business/domain/client logic.
- shared UI is possible but not mandatory.
- direct native/platform API integration remains available.
- strong Compose state/motion model.
- JVM desktop allows serious keyboard/mouse/window behavior.
- one engineering language across client and potentially backend.
- aligns with local/offline Room/KMP support.

Risks:
- cross-platform ecosystem smaller than Flutter for some plugins.
- Windows update strategy needs deliberate design beyond installer generation.
- some Jetpack Android-only APIs still need platform abstraction.
- iOS native integration/testing requires Mac/Xcode when that platform starts.
- dense enterprise table primitives may require custom work or specialist libraries.
- KMP-specific OpenTelemetry SDK is still in development; client observability abstraction should not depend on unstable APIs.

Current assessment:
LEADING CANDIDATE.

---

## Candidate B — Flutter

Current official state:
- Stable deployment to Android/iOS/web/Windows/macOS/Linux.
- mature cross-platform UI and plugin ecosystem.

Strengths:
- very strong one-codebase UI.
- broad desktop/mobile platform support.
- strong animation/rendering control.
- good engineering velocity.

Tradeoffs for HILTECH:
- introduces Dart while backend likely remains JVM/Kotlin/Java.
- Android platform APIs/background work/security require Flutter/native bridges where plugins are insufficient.
- Windows desktop integration is possible but creates a separate language/runtime ecosystem from backend.
- HILTECH's Android-first + deep local/platform integration makes native Kotlin particularly attractive.

Current assessment:
SERIOUS FALLBACK, not rejected.

---

## Candidate C — React Native + React Native Windows

Current state:
- Android/iOS core ecosystem mature.
- Windows is an out-of-tree platform maintained separately by Microsoft.

Strengths:
- large JS/TS ecosystem.
- fast hiring/ecosystem.
- shared conceptual stack with web.

Tradeoffs:
- Windows support is a separate platform implementation.
- HILTECH requires offline DB, background work, camera/scanning, native Android, desktop density and lifecycle-heavy business state.
- TypeScript/web stack would diverge from JVM backend and deeper Android APIs.

Current assessment:
NOT LEADING for current requirements, but remains a benchmark.

---

## Candidate D — Electron / Tauri Desktop + separate mobile

Strengths:
- excellent desktop ecosystem.
- web UI libraries/tables are extremely mature.
- Electron has strong tooling/distribution; Tauri is lighter and supports mobile too.

Tradeoffs:
- creates a webview/web runtime desktop path separate from Android-first mobile.
- likely duplicates interaction/client architecture.
- Electron bundles Chromium/Node; Tauri uses web frontend architecture.
- HILTECH's goal is one coherent product engineering model, not merely visual reuse.

Current assessment:
Desktop fallback only if Compose Desktop fails a critical spike.

---

# 2. Client Module Structure

JetBrains changed its recommended KMP project structure in 2026 toward:
- shared library module,
- separate runnable application modules per platform,
- optional server/core modules.

HILTECH should follow this direction conceptually:

app/
  androidApp/
  desktopApp/
  iosApp/      # later

shared/
  domain/
  data/
  sync/
  design/
  features/

Potential server-shared core types must be used conservatively; do not couple persistence/entity models across client/server merely to share classes.

---

# 3. Local Database

## Room 2.8.5 + SQLite

Official state:
- Room 2.8.5 stable as of 2026-09-09.
- Room 2.8 supports Kotlin Multiplatform.
- Google provides official KMP setup and migration guidance.

Why leading:
- Android first-class.
- schema/migration tooling.
- KMP support.
- coroutine integration.
- SQLite robustness.
- official Google ecosystem.

Room 3.x:
Google has active Room 3.x KMP documentation/releases, but final maturity/version should be rechecked at freeze time.

Current decision:
Room 2.8.x stable baseline candidate; re-evaluate Room 3 stable status at freeze.

---

# 4. Client Networking

## Ktor Client

Current official stable observed:
3.5.2 (2026-08-04).

Why:
- multiplatform asynchronous HTTP client.
- authentication/serialization/plugins.
- Android/iOS/Desktop support.
- Kotlin-native model.

Current assessment:
LEADING.

---

# 5. Android Reliable Background Work

## WorkManager

Official stable:
2.11.2; 2.12 release candidate exists.

Why:
- official Android API for reliable deferrable work.
- constraint-aware.
- appropriate for retryable offline sync/upload scheduling.

Important:
WorkManager is Android-specific implementation under HILTECH Sync abstraction, not the domain sync engine itself.

Current assessment:
LEADING for Android background scheduling.

---

# 6. Backend Runtime

## Kotlin + Spring Boot

Current stable:
Spring Boot 4.1.1 (2026-08-20).

Why:
- mature JVM operational ecosystem.
- security/data/integration/testing.
- excellent transaction semantics.
- observability support.
- Kotlin support.
- long-lived enterprise fit.

Alternative:
Ktor Server 3.5.2 is lighter and Kotlin-native.

Why Spring remains leading:
HILTECH contains payroll, finance, inventory, approvals, audit, integrations, transactional workflows and modular enterprise domains. Spring ecosystem reduces custom infrastructure risk.

Current assessment:
Spring Boot LEADING.

---

# 7. Modular Monolith

## Spring Modulith 2.1.1

Current official capabilities:
- domain-driven application modules.
- structural verification.
- module-level tests.
- documentation generation.
- runtime observability.
- transactional/persistent event publication registry.
- retry/resubmission for failed module event handling.

Major implication:
This is stronger evidence for Modular Monolith as HILTECH's initial architecture and reduces the immediate need for microservices or a separate event broker.

Current assessment:
LEADING companion to Spring Boot.

---

# 8. Durable Workflows

## Spring Modulith first, Temporal conditional

Spring Modulith event publication registry can persist event publications with PUBLISHED/PROCESSING/COMPLETED/FAILED/RESUBMITTED lifecycle and recover outstanding/failed publications.

Temporal guarantees durable workflow execution across crashes/network/infrastructure failure and is well suited to long-running business processes.

Decision direction:
- Use ordinary transaction/domain logic for local short workflows.
- Use Spring Modulith persistent events for reliable cross-module reactions.
- Introduce Temporal only for workflows whose duration/complexity/compensation/timers clearly exceed what the modular monolith orchestration should own.

Potential Temporal candidates later:
- complex multi-day onboarding.
- multi-party procurement with timers/compensation.
- complex maintenance SLA orchestration.
- bank/payment workflows with prolonged uncertain external state.

Current assessment:
TEMPORAL = CONDITIONAL, not Day-1 baseline.

---

# 9. Server Database

## PostgreSQL 18

Current:
PostgreSQL 18.6 (released 2026-08-13).

Why:
- transaction-heavy domain fit.
- mature reliability.
- relational constraints.
- JSONB when justified.
- strong indexing/query capabilities.
- current version includes uuidv7 support in PostgreSQL 18 generation.

Current assessment:
FROZEN-CANDIDATE; final deployment patch version checked at build freeze.

---

# 10. SQL / Persistence Layer

## jOOQ

Current observed:
3.21.8 (2026-09-04).

Why attractive:
- SQL-first/type-safe.
- excellent for PostgreSQL.
- avoids opaque ORM behavior in finance/inventory/reporting-heavy system.
- generated schema model.
- explicit complex queries.

Alternatives:
- Spring Data JDBC
- JPA/Hibernate
- Exposed

Direction:
Use jOOQ for explicit database access is LEADING, especially for ledgers/reporting.
Domain design should not leak jOOQ-generated records into domain/client APIs.

Commercial licensing/edition requirements for used features must be checked before freeze.

---

# 11. Database Migrations

## Flyway

Current 2026 docs support:
- migrations,
- validation,
- CI/CD,
- Java integration,
- PostgreSQL.

Direction:
LEADING migration candidate.

Use versioned SQL migrations in repo.
Do not let application auto-schema generation define production state.

Exact Flyway edition/version to freeze later.

---

# 12. Authentication

## Keycloak

Official current docs provide:
- OIDC/OAuth identity server capabilities,
- WebAuthn,
- passwordless/loginless WebAuthn,
- Passkeys,
- recovery codes,
- session/authentication flows.

Why:
- self-hostable.
- external users + employees.
- passkeys/2FA.
- avoids building authentication primitives ourselves.

Boundary:
Keycloak owns authentication/credential/session identity.
HILTECH owns business organizations, employee/client/supplier relationships and permissions.

Current assessment:
LEADING identity candidate.

Must prototype mobile/desktop native OIDC flow and device/session revocation.

---

# 13. Fine-Grained Authorization

## OpenFGA

Official modeling supports:
- roles and permissions,
- object-level relations,
- organization context,
- custom roles,
- conditions/time-based access.

Why matches HILTECH:
- project-scoped access,
- client org users,
- supplier/subcontractor restrictions,
- object relationships,
- temporary delegation.

Risk:
Adds operational component and authorization-model discipline.

Decision:
PROVISIONALLY LEADING because HILTECH complexity already exceeds simple RBAC.
Must validate with real Permission Matrix before freeze.

Fallback:
application-owned policy engine with Spring Security if model remains simpler than expected.

---

# 14. Observability

## Backend — OpenTelemetry Java

Status:
Traces / Metrics / Logs stable.
Current Java docs reference v1.66.0 in September 2026.

Direction:
LEADING server instrumentation standard.

## KMP client — OpenTelemetry Kotlin

Status:
in active development; traces/metrics/logs not all stable.

Decision:
Do NOT bind HILTECH client architecture directly to unstable OTel Kotlin SDK.
Create internal client telemetry interface; use platform/proven exporters/SDKs until KMP solution is stable enough.

---

# 15. Cache / Transient Infrastructure

## Redis

Current Redis line is rapidly evolving (8.x; 8.10 documentation current in September 2026).

Decision:
NOT BASELINE STORAGE.
Use Redis only for proven requirements:
- ephemeral caching,
- rate limits,
- selected queues/locks if architecture proves need,
- realtime support.

Never put authoritative payroll/asset/approval state only in Redis.

---

# 16. Object/File Storage

Requirement:
S3-compatible object storage abstraction for:
- photos/evidence,
- documents,
- test reports,
- generated handovers,
- exports.

Provider not frozen.
Need:
- checksum,
- immutable/versioned evidence where needed,
- signed access,
- encryption,
- lifecycle/retention,
- backup,
- malware/content scan path where appropriate.

---

# 17. Realtime

Potential:
- WebSocket or SSE.
- push notifications for background mobile attention.

Do not use realtime transport as source of truth.
Realtime event updates local/client state; authoritative state remains backend/database.

Exact protocol by use-case later.

---

# 18. Public Website

Keep:
Next.js + TypeScript for public hiltech-eg.com.

Reason:
public site has different deployment/content/SEO needs.

Do not force Compose Web because Compose Web/Wasm remains Beta and provides no advantage for this public surface.

---

# 19. Desktop Distribution

Compose Multiplatform official packaging supports:
Windows MSI/EXE,
macOS DMG/PKG,
Linux DEB/RPM,
self-contained runtime via jpackage/jlink.

HILTECH initial desktop target:
Windows.

Still required:
- auto-update strategy.
- code signing.
- enterprise deployment.
- rollback.
- deep links.
- crash reporting.

---

# 20. Preliminary Stack Direction

CLIENT
- Kotlin 2.4.x
- Kotlin Multiplatform
- Compose Multiplatform
- Room/SQLite
- Ktor Client
- Coroutines/Flow
- WorkManager Android
- platform native camera/QR/security bridges
- shared HILTECH telemetry abstraction

SERVER
- Kotlin/JVM
- Spring Boot 4.1.x
- Spring Modulith 2.1.x
- PostgreSQL 18.x
- jOOQ 3.21.x candidate
- Flyway
- Keycloak candidate
- OpenFGA candidate
- OpenTelemetry Java
- S3-compatible object storage

CONDITIONAL
- Temporal
- Redis
- dedicated broker
- OpenSearch
- active asset tags/UWB/BLE

PUBLIC WEB
- Next.js / TypeScript

---

# 21. Required Technical Spikes Before Freeze

1. KMP Android + Windows app from shared module.
2. Arabic/RTL adaptive UI.
3. Room shared local DB Android + Desktop.
4. Offline command queue + conflict simulation.
5. Camera/QR Android.
6. Desktop dense table performance.
7. Windows MSI/EXE packaging + signing/update research.
8. Keycloak native OIDC login + passkey/re-auth.
9. OpenFGA permission proof for Project/Client/Asset examples.
10. Spring Modulith module boundary + event registry recovery.
11. jOOQ + PostgreSQL transaction/ledger prototype.
12. Evidence binary upload/retry.
13. background sync with WorkManager.
14. end-to-end trace server; client telemetry abstraction.

Only after these are resolved should FINAL_STACK become authoritative.
