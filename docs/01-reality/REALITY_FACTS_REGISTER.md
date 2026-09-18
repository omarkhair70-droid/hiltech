# HILTECH Reality Facts Register

Status: **ACTIVE / PRE-FREEZE**
Updated: 2026-09-18

## Purpose

Keep real-company facts separate from assumptions.

Every important product/architecture statement that depends on HILTECH reality should be traceable to one of:

- VERIFIED_DOCUMENT
- VERIFIED_OBSERVATION
- VERIFIED_INTERVIEW
- INTERNAL_REPORTED
- PUBLIC_VERIFIED
- ASSUMPTION
- UNKNOWN

INTERNAL_REPORTED means a trusted internal report was relayed to the product work, but the team has not independently inspected the source/process yet.

---

# Confirmed / Reported Facts

## RF-001 — Internal operating system is the priority
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
Mohamed prioritizes HILTECH's internal operating system — Mohamed/Ahmed/warehouse/field/company operations — over an external client-facing portal.

Product consequence:
- implementation order remains internal-first,
- external Client Portal remains last,
- client business objects may exist earlier because internal workflows need them,
- client-facing polish must not delay Ahmed/warehouse/field/company core.

Repository effects:
- docs/13-delivery/IMPLEMENTATION_ORDER.md
- docs/13-delivery/PRE_CODE_ENDGAME.md

Validation still useful:
Direct Mohamed session should confirm whether any external client action is operationally mandatory earlier than the full Client Portal.

---

## RF-002 — Employee advances must be first-class
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
Employee financial advances / سلف are a real internal finance requirement and must not be omitted.

Product consequence:
Advance is a first-class object/workflow, not a payroll free-text deduction.

Current model now covers:
- request,
- approval,
- issue/funding,
- settlement,
- payroll deduction linkage when explicitly approved,
- cash return / expense offset / mixed settlement,
- outstanding balance,
- closure.

Validation required with Ahmed:
- actual request source,
- approval chain,
- deduction rules,
- settlement deadlines,
- legal/accounting treatment,
- whether multiple concurrent advances are permitted.

---

## RF-003 — Financial imprest / cash custody must be first-class
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
Financial imprest / عهدة مالية is a real internal finance requirement and must not be omitted.

Product consequence:
FinancialImprest is modeled separately from:
- salary/payroll,
- employee Advance,
- expense reimbursement,
- physical Asset custody.

Current model now covers:
- custodian,
- funding,
- append-only spend ledger,
- receipts/evidence,
- replenishment,
- settlement version,
- cash return,
- shortage/overage,
- clearance/closure.

Validation required with Ahmed:
- fixed vs temporary vs revolving imprest,
- who can hold one,
- concurrent imprests,
- funding method,
- receipt requirements,
- replenishment process,
- settlement cadence,
- shortage/overage handling,
- offboarding clearance.

---

# High-Priority Unknowns

## RF-010 — Payroll source of truth
Status: **UNKNOWN**
Need:
- current employee master source,
- payroll sheet/system,
- component calculation path,
- approval path,
- bank/payment handoff.

Owner for validation: Ahmed.

---

## RF-011 — Accounting / e-invoice system
Status: **UNKNOWN**
Need:
- accounting software name/version,
- e-invoice/e-receipt tools,
- export/import/API capability,
- authoritative accounting boundary.

Owner for validation: Ahmed/accountant.

---

## RF-012 — Bank execution reality
Status: **UNKNOWN**
Need:
- bank(s),
- portal/manual/bulk file/API path,
- maker/checker flow,
- payment result/reconciliation sources.

Do not store credentials or account secrets in GitHub.

Owner for validation: Ahmed/Mohamed.

---

## RF-013 — Warehouse physical-control reality
Status: **UNKNOWN / INTERNAL CONTEXT EXISTS**
Need direct walkthrough:
- doors/keys/access,
- responsible people,
- high-value assets,
- current records,
- issue/return process,
- calibration,
- cameras,
- direct-to-site delivery.

Owner for validation: Warehouse + Mohamed.

---

## RF-014 — Real field device/site restrictions
Status: **UNKNOWN**
Need:
- Android versions/devices,
- BYOD/company-owned,
- camera restrictions,
- connectivity,
- offline periods,
- client/security restrictions.

Owner for validation: Engineer/supervisor/technician.

---

# Register Rule

If a freeze decision materially depends on an UNKNOWN or ASSUMPTION, either:
1. validate it,
2. scope around it explicitly,
3. or record formal risk acceptance.

Never silently convert INTERNAL_REPORTED into VERIFIED_DOCUMENT/OBSERVATION.