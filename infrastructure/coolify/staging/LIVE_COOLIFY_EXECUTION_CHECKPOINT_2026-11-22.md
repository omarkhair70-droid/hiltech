# HILTECH staging — live Coolify state (2026-11-22)

This is a point-in-time **execution checkpoint**, not a deploy approval or a claim that HILTECH is operational. User expressly requested trying the existing server alongside Teswa before buying another VM.

## Actual Coolify

- Control plane: Coolify 4.7.6, project `HILTECH Internal OS` (`i2xxnpik1gr6gcfqfahe2qss`), `staging` (`r3ygvl4ylezsit7a224hzapq`).
- Server: `teswa-core01` (`s2nvk0fyktzm7sk6aoobld5e`). Existing Teswa multi-component service remained `running:healthy` at last check; **do not stop it**.
- THREE separate PostgreSQL 18 resources, all **running:healthy**, `is_public=false`:
  - app DB `hiltech-staging-postgres` (`ei5x0hwvcvbbzaswr6jwwmr9`): 0.4 CPU, 512m.
  - Keycloak DB `hiltech-keycloak-staging-postgres` (`yet9pjfbohz0wzs65xffgate`): 0.25 CPU, 384m.
  - OpenFGA DB `hiltech-openfga-staging-postgres` (`5vd1og3yyimgqubqrpq9tnzi`): 0.25 CPU, 384m.
- Keycloak application `hiltech-keycloak-staging` (`asnlu4h5knflvfqw837kp40s`), version 26.7.4, 0.6 CPU / 1152m, `start` command configured; **not started** (Coolify displays `exited:unhealthy` before startup).
- OpenFGA application `hiltech-openfga-staging` (`sq08tht73kkhr6svkwc2fmrj`), version v1.20.0, 0.30 CPU / 384m, `run` command configured; **not started**, its separate PostgreSQL migration is not run.
- HILTECH Spring API Docker-image application was **created**, UUID `pkysbfynkffrexh9tqhhtg99`, using verified published commit tag `ghcr.io/omarkhair70-droid/hiltech-server:staging-fae1d7f36eb9adeaecfea6ccbd07baba6b2af646`, corresponding to image digest `sha256:6bf83b62746cf6811a610e0f59e9f35469a9fe4b7061bc01debe48c1d8e4d1e8`. **Not started**. Fail-closed `HILTECH_OIDC_ENABLED=true`, `HILTECH_FGA_ENABLED=true`, `HILTECH_EVIDENCE_STORAGE_ENABLED=true` were created as runtime environment variables. Resource `app get` showed `limits_cpus=0`, `limits_memory=0`; **set strict resource limits before any deployment**. CLI `app update --limits-cpus` unsupported and a metadata update returned 422. Do not claim the API is capped.

## Constraints and integration work

1. Keycloak database credential + random bootstrap-admin credential provisioning attempted with Remote Desktop Commander, **blocked twice by client security**; an exact-call review panel was presented to owner and was still pending at subsequent key-only inspection. Never work around the block, publish secrets, or treat a chat reply as execution of the review UI button.
2. All DBs are separately created by Coolify; the existing [staging compose candidate](compose.yaml) uses **one** PostgreSQL service and is not identical to live topology. **Do not deploy that compose concurrently** on top of the 3 existing databases; reconcile it or use independent Coolify resources with correctly scoped DB accounts.
3. Current auto-generated Keycloak and FGA domains are HTTP at a Tailscale `100.x` host. Do not present them as production-safe or globally reachable. Prepare correct HTTPS/public or private-access native OIDC redirect plan; no public PostgreSQL/FGA port.
4. No OpenFGA migrated schema/store/model, no Keycloak realm/native client, no S3 private evidence bucket, no real native-client E2E. `coolify service create minio` returned `404 Invalid service type` and **did not create a new MinIO resource**. Existing older MinIO belongs to another service; do not reuse its data/credentials without scope approval.
5. Shared host capacity remains small, and Teswa must remain untouched. Run load and memory checks during staged activation. Owner accepted performance risk for **synthetic staging**, but no authorization to delete projects or company data.

**Next safe action:** Complete the already-presented review for the legitimate credential configuration (or explicitly choose a different owner-authorized secure credential-entry workflow). Then provision scoped credentials via supported Coolify controls, establish internal app-to-DB DNS, OpenFGA migrations, Keycloak HTTPS/realm/client, private S3 evidence with native-client reachability, and start one service at a time while watching Teswa. Deploy the API only after OIDC + FGA + evidence integration and memory/CPU limits are verified. Preserve synthetic staging separation.
