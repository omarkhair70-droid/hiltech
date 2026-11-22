# 2026-11-22 — Authenticated Coolify API inventory and backup coverage

**Authority:** owner-authorized self-hosted Coolify API via official Windows CLI v1.11.0, context `hiltech-oci`; calls were read-only (`context verify`, `server/project/resource/app/database/service list`, `server get`, `server get --resources`, `server sentinel get`, `database backup list/executions`, `s3 list`). No token, cookie, database URL, secret, log line or filesystem pathname from application mounts was copied to GitHub. **No deployment, restart, delete, reconfigure, provisioning or spending.**

## Verified fleet facts

- Authentication: **PASS**, Coolify version **4.7.6**.
- **2 servers** (both Coolify API `settings.is_reachable=true`, `is_usable=true`, `server_role=both`), **6 projects**, **16 resources**: **6 applications**, **8 standalone databases**, **2 services**.
- `localhost`: **15 resources**, existing shared Oracle host with known limited 1 OCPU/6 GB (older read-only host audit), heavy project mix. `teswa-core01`: **1 resource**, Teswa service `running:healthy`, behind Tailscale per owner dashboard. No evidence yet of Teswa host CPU/RAM/disk/free resources. **Do not repurpose Teswa or stop its service**.
- Coolify Sentinel `is_sentinel_enabled=true` on both servers but `is_metrics_enabled=false`. Official public Coolify API does not expose an established CPU/memory capacity endpoint in this installed version. Historical CPU/RAM capacity cannot be inferred from `is_usable`.
- Application statuses: `nova-backend` `degraded:unhealthy`; `balcona bar` `running:unhealthy`; `opportunity-os-staging` `running:unhealthy`; `Contact Thermography AI` and `Tuscanini` `running:healthy`. Several other app/service health values are unknown. **No automated cleanup solely from health/name**.
- Standalone DBs: `nova-postgres` `exited:unhealthy` (may contain retained data), `nova-postgres-18` and known preview DBs/Redis appear healthy; no verified current usage dependency map.

## Backup config evidence (Coolify-level only)

Coolify API `database backup list <uuid>` on **all 8** databases:

| Database | Coolify scheduled-backup configurations | Status |
| --- | ---: | --- |
| nova-postgres-18 | **1** | **enabled**, daily; `save_s3=false` |
| nova-postgres (old) | 0 | No Coolify backup schedule |
| plantupia-preview-db | 0 | No Coolify backup schedule |
| contact-thermography-db | 0 | No Coolify backup schedule |
| Tuscanini PostgreSQL | 0 | No Coolify backup schedule |
| Redis database (unnamed) | 0 | No Coolify backup schedule |
| plantupia-preview-redis | 0 | No Coolify backup schedule |
| nova-redis | 0 | No Coolify backup schedule |

Nova's daily backup configuration has **7 execution records**, all with `status=success`. This **does not prove a restore** or offsite retention. The CLI `s3 list` yielded literal `null` with exit 0 (no Coolify S3 storage targets listed). There may be *external independent backups outside Coolify*, which these API calls **cannot discover or disprove**. Redis requires an explicitly designed persistence/backup strategy, rather than assuming DB-aware PostgreSQL dump support applies.

**NO-GO for HILTECH company records:** no verified off-host backup/restore for dedicated HILTECH PostgreSQL + Keycloak DB + OpenFGA DB + object evidence; even Nova's currently working scheduled backup is local-only in the observed settings.

## Resulting decision: do NOT conflate one workload with another

1. `teswa-core01` is reachable and its single Teswa service healthy, but neither its owner-permitted capacity nor workload headroom is verified. Not assigned to HILTECH.
2. Original Oracle host is known CPU-constrained and has existing unhealthy apps. Do not add a new production-shaped full service stack yet.
3. Storage and auth for HILTECH should use dedicated data/identities, least privilege and independent **offsite** backup with witnessed disposable restore **before** real employee data.
4. Owner-controlled small pilot remains viable **conditionally** after measuring host headroom, verifying real Keycloak/OpenFGA/Evidence, resolving health/readiness #137 and native cross-role acceptance.
5. If the current host cannot fit the complete service stack, keep Coolify as a management plane and use an appropriately sized separate runtime host; funding requires explicit approval.

## Other operational observations

- Coolify GUI shows pending security patches/reboot on both servers; no updates/reboots performed.
- Exact VM CPU/RAM/disk metrics for Teswa require owner-authorized terminal/agent status or explicit monitoring enablement; turning on metrics and restarting Sentinel is a **change** requiring separate approval. Coolify's Sentinel metrics flag currently false.
- Database backup configurations are not equivalent to **Coolify's own internal database backup** or backup of volume/object evidence. Each must have independent coverage.
- All terminal/CLI inventory checks were conducted without `--show-sensitive`.
- Original private allowlisted JSON lives only on the authorized Windows workstation under `%LOCALAPPDATA%\HILTECH\coolify-private-inventory.json`.

**Status:** `COOLIFY_API_CONNECTED=YES`; `FLEET_INVENTORY_COMPLETE=YES`; `TESWA_CAPACITY_VERIFIED=NO`; `OFFSITE_BACKUP_AND_RESTORE_VERIFIED=NO`; `PREVIEW_STOP_AUTHORIZED=NO`; `HILTECH_DEPLOYED=NO`.

## Repeatable fail-closed check — implemented and run

- Script: `scripts/devops/coolify_backup_coverage.ps1`, also installed locally for the owner at `%LOCALAPPDATA%\\Programs\\CoolifyCLI\\hiltech-backup-coverage.ps1`.
- It uses **only** official read-only Coolify API/CLI calls for backup configuration and S3 destination count; it does not expose credentials, backup paths/filenames, secrets or full API response.
- Real connected run, same day: **8** standalone databases, **1** with an active local scheduled backup, **0** registered Coolify S3 targets, **8** databases without both a schedule and configured S3 upload, **0** unknown responses.
- Result **BACKUP_COVERAGE_NO_GO**, **exit code 1** (expected and correct). No backup triggered, deleted, modified, created or restored.
- This script cannot detect unrelated external backup tools and cannot confirm recovery. Independent complete restore drill remains a separate mandatory gate.

A future owner-authorized off-host backup and restore may change the real readiness outcome. Do **not** change exit code, hide failed gates or declare storage safe based only on the presence of a local scheduled backup.
