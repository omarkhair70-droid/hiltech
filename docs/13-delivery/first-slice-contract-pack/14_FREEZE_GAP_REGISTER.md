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

## A1 — Configuration metadata details

Still need exact final decisions for:
- max lengths for code/name/description.
- organization/global scope mechanism.
- policy-binding physical persistence representation.
- custom typed extension registry.
- exact configuration import/export/seed format.
- which configuration families require formal ApprovalPolicy before activation.

Impact:
DB/API/admin UI.

Does not reopen:
configurable operating model.

---

## A2 — Project / Work narrow closure

Still need:
- exact projectCode/workOrderCode generation rules.
- exact ProjectHealth derivation.
- exact progress weighting/aggregation model.
- exact typed instruction/checklist persistence.
- final policy-binding DB representation.
- exact ProjectSite lifecycle naming if current candidate changes.

Resolved:
- Site is durable physical/client identity.
- ProjectSite owns project-specific site context.
- Work lifecycle and readiness are separate.
- assignment is typed history, not arrays.
- WorkType/policies are version-bound configuration.

---

## A3 — Asset / Warehouse narrow closure

Still need:
- exact Warehouse vs StorageLocation implementation split where both exist.
- exact Asset vs Stock reservation persistence split/unification.
- Decimal precision/scale strategy for quantities.
- serial-number uniqueness scope.
- expected-accessory / return-inspection exact object shape.
- calibration exception policy.
- stock valuation/accounting boundary.
- exact offline return finalization behavior.

Resolved:
- Asset identity/state dimensions separated.
- availability is derived.
- one custody invariant.
- Main Warehouse + Project/Site temporary storage.
- Asset/Stock master categories are configuration, not code enums.
- checkout collision semantics.

---

## A4 — API final normalization

Still need:
- exact route wording for configuration revision resources.
- exact DTO shape for typed assignment targets.
- exact requirement-instance DTO.
- exact REAUTH_REQUIRED error details.
- object-hidden vs permission-denied policy by API audience.
- exact cursor encoding/expiry contract.
- exact max/string constraints.

Resolved:
- /v1 baseline.
- Ktor.
- command action routes.
- standard command fields.
- error envelope candidate.
- Work/Asset/Evidence DTO candidates.
- list/read-model shape.

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
- UUID representation/type converters.
- serialized command payload format.
- encryption-at-rest implementation for restricted cache.
- cache/evidence size budgets.
- migration support window.
- retry/backoff constants.
- exact cursor scope keys.
- old bundle retention/eviction rules.

Resolved:
- entity candidates.
- queue/dependency/evidence/conflict schema.
- local transactions.
- WorkManager behavior.
- conflict semantics.
- process-death/reconnect proof.

---

## A7 — OpenFGA exact model

Still need:
- exact DSL relation/permission expressions/model file.
- exact team subject-set expressions.
- exact projector retry/backoff constants.
- exact synchronous projection wait timeout.
- production OpenFGA cache/deployment settings.

Resolved:
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
