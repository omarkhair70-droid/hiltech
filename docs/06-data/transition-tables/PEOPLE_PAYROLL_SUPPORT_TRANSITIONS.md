# Transition Tables — Employee, Payroll & Support

Status: DOMAIN MODEL v0.1 / NOT FROZEN

---

# Employee Lifecycle

PRE_HIRE -> ApproveHire -> PREBOARDING
PREBOARDING -> CompleteRequiredOnboarding -> READY_TO_ACTIVATE
READY_TO_ACTIVATE -> ActivateEmployee -> ACTIVE
ACTIVE -> StartLeaveState -> ON_LEAVE if modeled as employment state
ON_LEAVE -> ReturnFromLeave -> ACTIVE
ACTIVE -> StartOffboarding -> OFFBOARDING
OFFBOARDING -> CompleteOffboarding -> FORMER

Possible restricted/suspended states require HR/legal validation.

ActivateEmployee preconditions:
- required identity/account
- required employment fields
- role/team/manager
- mandatory docs/policies
- required access prerequisites

CompleteOffboarding preconditions:
- access revoked
- required company assets cleared or explicitly unresolved/approved
- project responsibility transferred
- finance/final-pay handoff
- records retained

Invariants:
- former employee cannot retain active company access.
- historical project/audit/asset movements remain.
- role change is versioned/auditable.

---

# Role / Team Change

ACTIVE -> ChangeRole/Team -> ACTIVE(new assignment)

Preconditions:
- authorized HR/management action
- effective date
- permission recomputation
- project impacts handled

Event:
employee.role_changed / team_changed

Side effects:
- permission change
- home experience change
- training requirements
- project reassignment review

---

# Payroll Run

OPEN -> CollectInputs -> COLLECTING_INPUTS
COLLECTING_INPUTS -> GenerateDraft -> DRAFTED
DRAFTED -> StartReview -> REVIEW
REVIEW -> RequestCorrection -> CORRECTION_REQUIRED
CORRECTION_REQUIRED -> RegenerateDraft -> DRAFTED
REVIEW -> RequestApproval -> APPROVAL
APPROVAL -> ApprovePayrollVersion -> READY_TO_PAY
APPROVAL -> RejectPayrollVersion -> CORRECTION_REQUIRED
READY_TO_PAY -> StartPaymentBatch -> PAYMENT_IN_PROGRESS
PAYMENT_IN_PROGRESS -> AllPaid -> PAID
PAYMENT_IN_PROGRESS -> SomePaid -> PARTIALLY_PAID
PARTIALLY_PAID -> ResolveRemaining -> PAID
PAID -> ReconcilePayroll -> RECONCILED
RECONCILED -> ClosePayroll -> CLOSED

Post-close correction:
CLOSED -> CreateCorrectionRun -> separate CORRECTION RUN object

Invariants:
- approval binds exact run version.
- employee line source traceable.
- one employee failure does not turn entire run into success.
- closed run not destructively edited.

Offline:
No final payroll/approval/payment transitions offline.

---

# Support Ticket

OPEN -> Triage -> TRIAGED
TRIAGED -> Assign -> ASSIGNED
ASSIGNED -> StartResolution -> IN_PROGRESS
IN_PROGRESS -> WaitForClient -> WAITING_CLIENT
IN_PROGRESS -> WaitForVendor -> WAITING_VENDOR
IN_PROGRESS -> WaitForPart -> WAITING_PART
WAITING_* -> Resume -> IN_PROGRESS
IN_PROGRESS -> Resolve -> RESOLVED
RESOLVED -> Close -> CLOSED
RESOLVED/CLOSED -> Reopen -> REOPENED -> IN_PROGRESS

Critical path:
OPEN/TRIAGED/ASSIGNED/IN_PROGRESS -> Escalate -> same lifecycle + escalated dimension/event

Invariants:
- SLA is separate timing dimension, not ticket state.
- internal note and client communication remain distinct.
- reopen preserves prior resolution.
- coverage/warranty state traceable.

---

# Maintenance Visit

PLANNED -> SCHEDULED
SCHEDULED -> IN_PROGRESS
IN_PROGRESS -> SUBMITTED
SUBMITTED -> REVIEWED
REVIEWED -> COMPLETE

Branches:
SCHEDULED -> RESCHEDULED
IN_PROGRESS -> BLOCKED
REVIEWED -> FOLLOW_UP_REQUIRED

Invariants:
- completed visit keeps checklist/evidence/findings.
- finding can create ticket/work/quote without losing visit context.
