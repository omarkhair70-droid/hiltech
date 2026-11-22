# HILTECH — Owner-directed small-team operational pilot on existing Coolify (conditional)

Date: 2026-11-22  
Status: **CANDIDATE / NOT APPROVED TO DEPLOY / NO CHANGES TO EXISTING SERVER**  
Companion documents: [full journey production-shaped topology](PRODUCTION_SHAPED_STAGING_AND_FULL_JOURNEY_GATE_2026-11-22.md), [OCI account and host audit](OCI_ALWAYS_FREE_CAPACITY_AUDIT_2026-11-22.md).

## Why this option exists

Owner clarified HILTECH first use is a **small real internal group**: company leadership, accounts, ~2 engineers, technicians and a warehouse operator. No need to buy managed enterprise OCI services at the start, provided safety, privacy and recoverability remain intact. HILTECH is still one real, connected internal operating system across Android and Windows, not an API demo or product mock.

**Decision:** Existing Oracle VM + Coolify may be evaluated for a **limited owner-controlled pilot**; previous full production-stack NO-GO remains appropriate **until measured and prepared capacity clears**. Production-shaped data, auth, backup and integration rules remain unchanged even at small user count. Option to move the stack later must be preserved.

## Verified shared host snapshot (read-only, no modifications)

- `VM.Standard.A1.Flex`, **1 OCPU, 6 GB RAM** in OCI Jeddah.
- Root disk 45 GB; 20 GB free; Docker images 17.62 GB, of which 4.95 GB reported reclaimable (**do not prune**).
- 30 running containers (31 listed); 2 unhealthy.
- Memory: ~3.1 GiB available *in one snapshot*; 1.3/2 GiB swap used.
- CPU load averages 1.04 / 1.18 / 1.05 on 1 OCPU, CPU PSI `some avg10=14.08`, `avg60=17.09` at sampled instant.
- Multiple branch preview containers with `-pr-240`, `-pr-241`, `-pr-253` name patterns; **not confirmed unused**. Other container names/images do not establish project ownership or deletion permission.
- One existing private-looking MinIO container and several PostgreSQL containers run for other projects. They are **not** automatically authorized for shared HILTECH use or schema access.

## Pilot deployment target (not an executed deployment)

1. Dedicated Coolify **HILTECH** project and dedicated private Docker network; exact API domain and native OIDC redirects; no public DB/FGA/admin ports.
2. **HILTECH Spring Boot** immutable image from reviewed integrated source and native-compatible release; Java memory budget, read-only FS/nonroot, CPU/PID/memory limits after tested sizing.
3. Dedicated **PostgreSQL** durable volume and least-privilege database users/schemas for HILTECH, Keycloak and OpenFGA. No reuse of another project's production DB. Migration path validated in CI, back up and rehearse restore before real users.
4. **Keycloak production mode**, issuer HTTPS, PKCE for Android/Windows, session/device revocation; persistent DB. No `start-dev`.
5. **OpenFGA persistent PostgreSQL** and canonical version-pinned authorization model, fail-closed scope, all negative tests.
6. **Evidence storage:** prefer an isolated, private, durable OCI Object Storage bucket within verified free allocation, or a separately backed-up S3-compatible bucket. Do not treat another app's MinIO data volume as HILTECH backup. Private short-lived signed upload/download, finalize/hash, scanning gate where arbitrary files are accepted.
7. Real health/readiness `#137`, metrics/logs/trace, project ownership and crash/CPU/swap alerts, deploy and rollback runbook.
8. Actual Android/Windows authorized devices and accounts follow one E2E journey: PM -> warehouse -> technician offline + Evidence -> supervisor review -> manager updates + audit. Pilot starts only with approved HILTECH policies, real recovery tests, and human acceptance.

**Not needed on day one:** Kubernetes, managed OCI PostgreSQL, cross-region active/warm standby, redundant load balancers, external supplier/client portals, NOC, Redis or Temporal absent verified demand. **Not waived:** password/auth security, authorization, offsite backups, data integrity, evidence access controls, restore rehearsal, real-device testing, migration safety.

## Capacity review and no-data-loss cleanup protocol

**Step A — establish ownership without changes:** map every Docker container to Coolify project, repository, environment (production/preview), persistent volumes, last request/use, business owner and dependencies. Inspect unhealthy containers for CPU/PIDs/healthcheck false positives. Avoid publishing private log contents.

**Step B — identify safe candidates only:** leftover PR preview deployments may be stop candidates after checking they have no active endpoint, no dependent traffic and no irreplaceable data. The user's proposed unused thermography project must be matched to an exact Coolify resource ID and repo, with backup/retention decision, before stopping. **Never delete Docker images/volumes or run prune based on inactivity or names alone.**

**Step C — reversible staging cleanup proposal:** owner approves exact resource IDs; back up any state; stop identified preview app(s) without removing volumes, record baseline and rollback/restart command. Review host CPU, memory, swap, storage and pressure for a representative load period. If the 1-OCPU host remains persistently under pressure, no new real-user workload may be added.

**Step D — isolated synthetic burn-in:** build image outside the host and pin digest; test minimal full HILTECH runtime in a disposable environment **after** resource/capacity review and health work. Set and verify per-container limits (not fabricated blanket limits), reserve host memory for existing tenants, run DB/Keycloak/FGA+API boot/restart, E2E smoke, safe load simulation and backup/restore drill. Capture sustained p95 latency, peak memory, CPU pressure, swap-growth, restarts and disk forecast.

**Step E — named pilot users:** only after stable burn-in, limited rollout with role-aware feature flags, test/native distribution, daily backup proof and explicit abort criterion if existing tenant health degrades.

## Stop conditions

- Unsafe CPU contention impacting tenant apps; sustained memory/swap pressure; any restart/OOM or data-loss alarm.
- No verified external/off-host data backup and restore for app/Keycloak/OpenFGA + evidence.
- Security gate failure, unauthenticated business access or unverified authority.
- Unable to meet interactive latency/Android sync needs or on-call support capacity.
- Other projects not positively identified or owner-approved for stopping; health issue not assessed.
- No dedicated data isolation and no tested rollback.

**Fallback:** HILTECH can run via a separate inexpensive VM under the *same existing Coolify control plane*. Keep a portable Docker/OCI image and backup; move runtime while retaining system URLs/config contract and data, as part of tested migration. This is less intrusive than risking the shared 1-OCPU host. The owner indicates budget may be available if needed, but this is **not authorization to spend**.

## Next auditable output

1. Inventory mapping: Coolify application/service -> Docker name(s) -> volume owner -> request/uptime -> KEEP/STOP_CANDIDATE/UNKNOWN; no automatic changes.
2. No-effect resource-recovery plan with backups and owner-approved reversible stop list.
3. Pilot sizing benchmark and proof ledger with exact runs and responsible owners.
4. Deployment only after real system source integration, health #137, operator acceptance and verified backup/recovery gates.

Current flags: `COOLIFY_PILOT_DEPLOYED=NO`; `PREVIEW_CLEANUP_AUTHORIZED=NO`; `CROSS_ROLE_PROD_ACCEPTED=NO`.
