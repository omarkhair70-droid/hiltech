# HILTECH OS — Stack Research

Status: `RESEARCHING`

This file captures current technical candidates and the questions they still need to answer. Nothing here is frozen yet.

## Client platform candidate

### Kotlin Multiplatform + Compose Multiplatform

Current reasons for interest:

- first-class Android fit,
- shared business/data logic across platforms,
- serious Windows desktop path,
- future iOS path,
- strong local/offline architecture possibilities,
- direct access to platform APIs when required,
- strong motion/interaction potential,
- one language across a large part of the product.

Must validate:

- production Windows packaging/updating,
- camera/QR/scanning integration,
- biometric/security integration,
- background sync,
- local database performance,
- large-table/dense desktop UX,
- printing/export needs,
- notification behavior,
- accessibility,
- reduced-motion support,
- future iOS integration,
- actual engineering velocity vs alternatives.

Alternatives are still open until research and proof-of-concept work closes them.

## Local/offline candidate

Current direction:

- local database on device,
- UI reads primarily from local state where appropriate,
- explicit synchronization engine,
- background work for queued field operations,
- attachment/photo sync,
- online-only boundaries for sensitive authoritative actions.

Candidate Android/KMP technologies previously identified:

- Room / SQLite,
- Kotlin Coroutines + Flow,
- WorkManager on Android,
- Ktor client,
- kotlinx.serialization.

All remain subject to validation.

## Backend candidate

Current direction:

- Kotlin/JVM,
- Spring Boot ecosystem,
- modular monolith,
- PostgreSQL,
- explicit domain modules,
- strong transaction/audit behavior,
- ledgers for money/stock/movement where appropriate.

Must validate:

- current stable framework versions at freeze time,
- hosting model,
- operational complexity,
- developer ergonomics,
- migration strategy,
- integration requirements,
- async/realtime model,
- testing strategy,
- performance requirements.

## Architecture bias

### Modular monolith first

Current high-confidence proposal:

Do not start with microservices merely for architecture aesthetics.

Potential logical modules:

- identity,
- people,
- work,
- projects,
- assets,
- warehouse,
- finance,
- clients,
- procurement,
- security,
- documents,
- notifications,
- workflows,
- integrations,
- audit.

Service extraction should happen later only when evidence requires independent scaling, isolation, ownership, or deployment.

## Database candidate

PostgreSQL is the current default candidate.

Research must define:

- schema/module ownership,
- migrations,
- auditing,
- money representation,
- inventory/asset ledgers,
- temporal/history requirements,
- row-level vs application authorization responsibilities,
- backup/restore,
- HA/DR requirements.

## Identity / authorization candidates

Previously proposed for research:

- OIDC-based authentication,
- Keycloak or equivalent identity provider,
- passkeys/2FA/device sessions,
- OpenFGA or equivalent for fine-grained authorization if justified.

Important: do not adopt heavyweight components automatically. Validate actual HILTECH role/relationship complexity first.

## Durable workflow candidate

Temporal or equivalent was proposed for long-lived business workflows such as:

- onboarding,
- payroll approval,
- procurement,
- payment approval,
- maintenance SLA,
- handover.

Research question: does the business complexity justify a dedicated durable workflow engine, or is application-level orchestration sufficient initially?

## Storage / realtime / observability candidates

Current categories under research:

- S3-compatible object storage for documents/photos,
- WebSocket/SSE or equivalent for live updates,
- Redis only where a proven use exists,
- PostgreSQL search first, dedicated search only when necessary,
- OpenTelemetry-compatible observability.

## Public website

Current public HILTECH website remains a separate public surface. Next.js/TypeScript can remain appropriate there without dictating the authenticated HILTECH OS stack.

## UX / motion technology research

Potential layers:

- Compose native animation for ordinary product state transitions,
- Rive or equivalent only for high-value authored interactive/system visuals,
- custom HILTECH domain iconography,
- bilingual typography research rather than defaulting to generic ERP typography.

## Freeze requirement

`FINAL_STACK.md` must not be created as authoritative until:

1. required company workflows are sufficiently understood,
2. platform requirements are written,
3. competing approaches are compared,
4. critical risks are prototyped or otherwise validated,
5. exact versions are checked at freeze time,
6. operational/deployment burden is understood.
