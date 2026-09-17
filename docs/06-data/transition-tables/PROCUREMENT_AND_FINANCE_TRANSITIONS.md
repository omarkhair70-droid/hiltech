# Transition Tables — Procurement, Invoice & Payment

Status: DOMAIN MODEL v0.1 / NOT FROZEN

---

# Purchase Requirement

DRAFT -> SubmitRequirement -> VALIDATION
VALIDATION -> ValidateRequirement -> READY_FOR_SOURCING
VALIDATION -> RequestMoreInfo -> NEEDS_INFO
NEEDS_INFO -> ResubmitRequirement -> VALIDATION
VALIDATION -> RejectRequirement -> REJECTED
READY_FOR_SOURCING -> StartSourcing -> SOURCING
SOURCING -> StartComparison -> COMPARISON
COMPARISON -> RequestPurchaseApproval -> APPROVAL
APPROVAL -> PurchaseApproved -> APPROVED
APPROVAL -> PurchaseRejected -> REJECTED
APPROVED -> CreatePurchaseOrder -> PO_CREATED
PO_CREATED -> FulfillRequirement -> FULFILLED
FULFILLED -> CloseRequirement -> CLOSED

Key invariants:
- requirement must retain source context.
- no silent replacement of item/spec after approval.
- stock check result recorded before buying where policy requires.

---

# Purchase Order

DRAFT -> FinalizePO -> INTERNAL_READY
INTERNAL_READY -> IssuePO -> ISSUED
ISSUED -> SupplierConfirm -> SUPPLIER_CONFIRMED
SUPPLIER_CONFIRMED/ISSUED -> ReceivePartial -> PARTIALLY_RECEIVED
PARTIALLY_RECEIVED -> ReceiveRemaining -> RECEIVED
SUPPLIER_CONFIRMED/ISSUED -> ReceiveAll -> RECEIVED
RECEIVED -> BeginInvoiceMatch -> INVOICE_MATCH
INVOICE_MATCH -> MatchComplete -> CLOSED

Branches:
ISSUED/SUPPLIER_CONFIRMED -> AmendPO -> SUPERSEDED + new PO version
ISSUED/SUPPLIER_CONFIRMED -> CancelPO -> CANCELLED
RECEIVED/INVOICE_MATCH -> OpenDispute -> DISPUTED

Invariants:
- supplier-facing version immutable after issue; amendments create version.
- receipt never silently exceeds authorized PO without discrepancy path.
- PO closure does not imply payment.

---

# Client Invoice

DRAFT -> ReviewInvoice -> REVIEW
REVIEW -> IssueInvoice -> ISSUED
ISSUED -> RecordPartialPayment -> PARTIALLY_PAID
ISSUED/PARTIALLY_PAID -> RecordFullSettlement -> PAID
ISSUED/PARTIALLY_PAID -> MarkOverdue -> OVERDUE
ISSUED/PARTIALLY_PAID/OVERDUE -> OpenDispute -> DISPUTED
DISPUTED -> ResolveDispute -> ISSUED/PARTIALLY_PAID/PAID according settlement
ISSUED -> CancelBeforeSettlement -> CANCELLED where legal/policy permits
ISSUED/PARTIALLY_PAID -> IssueCredit -> CREDITED/PARTIALLY_CREDITED

Invariants:
- issued financial history is versioned/immutable according accounting rules.
- payment allocation is explicit.

---

# Supplier Invoice

RECEIVED -> StartMatching -> MATCHING
MATCHING -> MatchInvoice -> MATCHED
MATCHING -> DetectMismatch -> MISMATCH
MISMATCH -> ResolveMismatch -> MATCHING/MATCHED/DISPUTED
MATCHED -> ApproveForPayment -> APPROVED_FOR_PAYMENT
APPROVED_FOR_PAYMENT -> PaymentSettled -> PAID
APPROVED_FOR_PAYMENT -> RecordPartialPayment -> PARTIALLY_PAID
MISMATCH/APPROVED_FOR_PAYMENT -> OpenDispute -> DISPUTED

---

# Payment Instruction

DRAFT -> ValidatePayment -> READY
READY -> RequestApproval -> PENDING_APPROVAL
PENDING_APPROVAL -> ApprovalGranted -> APPROVED
PENDING_APPROVAL -> ApprovalRejected -> REJECTED
APPROVED -> SubmitToBank/ExecuteExternal -> SUBMITTED
SUBMITTED -> ConfirmSuccess -> SUCCESS
SUBMITTED -> ConfirmFailure -> FAILED
SUBMITTED -> OutcomeUnknown -> UNKNOWN
UNKNOWN -> ReconcileSuccess -> SUCCESS
UNKNOWN -> ReconcileFailure -> FAILED
SUCCESS -> PaymentReturned -> RETURNED

Rules:
- no automatic retry from UNKNOWN if duplicate execution is possible.
- idempotency key required for retriable integration path.
- material edit after approval supersedes approval/payment version.

Offline:
All final payment state transitions online-authoritative.

---

# Receivable

OPEN -> AllocatePartialPayment -> PARTIALLY_SETTLED
OPEN/PARTIALLY_SETTLED -> AllocateFullPayment -> SETTLED
OPEN/PARTIALLY_SETTLED -> MarkOverdue -> OVERDUE
OPEN/PARTIALLY_SETTLED/OVERDUE -> Dispute -> DISPUTED
DISPUTED -> Resolve -> OPEN/PARTIALLY_SETTLED/SETTLED

---

# Payable

OPEN -> ApproveForPayment -> READY_TO_PAY
READY_TO_PAY -> StartPayment -> PAYMENT_IN_PROGRESS
PAYMENT_IN_PROGRESS -> PartialSettlement -> PARTIALLY_PAID
PAYMENT_IN_PROGRESS/PARTIALLY_PAID -> FullSettlement -> PAID
OPEN/READY_TO_PAY -> Dispute -> DISPUTED
DISPUTED -> Resolve -> OPEN/READY_TO_PAY/CANCELLED

---

# Cross-Domain Invariants

- Procurement does not mark something paid.
- Finance does not create inventory receipt.
- Bank result, not button press, determines payment success.
- Invoice, payable/receivable, and payment are distinct objects.
- Partial/failed/unknown states remain visible until resolved.
