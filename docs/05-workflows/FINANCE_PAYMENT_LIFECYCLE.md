# Master Workflow — Finance & Payment Lifecycle

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Model how financial obligations and incoming money move through HILTECH from source event to authoritative settlement, while preserving approvals, versioning, audit, reconciliation, and failure handling.

This workflow connects:
Projects, Sales, Procurement, Payroll, Expenses, Clients, Suppliers, Banking, Approvals, Audit, Notifications.

---

# 1. Financial Sources

Financial items must originate from structured business truth.

## Incoming sources
- project billing milestone
- client invoice
- maintenance/service contract
- variation/change order
- advance/payment term
- recurring service

## Outgoing sources
- supplier payable
- payroll
- employee reimbursement
- advance
- subcontractor milestone
- operational expense
- refund/credit where applicable

No payment/receipt should exist without a business source/context unless explicitly classified as an authorized manual finance entry.

---

# 2. Receivable Creation

A receivable may be created when contractual/business conditions are satisfied.

Examples:
- milestone accepted
- invoice issued
- recurring service period due

Receivable captures:
- client
- project/service context
- invoice
- amount/currency
- due date
- contractual source
- status

States candidate:
DRAFT / ISSUED / PARTIALLY_PAID / PAID / OVERDUE / DISPUTED / CANCELLED / WRITTEN_OFF

Exact accounting/legal behavior requires validation.

---

# 3. Payable Creation

Sources:
- matched supplier invoice
- approved subcontractor milestone
- payroll batch obligation
- approved expense
- approved advance
- recurring operational obligation

Payable captures:
- payee
- source object
- amount/currency
- due date
- approval requirement
- payment method context
- accounting reference

---

# 4. Invoice Lifecycle

## Client invoice
DRAFT -> REVIEW -> ISSUED -> PARTIAL/PAID/OVERDUE/DISPUTED/CANCELLED/CREDITED

## Supplier invoice
RECEIVED -> MATCHING -> MATCHED / MISMATCH -> APPROVED_FOR_PAYMENT -> PAID / PARTIAL / DISPUTED / CREDITED

Important:
invoice and payment are separate objects/states.

---

# 5. Payment Preparation

Finance prepares exact payment instruction(s).

Input:
- payable source
- beneficiary/payment data
- amount
- bank/account
- reference
- execution date
- supporting approvals

System validates:
- source still payable
- not already paid
- exact approved amount/version
- beneficiary data present
- duplicates/idempotency
- authority path

Events:
- payment.prepared
- payment.validation_failed
- payment.ready_for_approval

---

# 6. Approval

Payment approval uses shared Approval Engine.

Approval is bound to:
- exact payment instruction version
- exact amount
- exact beneficiary
- exact source/payable

Any material change invalidates/supersedes approval.

---

# 7. External Execution

Possible modes depending bank reality:
- banking portal/manual authorized execution
- bank-approved bulk file
- host-to-host
- API

HILTECH does not pretend transfer succeeded merely because request/file was generated.

External execution returns/captures:
- submitted
- accepted
- rejected
- unknown
- partial
- returned

---

# 8. Unknown Outcome

Critical state.

Example:
bank request timed out after submission.

HILTECH must not blindly retry if duplicate payment is possible.

Flow:
UNKNOWN -> query/reconcile -> SUCCESS / FAILED / MANUAL_REVIEW

---

# 9. Reconciliation

Match external financial reality to HILTECH obligation.

Inputs:
- bank transaction
- payment result
- statement/import
- external reference
- receipt proof

Outcomes:
- reconciled
- partial
- unmatched
- duplicate
- reversed/returned

---

# 10. Incoming Payment

For client money:
- transaction detected/imported/recorded
- match to invoice/receivable
- partial allocation if applicable
- unresolved/unidentified payment queue
- reconciliation
- receivable state updated

Do not silently guess customer/invoice when match is ambiguous.

---

# 11. Partial Payment

Support:
- one payable/receivable paid in parts
- multiple invoices paid together
- one payment allocated across multiple items where reality requires

Allocation must be explicit/auditable.

---

# 12. Failed / Returned Payment

Preserve:
- original attempt
- reason/provider code
- current payable still open
- retry/new instruction
- notification
- reconciliation

Never overwrite failed attempt with successful retry.

---

# 13. Credits / Corrections

Use explicit:
- credit note
- reversal
- refund
- correction
- write-off

Never destructively edit already-issued/settled financial history.

Exact accounting semantics require accountant validation.

---

# 14. Project Financial Projection

Projects may consume:
- committed procurement
- actual stock/material usage
- subcontract costs
- employee/project time costing if adopted
- issued invoices
- received cash
- approved variation impact

Projection is derived from source modules; finance owns authoritative financial records.

---

# 15. Executive View

Mohamed should see:
- receivable/payable exceptions
- high-value approvals
- overdue client cash
- failed/unknown payments
- major project financial risks

Not every invoice/payment event.

---

# 16. Notifications

Examples:
- payment approval required
- payment failed
- unknown result unresolved
- client invoice overdue
- client payment received
- payable due
- reconciliation required

Sensitive amounts follow notification privacy policy.

---

# 17. Audit

Every critical finance action preserves:
- actor
- source
- version
- amount
- beneficiary/client
- approval
- bank/external correlation
- result
- timestamps
- correction lineage

---

# Major Objects Revealed

- Receivable
- Payable
- Invoice
- Invoice Version
- Credit Note
- Payment Instruction
- Payment Batch
- Payment Attempt
- Payment Result
- Allocation
- Reconciliation
- Bank Transaction
- Refund
- Write-off Request

DISCOVERED, not final.

---

# Completion Gate

Requires:
- real HILTECH accounting workflow,
- banks/providers,
- accounting/e-invoice/legal validation,
- payable/receivable rules,
- approval authority,
- reconciliation reality,
- schema/ledger model,
- bank adapter,
- UI,
- tests for duplicate/unknown/partial/returned payments,
- operational runbook.
