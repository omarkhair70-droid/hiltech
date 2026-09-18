# 23 — First-Slice Freeze Review Procedure

Status: **READY TO RUN ONCE REMAINING GATES CLOSE**
Date: 2026-09-18

## Purpose

Define the exact review that converts:

`FIRST-SLICE PRE-CODE FREEZE CLOSURE`

into either:

- `FIRST_SLICE_FREEZE = PASS`
- `FIRST_SLICE_FREEZE = PASS_WITH_DEFERRED_NON_BLOCKING`
- `FIRST_SLICE_FREEZE = FAIL`

No subjective “looks ready” approval.

---

# 1. Review inputs

Mandatory inputs:

## Program / control
- MASTER_INDEX
- CURRENT_PROGRAM_STATUS
- FREEZE_CHECKLIST
- PRE_CODE_ENDGAME
- DEFINITION_OF_COMPLETE

## Reality
- FIRST_PRODUCTION_SLICE_REALITY_CLOSURE
- FIRST_SLICE_REPRESENTATIVE_FIXTURES
- REALITY_FACTS_REGISTER

## Product / configuration
- PROJECT_CONSTITUTION
- CONFIGURABLE_OPERATING_MODEL
- FEATURE_CATALOG
- PRODUCT_MAP

## Contract pack
- 00 through 22
- especially:
  - 09_FREEZE_RECORD
  - 14_FREEZE_GAP_REGISTER
  - 17_FREEZE_TO_BOOTSTRAP_BOUNDARY
  - 21_FINAL_PRE_FREEZE_CONSISTENCY_REVIEW

## Stack
- STACK_VERSION_MATRIX
- FINAL_STACK_VERSION_REVIEW
- FINAL_STACK.md when created

## Design
- FIGMA_LOW_FI_STATUS
- FIRST_SLICE_DESIGN_FREEZE_EXECUTION_PACK
- actual rendered Figma frames/screenshots

## Architecture / ADR
- ADR-001..019 as applicable to first slice
- accepted spike results

---

# 2. Review Rule A — No unknown structural semantics

FAIL if implementation would still need to invent any first-slice answer for:

- aggregate ownership,
- lifecycle state,
- readiness semantics,
- authority relationship,
- offline class,
- idempotency,
- optimistic concurrency,
- conflict behavior,
- evidence lifecycle,
- asset custody,
- stock conservation,
- policy version binding,
- Site/ProjectSite identity,
- API error meaning,
- DB key/invariant,
- Room queue meaning,
- OpenFGA/application obligation split,
- provider boundary.

Seed values do not count as structural unknowns.

Examples of acceptable seed-later:
- employee names,
- current WorkType list,
- current approver tuple,
- current StorageLocation rows,
- current asset master,
- code prefix,
- tracking retention value when tracking mode remains disabled until configured.

---

# 3. Review Rule B — Configurable vs invariant separation

PASS only if:

- normal operating changes do not require source-code edits.
- configuration is typed/versioned/audited.
- old Work history remains explainable after config supersession.
- configuration cannot disable hard safety/truth invariants.

FAIL if:
- today's Mohamed/Ahmed/crew names are compiled into workflow logic,
- Evidence checklist is a global hard-coded rule,
- current warehouse count shapes the DB schema,
- one current role title equals permission truth.

---

# 4. Review Rule C — One-truth data ownership

For each object/event:
identify exactly one authoritative owner.

Must pass:
- Project/Site identity.
- Work lifecycle.
- Work policy binding.
- Asset movement/custody.
- Stock movement.
- Evidence metadata.
- authentication identity boundary.
- OpenFGA relationship projection.
- Configuration revisions.
- audit/activity.

FAIL on:
- duplicate editable current truth,
- client/server split-brain authority,
- warehouse/project double-entered physical event,
- screen-owned persistence.

---

# 5. Review Rule D — Offline safety

Use representative scenario:

Technician:
- receives durable bundle,
- loses connectivity,
- starts/blocks/resumes,
- captures evidence,
- submits local intent,
- app process dies/restarts,
- PM changes authoritative Work state,
- connection returns.

PASS only if:
- local intent/evidence preserved,
- replay re-authenticates/re-authorizes,
- stale command cannot overwrite newer state,
- conflict typed,
- dependent commands blocked,
- UI does not show false authoritative success,
- recovery path visible.

Architecture evidence:
SPIKE-15 + contract pack.

Design proof:
M06 offline/conflict variants.

---

# 6. Review Rule E — Physical custody safety

Scenario:
two devices attempt same Asset checkout.

PASS only if:
- one authoritative winner,
- second command conflict,
- one active custody,
- current safe state shown,
- no silent override,
- movement/audit preserved.

Also review:
- return,
- damage,
- calibration blocked,
- SiteStorage transfer.

Design proof:
M07 variants.

---

# 7. Review Rule F — Authorization safety

PASS only if:
- OpenFGA model tests green,
- domain/application obligations explicit,
- external object existence protected,
- field-level filtering server-side,
- offline replay re-authorized,
- revocation fail-closed,
- pending grant not optimistic,
- pinned model ID,
- config editor != activator where required.

Reference run:
OpenFGA contract run 35331537375.

---

# 8. Review Rule G — Evidence safety

PASS only if:
- local evidence durable,
- direct private upload,
- size/checksum verified,
- arbitrary file quarantined/scanned before READY if enabled,
- no permanent public restricted URL,
- HIGHLY_RESTRICTED proxy rule,
- no silent local deletion before authoritative finalize,
- retention policy explicit before automated delete.

---

# 9. Review Rule H — Database generation completeness

PASS pre-code if:
- table shapes defined,
- keys/FKs/checks/unique/index contract defined,
- module ownership defined,
- Flyway convention defined,
- jOOQ convention defined,
- optimistic update pattern defined,
- test obligations specified.

Do NOT fail because production SQL is not written yet.

That belongs to Bootstrap Verification.

---

# 10. Review Rule I — HTTP / local generation completeness

PASS pre-code if:
- route grammar,
- DTOs,
- error/conflict/reauth,
- cursor,
- versioning,
- visibility,
- native client metadata,
- Room entities/queue/conflict/evidence,
- migration/retry/storage rules

are exact enough to generate implementation.

Do NOT require Spring/Ktor/Room production classes pre-Freeze.

---

# 11. Review Rule J — Rendered design proof

This is a hard pre-code gate.

Must visually review:

- M06 Technician Job.
- M07 Warehouse Checkout.
- D06 Configuration Center.
- D07 Supervisor Review.
- D02 updated Project Command Center.
- Work offline conflict.
- Asset custody conflict.
- Configuration version conflict.
- Review stale-version conflict.
- Arabic RTL:
  - Technician,
  - Project Command,
  - Warehouse,
  - Config Center.
- tablet Technician.
- tablet Warehouse.
- navigation comparison.

PASS criteria:
- task hierarchy clear.
- local vs authoritative state clear.
- queued/offline clear.
- conflict resolution clear.
- permissions/actions plausible.
- RTL structurally correct.
- adaptive layouts coherent.
- one-product navigation coherent.

Visual polish/brand tokens are not required for low-fi Freeze.

---

# 12. Review Rule K — Stack reproducibility

PASS only if:

- FINAL_STACK version review passed.
- exact first-slice application/server pins recorded.
- deliberate non-upgrades documented.
- no preview/EAP dependency accidentally selected.
- GitHub production actions use immutable SHA policy.
- Gradle/JDK lines compatible.
- tested patch changes have focused evidence.

Do not require exact OCI runtime size or DigiCert certificate serial for application stack Freeze.

---

# 13. Review Rule L — Provider architecture

PASS if provider contracts are selected enough to generate IaC/adapters:

- cloud provider,
- region candidate,
- runtime shape,
- PostgreSQL provider,
- object storage,
- KMS/secrets,
- registry,
- ingress/private networking,
- observability boundary,
- IaC approach,
- Windows signing/distribution,
- DR architecture.

Operational activation can remain post-contract:
- tenancy subscription,
- quota,
- billing/cost,
- certificate issuance,
- real latency,
- staging deployment,
- recovery drill.

FAIL only if activation reveals accepted contracts cannot be hosted safely.

---

# 14. Deferred-item test

A deferred item can be non-blocking only if all are true:

1. not needed to generate first-slice implementation safely,
2. no hidden architectural choice remains,
3. no security/data integrity invariant depends on it,
4. explicit owner/revisit trigger exists,
5. the disabled/not-enabled default is safe.

Examples:
- arbitrary-file scanner when ARBITRARY_FILE not enabled,
- persisted active location tracking when tracking mode stays disabled,
- MDM integration,
- formal automated evidence deletion,
- crash-reporting vendor.

---

# 15. Freeze Review output

Record:

## Contract decision
PASS / FAIL.

## Design decision
PASS / FAIL.

## Stack decision
PASS / FAIL.

## Provider contract decision
PASS / FAIL.

## Blocking issues
exact list, no generic “more research”.

## Deferred non-blocking items
item + owner + trigger + safe default.

## Final result

Only if all hard gates pass:

`FIRST_SLICE_FREEZE = PASS`

Then set:
- freeze date,
- source main SHA,
- FINAL_STACK ref,
- Figma evidence refs,
- contract-pack version/status,
- Bootstrap starting commit/ref.

---

# 16. After PASS

Immediately move to:

`REPOSITORY BOOTSTRAP`

Generate:
- monorepo structure,
- Gradle wrapper/catalog/conventions,
- Android/Desktop/server modules,
- Flyway SQL,
- jOOQ config,
- DTOs,
- Ktor boundary,
- Spring module skeletons,
- Room entities/DAOs/migrations,
- WorkManager sync,
- OpenFGA adapter/projector,
- Keycloak config,
- evidence storage adapter,
- IaC,
- CI,
- implementation test suites.

The team then implements the first vertical slice from frozen contracts.

No broad rediscovery loop.

---

# Current readiness to run review

Contract consistency:
PASS.

Provider contract:
PASS.

Stack/version:
**PASS** — FINAL_STACK.md created; AGP 9.3.3 focused run 35389326629 PASS.

Rendered design:
**FAIL / external Figma quota blocker.**

Therefore:
**do not run final PASS decision yet.**

The only current pre-code failure is rendered design proof.
