# Transition Table — Advance & Financial Imprest

Status: **STRONG FIRST PASS / NOT REALITY-FROZEN**

## Employee Advance

| From | Command | Preconditions | Authority | To | Event | Offline |
|---|---|---|---|---|---|---|
| DRAFT | RequestAdvance | valid employee + amount/purpose | employee/admin policy | SUBMITTED | advance.requested | queued candidate |
| SUBMITTED | ApproveAdvance | exact version + approval policy | approver | APPROVED | advance.approved | no |
| SUBMITTED | RejectAdvance | exact version | approver | REJECTED | advance.rejected | no |
| APPROVED | IssueAdvance | approved version + funding succeeds/authoritative ref | finance | ISSUED | advance.issued | no |
| ISSUED | RecordAdvanceSettlement | valid settlement entry | finance | PARTIALLY_SETTLED or SETTLED | advance.settlement_recorded | capture may queue; final online |
| PARTIALLY_SETTLED | RecordAdvanceSettlement | remaining balance > 0 | finance | PARTIALLY_SETTLED or SETTLED | advance.settlement_recorded | capture may queue; final online |
| ISSUED/PARTIALLY_SETTLED | ApplyAdvancePayrollDeduction | approved payroll input/plan | payroll | same financial state until reconciled | advance.payroll_deduction_applied | no |
| SETTLED | CloseAdvance | outstanding = 0 or explicit approved exception | finance | CLOSED | advance.closed | no |

## Financial Imprest

| From | Command | Preconditions | Authority | To | Event | Offline |
|---|---|---|---|---|---|---|
| DRAFT | RequestFinancialImprest | custodian + purpose + amount | employee/admin | REQUESTED | imprest.requested | queued candidate |
| REQUESTED | ApproveFinancialImprest | exact version/policy | approver | APPROVED | imprest.approved | no |
| APPROVED | FundFinancialImprest | authoritative funding reference | finance | FUNDED/ACTIVE | imprest.funded | no |
| ACTIVE | RecordImprestSpend | valid amount/context | custodian | ACTIVE | imprest.spend_recorded | yes, authoritative review later |
| ACTIVE | RequestImprestReplenishment | policy + current ledger/balance | custodian/finance | REPLENISHMENT_PENDING | imprest.replenishment_requested | no |
| REPLENISHMENT_PENDING | ApproveImprestReplenishment | exact version | approver | REPLENISHMENT_PENDING | imprest.replenishment_approved | no |
| REPLENISHMENT_PENDING | FundImprestReplenishment | funding succeeds | finance | ACTIVE | imprest.replenished | no |
| ACTIVE | SubmitImprestSettlement | exact ledger set/version + required evidence | custodian | SETTLEMENT_SUBMITTED | imprest.settlement_submitted | queued candidate |
| SETTLEMENT_SUBMITTED | ReviewImprestSettlement | finance review | finance | UNDER_REVIEW/CLEARED/CHANGES_REQUIRED/SHORTAGE_REVIEW/OVERAGE_REVIEW | imprest.settlement_reviewed | no |
| CHANGES_REQUIRED | RecordImprestSpend / correction entries | explicit correction/reversal | custodian/finance policy | ACTIVE | imprest.settlement_changed | capture conditional |
| SHORTAGE_REVIEW | ResolveImprestShortage | explicit authorized resolution | finance/approver | CLEARED or remains review | imprest.shortage_resolved | no |
| OVERAGE_REVIEW | ResolveImprestOverage | explicit return/correction | finance | CLEARED or remains review | imprest.overage_resolved | no |
| CLEARED | CloseFinancialImprest | zero unresolved custody balance | finance | CLOSED | imprest.closed | no |

## Safety Rules

1. Approval binds exact object version.
2. Funding is authoritative only after payment/external result is known.
3. Ledger entries are append-only after acceptance.
4. Offline spend capture never overwrites authoritative finance review.
5. Payroll deduction is never an implicit FinancialImprest transition.
6. Shortage/overage cannot be hidden by editing a balance.
7. Closing requires reconciled obligations or explicit approved exception.
