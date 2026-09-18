# Exact Object Specs — People & Payroll

Status: DOMAIN DATA MODEL v0.1 / NOT LEGAL/SCHEMA-FROZEN

---

# Person

## Purpose
Human identity record independent of employment/user account.

## Fields
- id
- displayName
- legalName — O — HIGHLY_RESTRICTED
- preferredName — O
- mobile — O — RESTRICTED
- email — O — RESTRICTED
- emergencyContact — O — HIGHLY_RESTRICTED
- identityDocumentRefs — O — HIGHLY_RESTRICTED
- createdAt
- version

Minimize personal data.

---

# Employee

## Fields
- id
- personId
- employeeCode — R unique
- employmentId
- status
- hireDate
- termination/endDate — O
- primaryRoleId
- teamId — O
- managerEmployeeId — O
- baseOffice/siteRef — O
- payrollGroupId — O — RESTRICTED
- activeAccessExpected: Boolean — C
- version

## Invariants
- employeeCode unique.
- FORMER should not retain active HILTECH employee access.
- manager relationship cannot self-reference/cycle in obvious direct case.
- role/team changes audited.

---

# Employment

## Fields
- id
- employeeId
- employmentType
- contractStart/end
- probation metadata — O
- compensationReferenceId — O — HIGHLY_RESTRICTED
- legal/admin status
- version

Actual legal fields require HR/accounting validation.

---

# EmployeeDocument

## Fields
- id
- employeeId
- documentType
- documentId/storageRef
- issue/expiry date
- verifiedAt/by
- classification: HIGHLY_RESTRICTED
- retentionPolicyRef

---

# Certification

## Fields
- id
- employeeId
- type
- issuer
- issuedAt
- validUntil
- documentRef
- status
- verifiedBy

Used for work eligibility.

---

# AttendanceRecord

## Fields
- id
- employeeId
- date
- source: OFFICE_ACCESS/SITE/APP/MANUAL/etc.
- checkIn/out
- status
- correctionState
- approvedBy — O
- project/siteRef — O
- version

Important:
Access-control event != attendance automatically unless policy maps it.

---

# Timesheet / WorkTime

## Fields
- id
- employeeId
- projectId — O
- workOrderId — O
- date
- regularMinutes
- overtimeMinutes
- source
- state
- approvedBy
- version

Payroll consumes only approved/eligible facts.

---

# LeaveRequest

## Fields
- id
- employeeId
- leaveType
- start/end
- duration
- reason — O — RESTRICTED
- state
- approver
- createdAt
- version

---

# Expense

## Fields
- id
- employeeId
- project/site/workRef — O
- category
- amount/currency
- merchant/source — O
- receiptDocumentId — O
- purpose
- state
- approvalRef
- reimbursementState
- version

Classification: RESTRICTED.

---

# Advance

## Purpose
Employee cash/financial advance that creates an explicit recoverable financial obligation.

An Advance is not a payroll deduction by itself and is not the same object as a FinancialImprest/cash custody.

## Fields
- id
- advanceCode — R unique human reference
- employeeId
- projectId/siteId — O
- purpose/reason — RESTRICTED
- amount
- currency
- requestedAt
- requestedBy
- dueDate — O
- state
- version
- approvalRef — O
- approvedAmount — O
- approvedSettlementMode — O
- issuedPaymentInstructionRef — O
- issuedAt — O
- settlementMode:
  - PAYROLL_DEDUCTION
  - CASH_RETURN
  - EXPENSE_OFFSET
  - MIXED
  - OTHER_APPROVED
- payrollDeductionPlanRef — O
- settledAmount — derived
- outstandingAmount — derived
- closedAt — O

Classification: HIGHLY_RESTRICTED.

## Candidate Lifecycle
DRAFT
-> SUBMITTED
-> APPROVED
-> ISSUED
-> PARTIALLY_SETTLED
-> SETTLED
-> CLOSED

Branches:
- SUBMITTED -> REJECTED
- DRAFT | SUBMITTED -> CANCELLED
- APPROVED -> CANCELLED only before issue according policy
- ISSUED | PARTIALLY_SETTLED -> DISPUTED / REVIEW_REQUIRED when reconciliation differs

Exact state names/policies require Ahmed reality validation.

## Invariants
- approved decision binds to exact advance version, employee, amount, currency and settlement terms.
- outstandingAmount is derived from authoritative issue minus accepted settlements/deductions/returns; it is never an arbitrary editable balance.
- issuing the advance requires an authoritative payment/finance reference.
- payroll deduction requires an explicit approved payroll input/plan; it is not silently created merely because an Advance exists.
- issued financial history is not destructively edited; correction/reversal uses explicit entries.
- outstandingAmount must not become negative without an explicit over-settlement/refund correction path.
- closing requires outstandingAmount = 0 unless an explicit approved write-off/correction policy exists.
- legal/payroll rules for deduction limits require HILTECH/accountant validation.

---

# CompensationReference

## Purpose
HR/payroll input, not final payslip.

## Fields
- id
- employeeId
- effectiveFrom
- effectiveTo — O
- baseAmount
- currency
- componentRulesRef
- createdBy
- approvalRef — O
- version

Classification: HIGHLY_RESTRICTED.

---

# PayrollPeriod

## Fields
- id
- code
- startDate
- endDate
- cutoffDate
- targetPaymentDate
- payrollGroup
- state

---

# PayrollRun

## Fields
- id
- payrollPeriodId
- runNumber
- versionNumber
- state
- employeePopulationSnapshotRef
- totals:
  - gross
  - deductions
  - reimbursements
  - net
- currency
- preparedBy
- approvalRef
- createdAt
- frozenAt — O
- version

Classification: HIGHLY_RESTRICTED.

## Invariants
- approval applies exact version.
- frozen/approved version immutable.
- totals equal child lines under deterministic rounding policy.
- PAID requires employee-level outcomes reconciled per policy.

---

# PayrollEmployeeLine

## Fields
- id
- payrollRunVersionId
- employeeId
- employmentSnapshot
- bank/payment destination reference — HIGHLY_RESTRICTED
- earningComponents
- deductionComponents
- reimbursementComponents
- netAmount
- exceptionFlags
- paymentInstructionRef
- paymentResultState

All source components traceable.

---

# Payslip

## Fields
- id
- payrollRunVersionId
- employeeId
- lineSnapshot
- generatedAt
- documentRef — O
- visibility: employee + authorized payroll roles

Classification: HIGHLY_RESTRICTED.

---

# OffboardingCase

## Fields
- id
- employeeId
- initiatedAt/by
- reasonCategory — RESTRICTED
- lastWorkingDate
- projectClearanceState
- assetClearanceState
- accessRevocationState
- financeClearanceState
- finalPayrollState
- hrDocumentState
- state
- completedAt

## Invariants
Cannot mark complete if mandatory clearance unresolved unless explicit exception approved.

---

# Data Retention Notes

Employee identity/financial/legal retention requires Egypt-specific validation.
Offboarded employee access is revoked, but business/audit history remains according to policy.

## Next
Reality validation with Ahmed/HR determines actual fields, payroll components, attendance, bank data, employee categories.
