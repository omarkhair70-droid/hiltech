package com.hiltech.shared.core.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * Source-authorized Finance PREPARE + REQUEST REVIEW only. Neither operation
 * independently approves or posts a bank, GL, cashbox or customer action.
 * All button handlers are gated by a separate default-OFF native host flag.
 */
enum class FinanceReviewKind(val label: String) {
    BANK_STATEMENT_REVIEW("كشف بنك قائم"),
    BANK_RECONCILIATION("مطابقة البنك"),
    CASH_ALLOCATION("تخصيص AP / AR"),
    CASH_ALLOCATION_CORRECTION("تصحيح تخصيص"),
    CASH_COUNT("جرد الخزنة"),
    CASH_VOUCHER("تجهيز سند قبض أو صرف"),
    INVOICE_ISSUANCE_REVIEW("مراجعة فاتورة موجودة"),
    INVOICE_ISSUANCE_PREPARE("تجهيز إصدار فاتورة عميل"),
    EMPLOYEE_FUNDING("تجهيز سلفة أو عهدة"),
    EMPLOYEE_SETTLEMENT("تجهيز تسوية موظف"),
    EXPENSE_REIMBURSEMENT("تجهيز رد مصروف"),
    AP_JOURNAL("تجهيز قيد مورد"),
    AR_JOURNAL("تجهيز قيد عميل"),
    PERIOD_CLOSE("طلب مراجعة إقفال فترة"),
}

data class FinanceReviewJournalLine(val accountId:String="",val side:String="DEBIT",val amount:String="")

data class FinanceReviewDraft(
    val sourceId: String = "",
    val bankLineId: String = "",
    val sourceKind: String = "AR",
    val amount: String = "",
    val reference: String = "",
    val documentEvidenceId: String = "",
    val cashboxId: String = "",
    val voucherReference: String = "",
    val policyApprovalId: String = "",
    val fundingKind: String = "EMPLOYEE_ADVANCE",
    val settlementAction: String = "ADVANCE_RECOVERY_CONFIRMED",
    val evidenceSha256: String = "",
    val periodId: String = "",
    val taxDisposition: String = "",
    val lines: List<FinanceReviewJournalLine> = listOf(FinanceReviewJournalLine(),FinanceReviewJournalLine(side="CREDIT")),
)

data class FinancePreparedReview(
    val kind: FinanceReviewKind,
    val id: String,
    val organizationId: String,
    val state: String,
    val approvalRequestId: String? = null,
    val details: List<String> = emptyList(),
)

data class FinanceReviewAttempt(
    val kind: FinanceReviewKind,
    val draft: FinanceReviewDraft,
    val operationId: String,
)

fun financeReviewIsAmount(value: String, allowZero: Boolean): Boolean {
    if (!Regex("^[0-9]+(\\.[0-9]{1,6})?$").matches(value)) return false
    return allowZero || value.any { it in '1'..'9' }
}

fun financeReviewDraftIsValid(
    kind: FinanceReviewKind, draft: FinanceReviewDraft,
): Boolean {
    if (!financeLookupIdIsUuid(draft.sourceId)) return false
    return when (kind) {
        FinanceReviewKind.BANK_STATEMENT_REVIEW,
        FinanceReviewKind.INVOICE_ISSUANCE_REVIEW, FinanceReviewKind.PERIOD_CLOSE -> true
        FinanceReviewKind.EMPLOYEE_FUNDING ->
            draft.fundingKind in setOf("EMPLOYEE_ADVANCE","FINANCIAL_IMPREST") &&
                financeLookupIdIsUuid(draft.policyApprovalId) && financeLookupIdIsUuid(draft.bankLineId)
        FinanceReviewKind.EMPLOYEE_SETTLEMENT ->
            draft.settlementAction in setOf("ADVANCE_RECOVERY_CONFIRMED","IMPREST_CASH_RETURN_CONFIRMED","IMPREST_RECEIPTS_ACCEPTED") &&
                financeReviewIsAmount(draft.amount,false) && financeLookupIdIsUuid(draft.documentEvidenceId) &&
                draft.evidenceSha256.matches(Regex("^[a-f0-9]{64}$")) &&
                (if (draft.settlementAction=="IMPREST_RECEIPTS_ACCEPTED") draft.bankLineId.isBlank()
                 else financeLookupIdIsUuid(draft.bankLineId))
        FinanceReviewKind.EXPENSE_REIMBURSEMENT ->
            financeLookupIdIsUuid(draft.policyApprovalId) && financeLookupIdIsUuid(draft.bankLineId) &&
                financeReviewIsAmount(draft.amount,false)
        FinanceReviewKind.AP_JOURNAL, FinanceReviewKind.AR_JOURNAL ->
            financeLookupIdIsUuid(draft.periodId) && draft.reference.isNotBlank() &&
                draft.taxDisposition.isNotBlank() &&
                (draft.documentEvidenceId.isBlank() || financeLookupIdIsUuid(draft.documentEvidenceId)) &&
                draft.lines.size in 2..32 && draft.lines.all {
                    financeLookupIdIsUuid(it.accountId) && it.side in setOf("DEBIT","CREDIT") &&
                        financeReviewIsAmount(it.amount,false)
                } && draft.lines.any {it.side=="DEBIT"} && draft.lines.any {it.side=="CREDIT"}
        FinanceReviewKind.INVOICE_ISSUANCE_PREPARE ->
            draft.reference.trim().length in 3..100 &&
                financeLookupIdIsUuid(draft.documentEvidenceId)
        FinanceReviewKind.BANK_RECONCILIATION ->
            draft.reference.trim().length in 8..180
        FinanceReviewKind.CASH_ALLOCATION ->
            draft.sourceKind in setOf("AP", "AR") &&
                financeLookupIdIsUuid(draft.bankLineId) &&
                financeReviewIsAmount(draft.amount, allowZero = false) &&
                draft.reference.trim().length in 8..180
        FinanceReviewKind.CASH_ALLOCATION_CORRECTION ->
            draft.reference.trim().length in 8..180
        FinanceReviewKind.CASH_VOUCHER ->
            draft.sourceKind in setOf("AP","AR") && financeLookupIdIsUuid(draft.cashboxId) &&
                financeReviewIsAmount(draft.amount,false) && draft.reference.trim().length in 8..180 &&
                draft.voucherReference.trim().length in 3..100
        FinanceReviewKind.CASH_COUNT ->
            financeReviewIsAmount(draft.amount, allowZero = true) &&
                draft.reference.trim().length in 8..180
    }
}

fun financeReviewCanRequest(state: String, approvalRequestId: String?): Boolean =
    state == "PROPOSED" && approvalRequestId.isNullOrBlank()

fun financeReviewCanRequest(kind:FinanceReviewKind,state:String,approvalRequestId:String?):Boolean =
    approvalRequestId.isNullOrBlank() && state == when(kind) {
        FinanceReviewKind.AP_JOURNAL -> "SEALED_AWAITING_POSTING_REVIEW"
        FinanceReviewKind.AR_JOURNAL -> "SEALED_AWAITING_APPROVAL"
        FinanceReviewKind.PERIOD_CLOSE -> "NOT_REQUESTED"
        else -> "PROPOSED"
    }

private fun FinanceEmployeeFundingReceiptDto.review() = FinancePreparedReview(
    FinanceReviewKind.EMPLOYEE_FUNDING,proposalId,organizationId,state,approvalRequestId,
    listOf("الموظف: $employeeId","المبلغ: $amount $currency","النوع: $kind","حركة البنك: $bankStatementLineId"))
private fun FinanceEmployeeSettlementReceiptDto.review() = FinancePreparedReview(
    FinanceReviewKind.EMPLOYEE_SETTLEMENT,proposalId,organizationId,state,approvalRequestId,
    listOf("الموظف: $employeeId","المبلغ: $amount $currency","نوع التسوية: $action","التمويل: $fundingRecordId"))
private fun FinanceExpenseReimbursementReceiptDto.review() = FinancePreparedReview(
    FinanceReviewKind.EXPENSE_REIMBURSEMENT,proposalId,organizationId,state,approvalRequestId,
    listOf("الموظف: $employeeId","المبلغ: $amount $currency","المصروف: $expenseDraftId"))
private fun FinanceApPostingReceiptDto.review(org:String) = FinancePreparedReview(
    FinanceReviewKind.AP_JOURNAL,proposalId,org,state,postingReviewRequestId,
    listOf("مصدر المورد: $payableCandidateId","المبلغ: $invoiceAmount $currency","بصمة القيد: $ledgerFingerprintSha256"))
private fun FinanceArJournalReceiptDto.review() = FinancePreparedReview(
    FinanceReviewKind.AR_JOURNAL,proposalId,organizationId,state,approvalRequestId,
    listOf("الفاتورة: $issuedInvoiceId","المبلغ: $amount $currency","بصمة القيد: $ledgerFingerprintSha256"))
private fun FinanceGlPeriodCloseReceiptDto.review() = FinancePreparedReview(
    FinanceReviewKind.PERIOD_CLOSE,periodId,organizationId,state,approvalRequestId,
    listOf("عدد القيود: $journalCount","بصمة الدفتر: $ledgerSnapshotSha256"))

private fun requireScopedReview(
    org: String, requestedId: String, prepared: FinancePreparedReview,
): FinancePreparedReview {
    check(financeReadSourceMatches(org, prepared.organizationId, requestedId, prepared.id)) {
        "FINANCE_REVIEW_SOURCE_SCOPE_MISMATCH"
    }
    return prepared
}

private fun FinanceBankIntakeReceiptDto.review() =
    FinancePreparedReview(
        FinanceReviewKind.BANK_STATEMENT_REVIEW, proposalId, organizationId,
        state, approvalRequestId,
        listOf(
            "عدد حركات الكشف: $lineCount",
            "مسجل داخليًا: $internalBankStatementRecorded",
            "مطابقة مؤكدة: $bankReconciled",
        ),
    )

private fun FinanceBankReconciliationReceiptDto.review() =
    FinancePreparedReview(
        FinanceReviewKind.BANK_RECONCILIATION, proposalId, organizationId,
        state, approvalRequestId,
        listOf("عدد الحركات: $lineCount", "إجمالي المطابق: $totalMatched $currency",
            "مطابقة داخلية معتمدة: $internallyReconciled",
            "بنك مؤكد: $verifiedByBankProvider"),
    )

private fun FinanceCashAllocationReceiptDto.review() =
    FinancePreparedReview(
        FinanceReviewKind.CASH_ALLOCATION, proposalId, organizationId,
        state, approvalRequestId,
        listOf("مصدر القيد: $sourceKind", "المبلغ: $amount $currency",
            "تخصيص مسجل: $cashAllocatedToAccountingSource",
            "تحويل فعلي: $moneyTransferredByHiltech"),
    )

private fun FinanceCashVoidReceiptDto.review() =
    FinancePreparedReview(
        FinanceReviewKind.CASH_ALLOCATION_CORRECTION, proposalId,
        organizationId, state, approvalRequestId,
        listOf("المبلغ: $amount $currency",
            "السجل الأصلي محفوظ: $originalRecordPreserved",
            "قيد معكوس في دفتر الأستاذ: $generalLedgerReversed"),
    )

private fun FinanceCashVoucherReceiptDto.review() = FinancePreparedReview(
    FinanceReviewKind.CASH_VOUCHER,proposalId,organizationId,state,approvalRequestId,
    listOf("الخزنة: $cashboxId","النوع: $direction","المبلغ: $amount $currency","القيد: $sourceJournalId"))

private fun FinanceCashCountReceiptDto.review() =
    FinancePreparedReview(
        FinanceReviewKind.CASH_COUNT, proposalId, organizationId,
        state, approvalRequestId,
        listOf("المعدود: $countedAmount $currency", "الدفتري: $ledgerAmount $currency",
            "الفرق: $varianceAmount $currency", "تعديل دفتر الأستاذ: $ledgerAdjusted"),
    )

private fun FinanceArInvoiceIssuanceReceiptDto.review(
    kind: FinanceReviewKind = FinanceReviewKind.INVOICE_ISSUANCE_REVIEW,
) =
    FinancePreparedReview(
        kind, proposalId,
        organizationId, state, approvalRequestId,
        listOf("رقم الفاتورة: $invoiceNumber",
            "تسجيل داخلي: $recordedInternally",
            "تم إرسالها للعميل: $invoiceSentExternally"),
    )

/**
 * When the source is an existing proposal, lookup by the original ID first.
 * The backend owns actor, org, state and approval policy; UI never manufactures a grant.
 */
suspend fun financeReadReviewProposal(
    apis: FinanceEvidenceReadApis,
    kind: FinanceReviewKind,
    org: String,
    proposalId: String,
    install: String,
): FinancePreparedReview {
    require(financeLookupIdIsUuid(proposalId))
    val receipt = when (kind) {
        FinanceReviewKind.EMPLOYEE_FUNDING -> apis.employeeMoney.fundingStatus(org,proposalId,install).review()
        FinanceReviewKind.EMPLOYEE_SETTLEMENT -> apis.employeeMoney.settlementStatus(org,proposalId,install).review()
        FinanceReviewKind.EXPENSE_REIMBURSEMENT -> apis.reimbursement.status(org,proposalId,install).review()
        FinanceReviewKind.AP_JOURNAL -> apis.apJournal.status(org,proposalId,install).review(org)
        FinanceReviewKind.AR_JOURNAL -> apis.arJournal.status(org,proposalId,install).review()
        FinanceReviewKind.PERIOD_CLOSE -> apis.periodClose.status(org,proposalId,install).review()
        FinanceReviewKind.BANK_STATEMENT_REVIEW ->
            apis.bankIntake.status(org, proposalId, install).review()
        FinanceReviewKind.BANK_RECONCILIATION ->
            apis.reconciliation.status(org, proposalId, install).review()
        FinanceReviewKind.CASH_ALLOCATION ->
            apis.allocation.status(org, proposalId, install).review()
        FinanceReviewKind.CASH_ALLOCATION_CORRECTION ->
            apis.allocationCorrection.status(org, proposalId, install).review()
        FinanceReviewKind.CASH_VOUCHER -> apis.cash.voucherStatus(org,proposalId,install).review()
        FinanceReviewKind.CASH_COUNT ->
            apis.cashCount.status(org, proposalId, install).review()
        FinanceReviewKind.INVOICE_ISSUANCE_REVIEW,
        FinanceReviewKind.INVOICE_ISSUANCE_PREPARE ->
            apis.arIssuance.status(org, proposalId, install).review(kind)
    }
    return requireScopedReview(org, proposalId, receipt)
}

/** One idempotency UUID per frozen form, preserved across explicit retries. */
suspend fun financePrepareReviewProposal(
    apis: FinanceEvidenceReadApis,
    org: String,
    install: String,
    attempt: FinanceReviewAttempt,
): FinancePreparedReview {
    require(financeLookupIdIsUuid(attempt.operationId))
    val (kind, form, operation) = attempt
    require(financeReviewDraftIsValid(kind, form)) { "Incomplete Finance source fields." }
    val receipt = when (kind) {
        FinanceReviewKind.BANK_STATEMENT_REVIEW,
        FinanceReviewKind.INVOICE_ISSUANCE_REVIEW, FinanceReviewKind.PERIOD_CLOSE ->
            return financeReadReviewProposal(apis, kind, org, form.sourceId, install)
        FinanceReviewKind.EMPLOYEE_FUNDING -> {
            val r=apis.employeeMoney.prepareFunding(org,form.fundingKind,form.sourceId,operation,
                form.policyApprovalId,form.bankLineId,install)
            check(r.draftId.equals(form.sourceId,true) && r.kind==form.fundingKind &&
                r.bankStatementLineId.equals(form.bankLineId,true) && r.fundingRecordId==null &&
                !r.fundingEvidenceRecorded && !r.bankTransferInitiated)
            r.review()
        }
        FinanceReviewKind.EMPLOYEE_SETTLEMENT -> {
            val r=apis.employeeMoney.prepareSettlement(org,form.sourceId,FinanceEmployeeSettlementPrepareDto(
                operation,form.settlementAction,form.amount,form.documentEvidenceId,form.evidenceSha256,
                form.bankLineId.ifBlank {null}),install)
            check(r.fundingRecordId.equals(form.sourceId,true) && r.action==form.settlementAction &&
                r.settlementRecordId==null && !r.settlementRecorded && !r.bankTransferInitiated && !r.payrollDeductionInitiated)
            r.review()
        }
        FinanceReviewKind.EXPENSE_REIMBURSEMENT -> {
            val r=apis.reimbursement.prepare(org,form.sourceId,FinanceExpenseReimbursementPrepareDto(
                operation,form.policyApprovalId,form.bankLineId,form.amount),install)
            check(r.expenseDraftId.equals(form.sourceId,true) && r.bankStatementLineId.equals(form.bankLineId,true) &&
                r.reimbursementRecordId==null && !r.bankTransferInitiated && !r.bankDebitAllocatedToEmployeeExpense)
            r.review()
        }
        FinanceReviewKind.AP_JOURNAL -> {
            val r=apis.apJournal.propose(org,form.sourceId,FinanceApJournalProposalDto(operation,form.periodId,
                form.reference.trim(),form.taxDisposition.trim(),form.documentEvidenceId.ifBlank {null},
                form.lines.map {FinanceApJournalAllocationDto(it.accountId,it.side,it.amount)}),install)
            check(r.payableCandidateId.equals(form.sourceId,true) && r.postedJournalId==null &&
                !r.recognizedAccountsPayable && !r.paymentApproved && !r.moneyTransferred)
            r.review(org)
        }
        FinanceReviewKind.AR_JOURNAL -> {
            val r=apis.arJournal.propose(org,form.sourceId,FinanceArJournalProposalDto(operation,form.periodId,
                form.reference.trim(),form.taxDisposition.trim(),form.documentEvidenceId.ifBlank {null},
                form.lines.map {FinanceArJournalAllocationDto(it.accountId,it.side,it.amount)}),install)
            check(r.issuedInvoiceId.equals(form.sourceId,true) && r.postedJournalId==null &&
                !r.receivableRecognized && !r.cashCollected)
            r.review()
        }
        FinanceReviewKind.INVOICE_ISSUANCE_PREPARE -> {
            val prepared = apis.arIssuance.prepare(org, form.sourceId, operation,
                form.reference.trim(), form.documentEvidenceId, install)
            check(prepared.candidateId.equals(form.sourceId, ignoreCase = true) &&
                prepared.invoiceNumber == form.reference.trim() &&
                prepared.state == "PROPOSED" && prepared.approvalRequestId == null &&
                prepared.issuedInvoiceId == null && !prepared.recordedInternally &&
                !prepared.invoiceSentExternally && !prepared.receivableRecognized &&
                !prepared.paymentCollected) { "FINANCE_INVOICE_SOURCE_MISMATCH" }
            prepared.review(kind)
        }
        FinanceReviewKind.BANK_RECONCILIATION -> apis.reconciliation.prepare(
            org, FinanceBankReconciliationPrepareDto(
                operation, form.sourceId, form.reference.trim(),
            ), install,
        ).review()
        FinanceReviewKind.CASH_ALLOCATION -> apis.allocation.propose(
            org, form.sourceKind, form.sourceId, form.bankLineId,
            operation, form.amount, form.reference.trim(), install,
        ).review()
        FinanceReviewKind.CASH_ALLOCATION_CORRECTION ->
            apis.allocationCorrection.prepare(
                org, form.sourceId,
                FinanceCashVoidPrepareDto(operation, form.reference.trim()),
                install,
            ).review()
        FinanceReviewKind.CASH_VOUCHER -> {
            val box=apis.cash.balance(org,form.cashboxId,install)
            check(financeReadSourceMatches(org,box.organizationId,form.cashboxId,box.cashboxId))
            val result=apis.cash.prepareVoucher(org,form.sourceKind,form.sourceId,
                FinanceCashVoucherPrepareDto(operation,form.cashboxId,form.amount,
                    form.voucherReference.trim(),form.reference.trim()),install)
            check(result.cashboxId.equals(form.cashboxId,true) &&
                result.sourceJournalId.equals(form.sourceId,true) && result.sourceKind==form.sourceKind &&
                result.currency==box.currency && result.voucherRecordId==null &&
                !result.physicalCustodyConfirmed && !result.bankTransactionInitiated)
            result.review()
        }
        FinanceReviewKind.CASH_COUNT -> {
            // An original read of the cashbox is required before preparing
            // a witnessed observation; no invented ledger amount.
            val box = apis.cash.balance(org, form.sourceId, install)
            check(financeReadSourceMatches(org, box.organizationId, form.sourceId, box.cashboxId)) {
                "FINANCE_CASHBOX_SOURCE_SCOPE_MISMATCH"
            }
            apis.cashCount.prepare(
                org, FinanceCashCountPrepareDto(
                    operation, form.sourceId, form.amount, form.reference.trim(),
                ), install,
            ).review()
        }
    }
    check(receipt.organizationId.equals(org, ignoreCase = true) &&
        financeLookupIdIsUuid(receipt.id)) { "FINANCE_REVIEW_SOURCE_SCOPE_MISMATCH" }
    check(financeReviewCanRequest(kind,receipt.state,receipt.approvalRequestId)) {
        "FINANCE_PREPARE_RECEIPT_NOT_READY"
    }
    return receipt
}

/**
 * Deliberately only creates an independent *review request*. The initial
 * original GET is repeated immediately before the mutation; server re-checks
 * permission and proposal version/authority independently.
 */
suspend fun financeRequestIndependentReview(
    apis: FinanceEvidenceReadApis,
    org: String,
    install: String,
    kind: FinanceReviewKind,
    proposalId: String,
    operationId: String,
): FinancePreparedReview {
    require(financeLookupIdIsUuid(operationId))
    val current = financeReadReviewProposal(apis, kind, org, proposalId, install)
    check(financeReviewCanRequest(kind,current.state, current.approvalRequestId)) {
        "FINANCE_REVIEW_NOT_PROPOSED"
    }
    val response = when (kind) {
        FinanceReviewKind.EMPLOYEE_FUNDING -> apis.employeeMoney.requestFundingReview(org,proposalId,operationId,install).review()
        FinanceReviewKind.EMPLOYEE_SETTLEMENT -> apis.employeeMoney.requestSettlementReview(org,proposalId,operationId,install).review()
        FinanceReviewKind.EXPENSE_REIMBURSEMENT -> apis.reimbursement.requestReview(org,proposalId,operationId,install).review()
        FinanceReviewKind.AP_JOURNAL -> apis.apJournal.requestPostingApproval(org,proposalId,operationId,install).review(org)
        FinanceReviewKind.AR_JOURNAL -> apis.arJournal.requestApproval(org,proposalId,operationId,install).review()
        FinanceReviewKind.PERIOD_CLOSE -> apis.periodClose.requestReview(org,proposalId,operationId,install).review()
        FinanceReviewKind.BANK_STATEMENT_REVIEW ->
            apis.bankIntake.requestReview(org, proposalId, operationId, install).review()
        FinanceReviewKind.BANK_RECONCILIATION ->
            apis.reconciliation.requestReview(org, proposalId, operationId, install).review()
        FinanceReviewKind.CASH_ALLOCATION ->
            apis.allocation.requestApproval(org, proposalId, operationId, install).review()
        FinanceReviewKind.CASH_ALLOCATION_CORRECTION ->
            apis.allocationCorrection.requestReview(org, proposalId, operationId, install).review()
        FinanceReviewKind.CASH_VOUCHER -> apis.cash.reviewVoucher(org,proposalId,operationId,install).review()
        FinanceReviewKind.CASH_COUNT ->
            apis.cashCount.requestReview(org, proposalId, operationId, install).review()
        FinanceReviewKind.INVOICE_ISSUANCE_REVIEW,
        FinanceReviewKind.INVOICE_ISSUANCE_PREPARE ->
            apis.arIssuance.requestReview(org, proposalId, operationId, install).review(kind)
    }
    return requireScopedReview(org, proposalId, response)
}

@Composable
fun FinanceReviewPreparationSection(
    identityId: String,
    organizationId: String,
    installationId: String,
    apis: FinanceEvidenceReadApis,
    newOperationId: () -> String,
    handoff: FinanceReviewHandoff? = null,
    onHandoffConsumed: () -> Unit = {},
) {
    // A keyed state means an account/company/installation switch removes any
    // outstanding request, source evidence, error and in-memory draft.
    var expanded by remember(identityId, organizationId, installationId) {
        mutableStateOf(false)
    }
    var kind by remember(identityId, organizationId, installationId) {
        mutableStateOf(FinanceReviewKind.BANK_RECONCILIATION)
    }
    val scope = rememberCoroutineScope()
    var accountingChoices by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf<FinanceAccountingChoicesDto?>(null)
    }
    var accountingChoicesLoading by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf(false)
    }
    var accountingChoicesError by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf<String?>(null)
    }
    var accountSearch by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf("")
    }
    var selectedCandidate by remember(identityId, organizationId, installationId) { mutableStateOf<String?>(null) }
    var draft by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf(FinanceReviewDraft())
    }
    var attempt by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf<FinanceReviewAttempt?>(null)
    }
    var runRevision by remember(identityId, organizationId, installationId, kind) {
        mutableIntStateOf(0)
    }
    var preparing by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf(false)
    }
    var prepared by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf<FinancePreparedReview?>(null)
    }
    var error by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf<String?>(null)
    }
    var reviewOperationId by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf<String?>(null)
    }
    var reviewRevision by remember(identityId, organizationId, installationId, kind) {
        mutableIntStateOf(0)
    }
    var requestingReview by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf(false)
    }
    var refreshRevision by remember(identityId, organizationId, installationId, kind) {
        mutableIntStateOf(0)
    }
    var refreshing by remember(identityId, organizationId, installationId, kind) {
        mutableStateOf(false)
    }

    // Explicit refresh reconciles an uncertain timeout without repeating POST.
    LaunchedEffect(identityId, organizationId, installationId, kind, refreshRevision) {
        if (refreshRevision == 0) return@LaunchedEffect
        val current = prepared ?: return@LaunchedEffect
        refreshing = true
        error = null
        try {
            prepared = financeReadReviewProposal(
                apis, kind, organizationId, current.id, installationId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "تعذر تحديث حالة هذا الطلب من المصدر. لم نفترض أنه تم اعتماده."
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(identityId, organizationId, installationId, kind, attempt, runRevision) {
        val frozen = attempt ?: return@LaunchedEffect
        preparing = true
        error = null
        try {
            prepared = financePrepareReviewProposal(
                apis, organizationId, installationId, frozen,
            )
            reviewOperationId = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "تعذر تأكيد طلب Finance. تحقق من صلاحيتك والمصدر، ثم أعد المحاولة بنفس رقم العملية."
        } finally {
            preparing = false
        }
    }

    LaunchedEffect(identityId, organizationId, installationId, kind,
        reviewOperationId, reviewRevision
    ) {
        val reviewOp = reviewOperationId ?: return@LaunchedEffect
        val current = prepared ?: return@LaunchedEffect
        requestingReview = true
        error = null
        try {
            prepared = financeRequestIndependentReview(
                apis, organizationId, installationId,
                kind, current.id, reviewOp,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "طلب المراجعة لم يُؤكَّد؛ راجع الحالة من المصدر قبل إعادة المحاولة."
        } finally {
            requestingReview = false
        }
    }

    fun resetForFormChange() {
        attempt = null
        reviewOperationId = null
        prepared = null
        error = null
    }

    LaunchedEffect(handoff,kind,preparing,requestingReview,refreshing) {
        val next=handoff ?: return@LaunchedEffect
        if(preparing || requestingReview || refreshing)return@LaunchedEffect
        if(!financeLookupIdIsUuid(next.sourceId)) {onHandoffConsumed();return@LaunchedEffect}
        expanded=true
        if(kind!=next.kind) {kind=next.kind;return@LaunchedEffect}
        draft=FinanceReviewDraft(sourceId=next.sourceId,sourceKind=next.sourceKind)
        resetForFormChange()
        onHandoffConsumed()
    }

    LaunchedEffect(kind,selectedCandidate) {
        val candidate=selectedCandidate ?: return@LaunchedEffect
        if(kind==FinanceReviewKind.INVOICE_ISSUANCE_PREPARE) {
            draft=FinanceReviewDraft(sourceId=candidate)
            resetForFormChange()
            selectedCandidate=null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "إخفاء تجهيز المراجعات" else "تجهيز طلب مراجعة مالية")
        }
        if (!expanded) return@Column

        Text(
            "هذه ليست موافقة ولا ترحيل ولا دفع. إعداد الطلب وطلب مراجعته يظلان منفصلين؛ " +
                "لن يُسجل نقد أو يُرسل عميل أو يُخصم راتب من هنا.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!preparing && !requestingReview && !refreshing && prepared==null) {
            FinanceInvoiceCandidateSection(identityId,organizationId,installationId,apis,newOperationId) { candidate ->
                selectedCandidate=candidate
                kind=FinanceReviewKind.INVOICE_ISSUANCE_PREPARE
            }
        }
        val groups=listOf(
            "الفواتير والقيود" to listOf(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,
                FinanceReviewKind.INVOICE_ISSUANCE_REVIEW,FinanceReviewKind.AP_JOURNAL,FinanceReviewKind.AR_JOURNAL),
            "البنك والخزنة" to listOf(FinanceReviewKind.BANK_STATEMENT_REVIEW,FinanceReviewKind.BANK_RECONCILIATION,
                FinanceReviewKind.CASH_ALLOCATION,FinanceReviewKind.CASH_ALLOCATION_CORRECTION,
                FinanceReviewKind.CASH_VOUCHER,FinanceReviewKind.CASH_COUNT),
            "الموظفون والإقفال" to listOf(FinanceReviewKind.EMPLOYEE_FUNDING,
                FinanceReviewKind.EMPLOYEE_SETTLEMENT,FinanceReviewKind.EXPENSE_REIMBURSEMENT,FinanceReviewKind.PERIOD_CLOSE),
        )
        groups.forEach { (label,options) ->
            Text(label,style=MaterialTheme.typography.labelMedium)
            Row(modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    OutlinedButton(enabled=!preparing && !requestingReview && !refreshing && option!=kind,
                        onClick={kind=option}) {Text(option.label)}
                }
            }
        }
        Text("1. المصدر   ←   2. تجهيز المقترح   ←   3. طلب مراجعة مستقلة",
            style = MaterialTheme.typography.labelMedium)
        Text(kind.label, style = MaterialTheme.typography.titleSmall)
        val existing = kind == FinanceReviewKind.BANK_STATEMENT_REVIEW ||
            kind == FinanceReviewKind.INVOICE_ISSUANCE_REVIEW || kind == FinanceReviewKind.PERIOD_CLOSE
        val journal = kind == FinanceReviewKind.AP_JOURNAL || kind == FinanceReviewKind.AR_JOURNAL
        val employee = kind in setOf(FinanceReviewKind.EMPLOYEE_FUNDING,FinanceReviewKind.EMPLOYEE_SETTLEMENT,FinanceReviewKind.EXPENSE_REIMBURSEMENT)
        val editable = !preparing && !requestingReview && !refreshing && prepared == null
        val sourceLabel = when (kind) {
            FinanceReviewKind.BANK_STATEMENT_REVIEW -> "معرّف طلب كشف البنك (Proposal UUID)"
            FinanceReviewKind.BANK_RECONCILIATION -> "معرّف كشف البنك المسجل (Intake UUID)"
            FinanceReviewKind.CASH_ALLOCATION -> "معرّف قيد AP/AR المرحّل (Journal UUID)"
            FinanceReviewKind.CASH_ALLOCATION_CORRECTION -> "معرّف التخصيص الأصلي (Allocation UUID)"
            FinanceReviewKind.CASH_COUNT -> "معرّف الخزنة الحالي (Cashbox UUID)"
            FinanceReviewKind.CASH_VOUCHER -> "معرّف القيد المحاسبي المرحّل"
            FinanceReviewKind.INVOICE_ISSUANCE_REVIEW -> "معرّف طلب إصدار فاتورة قائم (Proposal UUID)"
            FinanceReviewKind.INVOICE_ISSUANCE_PREPARE -> "معرّف مسودة فاتورة العميل (Candidate UUID)"
            FinanceReviewKind.EMPLOYEE_FUNDING -> "معرّف مسودة السلفة أو العهدة"
            FinanceReviewKind.EMPLOYEE_SETTLEMENT -> "معرّف التمويل المسجل"
            FinanceReviewKind.EXPENSE_REIMBURSEMENT -> "معرّف مسودة المصروف"
            FinanceReviewKind.AP_JOURNAL -> "معرّف مرشح حساب المورد"
            FinanceReviewKind.AR_JOURNAL -> "معرّف فاتورة العميل الصادرة"
            FinanceReviewKind.PERIOD_CLOSE -> "معرّف الفترة المحاسبية"
        }
        OutlinedTextField(
            value = draft.sourceId,
            onValueChange = {
                draft = draft.copy(sourceId = it.trim())
                resetForFormChange()
            },
            label = { Text(sourceLabel) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            enabled = !preparing && !requestingReview && prepared == null,
        )
        if (kind == FinanceReviewKind.EMPLOYEE_FUNDING) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("EMPLOYEE_ADVANCE" to "سلفة","FINANCIAL_IMPREST" to "عهدة").forEach { (value,label) ->
                    OutlinedButton(enabled=editable && draft.fundingKind!=value,onClick={
                        draft=draft.copy(fundingKind=value); resetForFormChange()
                    }) {Text(label)}
                }
            }
        }
        if (kind == FinanceReviewKind.EMPLOYEE_SETTLEMENT) {
            Column {
                listOf("ADVANCE_RECOVERY_CONFIRMED" to "استرداد سلفة","IMPREST_CASH_RETURN_CONFIRMED" to "رد نقد العهدة",
                    "IMPREST_RECEIPTS_ACCEPTED" to "قبول مستندات العهدة").forEach { (value,label) ->
                    OutlinedButton(enabled=editable && draft.settlementAction!=value,onClick={
                        draft=draft.copy(settlementAction=value,bankLineId=""); resetForFormChange()
                    }) {Text(label)}
                }
            }
            OutlinedTextField(value=draft.evidenceSha256,onValueChange={
                draft=draft.copy(evidenceSha256=it.trim());resetForFormChange()
            },label={Text("بصمة مستند التسوية SHA-256")},enabled=editable,modifier=Modifier.fillMaxWidth())
        }
        if (kind == FinanceReviewKind.EMPLOYEE_FUNDING || kind == FinanceReviewKind.EXPENSE_REIMBURSEMENT) {
            OutlinedTextField(value=draft.policyApprovalId,onValueChange={
                draft=draft.copy(policyApprovalId=it.trim());resetForFormChange()
            },label={Text("معرّف الموافقة الأصلية")},enabled=editable,modifier=Modifier.fillMaxWidth())
        }
        if (employee && !(kind == FinanceReviewKind.EMPLOYEE_SETTLEMENT && draft.settlementAction=="IMPREST_RECEIPTS_ACCEPTED")) {
            OutlinedTextField(value=draft.bankLineId,onValueChange={
                draft=draft.copy(bankLineId=it.trim());resetForFormChange()
            },label={Text("معرّف حركة البنك المؤكدة")},enabled=editable,modifier=Modifier.fillMaxWidth())
        }
        if (journal) {
            val ledgerKind=if(kind==FinanceReviewKind.AP_JOURNAL) "AP" else "AR"
            OutlinedButton(enabled=editable && !accountingChoicesLoading,onClick={
                accountingChoicesLoading=true
                accountingChoicesError=null
                accountingChoices=null
                scope.launch {
                    try {
                        accountingChoices=financeReadAccountingChoices(
                            apis,organizationId,ledgerKind,installationId)
                    } catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception) {
                        accountingChoicesError="تعذر جلب حسابات الدفتر والفترات المتاحة لهذا الدور."
                    } finally {accountingChoicesLoading=false}
                }
            }) {
                Text(if(accountingChoicesLoading)"جارٍ تحميل الحسابات والفترات…" else "اختيار الحسابات والفترة من دفتر الشركة")
            }
            accountingChoicesError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            accountingChoices?.let { choices ->
                Text("الفترات المحاسبية المفتوحة",style=MaterialTheme.typography.titleSmall)
                if(choices.openPeriods.isEmpty())Text("لا توجد فترة مفتوحة متاحة لهذا الدور.")
                choices.openPeriods.take(8).forEach {period ->
                    OutlinedButton(enabled=editable && draft.periodId!=period.id,onClick={
                        draft=draft.copy(periodId=period.id);resetForFormChange()
                    }) {Text(period.dateStart+" — "+period.dateEnd)}
                }
                if(choices.hasMorePeriods || choices.openPeriods.size>8)
                    Text("تظهر أول 8 فترات. يمكن إدخال معرّف فترة أخرى معتمدة.")
                Text("حسابات دفتر الأستاذ المعتمدة",style=MaterialTheme.typography.titleSmall)
                OutlinedTextField(value=accountSearch,onValueChange={accountSearch=it},
                    label={Text("بحث بنوع الحساب أو العملة أو المعرّف")},
                    enabled=editable,modifier=Modifier.fillMaxWidth())
                if(choices.hasMoreAccounts)Text("هذه أول 100 حساب فقط من المصدر.")
            }
            OutlinedTextField(value=draft.periodId,onValueChange={
                draft=draft.copy(periodId=it.trim());resetForFormChange()
            },label={Text("الفترة المحاسبية")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.taxDisposition,onValueChange={
                draft=draft.copy(taxDisposition=it);resetForFormChange()
            },label={Text("المعالجة الضريبية المعتمدة")},enabled=editable,modifier=Modifier.fillMaxWidth())
            draft.lines.forEachIndexed { index,line ->
                Text("بند القيد ${index+1}",style=MaterialTheme.typography.titleSmall)
                fun changeLine(next:FinanceReviewJournalLine) {
                    draft=draft.copy(lines=draft.lines.mapIndexed { i,l -> if(i==index) next else l })
                    resetForFormChange()
                }
                OutlinedTextField(value=line.accountId,onValueChange={changeLine(line.copy(accountId=it.trim()))},
                    label={Text("معرّف حساب دفتر الأستاذ")},enabled=editable,modifier=Modifier.fillMaxWidth())
                accountingChoices?.let {choices ->
                    var accountMenuOpen by remember(identityId,organizationId,installationId,kind,index) {
                        mutableStateOf(false)
                    }
                    Box {
                        OutlinedButton(enabled=editable && choices.approvedAccounts.isNotEmpty(),
                            onClick={accountMenuOpen=true}) {Text("اختيار حساب معتمد")}
                        DropdownMenu(expanded=accountMenuOpen,onDismissRequest={accountMenuOpen=false}) {
                            val filtered=choices.approvedAccounts.filter {
                                accountSearch.isBlank() ||
                                    it.id.contains(accountSearch.trim(),true) ||
                                    it.accountKind.contains(accountSearch.trim(),true) ||
                                    (it.currency?.contains(accountSearch.trim(),true)==true)
                            }
                            filtered.take(12).forEach {account ->
                                DropdownMenuItem(
                                    text={Text(account.accountKind+" · "+(account.currency?:"كل العملات")+" · "+account.id.take(8))},
                                    onClick={
                                        changeLine(line.copy(accountId=account.id))
                                        accountMenuOpen=false
                                    },
                                )
                            }
                            if(filtered.isEmpty())DropdownMenuItem(text={Text("لا توجد نتائج مطابقة")},onClick={accountMenuOpen=false})
                            if(filtered.size>12)DropdownMenuItem(text={Text("ضيّق البحث لإظهار المزيد")},onClick={accountMenuOpen=false})
                        }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("DEBIT" to "مدين","CREDIT" to "دائن").forEach { (value,label) ->
                        OutlinedButton(enabled=editable && line.side!=value,onClick={changeLine(line.copy(side=value))}) {Text(label)}
                    }
                }
                OutlinedTextField(value=line.amount,onValueChange={changeLine(line.copy(amount=it.trim()))},
                    label={Text("المبلغ")},enabled=editable,modifier=Modifier.fillMaxWidth())
                if(draft.lines.size>2) OutlinedButton(enabled=editable,onClick={
                    draft=draft.copy(lines=draft.lines.filterIndexed { i,_ -> i!=index });resetForFormChange()
                }) {Text("حذف البند")}
            }
            OutlinedButton(enabled=editable && draft.lines.size<32,onClick={
                draft=draft.copy(lines=draft.lines+FinanceReviewJournalLine());resetForFormChange()
            }) {Text("إضافة بند")}
        }
        if (kind == FinanceReviewKind.CASH_VOUCHER) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("AR" to "قبض عميل","AP" to "صرف مورد").forEach { (value,label) ->
                    OutlinedButton(enabled=editable && draft.sourceKind!=value,onClick={
                        draft=draft.copy(sourceKind=value);resetForFormChange()
                    }) {Text(label)}
                }
            }
            OutlinedTextField(value=draft.cashboxId,onValueChange={
                draft=draft.copy(cashboxId=it.trim());resetForFormChange()
            },label={Text("معرّف الخزنة النشطة")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.voucherReference,onValueChange={
                draft=draft.copy(voucherReference=it);resetForFormChange()
            },label={Text("رقم السند")},enabled=editable,modifier=Modifier.fillMaxWidth())
        }
        if (kind == FinanceReviewKind.CASH_ALLOCATION) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AR", "AP").forEach { type ->
                    OutlinedButton(
                        enabled = !preparing && !requestingReview && prepared == null &&
                            draft.sourceKind != type,
                        onClick = { draft = draft.copy(sourceKind = type); resetForFormChange() },
                    ) { Text(type) }
                }
            }
            OutlinedTextField(
                value = draft.bankLineId,
                onValueChange = {
                    draft = draft.copy(bankLineId = it.trim()); resetForFormChange()
                },
                label = { Text("معرّف حركة البنك المؤكدة (Bank Line UUID)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !preparing && !requestingReview && prepared == null,
            )
        }
        if (kind == FinanceReviewKind.CASH_COUNT ||
            kind == FinanceReviewKind.CASH_ALLOCATION || kind == FinanceReviewKind.CASH_VOUCHER || kind == FinanceReviewKind.EMPLOYEE_SETTLEMENT ||
            kind == FinanceReviewKind.EXPENSE_REIMBURSEMENT
        ) {
            OutlinedTextField(
                value = draft.amount,
                onValueChange = {
                    draft = draft.copy(amount = it.trim()); resetForFormChange()
                },
                label = { Text(if (kind == FinanceReviewKind.CASH_COUNT)
                    "المبلغ المعدود فعليًا (حتى 6 كسور)" else "المبلغ المراد تخصيصه"
                ) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !preparing && !requestingReview && prepared == null,
            )
        }
        if (kind == FinanceReviewKind.INVOICE_ISSUANCE_PREPARE || kind == FinanceReviewKind.EMPLOYEE_SETTLEMENT || journal) {
            OutlinedTextField(
                value = draft.documentEvidenceId,
                onValueChange = { draft = draft.copy(documentEvidenceId = it.trim()); resetForFormChange() },
                label = { Text(if(journal) "معرّف المستند الضريبي (إن وجد)" else "معرّف المستند المحفوظ والجاهز (Evidence UUID)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !preparing && !requestingReview && prepared == null,
            )
        }
        if (!existing && !employee) {
            OutlinedTextField(
                value = draft.reference,
                onValueChange = {
                    draft = draft.copy(reference = it); resetForFormChange()
                },
                label = { Text(when (kind) {
                    FinanceReviewKind.BANK_RECONCILIATION -> "مرجع المطابقة المعتمد من الشركة"
                    FinanceReviewKind.INVOICE_ISSUANCE_PREPARE -> "رقم فاتورة الشركة"
                    FinanceReviewKind.AP_JOURNAL, FinanceReviewKind.AR_JOURNAL -> "مرجع المعالجة المحاسبية"
                    else -> "مرجع/سبب العملية"
                }) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !preparing && !requestingReview && prepared == null,
            )
        }
        val valid = financeReviewDraftIsValid(kind, draft)
        if (draft.sourceId.isNotEmpty() && !financeLookupIdIsUuid(draft.sourceId)) {
            Text("المعرّف يجب أن يكون UUID من المصدر، وليس اسمًا أو قيمة تخمينية.")
        }
        OutlinedButton(
            enabled = valid && !preparing && !requestingReview && prepared == null,
            onClick = {
                if (attempt == null || attempt?.draft != draft) {
                    attempt = FinanceReviewAttempt(kind, draft, newOperationId())
                }
                runRevision += 1
            },
        ) {
            Text(when {
                preparing -> "جارٍ التحقق من المصدر…"
                existing -> "قراءة الطلب القائم"
                else -> "تجهيز الطلب للمراجعة فقط"
            })
        }
        if (preparing) Text("جارٍ الاتصال بالمصدر مع حفظ رقم العملية لإعادة المحاولة…")
        error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            if (attempt != null && prepared == null && !preparing && !requestingReview) {
                OutlinedButton(onClick = { runRevision += 1 }) {
                    Text("إعادة محاولة تجهيز نفس العملية")
                }
            }
        }

        prepared?.let { current ->
            Text("الحالة المصدرية: " + current.state,
                style = MaterialTheme.typography.titleSmall)
            Text("طلب: " + current.id, style = MaterialTheme.typography.bodySmall)
            current.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("لم يُرحّل قيد أو يُنفذ صرف أو تحصيل بمجرد تجهيز الطلب.",
                style = MaterialTheme.typography.bodySmall)
            current.approvalRequestId?.let {
                Text("رقم طلب المراجعة المستقلة: " + it)
            }
            OutlinedButton(
                enabled = !preparing && !requestingReview && !refreshing,
                onClick = { refreshRevision += 1 },
            ) {
                Text(if (refreshing) "جارٍ تحديث الحالة…" else "تحديث حالة الطلب من المصدر")
            }
            if (!requestingReview && !refreshing &&
                financeReviewCanRequest(kind,current.state, current.approvalRequestId)
            ) {
                OutlinedButton(onClick = {
                    if (reviewOperationId == null) reviewOperationId = newOperationId()
                    reviewRevision += 1
                }) { Text("إرسال طلب مراجعة مستقلة — دون اعتماد") }
            }
            if (requestingReview) Text("جارٍ طلب المراجعة؛ لا يعني ذلك اعتمادًا ماليًا…")
            if (error != null && reviewOperationId != null && !requestingReview) {
                OutlinedButton(onClick = { reviewRevision += 1 }) {
                    Text("إعادة المحاولة بنفس عملية المراجعة")
                }
            }
            OutlinedButton(enabled = !preparing && !requestingReview && !refreshing, onClick = {
                draft = FinanceReviewDraft(sourceKind = draft.sourceKind)
                resetForFormChange()
            }) { Text("طلب جديد / إغلاق النتيجة الحالية") }
        }
    }
}


/** Signed recording is distinct from preparation, and always checks current source again. */
data class FinanceSignedSource(
    val kind:FinanceReviewKind,val id:String,val org:String,val state:String,
    val approvalId:String?,val recordId:String?,val facts:List<String>,
)

private fun FinanceCashVoucherReceiptDto.signedSource()=FinanceSignedSource(
    FinanceReviewKind.CASH_VOUCHER,proposalId,organizationId,state,approvalRequestId,voucherRecordId,
    listOf(cashboxId,sourceKind,sourceJournalId,direction,amount,currency))
private fun FinanceCashCountReceiptDto.signedSource()=FinanceSignedSource(
    FinanceReviewKind.CASH_COUNT,proposalId,organizationId,state,approvalRequestId,countRecordId,
    listOf(cashboxId,currency,countedAmount,ledgerAmount,varianceAmount,voucherCount.toString()))
private fun FinanceBankReconciliationReceiptDto.signedSource()=FinanceSignedSource(
    FinanceReviewKind.BANK_RECONCILIATION,proposalId,organizationId,state,approvalRequestId,reconciliationRecordId,
    listOf(intakeId,accountId,statementSha256,matchingSha256,currency,lineCount.toString(),totalCredit,totalDebit,totalMatched))

fun financeSignedReady(source:FinanceSignedSource,org:String):Boolean =
    source.kind in setOf(FinanceReviewKind.CASH_VOUCHER,FinanceReviewKind.CASH_COUNT,FinanceReviewKind.BANK_RECONCILIATION) &&
    source.org.equals(org,true) && financeLookupIdIsUuid(source.id) &&
    source.state=="APPROVED" && source.approvalId?.let(::financeLookupIdIsUuid)==true && source.recordId==null

suspend fun financeSignedQueue(apis:FinanceEvidenceReadApis,org:String,install:String,
    kind:FinanceReviewKind):List<FinanceSignedSource> {
    val items=when(kind) {
        FinanceReviewKind.CASH_VOUCHER -> apis.cash.operatorQueue(org,install).map {it.signedSource()}
        FinanceReviewKind.CASH_COUNT -> apis.cashCount.operatorQueue(org,install).map {it.signedSource()}
        FinanceReviewKind.BANK_RECONCILIATION -> apis.reconciliation.operatorQueue(org,install).map {it.signedSource()}
        else -> error("Unsupported signed operation")
    }
    check(items.all {it.kind==kind && financeSignedReady(it,org)})
    return items
}

suspend fun financeReadSignedSource(apis:FinanceEvidenceReadApis,org:String,install:String,
    kind:FinanceReviewKind,id:String):FinanceSignedSource {
    require(financeLookupIdIsUuid(id))
    val result=when(kind) {
        FinanceReviewKind.CASH_VOUCHER -> apis.cash.operatorStatus(org,id,install).signedSource()
        FinanceReviewKind.CASH_COUNT -> apis.cashCount.operatorStatus(org,id,install).signedSource()
        FinanceReviewKind.BANK_RECONCILIATION -> apis.reconciliation.operatorStatus(org,id,install).signedSource()
        else -> error("Unsupported signed operation")
    }
    check(result.kind==kind && financeReadSourceMatches(org,result.org,id,result.id))
    return result
}

suspend fun financeRecordSignedSource(apis:FinanceEvidenceReadApis,org:String,install:String,
    expected:FinanceSignedSource,evidence:String,operation:String):FinanceSignedSource {
    require(financeSignedReady(expected,org) && financeLookupIdIsUuid(evidence) && financeLookupIdIsUuid(operation))
    val current=financeReadSignedSource(apis,org,install,expected.kind,expected.id)
    check(current==expected && financeSignedReady(current,org)) {"FINANCE_SIGNED_SOURCE_CHANGED"}
    val result=when(expected.kind) {
        FinanceReviewKind.CASH_VOUCHER -> {
            val r=apis.cash.recordVoucher(org,expected.id,operation,evidence,install)
            check(r.physicalCustodyConfirmed && !r.bankTransactionInitiated)
            r.signedSource()
        }
        FinanceReviewKind.CASH_COUNT -> {
            val r=apis.cashCount.record(org,expected.id,operation,evidence,install)
            check(!r.ledgerAdjusted && !r.bankTransferInitiated)
            r.signedSource()
        }
        FinanceReviewKind.BANK_RECONCILIATION -> {
            val r=apis.reconciliation.sign(org,expected.id,operation,evidence,install)
            check(r.internallyReconciled && !r.bankTransferInitiated)
            r.signedSource()
        }
        else -> error("Unsupported signed operation")
    }
    val terminal=if(expected.kind==FinanceReviewKind.BANK_RECONCILIATION) "SIGNED" else "RECORDED"
    check(result.kind==expected.kind && financeReadSourceMatches(org,result.org,expected.id,result.id) &&
        result.approvalId==expected.approvalId && result.facts==expected.facts &&
        result.state==terminal && result.recordId?.let(::financeLookupIdIsUuid)==true)
    return result
}

@Composable
fun FinanceSignedCompletionSection(identityId:String,org:String,install:String,
    apis:FinanceEvidenceReadApis,newOperationId:()->String,embedded:Boolean=false) {
    key(identityId,org,install) {
        val scope=rememberCoroutineScope()
        var expanded by remember(embedded) {mutableStateOf(embedded)}
        var kind by remember {mutableStateOf(FinanceReviewKind.CASH_VOUCHER)}
        var initialSelectionDone by remember {mutableStateOf(false)}
        var id by remember {mutableStateOf("")}
        var evidence by remember {mutableStateOf("")}
        var current by remember {mutableStateOf<FinanceSignedSource?>(null)}
        var items by remember {mutableStateOf<List<FinanceSignedSource>>(emptyList())}
        var revision by remember {mutableIntStateOf(0)}
        var operation by remember {mutableStateOf<String?>(null)}
        var confirm by remember {mutableStateOf(false)}
        var busy by remember {mutableStateOf(false)}
        var error by remember {mutableStateOf<String?>(null)}
        fun perform(action:suspend ()->Unit) {
            if(busy)return
            busy=true;error=null
            scope.launch {
                try {action()}
                catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){error="تعذر تأكيد العملية من المصدر. حدّث الحالة قبل إعادة المحاولة."}
                finally {busy=false}
            }
        }
        LaunchedEffect(expanded,kind,revision) {
            if(!expanded)return@LaunchedEffect
            busy=true;error=null;items=emptyList()
            try {
                items=financeSignedQueue(apis,org,install,kind)
                initialSelectionDone=true
            } catch(cancelled:CancellationException) {
                throw cancelled
            } catch(_:Exception) {
                if(!initialSelectionDone) {
                    var alternate:FinanceReviewKind?=null
                    for(other in listOf(FinanceReviewKind.CASH_VOUCHER,
                        FinanceReviewKind.CASH_COUNT,FinanceReviewKind.BANK_RECONCILIATION)) {
                        if(other==kind)continue
                        try {
                            financeSignedQueue(apis,org,install,other)
                            alternate=other
                            break
                        } catch(cancelled:CancellationException){throw cancelled}
                        catch(_:Exception){}
                    }
                    if(alternate!=null)kind=alternate
                    else error="قائمة العمليات غير متاحة لهذا الدور أو تعذر الاتصال."
                } else error="قائمة العمليات غير متاحة لهذا النوع أو تعذر الاتصال."
            } finally {busy=false}
        }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!embedded) {
                OutlinedButton(enabled=!busy,onClick={expanded=!expanded}) {
                    Text(if(expanded) "إخفاء إتمام العمليات الموقعة" else "إتمام العمليات الموقعة")
                }
            }
            if(!expanded)return@Column
            Text("سجل سند الخزنة أو نتيجة الجرد أو المطابقة الداخلية بعد الموافقة المستقلة والمستند الموقع.")
            listOf(FinanceReviewKind.CASH_VOUCHER,FinanceReviewKind.CASH_COUNT,FinanceReviewKind.BANK_RECONCILIATION).forEach { option ->
                OutlinedButton(enabled=!busy && kind!=option,onClick={
                    kind=option;id="";evidence="";current=null;operation=null;confirm=false;error=null;items=emptyList()
                }) {Text(option.label)}
            }
            Text("العمليات المعتمدة الجاهزة للتسجيل",style=MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled=!busy,onClick={revision++}) {Text("تحديث القائمة")}
            if(items.isEmpty() && !busy && error==null) Text("لا توجد عمليات جاهزة في القائمة.")
            items.forEach { item ->
                val description=when(item.kind) {
                    FinanceReviewKind.CASH_VOUCHER -> item.facts.getOrNull(3).orEmpty()+" · "+item.facts.getOrNull(4).orEmpty()+" "+item.facts.getOrNull(5).orEmpty()
                    FinanceReviewKind.CASH_COUNT -> "جرد · "+item.facts.getOrNull(2).orEmpty()+" "+item.facts.getOrNull(1).orEmpty()
                    else -> "مطابقة · "+item.facts.getOrNull(8).orEmpty()+" "+item.facts.getOrNull(4).orEmpty()
                }
                OutlinedButton(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick={
                    id=item.id;current=item;evidence="";operation=null;confirm=false
                }) {Text(description+" · "+item.id.take(8))}
            }
            OutlinedTextField(value=id,onValueChange={
                id=it.trim();current=null;operation=null;evidence="";confirm=false
            },label={Text("معرّف المقترح")},enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedButton(enabled=!busy && financeLookupIdIsUuid(id),onClick={
                perform {
                    val fresh=financeReadSignedSource(apis,org,install,kind,id)
                    if(fresh!=current)operation=null
                    current=fresh;confirm=false
                }
            }) {Text("قراءة الحالة الحالية")}
            current?.let { source ->
                Text("الحالة: "+source.state)
                source.facts.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
                source.approvalId?.let {Text("الموافقة: "+it)}
                source.recordId?.let {Text("السجل المثبت: "+it)}
                if(financeSignedReady(source,org)) {
                    OutlinedTextField(value=evidence,onValueChange={
                        evidence=it.trim();operation=null;confirm=false
                    },label={Text("معرّف المستند الموقع والجاهز")},enabled=!busy,modifier=Modifier.fillMaxWidth())
                    if(!confirm) OutlinedButton(enabled=!busy && financeLookupIdIsUuid(evidence),onClick={confirm=true}) {
                        Text("مراجعة التسجيل النهائي")
                    } else {
                        Text("تأكيد التسجيل الداخلي وفق الموافقة والمستند الموقع.")
                        OutlinedButton(enabled=!busy,onClick={confirm=false}) {Text("رجوع")}
                        OutlinedButton(enabled=!busy,onClick={
                            val op=operation?:newOperationId().also {operation=it}
                            perform {
                                current=financeRecordSignedSource(apis,org,install,source,evidence,op)
                                items=items.filterNot {it.id==source.id}
                                confirm=false
                            }
                        }) {Text("تأكيد التسجيل")}
                    }
                }
            }
            if(busy)Text("جارٍ الاتصال بالمصدر…")
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}
