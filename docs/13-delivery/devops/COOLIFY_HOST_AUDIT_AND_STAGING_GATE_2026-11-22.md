# Coolify / Oracle host audit — HILTECH staging readiness

Date: 2026-11-22 (point-in-time, read-only inspection)  
Owner's task: use the **existing** Oracle/Coolify installation for HILTECH deployment preparation, without interrupting other apps.  
Tracking: [#133](https://github.com/omarkhair70-droid/hiltech/issues/133), coordination [#130](https://github.com/omarkhair70-droid/hiltech/issues/130).  
Status: **STAGING DEPLOYMENT BLOCKED — resource/health/isolation/recovery gates OPEN**. This is not approval for deploy, upgrade, migration, backup deletion, cleanup, or production activation.

## Direct evidence acquired

An authorized Windows Desktop Commander terminal established SSH to the owner's Oracle Linux VM using an **existing user-side SSH identity and host-key verification**, without exposing or copying key contents. This confirmed real on-host access; no separate hosted browser session is required for read-only Docker inspection.

Read-only commands used included `hostname`, `uname`, `uptime`, `free -h`, `df -h`, `nproc`, `docker --version`, `docker compose version`, `sudo -n docker ps -a`, `sudo -n docker system df`, and `sudo -n docker stats --no-stream`. No Docker lifecycle commands were run.

| Finding | Observed value | Consequence |
| --- | --- | --- |
| OS | Ubuntu 24.04.4 LTS, Oracle kernel | Existing host reachable via verified SSH |
| Compute | **1 vCPU**; load averages ~0.51 / 0.77 / 0.88 at inspection | Little headroom for Kotlin/Spring + Keycloak + OpenFGA + 3 PostgreSQL schemas/databases and container building |
| Memory | **5.8 GiB RAM**, ~2.6 GiB used, ~3.1 GiB available | Available estimate is not a service reservation or proof full stack will fit |
| Swap | **2.0 GiB total**, **1.3 GiB used** | Existing paging/pressure; adding services could degrade unrelated workloads |
| Disk | Root filesystem **45 GiB**, ~**25 GiB used / 20 GiB free** | Container images, WAL, logs, backup and rollback retention need an explicit budget |
| Docker | Engine **29.7.2**, Compose **v5.5.0** | Container runtime available |
| Coolify | Image **coollabsio/coolify:4.7.6**, current container healthy | Coolify control plane is running; app sign-in/configuration still unverified |
| Container inventory | **31 containers total, 30 running** at inspection | Shared host already holds many existing workloads |
| Health | **2 running containers unhealthy**: one Celery worker, one web frontend | Diagnose with their project owners before adding load; do not restart automatically |
| Docker image usage | **42 images / 17.62 GB**, ~4.95 GB reported reclaimable | **Do not prune images**: rollback images may be required |
| Docker stats process count | **~6,960 PIDs** in one unhealthy web container at a single snapshot | Critical abnormality to investigate safely; may indicate process/thread leak or a metrics artifact; not yet diagnosed |
| Observed privilege boundary | `ubuntu` lacks direct Docker socket access but `sudo -n docker` read-only inspection succeeded | Do not assume Coolify application privileges or deployment API access |
| Host network | HTTPS login endpoint responsive, TCP 22/80/443 reachable | Not evidence of healthy tenant apps or authenticated control-panel access |

**Data limits:** no Coolify project names, credentials, environment variables, tenant databases, internal domains, secret values, private keys or personal production records are included in this report. Snapshot values can change and must be rechecked before use. Previous GitHub-only architecture documents do not prove deployed infrastructure.

## Critical architectural mismatch to resolve

- `infrastructure/local/docker-compose.yml` is **development-only**: includes local example secrets and Keycloak `start-dev`, localhost port binds, OpenFGA playground, disposable Moto. **Never deploy this file to public Coolify**.
- `infrastructure/oci/README.md` says **scaffold/no production apply authorized**. OCI managed database/KMS/object-storage/ingress architecture is designed, not automatically provisioned just because the app is running on an OCI VM.
- HILTECH server in `server/application/build.gradle.kts` targets **Java 21**. Packaging and jOOQ/Flyway code-generation dependencies require an intentional, reproducible container-build pipeline; no verified HILTECH production Dockerfile or Coolify stack exists on inspected main.
- Source `server/application/src/main/resources/application.yml` has **development-friendly defaults** such as inactive OIDC/OpenFGA/evidence integrations. Staging must set explicit secure values and fail closed; no fallback to localhost, default DB credentials, or skipped authorization.
- Android and Windows are **native distributable clients**, not web pages hosted by Coolify. Coolify serves backend services; signing/distribution/update and device acceptance require separate gates.

## Deployment route — recommended

**Do not deploy full HILTECH stack on this already-busy 1-vCPU VM right now.** First resolve unhealthy/PID situation, collect CPU/RAM/disk baseline under expected load, assess backups/recovery, and obtain a capacity decision.

Preferred options, in order:
1. **Keep existing Coolify as control plane**, register a **separate, properly sized staging server/VM** for HILTECH. No new Coolify installation required. Provision only after explicit owner approval of resource/cost/security boundary.
2. Reuse this exact VM **only after** existing app owners verify workload stability, CPU/PID/memory/disk budgets, retained rollback images, tested backups, and isolated HILTECH services. No unreviewed app shutdown or Docker cleanup.
3. For production, review alignment with the frozen OCI service architecture and data/privacy/residency requirements; staging on Coolify does not silently replace the OCI production contract.

## Ordered staging gates

- [ ] **Host risk investigation**: investigate both unhealthy containers and `~6,960` PID observation through read-only, privacy-preserving diagnostics; identify impact and owner. Do not reset, kill or restart unrelated services.
- [ ] **Capacity budget**: measure peak CPU/RAM/swap/disk and rollback reservation; estimate Java 21 API, PostgreSQL domains, Keycloak, OpenFGA, object-storage endpoint, telemetry, ingress and concurrent apps. Require headroom under load.
- [ ] **Backup / recovery**: inventory existing backup destinations/schedule without reading secrets; obtain a tested restore to a disposable environment and identify RPO/RTO. No staging deployment without recovery path.
- [ ] **Isolation**: separate Coolify project, internal network, restricted volumes, DB accounts/permissions, object-store bucket, staging DNS/TLS, logging and worker budgets. Public ingress only for reviewed services.
- [ ] **Packaging**: add reviewed Java 21 backend image build with SBOM/immutable digest and non-root runtime; no compile on the crowded shared VM. Use exact SHA and CI artifact provenance.
- [ ] **Dependencies**: provision PostgreSQL, Keycloak, OpenFGA, storage and telemetry with no hard-coded creds/anonymous public ports. Configure OIDC native redirects, permission model IDs, signed evidence storage and scanner policy.
- [ ] **DB and auth**: test Flyway V00xx migration on disposable PostgreSQL 18.x, jOOQ generation, OpenFGA integration, identity/session revocation and ACL leakage before staging.
- [ ] **First smoke**: deploy **only after separate scoped approval**; synthetic company data, feature flags OFF, health endpoints, login/role access, Evidence READY, field offline replay, rollback rehearsal.
- [ ] **Native clients**: internal signed Android APK / Windows installer and staging-config contract; actual device screenshots, performance and operator evidence. Windows update/rollback and local DB preservation tested.
- [ ] **Release**: per `docs/13-delivery/RELEASE_STRATEGY.md`, no blanket activation or production data copied into test.

## Guardrails

Allowed now: audits, research, staging designs, reviewed PRs, CI validation and non-production disposable tests.  
Not allowed by this report: changing host firewall, enabling public DB/OpenFGA, reading/exposing keys or app secrets, altering existing Coolify apps, destructive Docker commands, installing a new production stack, or deploying without real isolation/backup/resource acceptance.

See `scripts/devops/coolify-readonly-audit.sh` for a repeatable on-host **read-only** resource and Docker summary. Do not publish its detailed container listing if it reveals private app metadata.

## Decision / handoff

`COOLIFY_HOST_SSH = VERIFIED`  
`COOLIFY_HOST_RUNTIME = VERIFIED`  
`HILTECH_STAGING_CAPACITY = NOT APPROVED`  
`HILTECH_STAGING_DEPLOYED = NO`  
`EXISTING_CONTAINERS_MODIFIED = NO`  
`NEXT_SAFE_ACTION = resolve unhealthy/high-PID root cause, confirm backup+capacity, review deployment target and packaging PR`.
