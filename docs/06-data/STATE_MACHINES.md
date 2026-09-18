# HILTECH State Machines

Status: DISCOVERED / NOT FROZEN

Purpose: capture lifecycle behavior explicitly before implementation. Exact states will be validated against HILTECH reality.

---

# Opportunity
LEAD
-> QUALIFIED
-> DISCOVERY
-> SOLUTION_COSTING
-> INTERNAL_REVIEW
-> SUBMITTED
-> NEGOTIATION
-> WON | LOST | ON_HOLD | CANCELLED

WON -> CONTRACTING -> DELIVERY_HANDOFF

---

# Project
DRAFT
-> KICKOFF
-> PLANNING
-> READY
-> ACTIVE
-> DELIVERY_REVIEW
-> HANDOVER
-> DELIVERED
-> WARRANTY / MAINTENANCE where applicable
-> CLOSED

Possible exceptional states:
ON_HOLD
AT_RISK
CANCELLED

AT_RISK may be a computed health flag rather than primary lifecycle state; not frozen.

---

# Work Order
DRAFT
-> PLANNED
-> READY
-> ASSIGNED
-> IN_PROGRESS
-> SUBMITTED_FOR_REVIEW
-> ACCEPTED
-> CLOSED

Branches:
READY -> BLOCKED
IN_PROGRESS -> BLOCKED
SUBMITTED_FOR_REVIEW -> REWORK_REQUIRED -> IN_PROGRESS
ANY_ALLOWED -> CANCELLED

Readiness blockers may be computed dimensions:
MATERIAL / EQUIPMENT / PEOPLE / ACCESS / DRAWING / DEPENDENCY.

---

# Asset
DRAFT
-> RECEIVED
-> AVAILABLE
-> RESERVED
-> CHECKED_OUT
-> ON_SITE
-> RETURN_PENDING
-> UNDER_INSPECTION
-> AVAILABLE

Exceptional/maintenance:
AVAILABLE | ON_SITE
-> DAMAGED
-> UNDER_REPAIR
-> UNDER_INSPECTION
-> AVAILABLE

AVAILABLE
-> CALIBRATION_DUE
-> UNDER_CALIBRATION
-> AVAILABLE

Loss/end:
CHECKED_OUT | ON_SITE -> MISSING
MISSING -> RECOVERED/UNDER_INSPECTION | LOST
AVAILABLE | DAMAGED -> RETIREMENT_REQUESTED -> RETIRED -> DISPOSED

Exact semantics require validation.

---

# Stock Reservation
REQUESTED
-> RESERVED
-> PARTIALLY_ISSUED
-> ISSUED
-> CONSUMED / RETURNED / PARTIALLY_RETURNED
-> CLOSED

Possible:
REQUESTED -> UNAVAILABLE
RESERVED -> CANCELLED
RESERVED -> EXPIRED

---

# Purchase Requirement
DRAFT
-> VALIDATION
-> READY_FOR_SOURCING
-> SOURCING
-> COMPARISON
-> APPROVAL
-> APPROVED
-> PO_CREATED
-> FULFILLED
-> CLOSED

Branches:
VALIDATION -> NEEDS_INFO
ANY_PRE_PO -> REJECTED / CANCELLED

---

# Purchase Order
DRAFT
-> INTERNAL_READY
-> ISSUED
-> SUPPLIER_CONFIRMED
-> PARTIALLY_RECEIVED
-> RECEIVED
-> INVOICE_MATCH
-> CLOSED

Branches:
ISSUED -> AMENDED/SUPERSEDED
ISSUED -> CANCELLED
RECEIVED -> DISPUTED if mismatch/claim

---

# Approval Request
DRAFT
-> PENDING
-> PARTIALLY_APPROVED
-> APPROVED

Branches:
PENDING | PARTIALLY_APPROVED -> REJECTED
PENDING -> CHANGE_REQUESTED
ANY_ACTIVE -> CANCELLED
ANY_ACTIVE -> EXPIRED
ANY_ACTIVE -> SUPERSEDED

Approved may later become SUPERSEDED if approved subject version changes.

---

# Employee
PRE_HIRE
-> PREBOARDING
-> ACTIVE
-> OFFBOARDING
-> FORMER

Possible:
ACTIVE -> LEAVE_STATE / RESTRICTED if HILTECH policy requires
then -> ACTIVE or OFFBOARDING

Do not encode legal/HR assumptions before validation.

---

# Leave Request
DRAFT
-> SUBMITTED
-> APPROVED | REJECTED
APPROVED -> SCHEDULED -> ACTIVE -> COMPLETED
Approved request may be CANCELLED according to policy.

---

# Employee Advance
DRAFT
-> SUBMITTED
-> APPROVED
-> ISSUED
-> PARTIALLY_SETTLED
-> SETTLED
-> CLOSED

Branches:
SUBMITTED -> REJECTED
DRAFT | SUBMITTED -> CANCELLED
APPROVED -> CANCELLED only before funding according policy
ISSUED | PARTIALLY_SETTLED -> REVIEW_REQUIRED / DISPUTED
REVIEW_REQUIRED -> PARTIALLY_SETTLED | SETTLED

Rules:
- settlement may combine payroll deduction, cash return and approved expense offset.
- outstanding balance is derived, not manually set.
- payroll deduction is a separate authorized payroll input.
- closure requires reconciled outstanding balance unless explicit approved exception.

---

# Financial Imprest / Cash Custody
DRAFT
-> REQUESTED
-> APPROVED
-> FUNDED
-> ACTIVE
-> SETTLEMENT_SUBMITTED
-> UNDER_REVIEW
-> CLEARED
-> CLOSED

Branches:
REQUESTED -> REJECTED
DRAFT | REQUESTED -> CANCELLED
ACTIVE -> REPLENISHMENT_PENDING -> ACTIVE
SETTLEMENT_SUBMITTED -> CHANGES_REQUIRED -> ACTIVE
UNDER_REVIEW -> SHORTAGE_REVIEW -> CLEARED
UNDER_REVIEW -> OVERAGE_REVIEW -> CLEARED

Rules:
- custody balance derives from append-only funding/replenishment/spend/return/correction entries.
- shortage/overage is explicit and auditable.
- no automatic payroll deduction from shortage.
- closed imprest cannot retain unresolved custody balance without explicit approved exception.

---

# Payroll Run
OPEN
-> COLLECTING_INPUTS
-> DRAFTED
-> REVIEW
-> APPROVAL
-> READY_TO_PAY
-> PAYMENT_IN_PROGRESS
-> PAID
-> CLOSED

Branches:
REVIEW -> CORRECTION_REQUIRED -> DRAFTED
APPROVAL -> REJECTED/CHANGE_REQUIRED -> DRAFTED
PAYMENT_IN_PROGRESS -> PARTIALLY_PAID
PARTIALLY_PAID -> PAYMENT_IN_PROGRESS | RECONCILIATION
CLOSED -> CORRECTION_RUN (separate object/version, not destructive edit)

---

# Payment Instruction
PENDING
-> SUBMITTED
-> SUCCESS

Branches:
SUBMITTED -> FAILED
SUBMITTED -> UNKNOWN
SUCCESS -> RETURNED where banking reality allows

Unknown must resolve through reconciliation, never assumption.

---

# Client Issue / Support Ticket
OPEN
-> TRIAGED
-> ASSIGNED
-> IN_PROGRESS
-> RESOLVED
-> CLOSED

Branches:
RESOLVED -> REOPENED -> IN_PROGRESS
ANY_ACTIVE -> WAITING_CLIENT / WAITING_VENDOR / WAITING_PART

Exact SLA state modeling later.

---

# Handover
PREPARING
-> READY_FOR_REVIEW
-> CLIENT_REVIEW
-> SNAGGING if needed
-> ACCEPTED
-> COMPLETE

No handover should be COMPLETE while required contractual conditions remain unresolved.

---

# Rules for all state machines

1. Transitions are commands with permission and validation, not arbitrary field edits.
2. State change emits auditable event.
3. Some "states" are better modelled as computed health/readiness flags; do not overload one enum.
4. Offline clients may propose a transition, but authoritative state is resolved by sync/server policy.
5. Approval-sensitive transitions must bind to exact object version.
6. Terminal states are not silently reopened unless an explicit transition exists.
7. State naming must become consistent before freeze.

## Next work
Create per-object transition tables:
FROM / COMMAND / PRECONDITION / AUTHORITY / TO / EVENT / SIDE EFFECTS / OFFLINE RULE.
