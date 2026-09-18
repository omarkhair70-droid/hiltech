# HILTECH Business Command Catalog

Status: DOMAIN/API MODEL v0.1 / NOT API-FROZEN

## Purpose
Define meaningful business actions before HTTP/API design.

HILTECH should not expose important state as generic unrestricted CRUD.

A command represents an intention:
`CheckoutAsset`

An event represents what happened:
`asset.checked_out`

---

# Command Envelope — Candidate

Every authoritative command may carry:

- operationId / idempotency key
- actor/session context — server derived where possible
- target object ID
- baseVersion — when concurrency-sensitive
- command payload
- reason — where policy requires
- clientOccurredAt — for offline capture where meaningful
- device ID — server/session derived or validated
- correlation/trace ID

Never trust client-supplied authority/role.

---

# Project Commands

## CreateProjectFromAward
Owner: Projects
Online: YES
Input:
- award/opportunity reference
- baseline source
Output:
- Project

## StartProjectKickoff
Owner: Projects
Online: YES

## CompleteProjectKickoff
Owner: Projects
Online: YES

## ActivateProject
Owner: Projects
Online: YES

## PutProjectOnHold
Owner: Projects
Online: YES
Reason required.

## ResumeProject
Owner: Projects
Online: YES

## StartHandover
Owner: Projects/Documents
Online: YES

## AcceptProjectDelivery
Owner: Projects
Online: YES
May require client approval artifact.

## CloseProject
Owner: Projects
Online: YES

---

# Work Commands

## CreateWorkOrder
Owner: Work
Online: YES

## AssignWork
Owner: Work
Online: YES
Concurrency-sensitive.

## StartWork
Owner: Work
Offline-capable: YES
Requires baseVersion.

## BlockWork
Owner: Work
Offline-capable: YES

## ResumeWork
Owner: Work
Offline-capable: YES

## SubmitWorkCompletion
Owner: Work
Offline-capable: YES
Depends on evidence operations.

## AcceptWork
Owner: Work
Online: YES

## RequestRework
Owner: Work
Online: YES

## CancelWork
Owner: Work
Online: YES

---

# Evidence Commands

## CaptureEvidence
Owner: Documents/Evidence
Offline-capable: YES
Creates local/server evidence metadata.

## ConfirmEvidenceUpload
Server/internal completion after storage success.

## LinkEvidenceToObject
Owner: Documents
Offline-capable: conditional.

## SupersedeDocumentVersion
Online: YES.

---

# Asset Commands

## RegisterAsset
Owner: Assets
Online: default.

## ReserveAsset
Owner: Warehouse
Online: YES.

## CheckoutAsset
Owner: Warehouse
Online: default.
Concurrency/idempotency critical.

## ReturnAsset
Owner: Warehouse
Online final authority; local scan draft possible.

## TransferAsset
Owner: Warehouse
Online default.

## ReportAssetDamage
Owner: Assets
Offline-capable: YES.

## ReportAssetMissing
Owner: Assets
Offline-capable: YES.

## SendAssetForRepair
Online.

## CompleteAssetRepair
Online.

## SendAssetForCalibration
Online.

## CompleteCalibration
Online.

## RequestAssetRetirement
Online.

## ApproveAssetRetirement
Online + approval.

---

# Stock Commands

## ReceiveStock
Online final; capture may be staged locally.

## ReserveStock
Online.

## IssueStock
Online default.

## ConsumeStock
Offline-capable: YES.

## ReturnUnusedStock
Offline-capable: YES.

## StartStocktake
Online.

## RecordStockCount
Offline-capable: YES.

## RequestStockAdjustment
Online.

## ApproveStockAdjustment
Online + critical authority.

---

# Procurement Commands

## CreatePurchaseRequirement
Offline draft possible.

## SubmitPurchaseRequirement
Online.

## ValidatePurchaseRequirement
Online.

## RecordSupplierQuote
Online.

## RequestPurchaseApproval
Online.

## ApprovePurchase
Uses Approval system.

## CreatePurchaseOrder
Online.

## IssuePurchaseOrder
Online.

## AmendPurchaseOrder
Online; new version.

## RecordSupplierConfirmation
Online.

## ReceivePurchaseDelivery
Online final; capture may be local depending process.

## ResolveReceivingDiscrepancy
Online.

---

# Finance Commands

## IssueClientInvoice
Online.

## RecordSupplierInvoice
Online.

## MatchSupplierInvoice
Online/system-assisted.

## CreatePayable
Server/domain/internal.

## CreateReceivable
Server/domain/internal.

## PreparePayment
Online.

## RequestPaymentApproval
Online.

## ApprovePayment
Online + re-auth candidate.

## SubmitPaymentToProvider
Online/integration.

## RecordPaymentProviderResult
Server/integration.

## ReconcilePayment
Online.

## AllocateIncomingPayment
Online.

## IssueCredit
Online.

## RequestWriteOff
Online.

## ApproveWriteOff
Online + critical approval.

## RequestFinancialImprest
Owner: Finance
Online/offline request queue candidate.
Creates requested cash-custody purpose/amount/custodian context.

## ApproveFinancialImprest
Owner: Finance + Approval
Online: YES.
Binds exact custodian, amount, currency, purpose and version.

## FundFinancialImprest
Owner: Finance
Online: YES.
Requires approved imprest + authoritative payment/funding reference.

## RecordImprestSpend
Owner: Finance
Offline-capable capture: YES.
Records append-only spend/evidence line; acceptance/reconciliation remains authoritative.

## RecordImprestCashReturn
Owner: Finance
Online final authority; local evidence capture may be queued.

## RequestImprestReplenishment
Owner: Finance
Online: YES.

## ApproveImprestReplenishment
Owner: Finance + Approval
Online: YES.

## FundImprestReplenishment
Owner: Finance
Online: YES.
Creates append-only replenishment entry.

## SubmitImprestSettlement
Owner: Finance
Online/offline queue candidate after local evidence completeness checks.
Binds an exact settlement version + ledger entry set.

## ReviewImprestSettlement
Owner: Finance
Online: YES.
Accepts/rejects lines and computes shortage/overage explicitly.

## ResolveImprestShortage
Owner: Finance
Online + reason/approval as policy requires.
Never silently creates payroll deduction.

## ResolveImprestOverage
Owner: Finance
Online + explicit return/correction path.

## ClearFinancialImprest
Owner: Finance
Online: YES.
Requires reconciled custody balance according approved policy.

## CloseFinancialImprest
Owner: Finance
Online: YES.
Requires CLEARED state or explicit approved exception.

---

# Payroll Commands

## OpenPayrollPeriod
Online.

## GeneratePayrollDraft
Online.

## AddPayrollAdjustment
Online, reason/authority.

## RequestPayrollApproval
Online; freezes exact version.

## ApprovePayrollVersion
Online + critical authority/re-auth candidate.

## RejectPayrollVersion
Online.

## PreparePayrollPaymentBatch
Online.

## StartPayrollPayment
Online/integration.

## ReconcilePayrollPayment
Online.

## CreatePayrollCorrectionRun
Online.

---

# People Commands

## InviteEmployee
Online.

## ActivateEmployee
Online.

## ChangeEmployeeRole
Online + permission recompute.

## ChangeEmployeeTeam
Online.

## SubmitLeaveRequest
Offline-capable request: YES.

## ApproveLeave
Online.

## SubmitExpense
Offline-capable capture/submission queue.

## ApproveExpense
Online.

## RequestAdvance
Owner: People/Finance
Offline-capable request queue: YES.
Creates/updates DRAFT/SUBMITTED Advance only.

## ApproveAdvance
Owner: People/Finance + Approval
Online: YES.
Binds exact employee/amount/currency/settlement terms/version.

## RejectAdvance
Online: YES.

## IssueAdvance
Owner: Finance
Online: YES.
Requires approved Advance + authoritative payment reference/idempotency.

## RecordAdvanceSettlement
Owner: Finance
Online final authority; evidence capture may be queued offline.
Settlement may be payroll deduction, cash return, expense offset, or approved mixed mode.

## ApplyAdvancePayrollDeduction
Owner: Payroll
Online/server authoritative.
Requires explicit approved deduction input; never inferred silently.

## CloseAdvance
Owner: Finance
Online: YES.
Requires zero unresolved outstanding balance unless explicit approved exception/write-off exists.

## StartOffboarding
Online.

## CompleteOffboarding
Online; clearance conditions.

---

# Client / Support Commands

## InviteClientUser
Online.

## RequestClientApproval
Online.

## SubmitClientApprovalDecision
Online + version-bound.

## CreateSupportTicket
Offline-capable queue: YES.

## TriageSupportTicket
Online.

## AssignSupportTicket
Online.

## ResolveSupportTicket
Online.

## ReopenSupportTicket
Online.

## ScheduleMaintenanceVisit
Online.

## StartMaintenanceVisit
Offline-capable.

## SubmitMaintenanceVisit
Offline-capable.

## CompleteMaintenanceReview
Online.

---

# Security Commands

## GrantAccessEntitlement
Online + critical policy.

## RevokeAccessEntitlement
Online + critical.

## RegisterVisitor
Online/local facility policy TBD.

## CreateSecurityIncident
Offline/local capture candidate.

## RemoteDoorAction
Integration-dependent + explicit high-security approval; not baseline.

---

# Approval Commands

## RequestApproval
Usually server/internal from subject module.

## ApproveRequest
Online.

## RejectRequest
Online.

## RequestApprovalChange
Online.

## DelegateApproval
Online + policy.

## CancelApproval
Online.

---

# Command Result Categories

- ACCEPTED
- REJECTED_VALIDATION
- REJECTED_PERMISSION
- REJECTED_STATE
- VERSION_CONFLICT
- DUPLICATE_REPLAY
- INTEGRATION_PENDING
- INTEGRATION_UNKNOWN
- FAILED_RETRYABLE
- FAILED_PERMANENT
- REAUTH_REQUIRED

Do not collapse into generic 400/500 in product layer.

---

# Command Invariants

1. Every critical command has one owning module.
2. Server re-checks permission/state.
3. Retriable commands use operationId/idempotency.
4. Concurrency-sensitive commands carry baseVersion.
5. Offline command timestamp does not override authoritative order automatically.
6. Critical external commands preserve UNKNOWN outcome.
7. Successful command emits audit/event as required.
8. Command payload never contains role/permission claim trusted from client.

## Next
Map commands to:
- HTTP/API conventions,
- exact request/response schemas,
- tests,
- feature IDs,
after reality/stack freeze.
