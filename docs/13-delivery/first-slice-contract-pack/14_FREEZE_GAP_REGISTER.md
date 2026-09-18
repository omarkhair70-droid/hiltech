# 14 — First-Slice Freeze Gap Register

Status: **ACTIVE / NARROW CLOSURE LIST**
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

Still need:
- Configuration Center visual proof.
- active-location tracking retention/legal policy before ACTIVE_SITE_PRESENCE can be enabled.
- final DDL/API contract tests for configuration revisions.

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

Still need:
- final physical DDL/indexes/constraints.
- final route/DTO normalization and contract tests.
- representative UI/RTL proof.

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

Still need:
- final physical DDL/indexes/constraints.
- final route/DTO normalization and contract tests.
- representative Warehouse/Asset UI proof.
- pilot asset/stock master seed.

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

## A4 — API final normalization

Still need:
- final route wording normalization for configuration/project admin surfaces.
- exact optional client-metadata headers only where server behavior consumes them.
- Project/Site admin create/update DTOs outside the core vertical where needed.
- executable contract tests for REAUTH_REQUIRED, cursor and representative safe 4xx/5xx mappings.

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

---

## A5 — PostgreSQL / Flyway / jOOQ finalization

Still need:
- final physical DDL.
- exact FK/check/unique constraints.
- index set from final read-model queries.
- migration module ordering.
- baseline/file naming.
- generated jOOQ package/forced types.
- generated-code commit vs CI generation.

Resolved:
- module ownership.
- table families.
- concrete first-slice table-shape candidates.
- optimistic update pattern.
- transaction boundaries.
- append/custody/idempotency semantics.

---

## A6 — Room/local finalization

Still need:
- restricted-cache encryption-at-rest decision/revisit trigger.
- storage-budget/eviction policy.
- migration support-window rule.
- retry/backoff constants.
- exact cursor scope keys.
- old bundle retention/eviction rule.

Resolved:
- UUID as canonical lowercase TEXT.
- Instant local representation contract.
- kotlinx.serialization UTF-8 JSON pending-command payload with payloadVersion.
- entity candidates.
- queue/dependency/evidence/conflict schema.
- local transactions.
- WorkManager behavior.
- conflict semantics.
- process-death/reconnect proof.

---

## A7 — OpenFGA exact model

Still need:
- exact projector retry/backoff constants.
- exact synchronous projection wait timeout.
- production OpenFGA cache/deployment settings.

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

---

## A8 — Evidence/provider closure

Still need:
- production object-storage provider.
- object-key/bucket/container layout.
- file-size limits.
- malware/quarantine requirement.
- retention/legal hold rules.
- multipart threshold.
- exact download delivery policy by classification.

Resolved:
- signed direct upload protocol.
- checksum/finalize.
- server metadata.
- local evidence.
- upload/finalize DTOs.
- authorization boundary.
- no permanent public restricted URL.

---

# B. Visual/design blockers

The interaction contract is defined.

Visual proof still required for:

- Configuration Center representative flow.
- Technician Job Detail.
- Warehouse Checkout.
- Supervisor/Engineer Review.
- offline Work conflict.
- Asset checkout collision.
- config revision conflict.
- Arabic RTL stress.
- field tablet adaptation.
- final navigation comparison.

Figma/tooling quota can delay rendering, but these remain actual design-freeze evidence requirements.

---

# C. Operational/provider blockers

Still need final production decisions for:

- hosting/infrastructure provider and deployment shape.
- production object-storage provider.
- observability backend/collector.
- production Windows signing certificate/provider.
- Windows enterprise distribution/update channel.
- backup/restore implementation.
- RPO/RTO.
- production secret-management path.
- final server/container/runtime pin review.
- final CI action pin review.

These do not reopen application/domain architecture.

---

# D. Reality / seed validation still useful

Representative internal fixtures already validate structural coverage for:
- Data Center / Rack work.
- remote cable pulling.
- Site/Project temporary material storage.
- technician pair.
- engineer + technician crew.
- factory/site infrastructure pattern.

Still useful before final Freeze:
- representative actual asset/stock master sample.
- representative current device/site restrictions.
- real current staff/team seed.
- initial WorkType/evidence/review policy seed.
- current Warehouse/SiteStorage seed.
- Project/client/site code terminology.
- any legal/security rule that materially changes tracking/retention.

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
**ADVANCED / ACTIVE**

Design evidence:
**OPEN**

Provider/ops closure:
**OPEN**

Production code:
**NOT STARTED BY DESIGN**

The remaining work is now a narrow closure exercise, not broad product discovery.
