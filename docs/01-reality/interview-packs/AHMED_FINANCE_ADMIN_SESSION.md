# Interview Pack — Ahmed / Finance & Admin

Status: **READY FOR REALITY SESSION**
Purpose: obtain the minimum real-company evidence needed to freeze Finance, Payroll, Advance, Financial Imprest, Payment and Admin contracts.

This is not a questionnaire to send as a giant form.
Use it as a guided conversation while looking at real/redacted examples.

Do not commit:
- bank credentials,
- full employee salary lists,
- national IDs,
- private account numbers,
- secret client pricing,
- passwords/tokens.

Use redacted/synthetic examples in the repo.

---

# Session 1 — Current Finance Map

## 1. Where truth lives today
Ask:
- What files/programs do you open first every morning?
- Where is the employee list?
- Where is payroll calculated?
- Where are supplier dues?
- Where are client receivables?
- Where do expenses/sلف/عهد appear?
- Which file/system do you trust when two sources disagree?

Capture:
- system/file names,
- owner,
- frequency,
- whether export/API exists,
- what is authoritative vs convenience copy.

Output:
Systems Inventory + authoritative-source map.

---

# Session 2 — Payroll

Ask Ahmed to walk one real payroll month from start to payment.

Need exact flow:
1. employee population source,
2. base compensation source,
3. attendance,
4. overtime,
5. leave/absence effect,
6. deductions,
7. bonuses/allowances,
8. expenses/reimbursements,
9. advance deductions,
10. manual adjustments,
11. exception review,
12. who prepares,
13. who reviews,
14. Mohamed approval,
15. payment/bank handoff,
16. payment-result/reconciliation,
17. payslip/correction.

For every step:
- who does it,
- where,
- input,
- output,
- approval,
- common error,
- what gets re-entered manually.

Evidence if safe:
- redacted payroll sheet structure,
- column headings,
- one synthetic/redacted employee line,
- bank batch format structure,
- exception example.

Do not copy real salaries into GitHub.

---

# Session 3 — Employee Advance / سلفة

Walk one actual recent Advance example.

Ask:
- Who requests it?
- Can Ahmed create it on behalf of employee?
- Who approves?
- Does amount change approval authority?
- How is money issued?
- When does it become an actual debt?
- Is settlement normally payroll deduction, cash return, expense offset, mixed?
- Can employee have more than one open advance?
- Is there a due date?
- Can it be cancelled after approval but before funding?
- What happens if employee leaves with balance?
- What documents/receipts are used?
- Can part be settled early?
- What correction happens if too much was deducted/returned?

Need to validate:
- Advance fields,
- lifecycle,
- approval policy,
- payroll linkage,
- offboarding clearance.

---

# Session 4 — Financial Imprest / عهدة مالية

Walk one actual عهدة from funding to settlement.

First distinguish what HILTECH calls عهدة:
- fixed imprest?
- temporary project cash?
- revolving petty cash?
- purchasing cash?
- site custody?
- more than one type?

Ask:
- Who can hold it?
- Who approves initial amount?
- Is it tied to project/site?
- Cash, transfer, card, wallet?
- Can one person hold multiple open imprests?
- Can it be replenished before full settlement?
- What receipt/evidence is mandatory?
- Who accepts/rejects a spend line?
- How often is settlement required?
- What happens with missing receipt?
- What happens with shortage?
- What happens with overage?
- Can shortage ever become payroll deduction? Under what approved/legal process?
- What happens on offboarding?
- Is there currently Excel/paper/form numbering?

Need to validate:
- fixed/temporary/revolving types,
- ledger entry types,
- settlement cadence,
- shortage/overage policy,
- clearance policy,
- approval thresholds.

Evidence if safe:
- blank/redacted form,
- receipt pack structure,
- settlement sheet headings.

---

# Session 5 — Expenses

Clarify difference between:
- employee spent personal money -> reimbursement,
- employee spent company imprest -> imprest ledger,
- project petty purchase,
- supplier invoice/payment,
- payroll reimbursement.

Ask:
- Who approves expense?
- Project manager involved?
- Receipt required?
- Cash/card/bank?
- Project/cost center?
- When is it paid?
- Can it enter payroll?

Goal:
Prevent one generic Expense object from mixing different accounting realities.

---

# Session 6 — Supplier / Client Money

Supplier side:
- invoice arrives how?
- PO/receipt match?
- who confirms quantity/service?
- who prepares payment?
- who approves?
- partial payment?
- credit note?
- disputed invoice?

Client side:
- when invoice is created,
- milestone/variation source,
- who approves issue,
- how collection is tracked,
- partial receipt,
- overdue/dispute,
- who matches incoming bank money.

Evidence:
one redacted supplier-invoice flow + one client-invoice flow.

---

# Session 7 — Bank / Payment Execution

Do not ask for credentials.

Need process only:
- bank name(s),
- portal/manual/bulk file/API,
- maker/checker roles,
- beneficiary creation,
- approval,
- execution,
- status/result,
- statement/import,
- reconciliation,
- timeout/unknown outcome,
- returned/failed transfer.

Ask:
What proves to you that a payment actually succeeded?

That source becomes authoritative in HILTECH's payment-result model.

---

# Session 8 — Accounting / Tax / E-Invoice

Ask:
- accounting software,
- e-invoice/e-receipt platform,
- tax workflow,
- invoice numbering,
- journal/export process,
- accountant role,
- month close,
- what HILTECH should integrate vs export to.

Do not invent accounting treatment before this session.

---

# Session 9 — Ahmed's Work Queue

Ask Ahmed:
- What interrupts you most?
- What do you wait on from Mohamed?
- What do you wait on from PM/warehouse/HR?
- What do people chase you for?
- What do you copy between files?
- Which errors scare you most?
- Which deadline do you keep in your head?
- What would you want on one desktop screen at 9 AM?

Output:
Ahmed Home / Finance Work Queue priorities.

---

# Minimum Evidence For Finance Reality Pass

Enough to move Finance/Payroll contracts forward:

- payroll columns/process structure,
- approval chain,
- salary payment handoff structure,
- one Advance example structure,
- one FinancialImprest example structure,
- expense process,
- supplier invoice/payment flow,
- client invoice/collection flow,
- accounting/e-invoice system names,
- bank execution/reconciliation method,
- Ahmed's recurring reports/work queue.

---

# Validation Recording

For every answer mark:
- VERIFIED_DOCUMENT
- VERIFIED_OBSERVATION
- VERIFIED_INTERVIEW
- INTERNAL_REPORTED
- ASSUMPTION
- UNKNOWN

Record contradiction explicitly instead of averaging two answers together.

---

# Session Output Checklist

After the meeting update:
1. Reality Facts Register.
2. Finance/Payroll object specs.
3. Advance/Imprest workflow.
4. Finance/payment workflow.
5. Payroll workflow/model.
6. approval policies.
7. permissions/field-level access.
8. Systems Inventory.
9. External integration map.
10. exact DB/API contract blockers.