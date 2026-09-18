# HILTECH Stack Version Matrix

Status: **PRE-FREEZE / EVIDENCE-BASED**
Date: 2026-09-18

This file is the canonical pre-freeze version ledger.

It is **not** FINAL_STACK.md.

Rules:
- PROVEN = exercised successfully in a HILTECH technical spike.
- ACCEPTED = architecture decision accepted by ADR/evidence.
- LEADING = current preferred candidate but not yet proven/frozen.
- CONDITIONAL = intentionally excluded from baseline unless a trigger appears.
- TBD = exact product/provider/version must be decided at freeze.
- Every exact version must be re-checked once more immediately before technical freeze.

---

# Client Platform

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| Kotlin | 2.4.20 | PROVEN | SPIKE-01/02/03/04/06 |
| Compose Multiplatform | 1.11.1 | PROVEN | Android + Windows build, dense desktop, RTL renders |
| Android Gradle Plugin | 9.3.1 | PROVEN | SPIKE-01 client build line |
| Android compile SDK | 36 | PROVEN | Android spike builds |
| Android min SDK | 23 | PROVEN | SPIKE-01 candidate line |
| JDK for client builds | 17 | PROVEN | Android/Windows KMP spikes |
| Room3 | 3.0.3 | PROVEN / ACCEPTED | SPIKE-03 / ADR-006 |
| SQLite bundled driver | 2.7.1 | PROVEN / ACCEPTED | SPIKE-03 |
| KSP | 2.3.10 | PROVEN | SPIKE-03 |
| Kotlin Coroutines | 1.11.0 | PROVEN | Room/offline spike line |
| Android WorkManager | 2.11.2 | PROVEN / ACCEPTED | SPIKE-13 |
| CameraX | 1.6.2 spike line | PROVEN | SPIKE-05; exact freeze version re-check before final freeze |
| Ktor Client | 3.5.2 observed candidate | LEADING | Must be proven/frozen through network/E2E path |

---

# Backend

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| Kotlin | 2.4.20 | PROVEN | SPIKE-10/11 |
| Java | 21 | PROVEN / ACCEPTED | Backend + DB spikes |
| Spring Boot | 4.1.1 | PROVEN / ACCEPTED | SPIKE-10 / ADR-002 |
| Spring Modulith | 2.1.1 | PROVEN / ACCEPTED | SPIKE-10 / ADR-003 |
| PostgreSQL | 18.6 | PROVEN / ACCEPTED | SPIKE-11 / ADR-004 |
| jOOQ | 3.21.8 | PROVEN / ACCEPTED | SPIKE-11 / ADR-005 |
| pgJDBC | 42.7.13 | PROVEN | SPIKE-11 |
| Flyway | exact version TBD | LEADING | Migration strategy freezes after exact schemas |
| Spring Security / OIDC integration | exact version inherited from Spring Boot line | LEADING | Final backend integration in SPIKE-15 / bootstrap |

---

# Identity / Authorization

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| Keycloak | 26.7.4 | PROVEN / ACCEPTED | SPIKE-08 / ADR-008 |
| OpenFGA | 1.20.0 | PROVEN / ACCEPTED | SPIKE-09 / ADR-009 |
| OIDC flow | Authorization Code + PKCE S256 | ACCEPTED | Native Android + Windows proof |
| Native password grant | disabled | ACCEPTED | SPIKE-08 |
| Keycloak offline_access | not baseline | ACCEPTED | Offline business work uses Room command queue |

---

# Storage / Files

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| S3-compatible protocol | current standard S3 semantics | PROVEN / ACCEPTED | SPIKE-12 / ADR-010 |
| AWS SDK for Java | 2.55.0 | PROVEN IN SPIKE | Protocol proof only; not a provider decision |
| Production object-storage provider | TBD | OPEN | Provider comparison required |
| Multipart threshold | TBD | OPEN | Freeze with evidence-size reality |
| Retention/versioning/legal hold | TBD | OPEN | Policy/reality dependent |

---

# Observability

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| OpenTelemetry Java | 1.66.0 | PROVEN / ACCEPTED CONTRACT | SPIKE-14 / ADR-013 |
| W3C Trace Context | standard | ACCEPTED | End-to-end correlation proof |
| OTLP collector | TBD | OPEN | Infrastructure freeze |
| Metrics/log backend | TBD | OPEN | Provider/ops choice |
| Crash reporting provider | TBD | OPEN | Client operations decision |

---

# Windows Distribution

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| Compose Desktop EXE | current client line | PROVEN | SPIKE-01 |
| Compose Desktop MSI | current client line | PROVEN | SPIKE-01 |
| Install/update/rollback policy | controlled installer-swap baseline | PROVEN / ACCEPTED | SPIKE-07 / ADR-012 |
| Code-signing production certificate/provider | TBD | OPEN | Production operations/security |
| Auto-update/MDM strategy | TBD | OPEN | HILTECH IT reality / production operations choice |

---

# Android Field Operations

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| Room durable command queue | accepted local pattern | PROVEN | SPIKE-03/04 |
| Offline sync semantics | operationId + baseVersion + conflicts | PROVEN / ACCEPTED | SPIKE-04 / ADR-011 |
| WorkManager reconnect/background execution | 2.11.2 | PROVEN / ACCEPTED | SPIKE-13 |
| Camera/QR/evidence capture | CameraX 1.6.2 + QR spike line | PROVEN | SPIKE-05 |
| Binary evidence handoff | S3 protocol | PROVEN | SPIKE-12 |

---

# CI / Delivery

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| GitHub Actions | hosted Linux + Windows | ACCEPTED | ADR-015; all spikes |
| actions/checkout | v4 in current spike workflows | PROVEN | Re-check at production bootstrap |
| actions/setup-java | v5 | PROVEN | Current spike workflows |
| gradle/actions/setup-gradle | v4 | PROVEN | Current spike workflows |
| Python setup action | v6 where used | PROVEN | Keycloak/evidence spikes |
| Android emulator runner | ReactiveCircus v2 | SPIKE HARNESS | Not production dependency |

---

# Explicit Non-Baseline Components

| Component | Baseline Decision | Evidence / Trigger |
|---|---|---|
| Temporal | NOT baseline | ADR-016; add only for verified durable distributed orchestration need |
| Redis | NOT baseline | ADR-017; add only for measured cache/rate-limit/coordination need |
| OpenSearch / Elasticsearch | NOT baseline | ADR-018; start PostgreSQL/read-model search |
| Kafka / RabbitMQ | NOT baseline | Spring Modulith internal reliability passed; broker only for verified integration/scale boundary |
| Microservices | NOT baseline | Modular monolith accepted; extract only on concrete evidence |

---

# Still Required Before FINAL_STACK.md

1. SPIKE-15 end-to-end vertical proof.
2. Ktor/network client proof through the real HILTECH API path (part of SPIKE-15).
3. Exact Flyway/version/migration baseline after schemas are frozen.
4. Infrastructure/provider decision.
5. Production object-storage provider.
6. Windows enterprise distribution/updater and production signing choice.
7. Final server/runtime/container versions.
8. Final CI action pinning strategy.
9. One last current-version verification immediately before freeze.

Only after those gates may FINAL_STACK.md be created.
