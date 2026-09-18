# HILTECH Pre-Code Endgame

Status: ACTIVE
Purpose: Define exactly what remains before production coding starts.

## Principle

We are no longer discovering HILTECH from zero.

The remaining pre-code phase is about:
1. validating the model against the real company,
2. proving risky technical choices,
3. finishing representative UX/design,
4. freezing exact contracts/schemas/versions,
5. locking implementation order.

Production coding does not begin until Freeze Review passes.

---

# Gate 1 — Reality Validation

Required with HILTECH people / evidence:

## Mohamed
- actual approval/management flow,
- company priorities,
- authority/delegation,
- executive exceptions,
- security/facility expectations.

## Ahmed / Finance
- payroll,
- advances,
- financial imprest,
- expenses,
- payables/receivables,
- client/supplier invoices,
- bank/payment process,
- accounting/e-invoice tools.

## Project / Field
- one real project from award to delivery,
- field reporting,
- drawings,
- tests,
- site access/connectivity,
- evidence.

## Warehouse
- actual warehouse walkthrough,
- asset categories,
- stock units,
- issue/return,
- calibration,
- receiving,
- physical access/cameras.

## Procurement / HR / Sales
- current documents,
- approval chains,
- tools/systems,
- naming conventions.

Output:
Update every contradicted workflow/object/permission/spec.

---

# Gate 2 — Low-Fi UX Completion

Complete representative low-fi prototypes for INTERNAL HILTECH first.

Priority:
1. Mohamed Home + Approval.
2. Ahmed Finance/Payroll.
3. PM Project Command Center.
4. Technician Today/Job/Offline.
5. Warehouse Scan/Checkout/Return.
6. Procurement.
7. HR/New Hire/Offboarding.
8. Sales/Tenders.
9. Search/Work Queue.
10. Security/Facilities.

Then:
- Arabic RTL stress test.
- English LTR.
- tablet/adaptive pass.
- loading/empty/error.
- offline/stale/conflict.
- permission differences.
- multi-role user.

External Client experience remains documented but is intentionally last in implementation priority.

---

# Gate 3 — Technical Spikes

Run and document:
- KMP Android + Windows shared feature.
- Arabic/RTL.
- Room shared local DB.
- offline command queue/conflict.
- Android Camera/QR/evidence.
- desktop dense grid.
- Windows packaging/update.
- Keycloak native OIDC.
- authorization model/OpenFGA.
- Spring Modulith recovery.
- PostgreSQL + jOOQ concurrency/ledger.
- binary upload.
- WorkManager sync.
- observability.
- end-to-end PM -> technician offline -> supervisor proof.

Every spike ends:
ACCEPT / REJECT / MODIFY.

---

# Gate 4 — Final Technology Decisions

Create accepted ADRs and freeze:

- FINAL_STACK.md
- VERSION_MATRIX.md
- auth choice.
- authorization choice.
- persistence choice.
- object-storage provider.
- infrastructure provider.
- CI/CD.
- Windows distribution/update.
- observability backend.

No final stack based only on preference.

---

# Gate 5 — Exact Build Contracts

Convert validated domain specs into exact implementation contracts:

## Database
- tables,
- keys,
- constraints,
- indexes,
- ledgers,
- migrations,
- retention.

## API
- commands,
- queries/read models,
- request/response schemas,
- errors,
- idempotency,
- versions.

## Client local data
- cached models,
- local operations,
- sync cursor,
- upload queue,
- conflict storage.

## Authorization
- policy/model definitions,
- field-level rules,
- tests.

## Files
- storage keys,
- upload/finalize,
- checksums,
- retention.

---

# Gate 6 — Design Freeze For Core Internal Product

Freeze enough design for coding:
- navigation.
- information architecture.
- typography.
- color/status tokens.
- spacing/density.
- component inventory.
- icon base + HILTECH domain icons.
- motion tokens.
- representative screens.
- RTL/accessibility.

Not every final screen needs pixel polish before backend work, but core design primitives and representative flows must be stable.

---

# Gate 7 — Delivery Engineering

Write/freeze:
- IMPLEMENTATION_ORDER.md.
- TEST_STRATEGY.md.
- CI_GATES.md.
- RELEASE_STRATEGY.md.
- MIGRATION_ROLLBACK.md.
- operational runbooks.
- environment plan.

---

# Gate 8 — Freeze Review

Freeze passes only if:
- no important workflow still depends on “we will decide later”,
- real company contradictions are resolved,
- technical spikes passed/decisions recorded,
- exact implementation contracts exist for the starting slices,
- security/offline/error behavior is explicit,
- repo/module/file plan is final enough to bootstrap.

Then:
REPOSITORY BOOTSTRAP -> PRODUCTION CODE.

---

# What Can Be Completed Without Mohamed Tonight?

Can continue:
- implementation order,
- test strategy,
- CI/release planning,
- remaining low-fi if Figma access is available,
- approval/automation refinements,
- exact contract templates,
- ADR templates,
- spike execution plans.

Cannot honestly freeze without company input:
- payroll/accounting/bank exact schema,
- real approval thresholds,
- warehouse categories/hardware,
- physical security vendors,
- real field restrictions,
- exact authority map.

Those remain validation gates, not documentation failures.
