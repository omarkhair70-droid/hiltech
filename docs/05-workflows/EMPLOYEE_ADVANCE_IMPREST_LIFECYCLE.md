# Master Workflow — Employee Advance & Financial Imprest

Status: PRODUCT / DOMAIN MODEL v0.1 / REALITY VALIDATION REQUIRED

## Purpose

Separate three concepts that are often mixed operationally:

- Employee Advance / سلفة.
- Financial Imprest / عهدة مالية.
- Physical Asset Custody / عهدة معدات.

Physical asset custody is already modeled in Asset/Warehouse. This document defines the financial side.

---

# 1. Employee Advance — سلفة

Typical lifecycle:

REQUESTED
-> REVIEW
-> APPROVED
-> ISSUED
-> OUTSTANDING
-> PARTIALLY_SETTLED / SETTLED

Possible settlement methods:
- payroll deduction,
- direct repayment,
- expense/finance settlement,
- controlled write-off only by explicit authority.

Required facts:
- employee,
- amount/currency,
- reason,
- approval,
- issued payment,
- outstanding amount,
- settlement mode,
- deductions/repayments,
- history.

Rules:
- never silently alter payroll to recover an advance.
- payroll deduction must reference the approved advance.
- every repayment/deduction reduces outstanding balance through explicit movement/history.
- closed/settled advance remains auditable.

---

# 2. Financial Imprest — عهدة مالية

## Meaning

Company money temporarily placed under a person's responsibility for a defined operational purpose.

Examples:
- site expenses,
- travel/site purchases,
- emergency project spend,
- operational petty-cash responsibility.

This is NOT salary and NOT an employee loan.

## Lifecycle

DRAFT
-> REQUESTED
-> APPROVED
-> ISSUED
-> ACTIVE
-> SETTLEMENT_SUBMITTED
-> REVIEW
-> SETTLED

Branches:
ACTIVE -> REPLENISHMENT_REQUESTED -> REPLENISHED
REVIEW -> MISSING_RECEIPT / DISCREPANCY
REVIEW -> RETURN_REQUIRED
DISCREPANCY -> RESOLVED / SHORTAGE_CONFIRMED / OVERAGE_CONFIRMED

---

# 3. Imprest Issue

Fields/inputs:
- custodian employee,
- project/site/context,
- purpose,
- amount,
- currency,
- allowed categories/policy,
- expected settlement date,
- approval reference,
- payment/cash issue reference.

On issue:
- employee becomes financial custodian for exact amount.
- outstanding imprest balance begins.
- finance sees active custody.

---

# 4. Spending

Each spend line records:
- date/time,
- amount,
- category,
- merchant/payee,
- project/site/work context,
- description,
- receipt/evidence,
- entered by,
- review state.

Offline capture:
possible for site users if secure policy allows.
Final settlement remains online-authoritative.

---

# 5. Running Balance

Derived:

opening amount
+ approved replenishments
- accepted spend
- returned cash
= outstanding amount requiring settlement

Do not let users directly edit the outstanding balance.

---

# 6. Replenishment

If policy allows:

ACTIVE
-> request replenishment
-> finance/approval
-> issue additional amount
-> movement appended to same custody or linked custody period.

No hidden top-up.

---

# 7. Settlement

Custodian submits:
- all spend lines,
- receipts,
- unused cash returned,
- explanation for missing receipt/discrepancy.

Finance reviews.

Possible outcomes:
- fully settled,
- needs correction,
- missing document,
- shortage,
- overage,
- amount due back from employee,
- approved exception.

---

# 8. Offboarding

Employee offboarding cannot be completed while financial imprest remains unresolved unless an explicit approved exception exists.

Clearance checks:
- active imprest,
- outstanding advance,
- physical asset custody,
- expenses,
- payroll/final pay.

---

# 9. Difference From Expense Reimbursement

Expense reimbursement:
employee spends own money and HILTECH owes employee.

Financial imprest:
employee spends HILTECH money and employee owes HILTECH a settlement/accounting of that money.

Advance:
employee personally owes HILTECH money until repayment/deduction/settlement.

These are separate ledgers/workflows.

---

# 10. Notifications

Employee:
- imprest issued,
- settlement due,
- missing receipt/action required.

Finance:
- settlement submitted,
- overdue imprest,
- discrepancy.

Mohamed/management:
- only threshold/exception approvals,
- major overdue or shortage exception.

---

# 11. Audit

Append-only financial movements:
- issue,
- replenishment,
- spend acceptance,
- cash return,
- shortage/overage,
- settlement,
- correction.

Never overwrite past financial custody history.

---

# 12. Reality Questions For Ahmed/Mohamed

Before freeze:
- do they currently call this عهدة, سلفة, سحب, مصروف موقع, or something else?
- is it cash, transfer, card, or mixed?
- who can receive one?
- maximum amounts?
- approval thresholds?
- settlement deadline?
- missing receipt policy?
- can one employee hold multiple imprests?
- one imprest per project/site or general?
- how is shortage handled?
- how does it enter accounting/payroll today?

## Completion Gate

Requires real HILTECH finance validation, exact accounting treatment, approval thresholds, UI, permissions, object schema, ledger entries, offline policy, and tests.
