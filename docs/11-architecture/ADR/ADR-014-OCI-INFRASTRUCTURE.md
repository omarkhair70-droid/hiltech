# ADR-014 — OCI Production Infrastructure Baseline

Status: **ACCEPTED / PRE-FREEZE**
Date: 2026-09-18

## Context

HILTECH OS now has accepted provider-neutral contracts for:
- Kotlin/Spring modular-monolith backend,
- PostgreSQL authoritative data,
- Keycloak identity,
- OpenFGA authorization,
- S3-compatible evidence storage,
- OpenTelemetry observability,
- GitHub Actions CI/CD,
- Android/Windows native clients.

The remaining infrastructure decision must:
- instantiate those contracts without redesigning them,
- keep application code portable,
- support a Middle East deployment close to Egypt,
- avoid premature Kubernetes/microservice operations,
- provide managed database, private networking, object storage, secrets/KMS and container registry,
- support repeatable IaC and disaster recovery.

## Decision

Use **Oracle Cloud Infrastructure (OCI)** as the first HILTECH production infrastructure provider.

Primary-region candidate:
- **Saudi Arabia West (Jeddah) — `me-jeddah-1`**

Why this region:
- OCI lists Jeddah as a commercial region.
- the OCI API endpoint catalog exposes OCI Database with PostgreSQL in `me-jeddah-1`.
- it is geographically appropriate to test as the first Middle East deployment for HILTECH users in Egypt.

This is a deployment baseline, not a hard product dependency.

Before production cutover the HILTECH tenancy must prove:
- subscription/access to required services in the selected region,
- quotas/limits,
- representative latency from HILTECH Egypt networks,
- capacity for the selected PostgreSQL/container shapes.

If Jeddah fails those operational checks, another OCI region may be selected through deployment configuration/change control without reopening product architecture.

---

# Runtime Topology

## Public edge

- OCI public Load Balancer.
- only intended public HTTPS entry points are exposed.
- TLS termination/certificate management is deployment configuration.
- backend application containers remain in private subnets.

## Private application runtime

Baseline:
- OCI Container Instances for containerized HILTECH services where service availability/quotas are confirmed.
- private subnet.
- images from OCI Container Registry.
- service gateway/NAT as required for controlled egress.

First-slice containers:
- HILTECH Spring Boot API.
- Keycloak.
- OpenFGA.
- OpenTelemetry Collector.
- supporting malware scanner for ARBITRARY_FILE evidence if selected.

Do **not** introduce Kubernetes/OKE in the first production baseline.

Revisit Kubernetes only when verified operational/scale/service-isolation need justifies it.

If Container Instances availability/operational limits in the selected region fail pre-cutover validation, the fallback is OCI Compute running the same OCI-container-registry images through reproducible deployment automation.

That fallback changes runtime substrate, not application architecture.

---

# PostgreSQL

Use **OCI Database with PostgreSQL** as the production PostgreSQL service when tenancy/region validation passes.

Networking:
- private VCN/subnet access.
- no public application database endpoint.

Database system supports separate logical databases/schemas/users as needed for:
- HILTECH application.
- Keycloak.
- OpenFGA.

Business ownership remains in HILTECH modular-monolith contracts; sharing one managed PostgreSQL service does not permit cross-domain writes.

## Recovery

Enable:
- automated backups,
- point-in-time recovery,
- manual pre-high-risk-change backup where operationally appropriate.

OCI PostgreSQL supports point-in-time recovery through WAL + backup policy and allows configured restore windows up to the service maximum.

Cross-region backup copy is part of disaster-recovery planning once production business RPO/RTO is approved.

---

# Object Storage

Use **OCI Object Storage** for evidence/documents requiring the accepted S3-compatible protocol.

Contract:
- private bucket/container per environment.
- OCI Amazon S3 Compatibility API.
- AWS SigV4-compatible client path.
- opaque HILTECH object keys.
- server-side encryption.
- customer-managed OCI KMS key for production evidence bucket unless final security/cost review explicitly accepts provider-managed encryption.
- bucket/object versioning enabled where compatible with retention policy.
- no public object ACL.

Application remains coded to the accepted S3-compatible boundary, not OCI-native object APIs.

---

# Secrets / Keys

Use:
- OCI Key Management Service for customer-managed encryption keys.
- OCI Secret Management for application/provider secrets.

Secrets include:
- DB credentials where static credentials are unavoidable,
- Keycloak/OpenFGA secrets,
- signing/integration credentials,
- provider tokens.

Rules:
- no production secret in Git,
- no plaintext secret in Terraform variables committed to repo,
- secret references rather than values in app configuration,
- least-privilege dynamic workload/instance/resource principal access where supported.

---

# Container Registry

Use OCI Container Registry for immutable server/runtime images.

Image identity:
- semantic release tag for humans,
- immutable digest used by deployment.

Production deployment pins digest, not mutable `latest`.

---

# Observability

Keep ADR-013 OpenTelemetry contract.

First OCI baseline:
- OpenTelemetry Collector in the private application environment,
- export traces/metrics/log signals to OCI-compatible observability services such as APM / Logging / Monitoring where they satisfy the contract,
- keep collector/exporter boundary explicit so backend can change without application instrumentation rewrite.

No raw business payloads or sensitive evidence content enters telemetry.

Sampling/retention/alert thresholds are operations configuration, not domain code.

---

# Infrastructure as Code

Use **Terraform configuration with the OCI Terraform Provider** as the infrastructure definition.

Repository source of truth:
- IaC lives in HILTECH GitHub repo.
- provider/tool versions are pinned and rechecked at Freeze/Bootstrap.

Execution baseline:
- OCI Resource Manager may own remote Terraform state/locking and execute plans/applies.
- GitHub Actions remains the engineering delivery control plane and can trigger/coordinate Resource Manager or OCI deployment APIs.
- infrastructure must not exist only as console clicks.

Rationale:
- OCI officially supports Terraform/OCI provider through Resource Manager.
- Resource Manager stores/locks stack state and supports Git-backed configurations.

The initial baseline uses Oracle-supported Terraform rather than adding a second IaC implementation.

---

# Environment Isolation

Required environments:
- staging
- production

Isolation baseline:
- separate OCI compartments for staging and production.
- separate networks/subnets.
- separate PostgreSQL systems/databases according final sizing.
- separate buckets.
- separate KMS/secrets.
- separate runtime resources.
- production credentials are inaccessible to staging jobs/users by default.

CI/test ephemeral infrastructure remains separate from real production data.

---

# Networking

Candidate VCN shape per environment:

Public:
- public Load Balancer only.

Private application subnet:
- API.
- Keycloak.
- OpenFGA.
- OTel Collector.
- supporting private containers.

Private data/service access:
- PostgreSQL.
- service gateway/private OCI service access where available.
- NAT only for controlled outbound internet dependencies.

Security:
- Network Security Groups preferred over broad subnet security rules for workload-specific access.
- DB ingress only from authorized application/security components.
- admin access through controlled management path; no open SSH/RDP baseline.

---

# Availability / Scale

HILTECH application architecture remains scale-neutral.

Initial deployment may start with modest runtime shapes and scale vertically/horizontally as measured.

Do not claim multi-Availability-Domain application HA in Jeddah:
- current OCI public region table lists Jeddah with one Availability Domain.

Use:
- fault-domain/service-level resilience where supported,
- managed-service backups,
- reproducible runtime deployment,
- cross-region backup/DR planning.

A future stronger HA/RTO requirement may justify:
- another region,
- active/passive regional design,
- different OCI runtime,
- database topology expansion.

That is an operations evolution, not an application rewrite.

---

# Deployment Flow

GitHub Actions:
1. validate/test application and contracts.
2. build immutable containers.
3. push to OCI Container Registry.
4. plan infrastructure/deployment change.
5. apply to staging.
6. run migration/health/smoke gates.
7. explicit production approval.
8. apply production deployment.
9. run production smoke/health gates.
10. observe release health.
11. rollback application artifact where schema compatibility permits; otherwise use forward recovery strategy.

No production console-only hotfix is the normal deployment path.

---

# Portability Boundary

The following remain provider-neutral:
- Spring Boot container.
- PostgreSQL SQL/Flyway/jOOQ.
- Keycloak OIDC.
- OpenFGA API/model.
- S3-compatible storage interface.
- OpenTelemetry.
- GitHub Actions orchestration.
- native client API contracts.

OCI-specific code belongs only in:
- IaC,
- deployment adapters,
- secret/KMS integration adapter,
- observability exporters,
- optional provider operations tooling.

---

# Pre-Cutover Validation Gates

Before calling OCI production-ready:

- [ ] HILTECH OCI tenancy active.
- [ ] Jeddah region subscribed/usable.
- [ ] OCI Database with PostgreSQL quota/capacity confirmed.
- [ ] Container Instances or chosen Compute fallback availability confirmed.
- [ ] Object Storage/KMS/Secret Management confirmed.
- [ ] latency smoke from representative Egypt office/mobile networks.
- [ ] staging deploy from IaC succeeds.
- [ ] database PITR restore rehearsal succeeds.
- [ ] object evidence upload/download/restore exercise succeeds.
- [ ] secret rotation rehearsal succeeds.
- [ ] observability trace arrives end-to-end.
- [ ] production network/security review.
- [ ] backup/DR runbook reviewed.

These are deployment validation gates, not reasons to reopen domain/application architecture.

---

# Evidence / Official Provider Capabilities

Decision was based on current Oracle Cloud documentation available on 2026-09-18, including:
- OCI Regions and Availability Domains.
- OCI API Reference / PostgreSQL regional endpoints.
- OCI Database with PostgreSQL point-in-time recovery and backup documentation.
- OCI Object Storage Amazon S3 Compatibility API.
- OCI Vault / Key Management and Secret Management.
- OCI Container Instances / Container Registry.
- OCI Resource Manager / Terraform Provider.
- OCI APM/OpenTelemetry integration guidance.

---

# Revisit Triggers

Revisit provider/runtime selection if:
- Jeddah required services/quotas are unavailable,
- measured Egypt latency is unacceptable,
- provider economics materially fail approved budget,
- HILTECH receives a contractual/data-residency requirement incompatible with the region/provider,
- required HA/RTO cannot be met by the baseline,
- OCI service limitations break an accepted HILTECH contract.

Provider change must preserve the accepted product/architecture contracts wherever possible.

---

# Decision

**OCI is the accepted first production infrastructure provider baseline.**

Primary-region candidate:
**`me-jeddah-1`**, pending tenancy/quota/service/latency cutover validation.

No Kubernetes baseline.
No application-level OCI lock-in.
