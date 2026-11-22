# HILTECH — verified shared-Coolify staging (2026-11-22)

**Actual runtime**, not the not-yet-deployed `compose.yaml` candidate. Owner approved using the shared `teswa-core01` host for **synthetic staging**, accepting possible resource contention and no new VM charges. **Do not deploy the reference Compose over these existing resources.** Existing Teswa service was last observed `running:healthy`; no Teswa service or volume was deleted, stopped or modified.

## Live resource inventory

| Resource | UUID | Verified status and limits |
|---|---|---|
| App PostgreSQL | `ei5x0hwvcvbbzaswr6jwwmr9` | Private, `running:healthy`, 0.4 CPU / 512 MB |
| Keycloak PostgreSQL | `yet9pjfbohz0wzs65xffgate` | Private, `running:healthy`, 0.25 CPU / 384 MB |
| OpenFGA PostgreSQL | `5vd1og3yyimgqubqrpq9tnzi` | Private, `running:healthy`, 0.25 CPU / 384 MB |
| Keycloak v26.7.4 | `hdcv8jgvtxqt9uhyv0pj5atc` | Dockerfile runtime, `running:unknown`, logs show prod startup and schema init; internal :8080; 0.6 CPU / 1152 MB, no public FQDN |
| OpenFGA v1.20.0 | `dod3foopx0kqpkafyxarcmga` | Dockerfile runtime, `running:unknown`, **preshared auth enabled**; 0.3 CPU / 384 MB, no public FQDN, internal `hiltech-fga-private:8080` |
| One-shot OpenFGA migration | `ekmx5nbtd2gj5smhqq79s6if` | PostgreSQL schema migrated **0 -> 6** and helper stopped intentionally; its `exited:unhealthy` status is normal for the stopped helper |
| RustFS 1.0.6 S3 storage | `vp6uhpyidhl7cewuhxlunlen` | `running:unknown`, no public FQDN, 0.35 CPU / 640 MB, internal `hiltech-evidence-private:9000`; isolated persistent volume `vp6uhpyidhl7cewuhxlunlen-hiltech-rustfs-evidence-staging-volume` mounted at `/data` |
| Capped immutable-image Spring API candidate | `wgjw7g8howou32biinncnxul` | **NOT started**, no public FQDN, 0.55 CPU / 1536 MB; OIDC/FGA/evidence require full configuration |

Earlier failed or superseded applications (`asnlu4h5knflvfqw837kp40s` old Keycloak, `sq08tht73kkhr6svkwc2fmrj` old OpenFGA, `pkysbfynkffrexh9tqhhtg99` old uncapped API) must **not** be used as the deployment targets.

**Note:** these are Coolify point-in-time status readings; `running:unknown` is **not** a passing configured healthcheck or an end-to-end proof. The source Compose reference defines a *single* Postgres server with multiple databases whereas these three PGs are independently deployed. No real employee, salary, stock or asset records were introduced.

## Verified authentication/network integration

- Previously a RustFS-to-OpenFGA probe could not resolve either the app UUID or the app's display name via Docker DNS. The Coolify destination selection alone did not imply `connect_to_docker_network=true`.
- A strong random preshared key was stored in **private Coolify environment variables** for OpenFGA and a matching key in the **inactive** Spring API candidate. No keys printed, committed or copied to a scheduled task.
- OpenFGA was redeployed with `OPENFGA_AUTHN_METHOD=preshared`, then given the stable internal name `hiltech-fga-private` and connected to the predefined Docker network. RustFS was also joined through `hiltech-evidence-private`; all public FQDNs remain blank.
- From inside RustFS, `curl -fsS http://hiltech-fga-private:8080/stores` returned **HTTP 401**, demonstrating successful internal DNS/routing **and rejection without a token**. Positive authenticated OpenFGA checks and real store/model setup **remain incomplete**.
- OpenFGA internal HTTP traffic is **not TLS**, so do not expose its port outside the host or network. Keycloak is not yet configured with proper HTTPS OIDC issuer/native redirect URIs.

## Actual S3 storage proof

Bucket `hiltech-internal-evidence-staging` was created on RustFS using the locally stored S3 access/secret credentials and AWS Signature V4. A synthetic object at `synthetic/health-probe.txt` completed all checks:

1. Signed S3 PUT: **success**.
2. Signed S3 GET: **success**, returned bytes exactly matched `HILTECH_STAGING_SYNTHETIC_PROBE_20261122`.
3. Anonymous GET of same object: **HTTP 403** (not public).
4. Signed DELETE: **success**.
5. Signed GET after deletion: **HTTP 404**, confirming cleanup.

Bucket remains for staging; the synthetic object was deleted. This is **not yet** an HTTPS presigned-URL test on Android or Windows. CORS, tenant-scoped evidence access, AV, object retention, backups and restoration require additional validation.

The probe tasks on RustFS are **disabled** (not recurring). Attempts to delete their task definitions returned Coolify API 403 missing delete permissions, so leave them disabled pending owner cleanup. No secret values are stored in task command strings; the S3 commands refer only to existing environment-variable names.

## Source and remaining gates

- [Draft PR #155](https://github.com/omarkhair70-droid/hiltech/pull/155) contains Compose reference and Dockerfile/deployment docs. It must be reconciled with real live topology before a fresh installation.
- [Draft PR #159](https://github.com/omarkhair70-droid/hiltech/pull/159) implements OpenFGA preshared bearer HTTP requests, fail-closed validation, and loopback transport tests in Spring. The [dedicated security run](https://github.com/omarkhair70-droid/hiltech/actions/runs/45057839739) and [server package proof](https://github.com/omarkhair70-droid/hiltech/actions/runs/45057839567) passed. The changes have **not been merged or shipped into the current immutable Spring API image**, so do not deploy that old candidate with an assumption of authenticated OpenFGA support.
- Canonical reviewed policy is `docs/13-delivery/first-slice-contract-pack/openfga/first-slice-model.fga`. GitHub CI candidate `.github/workflows/devops-fga-live-model-export.yml` is intended to convert it with official CLI into versioned JSON. Do not invent or substitute a policy model.
- Before starting Spring API: create dedicated FGA store/model and configure IDs; publish updated authenticated API image; configure Keycloak realm and HTTPS issuer/audience plus Android/Windows native OIDC clients; arrange native-reachable HTTPS S3 presigned URLs, scoped bucket rights and cursor signing keys; run readiness, auth allow/deny, evidence, QR/stock/workflow client E2E and backup/restore. Sensitive business-operation feature flags remain OFF until approval.

**Bottom line:** PostgreSQL, Keycloak, authenticated OpenFGA infrastructure, and internal S3 storage have actually been deployed and partially integration-tested on shared Coolify; **HILTECH is not yet fully deployed or production-ready**. See [HILTECH issue #133](https://github.com/omarkhair70-droid/hiltech/issues/133) for individual execution checkpoints.
