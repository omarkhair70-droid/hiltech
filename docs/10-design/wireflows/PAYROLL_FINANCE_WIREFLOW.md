# Wireflow — Ahmed / Payroll & Finance

Status: EXPERIENCE WIREFLOW v0.1 / NOT VISUAL DESIGN

## Goal
Turn payroll from spreadsheet reconstruction into exception-driven, versioned, reviewable financial workflow.

---

# Desktop Flow

```text
Finance Home
    ↓
Payroll
    ↓
September 2026
    ↓
Collect Inputs
    ↓
Draft Generated
    ↓
Exception Review
    ↓
Resolve / Adjust
    ↓
Freeze v4
    ↓
Request Approval
    ↓
Waiting on Mohamed
    ↓
APPROVED
    ↓
Prepare Payment Batch
    ↓
Bank Handoff / Execution
    ↓
Results
    ↓
Reconciliation
    ↓
Payslips
    ↓
Close Run
```

---

# Payroll Home

Shows:
- current period.
- last closed period.
- missing inputs.
- exceptions.
- approval state.
- payment status.
- failed employees.
- upcoming cutoff.

Primary action should be obvious.

---

# Draft Grid

Desktop layout candidate:

```text
[Employee rows] | [Selected Employee Inspector]

Ahmed Hassan      → base salary
Mona Ali          → overtime source
...               → deduction
                  → reimbursement
                  → previous month delta
                  → audit/source
```

Columns candidates:
- Employee
- Status
- Base
- Overtime
- Bonus
- Deductions
- Reimbursements
- Net
- Delta
- Exception

Sensitive.

---

# Exception Review

Example:
```text
Ahmed Hassan
Net pay changed +34%

Reasons:
+ approved overtime 28h
+ expense reimbursement
- advance deduction

[Source details]
[Mark reviewed]
```

Automation flags.
Human decides.

---

# Controlled Adjustment

Ahmed cannot silently edit source payroll truth.

```text
Add adjustment
  ↓
Type
Amount
Reason
Evidence
  ↓
Creates PayrollAdjustment
  ↓
Recalculate run version
```

---

# Request Approval

```text
Review complete
   ↓
Freeze version v4
   ↓
Request Approval
   ↓
Run becomes immutable
   ↓
ApprovalRequest(subject=v4)
   ↓
Ahmed sees:
Waiting on Mohamed
```

Any edit:
new version v5
old approval superseded.

---

# Owner Decision Return

```text
Mohamed approves on mobile
        ↓
Realtime hint
        ↓
Ahmed local/read state updates
        ↓
READY_TO_PAY
```

No WhatsApp:
"وافقت؟"

---

# Payment Batch

```text
READY_TO_PAY
    ↓
Prepare payment instructions
    ↓
Validate employee payment data
    ↓
Exceptions?
   ├─ yes → hold affected employee / resolve
   └─ no
    ↓
Approved execution path
    ↓
Bank/provider
```

---

# Partial Result

```text
42 employees
39 SUCCESS
2 FAILED
1 UNKNOWN
```

UI must NOT show:
"Payroll paid successfully."

Instead:
```text
Payment incomplete
39 paid
2 failed
1 needs reconciliation

[Resolve remaining]
```

---

# Unknown Bank Outcome

```text
Transfer submitted
     ↓
Connection timed out
     ↓
UNKNOWN
     ↓
Do NOT retry
     ↓
Query/reconcile
     ↓
SUCCESS / FAILED / manual review
```

---

# Employee Payslip

Only once policy allows:
- employee sees own payslip.
- payment state.
- period.
- breakdown.

No peer visibility.

---

# Closed Payroll Correction

```text
Closed run
   ↓
Issue discovered
   ↓
Create Correction Run
   ↓
linked to original
```

Never edit closed v4.

---

# Mobile Ahmed

Mobile is exception/action oriented:
- approval result.
- failed payment.
- urgent employee line.
- reconciliation item.

Dense editing stays Desktop.

---

# Error States

- missing bank data.
- duplicate employee.
- invalid negative net.
- stale compensation reference.
- approval superseded.
- bank unavailable.
- payment unknown.
- permission removed.

---

# Success Criteria

Ahmed can trace every number to source and spends time on exceptions instead of copying data.
