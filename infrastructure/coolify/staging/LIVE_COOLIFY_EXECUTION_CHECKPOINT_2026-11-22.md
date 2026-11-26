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


## 2026-11-22 late checkpoint — verified native Keycloak Realm import

- The user approved a security review permitting the authorized Coolify API to correct the **non-secret** native OIDC Realm JSON file mount. This fixed earlier Windows CLI quoting damage. On retrieval from Coolify, the mounted JSON successfully parsed and showed `realm=hiltech`, `clientId=hiltech-native` and `pkce.code.challenge.method=S256`.
- Staging Keycloak Dockerfile was restored to `CMD ["start", "--import-realm"]`, deployed successfully under UUID `hdcv8jgvtxqt9uhyv0pj5atc` (deployment `rbuceosqbp7mm9qcamixjxob`). Application logs explicitly report `Realm 'hiltech' imported` and `Import finished successfully`.
- A new **cross-container** HTTP probe, from RustFS through private DNS `hiltech-keycloak-private:8080`, received **HTTP 200** from `/realms/hiltech/.well-known/openid-configuration`. This demonstrates an actual native realm discovery response, **not** a completed authorization-code/PKCE browser sign-in.
- The Keycloak Dockerfile on this PR now matches the verified import startup (commit `e5b42c397964fc24d05a8ba291d8ae401ba1ee8c`). Keycloak still advertises an internal HTTP hostname, with no approved external HTTPS issuer or public domain. It is therefore **NOT ready for production or native login cutover**.
- The separate security-review request for temporary transfer of an existing OpenFGA staging preshared credential to the internal bootstrap runner was **not** part of the approved Keycloak correction. Do not assume it ran without its own reviewed execution result.
- A dedicated `HILTECH Internal OS` Coolify production environment exists (`eo70wvnwfgwcrmrrw2ogyn2b`), but no production services or data have been deployed. Draft [PR #160](https://github.com/omarkhair70-droid/hiltech/pull/160) has passing **static** DNS/TLS/OIDC/health safety-gate tests; that is not a live public HTTPS test.

## 2026-11-22 secured API image + real bootstrap dry-run

- **Reviewed OpenFGA-authenticated API image published.** Source SHA `634e6dd1ec265dfd49f669a197125f04bf9d0fc1`, from draft PR #159. GitHub Actions [run #45069429956](https://github.com/omarkhair70-droid/hiltech/actions/runs/45069429956) completed **success** for both the bearer authorization contract tests and full disposable-Postgres Java 21 / multiarch package proof, then published `ghcr.io/omarkhair70-droid/hiltech-server@sha256:d5254f53580322d9b2a07fc5adc1de3f813cd1e8b239535ba81d51d9c239b2b9`. Both linux/amd64 and linux/arm64 are recorded in the retained artifact. This image is **not deployed**.
- The existing capped API candidate `wgjw7g8howou32biinncnxul` was updated to pin the **new immutable digest**, exposed internal container port `8080`, and stored private FGA API URL `http://hiltech-fga-private:8080`; configured private Docker network alias `hiltech-api-private`. Remains **unstarted**, with no public FQDN and 0.55 CPU / 1536MB caps. Do not launch until FGA store/model, JWT issuer, database and evidence configuration are complete.
- Added a pinned, nonroot, no-port OpenFGA initialization Dockerfile and idempotent reviewed model bootstrap script in draft PR #155. [GitHub Actions run #45069686125](https://github.com/omarkhair70-droid/hiltech/actions/runs/45069686125) **passed** both the bootstrap unit tests and container-build/no-network dry-run.
- Created private helper resource `hiltech-fga-bootstrap-runner` UUID `x9jk1tkqoaktfks90clxkfcy` on shared staging, caps 0.15 CPU / 128MB, Docker network alias `hiltech-fga-bootstrap-private`, no public route. Live deployment `rrub7763iwuo2xcotndgexnc` **finished**. The disabled one-off Coolify task `ibt0b9blu7wrbmtscez20wua` executed `python /opt/hiltech-fga/bootstrap.py --model /opt/hiltech-fga/first-slice-model.json` and returned **success**: `DRY RUN: reviewed model valid, types=14; no network or writes`. A stop request was sent afterwards to minimize shared-server resource use; final stopped status was not yet confirmed at the time of the first follow-up. No FGA model/store mutation happened.
- **Security review gate outstanding:** a new device review was surfaced to temporarily transfer existing OpenFGA preshared authorization key to this **isolated helper** as private Coolify environment variable. It remains `awaiting_user` and **must not be bypassed**; a read-only check confirmed `HILTECH_FGA_BOOTSTRAP_KEY` absent. Once approved: ensure helper running with new env, change the disabled task command to explicit `--apply`, execute once, capture nonsecret store/model IDs, verify contextual allow + outsider deny, delete temporary helper credential, and stop the helper. Do not create duplicate stores or copy real company tuples.
- Latest direct health check: separate Teswa service `running:healthy`; Keycloak, bearer-protected OpenFGA and RustFS `running:unknown` privately; capped API `exited:unhealthy` because it has **not been deployed**. All HILTECH app FQDNs remain blank. Production environment is empty, and native Android/Windows E2E and public HTTPS remain pending.

## 2026-11-22 23:xx Cairo — owner-reviewed live FGA activation: PASSED

- User-approved security-review command completed with exit code 0 and saved **one temporary private copy** of the OpenFGA pre-shared credential in the isolated `hiltech-fga-bootstrap-runner` Coolify environment. No credential value was printed or committed. Its private nonroot container was started only for this task.
- The disabled one-time task `ibt0b9blu7wrbmtscez20wua` was manually executed **once** with `--apply`. Coolify returned `status=success` and the script printed `PASS: internal staging OpenFGA model with contextual allow/deny; no permanent QA tuples`.
- Real **staging-only** store: `hiltech-internal-staging`, ID `01M82HW5JVHE75XSKPSK3CZ7YE`. Reviewed 14-type schema model ID `01M82HW5K678TMQ73MPW7MNC82`. Both are returned by live OpenFGA, **not placeholders**.
- Both IDs were saved as nonsecret `HILTECH_FGA_STORE_ID` / `HILTECH_FGA_MODEL_ID` in the **inactive** capped API candidate `wgjw7g8howou32biinncnxul`. Also set native client `hiltech-native` and nonsecret RustFS storage region `us-east-1` / bucket `hiltech-internal-evidence-staging`. API stays **stopped**, with no public route; proper HTTPS issuer, S3 HTTPS endpoint/credentials and DB secrets are not yet configured.
- Cleanup caveat: authenticated Coolify CLI `app env delete` was rejected **HTTP 403 Missing required permissions: delete** for the *temporary helper copy only*. As mitigation, the helper was stopped and its environment key was **overwritten with a deliberately invalid sentinel** `REVOKED_NO_VALID_BOOTSTRAP_KEY`, which cannot meet the script's secret validation; the disabled one-shot task command was reset to nonmutating dry run. The helper was subsequently observed `exited:unhealthy` (expected for stopped one-shot service), no public FQDN; the original OpenFGA service's working key was **not modified**. The **name** of the temporary variable still exists in Coolify and needs an owner with delete permission to remove it permanently. Do not call this a fully deleted variable.
- Teswa service `wn3d5xkyj3wpbldo4jp0wlec` was rechecked `running:healthy`. No real staff, customer or warehouse tuples were inserted.
- **Still NOT done:** end-to-end API authorization with real OIDC JWTs, public HTTPS issuer, HTTPS-native presigned object URLs, isolated production database and recovery, signed native releases, and closure of Engineering phases. Staging FGA validation success is not production readiness.

