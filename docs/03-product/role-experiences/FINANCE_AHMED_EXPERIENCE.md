# Complete Role Experience — Ahmed / Finance-Admin

Status: PRODUCT MODEL v0.1 / NOT BUILD-READY

## Product thesis
Ahmed should move from repeated data entry and chasing confirmations toward financial control, review, exception handling, reconciliation, and execution.

HILTECH should collect valid operational facts before Finance sees them.

---

# 1. Mobile Home — Finance

### Needs Action
- payroll exception
- payment ready/requires review
- invoice mismatch
- overdue receivable/payable requiring attention
- expense/advance queue
- supplier invoice ready
- failed transfer
- missing financial input

### Finance Pulse
- expected inflow/outflow
- payroll status
- receivables
- payables
- invoices awaiting action
- unreconciled payments
- exceptions

Mobile prioritizes review/action, not dense bookkeeping.

---

# 2. Desktop — Finance Workspace

Desktop is the primary deep-work surface.

Potential areas:
- Finance Home
- Payroll
- Receivables
- Payables
- Client Invoices
- Supplier Invoices
- Expenses
- Advances
- Payments
- Reconciliation
- Project Cost
- Reports
- Exceptions

Support:
- dense tables
- keyboard navigation
- bulk selection
- filtering
- split view
- side-by-side source/evidence
- export where authorized
- audit drill-down

---

# 3. Payroll

Ahmed:
- opens payroll period
- reviews eligibility
- reviews collected inputs
- resolves exceptions
- traces every component to source
- adds controlled/manual adjustment with reason when authorized
- prepares version
- sends exact version for approval
- prepares payment instructions
- monitors payment results
- reconciles failures
- closes according to policy

Never manually retype facts that already exist as approved source data.

---

# 4. Payables / Procurement Handoff

When procurement/warehouse completes valid chain:
PO -> receipt -> supplier invoice -> match

Finance sees:
- matched
- mismatch reason
- project/cost context
- supplier
- due date
- approval state
- payment eligibility

Ahmed does not need to ask warehouse if delivery happened when receipt is authoritative.

---

# 5. Receivables / Client Billing

Finance sees:
- contract/payment terms
- billing milestone eligibility
- invoice draft/issued
- client
- due date
- received/partial/overdue
- reconciliation
- project context

Operational completion should trigger eligibility, not auto-issue invoice unless policy says so.

---

# 6. Expenses / Advances

Ahmed sees:
- employee
- project/site/purpose
- amount
- evidence
- manager/PM approval
- policy exception
- settlement/reimbursement state

Employee can track own status without asking Finance manually.

---

# 7. Payment Run

Possible workflow:
Prepare -> Validate -> Approval -> Bank Execution/Handoff -> Result -> Reconcile.

Must support:
- partial success
- failure
- returned/unknown
- retry/correction

No payment is successful merely because a file/API request was generated.

---

# 8. Project Financial Context

Where allowed:
- budget baseline
- committed procurement
- actual material usage
- labor/time inputs if costed
- supplier/subcontract cost
- invoiced revenue
- received revenue
- variation impact
- margin/forecast

Exact project accounting model requires reality/accounting validation.

---

# 9. Employee/HR Boundary

Ahmed may require:
- employee payroll identity
- compensation reference
- bank/payment details
- approved payroll inputs

He should not automatically receive unrelated HR-sensitive information.

If Ahmed currently holds HR/Admin duties too, product still models Finance and HR permissions separately so future organization changes are possible.

---

# 10. Owner Handoff

Ahmed prepares financial truth.
Mohamed receives only decisions requiring owner authority.

Examples:
- payroll exact version
- high-value payment
- exceptional write-off
- unusual commercial decision

Decision returns into Ahmed workspace immediately.

---

# 11. Notifications

Finance-relevant:
- payroll cutoff/exception
- approval result
- failed payment
- invoice mismatch
- due/overdue critical item
- payable ready
- client payment received
- reconciliation required

Avoid low-value operational noise.

---

# 12. Offline

Finance deep work generally assumes authoritative online state.

Cached read may exist, but:
- approvals
- payments
- payroll finalization
- financial corrections
require online authority.

No offline financial write should silently become final.

---

# 13. Audit / Reversibility

Every sensitive operation should reveal:
- source
- actor
- timestamp
- version
- approval
- result

Corrections are controlled transactions/versions, not silent edits.

---

# 14. Empty / Error / Edge States

Design:
- no payroll inputs
- duplicate/missing employee bank info
- invoice mismatch
- payment provider unavailable
- bank result unknown
- partial batch success
- approval invalidated by edit
- source document missing
- stale exchange/tax/legal configuration if later relevant
- permission removed during session

---

# 15. Search

Ahmed should find:
- employee
- payroll period
- supplier
- invoice
- PO
- client invoice
- payment
- project cost item
- expense
- advance

Search result opens directly in financial context.

---

# 16. Key objects

Payroll Run
Payroll Line
Employee compensation context
Invoice
Payable
Receivable
Payment Batch
Payment Result
Reconciliation
Expense
Advance
Purchase Order
Receipt
Supplier
Client
Project Cost
Approval

---

# 17. Success criteria

- Repeated data entry is reduced.
- Every number can be traced to source.
- Payroll is exception-driven.
- Procurement receipts feed payables.
- Project delivery feeds billing eligibility.
- Ahmed and Mohamed coordinate through shared state, not files/messages.
- Payment failures remain visible until resolved.
- Historical financial truth is auditable.

## Current completion
PRODUCT MODEL ONLY.

Not BUILD-READY until:
- real Ahmed workflow is documented,
- accounting/legal/payroll rules are validated,
- banking path is known,
- finance object/state model is frozen,
- UI references and surface maps exist,
- technical module/schema/API plan exists,
- tests/observability/recovery are defined.
