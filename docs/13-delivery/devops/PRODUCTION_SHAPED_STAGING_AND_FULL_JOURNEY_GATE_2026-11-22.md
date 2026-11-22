# HILTECH — Production-shaped integration environment and whole-journey release gate

Date: 2026-11-22  
Owner direction: **Coolify is not a place to publish a disconnected demo**. Build a persistent production-shaped environment which can run, test and retain the single internal HILTECH OS through Phase 13, then promote the proven artifact to a separately protected production environment. All existing unrelated Coolify projects **remain**.

Status: **CONTRACT / IMPLEMENTATION PLAN ONLY — NO DEPLOY, NO PRODUCTION GO**. This document is not a claim that any of the gates have passed.

## The essential distinction

- **CI integration:** ephemeral GitHub-hosted PostgreSQL/OpenFGA and Java21 nonroot Docker. The Phase6 integrated source [#140](https://github.com/omarkhair70-droid/hiltech/pull/140) has achieved a successful image/runtime smoke on an exact commit; that proves boot and fail-closed anonymous requests, **not** production operations.
- **Persistent pre-production (staging):** production-shaped protocols, real PostgreSQL persistence, real Keycloak non-dev mode, real persistent OpenFGA + canonical policy model, *private durable* object storage, real TLS/HTTPS identity redirects, actual signed native clients and role-specific test accounts, real migration/recovery/telemetry. **Synthetic company data** are deliberately created and remain distinct from company data.
- **Production:** a distinct, access-controlled deployment/DB/bucket/identities with real HILTECH data and explicit owner/operator approval. It may be orchestrated by the **same Coolify control plane** but must not share staging credentials, mutable data stores, object buckets or unsafe public ports.
- **Release parity:** promote the *same immutable image digest* and matching Android/Windows release code to production; environment-specific configuration/secrets differ. No rebuilding from a moving branch or silently turning test credentials into real credentials.
- **Scope:** HILTECH internal Phases 0–11 and 13. Phase 12 only internal scoped facility/security work based on verified reality; vendor CCTV/access integration deferred until real hardware/consent. No external supplier/client/NOC surfaces (14–16) without new approval.

## Deploy topology (target — not current deployed state)

```text
Existing Coolify control plane [retain existing tenant apps]
    |
    +-- hiltech-preprod [private / stable / synthetic data]
    |   HTTPS ingress -> HILTECH API [Java 21 nonroot, immutable image]
    |                 -> HILTECH PostgreSQL DB + own account
    |                 -> Keycloak [production mode] -> own PostgreSQL DB/account
    |                 -> OpenFGA [persistent datastore] -> own PostgreSQL DB/account
    |                 -> private S3-compatible Evidence object storage
    |                 -> logs, metrics, trace/correlation, backup/restore
    |   Windows tester + Android tester -> same HTTPS API / native OIDC
    |
    +-- hiltech-production [NOT ENABLED before release gate]
        Same service contracts / image digest; entirely separate live data,
        credentials, database ownership, bucket, ingress, release policy
```

No public PostgreSQL, OpenFGA, Keycloak admin, object-store admin or diagnostics ports. Keycloak `start-dev`, anonymous or all-allow authorization, in-memory FGA, disposable Moto and example passwords are **CI/local only** and must never be used as substitutes for persistent staging. Keep default-**OFF** business rollout flags until explicitly reviewed for limited staging use, then production role rollout.

## Capacity and deployment constraints

Existing Oracle/Coolify VM read-only inspection: **1 vCPU, ~5.8 GiB RAM, ~3 GiB available, 20 GB disk free**, ~30 running containers and two unhealthy services; short samples observed CPU idle but do not demonstrate full-stack headroom. **Do not simply deploy the entire stack there.** The existing Coolify can manage a *separate* appropriately budgeted execution host, while the original apps remain intact. Before allocating new services, record a load-test/peak and resource reservation, isolation, restart policies, backpressure, backup destinations and rollout rollback constraints. Costs/quotas and operational deployment require approval. An isolated limited CI test is not a capacity proof.

## Non-negotiable operational gates

| Gate | Required proof before persistent staging | Current evidence |
| --- | --- | --- |
| Exact-source release | reviewed integrated SHA, reproducible Boot JAR, immutable tagged/pushed registry **digest**, SBOM/scanning, no run-time build on shared host | Java21 image CI pass; registry/release not yet proven |
| Provisioned isolation | dedicated Coolify project and constrained CPU/memory/PIDs, private network, separate staging databases/users/volumes/bucket and DNS | NOT VERIFIED |
| Identity & access | real non-dev Keycloak OIDC issuer, correct native PKCE redirects, user/session/device revocation, non-privileged and forbidden role tests | CI-only security smoke; persistent staging not verified |
| Authorization | canonical OpenFGA model in persistent store with exact store/model identifiers, access denial and projection/revocation behavior | Canonical in-memory CI only |
| Data durability | PostgreSQL 18 schema/migration compatibility, per-service DB ownership, secure object storage, private Evidence PUT/finalize/read + integrity/malware review | CI DB migration, local contracts; staging not verified |
| Observability | liveness and DB/dependency readiness, structured logs and correlation IDs, error/queue/latency metrics, alert and runbook | Health endpoint gap [#137](https://github.com/omarkhair70-droid/hiltech/issues/137) OPEN |
| Recovery | encrypted backup inventories (business + Keycloak + OpenFGA + object storage), witnessed restore to disposable target, RPO/RTO, schema and image rollback playbook | NOT VERIFIED |
| Native distribution | Android signed internal build, Windows signed installer, same API/auth configuration, version compatibility, local DB persistence through update | CI compiles; real signed device release acceptance pending |
| Whole-journey proof | role-by-role test ledger, clean negative tests, real Android/Windows sessions, server authoritative state, private Evidence and audit; owner signoff | NOT VERIFIED |
| Release promotion | independent review, merged source + post-merge CI, same image digest, staged rollout, rollback and production approval | NOT AUTHORIZED |

## The first real cross-device journey — must be end to end, not seven demonstrations

An authorized HILTECH human operator must complete and observe this as a single connected history:

1. **Identity / People:** authorized administrator provisions manager, warehouse operator, field technician and supervisor; native PKCE login/device registration works, wrong-role view is denied.
2. **Project / Site / Work:** Windows PM creates project/site/work order, binds current requirements, assigns a real test technician; version, role, canonical work history and inbox are consistent.
3. **Warehouse:** authorized operator receives/locates/reserves/checks out approved asset or materials for that work; AssetTag/QR lookup agrees with ledger, custody, availability and current assignment.
4. **Android:** assigned technician receives the scoped WorkOrder, captures Start/Block/Resume, works offline, force-stops/restarts and resumes with durable Room queue; unrelated technician/device cannot access or replay the job.
5. **Evidence / engineering:** photos and required files upload privately to durable object storage with integrity provenance, suitable scanner/policy gates; engineering test result follows **approved customer-specific definitions**; drawing revision freshness conflicts fail closed. Never invent an OTDR/Fluke threshold or drawing approver.
6. **Material consumption / completion:** pending material reports go through canonical warehouse reconciliation, not secret stock mutation. Finish checklist, evidence and test gates; Submit Completion once despite duplicate response or offline retry.
7. **Supervisor / PM:** permitted Windows supervisor reviews/accepts or requests rework; manager and field client observe the same authoritative WorkOrder version, stock/custody state, activity, audit trail and exception status.
8. **Security & operations:** revoke device/session midway and verify denial; demonstrate DB/object restore, retry/503, stale-data conflict, audit correlation and rollback without orphaned operations.

**Acceptance unit:** one linked trace ID and identifiers across Windows, Android, API, database, OpenFGA decision, object evidence, ledger and audit. Each step requires evidence and an accountable owner. A web screenshot or server 200 alone is **not** acceptance. Tests must also prove rejection for cross-user access, old tokens, stale versions, unsigned file reads, lost replies, duplicate command IDs and invalid stock or approval authority.

### Phase 7–13: extend the SAME system, never build isolated demos

| Phase | Incremental connected production-shaped rehearsal |
| --- | --- |
| 7 Procurement | Work requirement -> stock-before-buy -> approved purchase flow -> canonical receiving -> warehouse ledger -> finance invoice evidence handoff. Must use company-authorized suppliers/approver policy. |
| 8 Finance | approved operational evidence -> expense/advance/imprest/AP/AR -> authorized posting/payment handoff -> reconciliation and project visibility; no simulated bank as real bank execution. |
| 9 Payroll | approved workforce/time/advance data -> review -> authorized payroll result -> employee visibility, correction, finance handoff; real policy and salaries require authorized source. |
| 10 Sales | opportunity/RFQ -> versioned quote/approval -> won project creation -> same Work/warehouse/finance lineage. |
| 11 Handover / support | approved completion + evidence/drawings -> handover/warranty -> support ticket/maintenance -> project and financial history. |
| 12 (deferred adapters) | only verified internal facility registry/events; no invented CCTV/NVR live integration or remote-control authority. |
| 13 Executive | Mohamed's authorized Home/Needs You/Company Pulse consumes *actual* project, work, warehouse, procurement, people and finance data; drill-down, exceptions and approvals return to their origin, not fake counters. |

**Each vertical release** must add to the persistent end-to-end journey, update a regression matrix and exercise both happy and failure paths. No Phase may be called COMPLETE if the cross-domain chain is broken. Reference `docs/13-delivery/DEFINITION_OF_COMPLETE.md`.

## Deployment order and safety

1. **Now, without changing live Coolify:** freeze this environmental contract; finish integration PR #140, resolve health/readiness #137, CI proof of all permitted feature flags, reproduce image digest/registry and confirm test-native artifacts.
2. **Before persistent deployment:** select and measure an adequate isolated host/budget; produce secret/DB/bucket separation; prove backup and disposable restore; validate TLS/OIDC, production-shaped Keycloak/OpenFGA and no exposed admin ports.
3. **Create only the preprod environment:** deploy by reviewed immutable digest using synthetic users and records, limits and healthchecks. Migrate only its empty DB; retain existing tenants.
4. **Perform whole-journey rehearsal after each integrated Phase:** Windows and Android actual signed builds against the same preprod API; maintain evidence, negative tests, a measured performance and recovery report.
5. **Production activation only after Phase-scope signoff:** reviewed promotion of the tested digest, distinct live secrets/storage, controlled employee rollout, monitoring and rollback drill. Don't claim complete Phase 13 before actual authorized operations prove it.

**STOP immediately** on permission/data leakage, missing verified data owner, duplicate stock/finance action, unknown technical approval rule, unproven backup/recovery, service pressure on other tenants, unsafe public endpoints or mismatch between tested and deployed digest.

## Exact live state at authoring

- Engineering integration: [#140](https://github.com/omarkhair70-droid/hiltech/pull/140) (Draft, not merged); CI Java21 container startup has passed an integrated commit. Phase6 further acceptance and merge gates are distinct.
- DevOps: [#135](https://github.com/omarkhair70-droid/hiltech/pull/135) (Draft). Current GitHub image is **CI-only** and has **not** been pushed or deployed.
- Tracking: [#133](https://github.com/omarkhair70-droid/hiltech/issues/133), [#130](https://github.com/omarkhair70-droid/hiltech/issues/130), health [#137](https://github.com/omarkhair70-droid/hiltech/issues/137).
- **PERSISTENT_PREPROD_DEPLOYED=NO; PRODUCTION_DEPLOYED=NO; CROSS_DEVICE_E2E_ACCEPTED=NO.** No changes to other live Coolify projects or their data.
