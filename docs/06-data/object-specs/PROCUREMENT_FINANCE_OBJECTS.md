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

# Invariants Summary

- invoice != receivable/payable != payment.
- money uses decimal, never floating point.
- issued/approved versions immutable according policy.
- payment success requires authoritative external result/reconciliation.
- UNKNOWN external outcome never blind-retried.
- allocations explicit.
- bank/beneficiary data HIGHLY_RESTRICTED.

## Next
Ahmed/accountant/bank validation required before schema freeze.
