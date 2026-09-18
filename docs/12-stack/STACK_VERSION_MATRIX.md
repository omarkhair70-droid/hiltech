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
| Compose Multiplatform | 1.11.1 | FINAL FIRST-SLICE PIN | 1.12.0 exists but intentionally deferred; 1.11.1 is HILTECH-proven across Android + Windows |
| Android Gradle Plugin | 9.3.1 | PROVEN | SPIKE-01 client build line |
| Android compile SDK | 36 | PROVEN | Android spike builds |
| Android min SDK | 23 | PROVEN | SPIKE-01 candidate line |
| JDK for client builds | 17 | PROVEN | Android/Windows KMP spikes |
| Room3 | 3.0.3 | PROVEN / ACCEPTED | SPIKE-03 / ADR-006 |
| SQLite bundled driver | 2.7.1 | PROVEN / ACCEPTED | SPIKE-03 |
| KSP | 2.3.10 | FINAL FIRST-SLICE PIN | 2.3.12 exists but intentionally deferred; accepted Room/KMP proof remains on 2.3.10 |
| Kotlin Coroutines | 1.11.0 | PROVEN | Room/offline spike line |
| Android WorkManager | 2.11.2 | PROVEN / ACCEPTED | SPIKE-13 |
| CameraX | 1.6.2 | FINAL FIRST-SLICE PIN | Current reviewed stable; SPIKE-05 proven |
| Ktor Client | 3.5.2 | PROVEN / ACCEPTED | SPIKE-15 / ADR-007; Android OkHttp + JVM Desktop CIO |

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
| Flyway | Spring Boot 4.1.1 managed line; exact resolved dependency locked at bootstrap | ACCEPTED LINE | Flyway conventions/DDL contract frozen; capture resolved version in Gradle dependency lock during repository bootstrap |
| Spring Security / OIDC integration | exact version inherited from Spring Boot 4.1.1 line | PROVEN | SPIKE-15 authenticated server integration; final production pin re-check at freeze |

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
| Production object-storage provider | OCI Object Storage | ACCEPTED / PRE-FREEZE | ADR-014; private bucket, S3 Compatibility API, OCI KMS; tenancy/region cutover validation pending |
| Multipart threshold | no multipart in first slice; 16 MiB max/object | ACCEPTED | Evidence contract v0.2 |
| Retention/versioning/legal hold | no automatic deletion baseline; versioning/provider settings at ops freeze | ACCEPTED BASELINE | Explicit RetentionPolicy required before automated deletion |

---

# Observability

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| OpenTelemetry Java | 1.66.0 | PROVEN / ACCEPTED CONTRACT | SPIKE-14 / ADR-013 |
| W3C Trace Context | standard | ACCEPTED | End-to-end correlation proof |
| OTLP collector | OpenTelemetry Collector | ACCEPTED BASELINE | ADR-013 + ADR-014; private OCI runtime |
| Metrics/log backend | OCI APM + Logging/Logging Analytics + Monitoring baseline | ACCEPTED / PRE-FREEZE | Exporter/backend choice remains behind OTel contract; retention/sampling/cost settings pending |
| Crash reporting provider | no separate vendor frozen | DEFERRED NON-BLOCKING | Native crash capture can be added behind client telemetry abstraction when operations require it |

---

# Infrastructure / Provider

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| Cloud provider | Oracle Cloud Infrastructure | ACCEPTED / PRE-FREEZE | ADR-014 |
| Primary region | me-jeddah-1 candidate | ACCEPTED CANDIDATE | OCI PostgreSQL endpoint exists; tenancy/quota/service + Egypt latency smoke required before cutover |
| Server runtime | OCI Container Instances preferred; OCI Compute container fallback | ACCEPTED BASELINE | No Kubernetes baseline |
| Container registry | OCI Container Registry | ACCEPTED | Immutable digest deployment |
| Managed PostgreSQL provider | OCI Database with PostgreSQL | ACCEPTED / PRE-FREEZE | Private network + backups/PITR; tenancy/capacity validation pending |
| Secrets | OCI Secret Management | ACCEPTED / PRE-FREEZE | ADR-014 |
| KMS | OCI Key Management Service | ACCEPTED / PRE-FREEZE | Evidence bucket customer-managed key baseline |
| IaC | Terraform + OCI Terraform Provider; OCI Resource Manager state/locking | ACCEPTED / PRE-FREEZE | Git repo remains source; provider/tool version pinned at bootstrap |
| Public ingress | OCI Load Balancer | ACCEPTED BASELINE | Private application/data tiers |
| Kubernetes / OKE | NOT baseline | CONDITIONAL | Add only on verified scale/ops trigger |

---

# Windows Distribution

| Component | Version / Line | Status | Evidence / Note |
|---|---:|---|---|
| Compose Desktop EXE | current client line | PROVEN | SPIKE-01 |
| Compose Desktop MSI | current client line | PROVEN | SPIKE-01 |
| Install/update/rollback policy | controlled installer-swap baseline | PROVEN / ACCEPTED | SPIKE-07 / ADR-012 |
| Code-signing production certificate/provider | DigiCert OV Code Signing + KeyLocker cloud HSM | ACCEPTED / PRE-FREEZE | ADR-019; organization validation/issuance and protected CI setup pending |
| Auto-update/MDM strategy | HILTECH Update Service + ADR-012 signed MSI swap; MDM optional adapter | ACCEPTED / PRE-FREEZE | Authenticated release manifest + private OCI artifact + Authenticode verification; no Intune dependency |

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
| actions/checkout | v7.0.1 @ `3d3c42e5aac5ba805825da76410c181273ba90b1` | FINAL PRODUCTION PIN | Full-SHA policy |
| actions/setup-java | v6.0.1 @ `de7274f081f381c8f8158605e0321c36c376e2e6` | FINAL PRODUCTION PIN | Full-SHA policy |
| gradle/actions/setup-gradle | v6.3.0 @ `9c971963bec38e04b3d30dcc455b5382be2fdbfb` | FINAL PRODUCTION PIN | Full-SHA policy |
| OpenFGA contract action | v0.1.2 @ `e89aa8259796cd5ee5c1b1ae7d72c401029cb947` | FINAL CONTRACT PIN | Green run 35331537375 |
| Action update policy | Dependabot weekly + reviewed SHA PRs | ACCEPTED | CI supply-chain contract |
| Python setup action | exact production pin only if production workflow needs it | DEFERRED | Spike usage does not force production dependency |
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

Pre-code version gate:
1. final focused AGP 9.3.3 compatibility validation.

Already reviewed/closed at contract level:
- current application/server dependency pins,
- deliberate Compose/KSP non-upgrades,
- immutable production GitHub Action SHAs,
- OCI provider/runtime architecture,
- DigiCert/Windows update architecture,
- staged DR architecture.

Bootstrap/cutover activation items do not block creating FINAL_STACK.md:
- OCI tenancy/quota/latency/sizing/cost.
- DigiCert certificate issuance/KeyLocker credential setup.
- exact OCI/Terraform/Collector deployment pins after tenancy validation.
- telemetry operational retention/sampling.
- recovery rehearsals.
- exact Flyway transitive resolved lock after Gradle bootstrap.

Once AGP validation passes, FINAL_STACK.md may be created and the remaining pre-code blocker is design proof.
