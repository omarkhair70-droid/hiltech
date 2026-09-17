# Master Workflow — Payroll

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Transform valid employee, time, compensation, allowance, deduction, expense, and advance facts into a reviewed, approved, payable payroll run with employee-visible payslips and complete audit history.

Payroll is not COMPLETE when salary arithmetic works. It is complete only when the whole lifecycle works safely.

---

# 1. Payroll Period

Create period:
- month/date range
- eligible employee population
- cutoff dates
- payment date target
- policy/version

States:
OPEN / COLLECTING_INPUTS / DRAFTED / REVIEW / APPROVAL / READY_TO_PAY / PAYMENT_IN_PROGRESS / PAID / PARTIALLY_PAID / CLOSED / CORRECTION_REQUIRED

Exact states not frozen.

---

# 2. Eligibility Snapshot

Determine employees included based on:
- employment state
- start/end date
- employee category
- payroll group
- suspension/leave rules where applicable

Snapshot must preserve historical truth even if employee data changes later.

---

# 3. Input Collection

Potential inputs:
- base compensation reference
- approved overtime
- attendance/absence effects
- bonuses
- deductions
- advances/loan deductions if applicable
- expense reimbursements
- allowances
- project/site-specific additions
- manual adjustment with reason/authority

Each input must have source and audit trail.

---

# 4. Calculation

Calculate per employee:
- gross components
- deductions
- reimbursements
- net

Tax/social insurance/legal calculation is NOT assumed and requires Egypt/HILTECH accounting validation before implementation.

Never infer legal payroll formulas from generic assumptions.

---

# 5. Exceptions

System highlights:
- missing bank/payment data
- unusually large change
- negative/invalid net
- missing attendance approvals
- unapproved overtime
- duplicate adjustment
- employee state inconsistency

Ahmed reviews exceptions rather than manually rebuilding every row.

---

# 6. Draft Review

Finance/Admin can:
- inspect employee line
- trace every component to source
- correct via controlled adjustment
- exclude/hold employee with reason
- regenerate calculation

Never silently edit historical source facts from payroll screen.

---

# 7. Payroll Approval

Approved through shared Approval Engine.

Approval applies to exact payroll run version.

Any material change after approval supersedes/invalidates approval as policy defines.

Events:
- payroll.approval_requested
- payroll.approved
- payroll.rejected
- payroll.superseded

---

# 8. Payment Preparation

Create payment instructions/batch according to actual HILTECH bank/integration path.

Possible future modes:
- bank API
- host-to-host
- bank-approved file
- banking portal assisted flow

No banking integration is frozen yet.

Payment batch links exact employees and net amounts.

---

# 9. Payment Authorization

High-risk online-authoritative action.

May require:
- finance preparation
- owner/authorized signatory approval
- bank OTP/signature outside HILTECH depending integration

HILTECH must reflect actual bank authority, not pretend to replace it.

---

# 10. Payment Result

Per employee:
PENDING / SUCCESS / FAILED / RETURNED / UNKNOWN / MANUAL_REVIEW

A batch can be partially successful.

Do not mark whole payroll PAID if one or more employees failed.

---

# 11. Reconciliation

Match bank/payment result to payroll instructions.

Handle:
- success
- failed transfer
- wrong/closed account
- returned payment
- retry
- alternate payment according to policy

Events:
- payroll.payment_started
- payroll.employee_paid
- payroll.payment_failed
- payroll.reconciled

---

# 12. Payslip

Employee sees:
- period
- earnings components
- deductions
- reimbursements
- net
- payment status/date
- downloadable/official representation if required

Visibility is employee-specific.

---

# 13. Notifications

Examples:
- payroll cutoff approaching (relevant admins/managers)
- payroll ready for review
- approval needed
- payment completed
- employee payment failed (finance)
- payslip available (employee)

Do not expose sensitive amounts in unsafe notification surfaces by default.

---

# 14. Corrections

Closed payroll must not be destructively edited.

Use:
- correction run
- adjustment in next payroll
- reversal/reissue workflow
according to accounting/legal reality.

Maintain complete history.

---

# 15. Offboarding / Final Pay

Final employee settlement may require:
- final attendance
- asset return
- advance settlement
- expenses
- leave/other entitlements if policy/law requires
- final approvals

Exact rules require HR/accounting/legal validation.

---

# Invariants
- Every payroll component has a source/reason.
- Approved payroll refers to a fixed version.
- Employee cannot see another employee's payroll.
- Payment state is not assumed from file generation.
- Failed employee payment does not disappear.
- Closed payroll is immutable except through controlled correction.
- Bank integration result is auditable.

---

# Major Objects
- Payroll Period
- Payroll Run
- Payroll Run Version
- Payroll Employee Line
- Compensation Component
- Adjustment
- Payroll Exception
- Approval
- Payment Batch
- Payment Instruction
- Payment Result
- Reconciliation
- Payslip
- Correction Run

DISCOVERED, not final.

---

# Completion gate
Requires:
- actual HILTECH payroll process with Ahmed/Mohamed,
- employee categories,
- payroll legal/accounting rules,
- bank/payment path,
- approval authority,
- HR inputs,
- expense/advance behavior,
- correction/failure handling,
- permission model,
- UI surfaces,
- technical modules/schema/files,
- bank sandbox/test strategy where available,
- tests for partial payment, changed payroll after approval, terminated employee, missing account data, and correction.
