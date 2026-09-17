# Technical Architecture Candidates — Decision Log Addendum

Status: RESEARCHING

## Candidate Decision TA-001 — Client
Prefer Kotlin Multiplatform + Compose Multiplatform for Android and Windows first.

Confidence: HIGH, pending spikes.

## Candidate Decision TA-002 — Public Website
Keep Next.js/TypeScript separate from authenticated HILTECH OS.

Confidence: HIGH.

## Candidate Decision TA-003 — Backend
Kotlin/JVM + Spring Boot modular monolith.

Confidence: HIGH.

## Candidate Decision TA-004 — Module Discipline
Use Spring Modulith to verify boundaries/test modules/document interactions.

Confidence: HIGH.

## Candidate Decision TA-005 — Event Reliability
Use transactional/persistent module events for cross-domain reactions where applicable.

Confidence: HIGH.

## Candidate Decision TA-006 — Temporal
Do not install Temporal by default. Introduce only after a workflow requires dedicated durable orchestration.

Confidence: HIGH.

## Candidate Decision TA-007 — Database
PostgreSQL.

Confidence: VERY HIGH.

## Candidate Decision TA-008 — SQL Access
jOOQ candidate preferred over ORM-heavy domain persistence.

Confidence: MEDIUM-HIGH pending prototype/licensing.

## Candidate Decision TA-009 — Client Local DB
Room/SQLite.

Confidence: HIGH pending desktop/KMP prototype.

## Candidate Decision TA-010 — Authentication
Keycloak/OIDC candidate.

Confidence: MEDIUM-HIGH pending native flow/ops prototype.

## Candidate Decision TA-011 — Authorization
OpenFGA candidate for relation/object-level authorization.

Confidence: MEDIUM-HIGH pending model prototype.

## Candidate Decision TA-012 — Observability
OpenTelemetry Java on server; internal abstraction on clients until KMP telemetry ecosystem stabilizes.

Confidence: HIGH.

## Candidate Decision TA-013 — Redis
No Redis by default. Add only for demonstrated need.

Confidence: HIGH.

## Candidate Decision TA-014 — Microservices
No microservices at launch. Extract only when scale/isolation/team/deployment evidence demands.

Confidence: VERY HIGH.

## Candidate Decision TA-015 — Realtime
Realtime is transport, never source of truth.

Confidence: VERY HIGH.
