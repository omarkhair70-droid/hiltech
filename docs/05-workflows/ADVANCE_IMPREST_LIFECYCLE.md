# Master Workflow — Employee Advance & Financial Imprest

Status: **RESEARCHING / SYSTEM MODEL v0.1 / REALITY VALIDATION REQUIRED**

## Objective

Model two different HILTECH financial responsibilities that must not be collapsed:

1. **Employee Advance / سلفة**
   Money advanced to an employee and later settled through an approved mechanism.

2. **Financial Imprest / Cash Custody / عهدة مالية**
   Company money placed in an accountable custodian's hands for operational spending, supported by receipts/evidence and explicit settlement.

They may both involve an employee and cash, but they have different accounting, payroll and accountability semantics.

---

# 1. Employee Advance

## Request
Employee/authorized admin records:
- employee,
- requested amount/currency,
- purpose,
- project/site if relevant,
- proposed settlement mode,
- due/settlement context.

Possible source:
- employee self-service,
- Ahmed/finance on behalf of employee,
- approved operational request.

## Approval
Approval binds exact:
- employee,
- amount,
- currency,
- purpose,
- settlement terms,
- version.

Any material change supersedes the approval.

## Issue
Finance issues money only after approval.

Funding creates/links:
- PaymentInstruction/authoritative payment reference,
- issued timestamp,
- exact funded amount.

The Advance becomes a real outstanding obligation only when money is actually issued.

## Settlement
Candidate settlement modes:
- payroll deduction,
- direct cash/bank return,
- approved expense offset,
- mixed.

Every accepted settlement is explicit and auditable.

## Close
Close only after:
- outstanding amount reconciles to zero,
- or an explicit approved correction/write-off policy resolves the remainder.

No hidden balance edit.

---

# 2. Financial Imprest / Cash Custody

## Request
Request includes:
- custodian employee,
- operational purpose,
- requested amount/currency,
- project/site/cost context,
- expected settlement date.

## Approval
Approval binds exact:
- custodian,
- amount,
- currency,
- purpose,
- version.

## Funding
Funding creates an append-only INITIAL_ISSUE ledger entry and authoritative payment/funding reference.

State becomes ACTIVE after funds are actually placed in custody.

## Operational Spend
Custodian records spending with:
- amount,
- merchant/payee,
- category,
- project/site/work context,
- receipt/evidence when required,
- date.

Capture may be offline-capable.

An offline record is not the same as final finance acceptance.

## Replenishment
If policy allows revolving imprest:
request -> approve -> fund -> append REPLENISHMENT entry.

Replenishment never rewrites the original issue.

## Settlement Submission
Custodian submits an exact settlement version containing:
- covered ledger entries,
- claimed spend,
- receipt coverage,
- cash remaining/returned,
- computed balance.

Submitted version is immutable for review; corrections create a new version/entries.

## Finance Review
Finance checks:
- evidence,
- eligible spend,
- duplicate receipt/entry,
- project/cost allocation,
- cash return,
- balance.

Outcome:
- accepted,
- changes required,
- shortage review,
- overage review.

## Shortage
Shortage is explicit.

Possible policy outcomes require Ahmed/accountant validation:
- employee returns cash,
- approved recoverable obligation,
- disciplinary/exception process,
- legal payroll deduction process if valid.

**Never automatically deduct from payroll merely because a shortage exists.**

## Overage
Overage is explicit:
- return/record extra cash,
- investigate source,
- approved correction entry.

Never erase through manual balance editing.

## Clearance
CLEARED means:
funded + replenished
=
accepted spend + accepted cash return + approved correction/adjustment

within the exact approved accounting policy.

Only then can the financial custody close.

---

# 3. Relationship to Payroll

Advance:
- may intentionally generate approved deduction components.

FinancialImprest:
- is not payroll by default.
- shortage cannot silently become salary deduction.

Payroll receives only explicit approved finance/HR inputs.

---

# 4. Relationship to Expense

Expense reimbursement:
employee spends personal money and asks HILTECH to reimburse.

FinancialImprest:
employee spends company money already in custody.

These must remain separate objects even when receipt evidence looks similar.

---

# 5. Relationship to Physical Asset Custody

FinancialImprest is money custody.

Asset custody is a physical tool/equipment lifecycle owned by Asset/Warehouse.

Employee clearance may require both:
- zero unresolved financial custody,
- returned/cleared physical assets.

---

# 6. Offboarding

Offboarding finance clearance must detect:
- open Advances,
- unsettled FinancialImprest,
- unresolved shortage/overage,
- pending expense reimbursement.

Employee cannot be considered financially cleared while mandatory obligations remain unresolved, unless an explicit approved exception exists.

---

# 7. Permissions

Employee:
- own request/status/settlement evidence according policy.

Manager/PM:
- business-purpose/project approval when policy requires.

Ahmed/Finance:
- funding, review, reconciliation, settlement, finance exception handling.

Mohamed/Owner:
- high-value/exception approvals according approval policy.

Payroll:
- only approved payroll-deduction input; not unrestricted imprest visibility.

Sensitive financial fields remain HIGHLY_RESTRICTED.

---

# 8. Notifications / Exceptions

Examples:
- advance approval required,
- advance settlement due,
- payroll deduction input pending,
- imprest settlement overdue,
- receipt missing,
- shortage/overage detected,
- replenishment approval required,
- finance clearance blocked.

Management receives exception signals, not every routine spend.

---

# 9. Audit

Preserve:
- requester/custodian,
- exact approved version,
- funding/payment reference,
- every ledger entry,
- receipt/evidence link,
- settlement versions,
- reviewer,
- shortage/overage resolution,
- payroll deduction linkage when applicable,
- corrections/reversals,
- timestamps/correlation IDs.

---

# Reality Validation Required

Ahmed/Mohamed must confirm:
- who can request/approve each kind,
- whether there are fixed/temporary/revolving imprests,
- concurrent imprest policy,
- cash vs bank/card funding,
- receipt requirements,
- settlement deadlines,
- replenishment rules,
- shortage/overage handling,
- whether/how employee advances are deducted from payroll,
- legal/accounting treatment,
- actual documents/spreadsheets currently used.

No automatic policy above is frozen until that validation.
