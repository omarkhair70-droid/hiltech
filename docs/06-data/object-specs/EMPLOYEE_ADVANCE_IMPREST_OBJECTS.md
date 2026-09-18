# Exact Object Specs — Employee Advance & Financial Imprest

Status: DOMAIN DATA MODEL v0.1 / NOT ACCOUNTING-FROZEN

# FinancialImprest

Fields:
- id
- imprestCode
- custodianEmployeeId
- projectId — optional
- siteId — optional
- purpose
- currency
- openingAmount
- approvedReplenishmentTotal
- acceptedSpendTotal — derived
- returnedCashTotal — derived
- outstandingAmount — derived
- state
- issuedAt
- expectedSettlementAt — optional
- approvalRef
- issuedPaymentRef — optional
- version

Classification: HIGHLY_RESTRICTED.

Invariants:
- outstanding amount is derived, never directly edited.
- one movement ledger explains the balance.
- SETTLED requires outstanding amount = 0 or explicit approved accounting exception.
- offboarding clearance checks active/unsettled imprest.

---

# ImprestMovement

Append-only.

Fields:
- id
- imprestId
- movementType:
  ISSUE / REPLENISHMENT / ACCEPTED_SPEND / CASH_RETURN / SHORTAGE / OVERAGE / CORRECTION
- amount
- currency
- occurredAt
- actor
- sourceRef
- approvalRef — optional
- notes — optional
- operationId

---

# ImprestSpend

Fields:
- id
- imprestId
- spentAt
- amount
- currency
- category
- payee/merchant — optional
- project/site/workRef — optional
- purpose
- receiptDocumentId — optional
- captureSource
- reviewState
- reviewedBy — optional
- version

States:
DRAFT / SUBMITTED / ACCEPTED / REJECTED / NEEDS_INFO.

---

# ImprestSettlement

Fields:
- id
- imprestId
- submittedBy
- submittedAt
- declaredCashRemaining
- returnedCashRef — optional
- spendTotal
- discrepancyAmount — derived
- missingReceiptCount
- reviewer
- state
- resolution
- completedAt
- version

States:
DRAFT / SUBMITTED / REVIEW / NEEDS_CORRECTION / DISCREPANCY / SETTLED.

---

# Advance

Existing Advance object remains separate.

Add/confirm:
- advanceCode
- employeeId
- principalAmount
- outstandingAmount
- settlementMode
- payrollDeductionPlanRef — optional
- issuedPaymentRef
- state
- approvalRef
- version

---

# Employee Clearance Integration

OffboardingCase adds/derives:
- advanceClearanceState
- imprestClearanceState
- assetClearanceState
- financeClearanceState

Employee cannot disappear from system history after settlement.
