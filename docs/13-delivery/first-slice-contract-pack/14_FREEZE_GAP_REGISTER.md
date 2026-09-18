# 14 — First-Slice Freeze Gap Register

Status: **CLOSED FOR FIRST-SLICE FREEZE / ACTIVATION ITEMS REMAIN**
Date: 2026-09-18

## Purpose

Keep the remaining blockers to FIRST_SLICE_FREEZE precise.

This file replaces broad statements such as:
- “reality still unknown,”
- “API still TBD,”
- “warehouse still needs validation,”
- “offline still needs proof.”

Those are no longer accurate at the same level.

Technical feasibility is closed.
Domain/configuration structure is now represented by contract candidates.

---

# A. Contract closure blockers

## A1 — Configuration finalization

Pre-code Freeze blockers:
- **NONE**.

Resolved design evidence:
- Configuration Center rendered and reviewed in final design run `35396169606`.

Bounded/deferred:
- persisted ARRIVAL_PROOF / ACTIVE_SITE_PRESENCE remains disabled until retention/notice seed values are approved.
- executable DDL/API tests are post-Freeze Bootstrap verification per 17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md.

Resolved:
- string bounds baseline.
- SYSTEM/ORGANIZATION scope.
- versioned WorkPolicyBinding history.
- no arbitrary custom requirement scripts in first slice.
- JSON v1 draft-only import/export/seed.
- activation sensitivity classes: STANDARD / REAUTH / APPROVAL / REAUTH_AND_APPROVAL.
- CodePolicy.
- ProjectHealthPolicy.
- typed TemplateDefinition.

---

## A2 — Project / Work finalization

No broad structural domain decision remains.

Pre-code Freeze blocker:
- **NONE**.

Resolved design evidence:
- Project/Work UI + RTL rendered and reviewed in final design run `35396169606`.

Resolved:
- exact pre-code DDL/index/constraint contract.
- exact first-slice HTTP route/DTO semantic contract.
- executable DB/API tests are post-Freeze Bootstrap verification.

Resolved:
- durable Site + ProjectSite association.
- frozen ProjectSite lifecycle.
- Work lifecycle/readiness separation.
- typed assignment history.
- CodePolicy-driven Project/WorkOrder human codes.
- explainable ProjectHealth signal model.
- accepted-weight progress formula.
- typed append-only instruction revisions.
- materialized checklist instances.
- versioned normalized WorkPolicyBinding history.
- representative model coverage through internal fixtures.

---

## A3 — Asset / Warehouse finalization

No broad structural domain decision remains.

Pre-code Freeze blocker:
- **NONE**.

Resolved design evidence:
- Warehouse/Asset custody states rendered and reviewed in final design run `35396169606`.

Pilot activation input:
- asset/stock/storage seed can be loaded/configured before pilot and does not block schema Freeze.

Resolved:
- exact pre-code DDL/index/constraint contract.
- exact first-slice HTTP semantic contract.

Resolved:
- Asset lifecycle/custody/condition/calibration dimensions.
- derived availability.
- one custody invariant.
- Warehouse vs StorageLocation semantic split.
- Main Warehouse + Project/Site storage.
- typed AssetReservation / StockReservation persistence.
- numeric(20,6) quantities.
- assetCode hard identity + serial duplicate-warning strategy.
- AssetReturnInspection.
- online-authoritative final Return.
- no first-slice expired-calibration override.
- physical inventory vs Finance valuation boundary.
- checkout collision semantics.

---

## A4 — API finalization

Pre-code Freeze blockers:
- none at first-slice HTTP semantic level.

Post-Freeze Bootstrap verification:
- generate OpenAPI 3.1 snapshot.
- run REAUTH_REQUIRED/cursor/4xx/5xx integration tests.
- run Android/Desktop DTO compatibility tests.

Resolved:
- /v1 baseline and additive compatibility.
- Ktor + thin shared manual typed client.
- command action routes.
- UUID and string/text bounds.
- typed assignment target.
- requirement-instance DTO.
- REAUTH_REQUIRED envelope.
- external object-hidden/internal permission-denied policy.
- stateless tamper-protected opaque cursor contract.
- Work/Asset/Evidence DTO candidates.
- Project progress/health summaries.
- Configuration + Project/Site admin route grammar.
- native X-Client-Platform / X-Client-Version / X-Device-Installation-Id metadata.
- OpenAPI publication/drift rule.

---

## A5 — PostgreSQL / Flyway / jOOQ finalization

Pre-code Freeze blockers:
- none beyond keeping the DDL/constraint contract internally consistent with other contracts.

Post-Freeze Bootstrap verification:
- generate production Flyway SQL.
- run DB integration/migration tests.

Resolved:
- module ownership.
- concrete table-shape candidates.
- exact FK/check/unique/index contract in 16_DATABASE_DDL_CONSTRAINT_CONTRACT.md.
- optimistic update pattern.
- transaction boundaries.
- append/custody/idempotency semantics.
- single global Flyway stream under database/migrations.
- VNNNN__module__description.sql naming.
- initial V0001..V0009 bootstrap order.
- no production baselineOnMigrate.
- expand/contract forward migration policy.
- jOOQ KotlinGenerator.
- generated source path/package.
- generated jOOQ source not committed.
- PostgreSQL/JVM type mapping.
- varchar + CHECK state persistence instead of PostgreSQL enum types.

---

## A6 — Room/local finalization

Pre-code Freeze blockers:
- representative device check may tune OfflineStoragePolicy seed, but schema/semantics do not change.

Post-Freeze Bootstrap verification:
- implement Room converters/entities/migrations.
- run migration/retry/error mapping tests.

Resolved:
- UUID as canonical lowercase TEXT.
- Instant local representation contract.
- kotlinx.serialization UTF-8 JSON pending-command payload with payloadVersion.
- app-private/OS-encrypted storage baseline; no SQLCipher in first slice.
- HIGHLY_RESTRICTED excluded from offline cache by default.
- configurable OfflineStoragePolicy with safe default seed.
- eviction pinning for pending/conflict/unsynced evidence.
- release-declared migration support window.
- retry/backoff schedule.
- cursor scope keys.
- bundle invalidation/retention.
- semantic Desktop parity boundary.
- process-death/reconnect proof.

---

## A7 — OpenFGA exact model

Pre-code Freeze blockers:
- **NONE**.

Production activation:
- final OCI OpenFGA runtime sizing/storage connection/model deployment settings after tenancy validation.

Post-Freeze Bootstrap verification:
- implement PostgreSQL projection/outbox processor and integration tests.

Resolved:
- first-slice FGA DSL + team userset semantics validated in GitHub Actions run 35331537375.
- object relationship vocabulary.
- action→FGA→application obligation map.
- configuration permissions.
- delegation semantics.
- offline re-authorization.
- negative-test families.
- PostgreSQL source-of-truth vs OpenFGA projection boundary.
- transactional projection intent/outbox.
- fail-closed grant/revoke behavior.
- pinned authorization model ID.
- selective HIGHER_CONSISTENCY policy.
- external OBJECT_NOT_VISIBLE policy.
- 2-second synchronous projection fast path.
- fixed projector retry schedule.
- no first-slice application positive/allow cache.

---

## A8 — Evidence/provider closure

Pre-code Freeze blockers:
- **NONE**.

Production/pilot activation:
- exact malware-scanner service only if ARBITRARY_FILE is enabled.
- final OCI Object Storage versioning/backup/lifecycle settings after tenancy validation.

Deferred/non-blocking unless enabled by pilot:
- formal retention/legal-hold policy for automated deletion/hold.

Resolved:
- OCI Object Storage + OCI KMS selected.
- signed direct upload protocol.
- checksum/finalize.
- opaque private object-key layout.
- one private environment bucket/container baseline.
- encryption-at-rest requirement.
- 16 MiB system max per evidence object.
- no multipart in first slice.
- scan/quarantine classes.
- classification-based download policy.
- no automatic authoritative evidence deletion by default.
- authorization boundary.
- no permanent public restricted URL.

---

# B. Visual/design gate

**PASS**

Final rendered proof:
- run `35396169606` — PASS.
- 37 / 37 browser-rendered screenshots.
- Arabic RTL.
- phone/tablet/desktop.
- Technician Job.
- Warehouse custody.
- Configuration Center.
- Supervisor Review.
- Project Command Center.
- required conflict classes.
- one-product navigation comparison.

Artifact digest:
`sha256:6988eb2f01613a0e89c72a74f90e5304ddc349b1d8bc9e49819ec5f0056ac1b3`

Figma is optional later visual-craft work and is not a first-slice Freeze dependency.

---

# C. Operational/provider blockers

Provider architecture now resolved:
- DigiCert OV Code Signing + KeyLocker selected for production Authenticode.
- HILTECH Update Service selected as baseline Windows distribution; MDM remains optional adapter.
- OCI accepted as production provider baseline.
- Jeddah `me-jeddah-1` primary-region candidate.
- OCI Container Instances preferred / OCI Compute fallback.
- OCI Database with PostgreSQL.
- OCI Object Storage + KMS.
- OCI Secret Management.
- OCI Container Registry.
- OpenTelemetry Collector → OCI observability baseline.
- Terraform + OCI provider + Resource Manager.
- GitHub Actions remains delivery control plane.
- staged DR architecture: PILOT backup/PITR; STABLE Jeddah→Riyadh Warm Standby with 5-minute enforced RPO and <=60-minute recovery-drill target.

Pre-code provider/ops decisions resolved:
- OCI provider/topology.
- Jeddah primary candidate + Riyadh DR candidate.
- PostgreSQL/Object Storage/KMS/Secrets/Registry.
- Container Instances preferred / Compute fallback.
- Terraform/Resource Manager.
- OpenTelemetry Collector → OCI observability baseline.
- DigiCert OV + KeyLocker signing.
- HILTECH Update Service distribution.
- staged PILOT/STABLE DR contract.

Operational activation evidence still required before real production cutover, not before contract Freeze:
- OCI tenancy/region subscription.
- quota/capacity checks.
- representative Egypt latency smoke.
- final runtime/database sizing + cost check.
- staging IaC deploy proof.
- PITR/DR rehearsals.
- telemetry retention/sampling/alert settings.
- malware scanner only if ARBITRARY_FILE is enabled.
- production domain/TLS ownership.
- DigiCert certificate issuance/KeyLocker credential setup.
- signed-MSI staging proof.

These are operational instantiation/validation items and do not reopen application/domain architecture.

---

# D. Reality / seed validation still useful

Representative internal fixtures already validate structural coverage for:
- Data Center / Rack work.
- remote cable pulling.
- Site/Project temporary material storage.
- technician pair.
- engineer + technician crew.
- factory/site infrastructure pattern.

Still useful before pilot activation:
- representative actual asset/stock master sample.
- representative current device/site restrictions.
- real current staff/team seed.
- initial WorkType/evidence/review policy seed.
- current Warehouse/SiteStorage seed.
- Project/client/site code terminology.
- any legal/security rule that materially changes a feature that is actually enabled.

These are now primarily:
- model confirmation,
- initial seed data,
- terminology,
- non-configurable legal/security discovery.

They are not a reason to stop contract conversion.

---

# E. Explicitly NOT blockers for first-slice Freeze

Unless directly pulled into the first production vertical:

- full payroll schema.
- bank execution integration.
- full Finance implementation.
- full Sales/Tender implementation.
- Client Portal.
- NOC/managed monitoring.
- CCTV remote control.
- all future WorkTypes.
- exact future headcount.
- exact future number of projects/warehouses/branches.

The platform/domain model is scale-neutral.

---

# Freeze vs Bootstrap interpretation

Canonical boundary:
`17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`

Production executable artifacts that can only exist after implementation are **not** pre-code Freeze blockers when their contracts/generation/test specifications are frozen.

---

# Freeze readiness meaning

FIRST_SLICE_FREEZE can pass when:

1. A1–A8 exact contract decisions are closed.
2. required first-slice UI/design evidence exists.
3. provider/ops decisions required to bootstrap safely are selected.
4. no representative reality fixture exposes a missing structural domain capability.
5. all contract files agree.
6. 09_FREEZE_RECORD.md has no unchecked blocking item.

Then:

FREEZE PASS
→ repository bootstrap
→ generated module/schema/client/auth/test scaffolding
→ production vertical implementation.

---

# Current assessment

Technical feasibility:
**CLOSED**

Domain/configuration structural discovery:
**LARGELY CLOSED**

Contract conversion:
**PASS / FROZEN FOR FIRST SLICE**

Design evidence:
**PASS**

Provider/ops architecture:
**PASS AT CONTRACT LEVEL**

Final stack/version:
**PASS**

Provider/ops activation:
**POST-FREEZE / PRE-CUTOVER VALIDATION REMAINS**

Production code:
**NOT STARTED YET**

The first-slice pre-code closure is complete.
Remaining work is Bootstrap, pilot setup, production activation/cutover, and later-domain closure.


---

# Final first-slice Freeze view

- FIRST_SLICE_CONTRACT_CONSISTENCY = PASS.
- FINAL_STACK_REVIEW = PASS.
- DESIGN_PROOF = PASS.
- PROVIDER_CONTRACT_REVIEW = PASS.
- FIRST_SLICE_FREEZE = PASS.

Canonical decision:
`24_FIRST_SLICE_FREEZE_DECISION_2026-09-19.md`

Remaining first-slice pre-code blockers:
**NONE**

Next:
`REPOSITORY_BOOTSTRAP`

All other open items in this register are:
- pilot seed/setup,
- post-Freeze Bootstrap verification,
- production activation/cutover,
- or later-domain closure.
