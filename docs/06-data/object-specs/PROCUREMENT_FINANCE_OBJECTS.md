# Exact Object Specs — Procurement & Finance

Status: DOMAIN DATA MODEL v0.1 / NOT ACCOUNTING/SCHEMA-FROZEN

---

# PurchaseRequirement

## Fields
- id
- code
- sourceType
- sourceRef
- requesterUserId
- projectId — O
- siteId — O
- requestedDate
- neededByDate — O
- urgency
- state
- justification
- currency — O
- estimatedAmount — O — RESTRICTED
- version

Child RequirementLine:
- item/service description
- stockItem/assetType ref — O
- quantity/unit
- technical specification
- preferred supplier — O
- budget/cost-code ref — O

---

# SupplierQuote

## Fields
- id
- supplierOrganizationId
- purchaseRequirementId
- versionNumber
- quoteNumber — O
- issuedAt
- validUntil — O
- currency
- subtotal/tax/total
- deliveryTerms
- paymentTerms
- warrantyTerms
- attachment/documentRef
- technicalComplianceState
- state
- version

Classification: RESTRICTED.

## Invariants
Issued quote version immutable.
Competitor suppliers never see each other's quote.

---

# PurchaseOrder

## Fields
- id
- poCode
- supplierOrganizationId
- purchaseRequirementId
- versionNumber
- state
- currency
- subtotal
- tax
- total
- deliveryDestinationRef
- expectedDeliveryDate
- paymentTerms
- projectAllocationRefs
- issuedAt/by
- supplierConfirmedAt
- version

Child POLine:
- item/service ref
- description/spec
- quantity
- unit
- unitPrice
- tax
- total
- project/cost allocation

Classification: RESTRICTED.

---

# Delivery

## Fields
- id
- purchaseOrderId
- supplierOrgId
- expectedAt
- arrivedAt — O
- destinationRef
- state
- carrier/reference — O
- deliveryNoteDocumentId — O

Child DeliveryLine:
- poLineRef
- expectedQty
- deliveredQty
- acceptedQty
- rejectedQty
- condition
- serials
- discrepancyRef

---

# ReceivingDiscrepancy

## Fields
- id
- deliveryId
- type
- description
- expectedValue
- actualValue
- evidenceRefs
- state
- owner
- resolution

---

# SupplierInvoice

## Fields
- id
- supplierOrgId
- invoiceNumber
- issueDate
- dueDate
- currency
- subtotal/tax/total
- documentRef
- poRefs
- receiptRefs
- matchState
- financeState
- version

Classification: RESTRICTED.

---

# MatchResult

## Fields
- id
- supplierInvoiceId
- purchaseOrderVersionRefs
- receiptRefs
- state
- priceVariance
- quantityVariance
- taxVariance
- missingDocs
- generatedAt
- reviewedBy

---

# ClientInvoice

## Fields
- id
- invoiceCode
- clientOrganizationId
- projectId — O
- serviceContractId — O
- sourceBillingMilestoneRef — O
- issueDate
- dueDate
- currency
- subtotal/tax/total
- state
- documentRef
- versionNumber
- version

Classification: RESTRICTED.

---

# Receivable

## Fields
- id
- clientOrganizationId
- sourceInvoiceId
- originalAmount
- outstandingAmount
- currency
- dueDate
- state
- disputeRef — O
- version

Invariant:
outstanding = original - valid allocations/credits, never manually arbitrary.

---

# Payable

## Fields
- id
- payeeType
- payeeRef
- sourceType
- sourceRef
- originalAmount
- outstandingAmount
- currency
- dueDate
- state
- approvalRef — O
- version

---

# PaymentInstruction

## Fields
- id
- paymentCode
- payableRefs
- payerAccountRef — HIGHLY_RESTRICTED
- beneficiaryRef — HIGHLY_RESTRICTED
- amount
- currency
- executionDate
- purpose/reference
- versionNumber
- approvalRef
- state
- idempotencyKey
- createdBy
- submittedAt — O
- version

Classification: HIGHLY_RESTRICTED.

---

# PaymentAttempt

## Fields
- id
- paymentInstructionId
- attemptNumber
- integrationProvider
- externalCorrelationId — O
- submittedAt
- resultState
- providerCode — O
- providerMessageRedacted — O
- unknownOutcomeSince — O

Append-only attempt history.

---

# Allocation

## Fields
- id
- paymentResult/transactionRef
- targetReceivable/payableRef
- amount
- currency
- allocatedAt/by

Invariant:
cannot allocate more than available payment or outstanding target without explicit overpayment policy.

---

# Reconciliation

## Fields
- id
- externalTransactionRef
- internalPaymentRef — O
- state
- matchedAmount
- variance
- reviewer
- resolvedAt
- notes

---

# BankTransaction

## Fields
- id
- bankAccountRef — HIGHLY_RESTRICTED
- externalTransactionId
- bookingDate
- valueDate
- amount
- currency
- direction
- counterpartyRef/description — HIGHLY_RESTRICTED
- rawProviderRef — RESTRICTED
- reconciliationState

---

# FinancialImprest / Cash Custody

## Purpose
Accountable company money placed in an employee/custodian's financial custody for approved operational spending.

This is distinct from:
- employee Advance,
- salary/payroll,
- physical Asset custody,
- ordinary reimbursement.

## Fields
- id
- imprestCode — R unique
- custodianEmployeeId
- purpose
- projectId/siteId/costContextRef — O
- currency
- requestedAmount
- approvedAmount — O
- currentCustodyBalance — derived
- acceptedSpendTotal — derived
- cashReturnedTotal — derived
- replenishedTotal — derived
- shortageAmount — derived
- overageAmount — derived
- state
- version
- approvalRef — O
- issuePaymentInstructionRef — O
- openedAt — O
- expectedSettlementAt — O
- lastSettlementRef — O
- closedAt — O

Classification: HIGHLY_RESTRICTED.

## Candidate Lifecycle
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
- REQUESTED -> REJECTED
- DRAFT | REQUESTED -> CANCELLED
- ACTIVE -> REPLENISHMENT_PENDING -> ACTIVE
- SETTLEMENT_SUBMITTED -> CHANGES_REQUIRED -> ACTIVE
- UNDER_REVIEW -> SHORTAGE_REVIEW / OVERAGE_REVIEW -> CLEARED

Whether multiple concurrent imprests per employee are allowed is policy/reality dependent.

---

# ImprestLedgerEntry

## Purpose
Append-only financial movement/evidence entry for a FinancialImprest.

## Fields
- id
- financialImprestId
- entryType:
  - INITIAL_ISSUE
  - REPLENISHMENT
  - SPEND
  - CASH_RETURN
  - APPROVED_ADJUSTMENT
  - REVERSAL
- occurredAt
- amount
- currency
- merchant/payeeRef — O
- expenseCategory — O
- project/site/workRef — O
- receiptEvidenceRefs — O
- payment/externalRef — O
- recordedBy
- approvalRef — O
- reversalOfEntryId — O
- notes — O — RESTRICTED
- createdAt

Classification: HIGHLY_RESTRICTED.

## Invariants
- entries are append-only after acceptance.
- corrections create reversal/correction entries; they do not rewrite historical money movement.
- currency must match the imprest unless an explicit FX policy exists.
- spend may require receipt/evidence according category/amount policy.
- an entry cannot be counted twice in settlement/reconciliation.

---

# ImprestSettlement

## Purpose
Versioned settlement package submitted by the custodian and reviewed by finance.

## Fields
- id
- financialImprestId
- versionNumber
- state
- submittedBy
- submittedAt
- coveredLedgerEntryRefs
- claimedSpendTotal
- acceptedSpendTotal — O
- cashReturnAmount
- computedCustodyBalance
- computedShortageAmount
- computedOverageAmount
- receiptCoverageStatus
- reviewerEmployeeId — O
- reviewedAt — O
- approvalRef — O
- decisionReason — O — RESTRICTED
- version

Classification: HIGHLY_RESTRICTED.

## Invariants
- settlement binds to an exact immutable set/version of entries.
- finance review cannot silently change submitted spend; rejected/changed lines create explicit review outcomes.
- CLEARED requires accepted spend + accepted cash return + approved adjustments to reconcile the funded/replenished amount.
- shortage/overage is explicit and auditable.
- shortage must never be converted automatically into payroll deduction without a separate authorized/legal process.
- overage must never disappear through manual balance editing.
- closed imprest has zero unresolved custody balance unless an explicit approved exception/write-off exists.

---

# Invariants Summary

- invoice != receivable/payable != payment.
- money uses decimal, never floating point.
- issued/approved versions immutable according policy.
- payment success requires authoritative external result/reconciliation.
- UNKNOWN external outcome never blind-retried.
- allocations explicit.
- employee Advance and FinancialImprest are separate obligations with separate settlement semantics.
- FinancialImprest custody balance is derived from append-only issue/replenishment/spend/return/adjustment entries.
- shortage/overage is explicit and cannot silently become payroll deduction or disappear through balance edits.
- bank/beneficiary/imprest data HIGHLY_RESTRICTED.

## Next
Ahmed/accountant/bank validation required before schema freeze.
