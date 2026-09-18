# 18 — First-Slice Production Infrastructure Contract

Status: **CONTRACT CANDIDATE v0.1 / OCI BASELINE**
Date: 2026-09-18

Canonical provider decision:
`docs/11-architecture/ADR/ADR-014-OCI-INFRASTRUCTURE.md`

## Purpose

Turn the accepted provider baseline into an implementation-ready infrastructure contract without embedding OCI into business/application code.

---

# 1. Environments

Required:
- CI/Test — ephemeral/synthetic.
- Staging — production-shaped, no real production data.
- Production — real HILTECH operation.

OCI baseline:
- separate compartments for staging and production.
- separate VCN/subnets.
- separate PostgreSQL systems/databases according approved sizing.
- separate object-storage buckets.
- separate KMS keys/secrets.
- separate runtime resources.
- separate environment-scoped GitHub deployment permissions.

No production data is copied casually into lower environments.

---

# 2. Region

Primary production candidate:
`me-jeddah-1`.

Before cutover:
- tenancy subscribed.
- service limits/quota checked.
- PostgreSQL capacity available.
- runtime availability checked.
- representative office/mobile latency measured.

Region is deployment config.

No application URL, object key or business row embeds region identity.

---

# 3. Network topology

Per staging/production:

Internet
→ OCI Public Load Balancer
→ private application subnet
→ HILTECH API / Keycloak / OpenFGA / OTel Collector
→ private DB/service endpoints.

## Public ingress

Allowed:
- HTTPS application/API endpoints.
- Keycloak browser/OIDC endpoint as required by native login.

Not public:
- PostgreSQL.
- OpenFGA management/API except through application/internal network.
- OTel collector admin/internal ports.
- DB/admin panels.
- internal metrics/debug endpoints.

## Egress

Private workloads use:
- service gateway for OCI services where supported.
- NAT gateway only for required outbound internet access.

Network Security Groups are workload-specific.

Default deny inbound between tiers except required flows.

---

# 4. Runtime units

## hiltech-api
- immutable OCI Registry image.
- Spring Boot modular monolith.
- private subnet.
- horizontally replaceable/stateless except external durable stores.
- health/readiness endpoints.
- graceful shutdown.
- no local authoritative file persistence.

## keycloak
- pinned image/version.
- private application runtime with public OIDC path through load balancer.
- PostgreSQL-backed.
- secrets from Secret Management.
- realm/client configuration exported/version-controlled as safe config; secrets excluded.

## openfga
- pinned image/version.
- private-only application access.
- PostgreSQL-backed persistent store.
- authorization model ID pinned in HILTECH server configuration.
- management access restricted.

## otel-collector
- private runtime.
- receives allow-listed HILTECH telemetry.
- exports to selected OCI backend.
- config version-controlled.
- no raw business payload/evidence.

## evidence-security-scanner
Only required when ARBITRARY_FILE evidence is enabled.

Baseline:
- private runtime/service.
- accepts object reference/stream via controlled server workflow.
- produces PASS/REJECT/ERROR result.
- never authorizes Evidence READY itself; server owns state transition.

Exact scanner product can be selected separately.

---

# 5. Runtime substrate

Preferred:
OCI Container Instances.

Fallback if region/quota/service fit fails:
OCI Compute + reproducible container runtime.

Contract:
- same images.
- same environment variables/secret refs.
- same health endpoints.
- same private networking.
- same GitHub Actions deployment interface.

No Kubernetes baseline.

---

# 6. Container Registry

Use OCI Container Registry.

Repositories:
- hiltech/api
- hiltech/keycloak-custom only if custom build becomes necessary
- hiltech/openfga-extension only if a custom image becomes necessary
- hiltech/otel-collector only if custom packaged config/image is selected
- hiltech/evidence-scanner where applicable

Prefer official upstream images for Keycloak/OpenFGA/Collector when configuration alone is sufficient.

Production deployment pins image digest.

Mutable latest tags are not production deployment identity.

---

# 7. PostgreSQL layout

Managed service:
OCI Database with PostgreSQL.

Private network only.

Logical separation candidate:
- `hiltech_app`
- `hiltech_keycloak`
- `hiltech_openfga`

Each:
- separate database and/or owner credentials.
- least-privilege DB user.
- no Keycloak/OpenFGA account can access HILTECH business tables.
- HILTECH app account cannot mutate identity/authz vendor-owned schemas.

Exact server/database-system topology may share one managed DB system initially if isolation, performance and service capabilities permit.

Scaling to separate DB systems later must not change application contracts.

---

# 8. PostgreSQL backup / recovery

Required:
- automated backups.
- PITR enabled.
- restore window configured.
- manual/pre-change backup for selected high-risk operations where useful.
- restore rehearsal in staging before production acceptance.

Technical initial baseline:
- configure at least a 10-day PITR window.
- this is an operational recovery baseline, not a business/legal retention statement.

Cross-region backup:
- design enabled/available as DR option.
- exact secondary OCI region chosen during DR runbook closure.

Business RPO/RTO remains a management/operations target to approve.
The infrastructure must expose measured restore time and achievable recovery point so HILTECH can set the final target from evidence.

---

# 9. Object storage

Per environment:
- private bucket/container.
- accepted S3 Compatibility API.
- no public ACL.
- opaque object keys from evidence contract.
- production bucket uses KMS encryption.
- versioning enabled where operationally compatible.
- no automatic business evidence deletion until RetentionPolicy exists.

Access:
- HILTECH API service identity can reserve/finalize/read according application workflow.
- native clients receive only short-lived signed targets.
- HIGHLY_RESTRICTED evidence is proxied through API.

Backup/durability settings:
- provider durability is not considered a substitute for retention/recovery validation.
- recovery/versioning exercise required before production.

---

# 10. KMS / Secrets

OCI KMS:
- production evidence/object-storage key.
- other customer-managed encryption keys where required.

OCI Secret Management:
- DB credentials.
- Keycloak secrets.
- OpenFGA secrets.
- signing/integration provider credentials.
- observability/exporter credentials if needed.

Rules:
- secrets fetched by workload identity/resource principal where supported.
- production secret values never reside in GitHub repo.
- GitHub stores only OCI deployment bootstrap credentials/federated identity required to initiate deployment.
- prefer short-lived/federated OCI auth over long-lived static keys where supported by final GitHub→OCI setup.
- secret rotation has a runbook + rehearsal.

---

# 11. IaC repository contract

Candidate roots:

`infrastructure/oci/modules/`
- network
- load-balancer
- postgres
- object-storage
- kms-secrets
- container-runtime
- registry
- observability
- iam

`infrastructure/oci/environments/staging/`
`infrastructure/oci/environments/production/`

Terraform:
- required provider OCI version pinned.
- Terraform version pinned.
- variables contain IDs/config, not plaintext secrets.
- plan/apply through controlled jobs.
- state/locking through OCI Resource Manager baseline.

Every manually created production resource must either:
- be imported into IaC,
- or be explicitly documented as an approved provider bootstrap exception.

No permanent console-drift baseline.

---

# 12. GitHub Actions deployment contract

PR:
- Terraform format/validate.
- provider init.
- plan/static security validation where credentials/environment allow.
- application tests/contracts.

Staging deployment:
- build/push immutable images.
- apply approved IaC/deployment.
- Flyway migration.
- server health/readiness.
- Keycloak/OIDC smoke.
- OpenFGA model/pinned-ID smoke.
- evidence reserve/upload/finalize smoke.
- trace/correlation smoke.

Production:
- environment protection/explicit approval.
- immutable image digest.
- migration compatibility gate.
- deploy.
- smoke.
- observation gate.
- internal release note.

---

# 13. Runtime health

## API
Liveness:
- process/runtime alive only.

Readiness:
- app initialized.
- required DB connectivity.
- critical internal configuration loaded.
- must not fail only because a noncritical external integration is temporarily unavailable.

## Keycloak/OpenFGA
Separate health probes.
HILTECH API readiness should distinguish identity/authz dependency failures.

## Observability
Telemetry exporter failure cannot corrupt/block business transaction completion.
It must raise operational health signal through bounded/nonrecursive mechanism.

---

# 14. Secret / credential failure

If secret retrieval fails:
- service fails startup/readiness if secret is required for safe operation.
- no plaintext/default fallback.
- no value logged.

If rotation produces invalid credential:
- rollback secret/version or redeploy known-valid reference through runbook.
- audit deployment/rotation event.

---

# 15. Deployment rollback

Application artifact rollback:
- previous image digest retained.
- only when current DB migration remains backward-compatible.

Database:
- no automatic downgrade migration.
- use forward fix/PITR/restore according incident.

Keycloak/OpenFGA config:
- model/config versioned.
- rollback to known compatible version only through explicit deployment path.

Object storage:
- no release rollback deletes business evidence.

---

# 16. Observability

Provider contract:
OpenTelemetry remains source instrumentation.

First OCI exporter/backend candidate:
- OCI Application Performance Monitoring for traces.
- OCI Logging/Logging Analytics for application/operational logs.
- OCI Monitoring/alarms for infrastructure metrics where appropriate.

Before production:
- define telemetry retention/access.
- define sampling.
- alert on:
  - API error rate.
  - auth failure anomaly.
  - sync backlog.
  - authorization projection failures.
  - DB resource pressure.
  - evidence finalize/quarantine failures.
  - backup/PITR health.
  - deployment health.

No salary, token, evidence content or raw sensitive payload in telemetry.

---

# 17. IAM

Separate responsibilities:
- IaC/deployment identity.
- runtime service identities.
- human operations/admin identity.

Principles:
- least privilege.
- environment separation.
- no shared human/root API key.
- production apply permission narrower than read/plan.
- runtime access only to exact buckets/secrets/services needed.
- break-glass path documented/audited separately.

---

# 18. Production acceptance smoke

Must prove on staging/production-shaped environment:

- HTTPS endpoint reachable from representative HILTECH Egypt network.
- native OIDC login.
- PM/read API.
- Android field bundle.
- offline/reconnect representative path.
- OpenFGA allow/deny.
- evidence upload/finalize/download.
- Object Storage KMS encryption configured.
- DB PITR enabled.
- one restore rehearsal.
- one secret rotation rehearsal.
- OTel trace correlation.
- deployment rollback of app image without local/data loss.
- latency measurements recorded.

---

# 19. Provider-portability rule

No business/domain module imports OCI SDK.

OCI SDK/provider-specific calls live in:
- infrastructure/deployment.
- storage/security provider adapter where the S3/KMS contract requires it.
- secret provider adapter.
- telemetry exporter.
- operations tooling.

A future provider change reimplements adapters/IaC, not HILTECH business modules.

---

# 20. Open items before infrastructure Freeze

- actual OCI tenancy/region subscription.
- measured Jeddah latency from Egypt.
- final runtime shape/sizing/cost budget.
- confirm Container Instances availability/quotas or choose Compute fallback.
- exact OpenFGA/Keycloak/Postgres managed sizing.
- exact malware scanner product if ARBITRARY_FILE enabled.
- final telemetry retention/sampling/backend cost settings.
- final business RPO/RTO target.
- production domain/TLS certificate ownership.
- Windows signing/distribution remains a separate client-operations decision.

These are operational instantiation items, not architecture rediscovery.

---

# Decision

OCI production infrastructure is contract-defined enough to remove the generic "which cloud/which storage/which secrets/which observability shape?" gap.

Remaining work is tenancy/quotas/latency/sizing/cost + final ops runbooks.
