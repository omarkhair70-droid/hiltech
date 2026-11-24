package com.hiltech.shared.core.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordTable
import com.hiltech.shared.core.design.HiltechTableColumn
import com.hiltech.shared.core.design.HiltechTableRecord
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.LocalHiltechLanguage
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.procurement.SupplierInvoiceFinanceApiClient
import kotlinx.coroutines.CancellationException

/**
 * Original Finance transports. Read-only evidence remains the default.
 * Separate independent-review preparation is available only behind its own
 * default-OFF native host flag. No posting, issuance, payments or bank writes.
 */
class FinanceEvidenceReadApis(api: HiltechApiClient) {
    val apEvidence = FinanceApLedgerEvidenceReportApiClient(api)
    val supplierInvoices = SupplierInvoiceFinanceApiClient(api)
    val bankIntake = FinanceBankStatementIntakeApiClient(api)
    val reconciliation = FinanceBankReconciliationSignoffApiClient(api)
    val allocation = FinanceCashAllocationApiClient(api)
    val allocationCorrection = FinanceCashVoidApiClient(api)
    val cash = FinancePhysicalCashApiClient(api)
    val cashCount = FinanceCashCountApiClient(api)
    val apJournal = FinanceApJournalPostingApiClient(api)
    val clientAwards = FinanceClientAwardSourceApiClient(api)
    val billingEvidence = FinanceBillingEvidenceLookupApiClient(api)
    val accountingChoices = FinanceAccountingChoicesApiClient(api)
    val arCandidate = FinanceArInvoiceCandidateApiClient(api)
    val arIssuance = FinanceArInvoiceIssuanceApiClient(api)
    val arJournal = FinanceArJournalPostingApiClient(api)
    val employeeMoney = FinanceEmployeeMoneyApiClient(api)
    val reimbursement = FinanceExpenseReimbursementApiClient(api)
    val periodClose = FinanceGlPeriodCloseApiClient(api)
}

enum class FinanceReadKind(val label: String, val english: String) {
    BANK_INTAKE("كشف البنك", "Bank statement"),
    BANK_RECONCILIATION("مطابقة البنك", "Bank reconciliation"),
    BANK_ALLOCATION("تخصيص التحصيل/السداد", "Receipt/payment allocation"),
    BANK_ALLOCATION_CORRECTION("تصحيح التخصيص", "Allocation correction"),
    CASHBOX("رصيد خزنة", "Cashbox balance"),
    CASH_VOUCHER("سند خزنة", "Cash voucher"),
    CASH_COUNT("جرد الخزنة", "Cash count"),
    AP_JOURNAL("قيد المورد", "Supplier journal"),
    AR_CANDIDATE("مرشح فاتورة عميل", "Customer invoice candidate"),
    AR_INVOICE("إثبات فاتورة عميل", "Customer invoice evidence"),
    AR_JOURNAL("قيد العميل", "Customer journal"),
    EMPLOYEE_FUNDING("عهدة/سلفة", "Employee advance / imprest"),
    EMPLOYEE_SETTLEMENT("تسوية موظف", "Employee settlement"),
    EMPLOYEE_REIMBURSEMENT("رد مصروف موظف", "Employee expense reimbursement"),
    PERIOD_CLOSE("إقفال الفترة", "Period close"),
}

data class FinanceReviewHandoff(val kind:FinanceReviewKind,val sourceId:String,val sourceKind:String="AR")

data class FinanceSourceSnapshot(
    val title: String,
    val state: String,
    val facts: List<String>,
    val nextActions: List<FinanceReviewHandoff> = emptyList(),
)

fun financePostedJournalHandoffs(id:String?,kind:String):List<FinanceReviewHandoff> =
    if(id!=null && financeLookupIdIsUuid(id) && kind in setOf("AP","AR")) listOf(
        FinanceReviewHandoff(FinanceReviewKind.CASH_ALLOCATION,id,kind),
        FinanceReviewHandoff(FinanceReviewKind.CASH_VOUCHER,id,kind),
    ) else emptyList()

/** The requested object and organization must match the actual current source. */
fun financeReadSourceMatches(
    requestedOrganizationId: String,
    actualOrganizationId: String,
    requestedObjectId: String,
    actualObjectId: String,
): Boolean = requestedOrganizationId.equals(actualOrganizationId, ignoreCase = true) &&
    requestedObjectId.equals(actualObjectId, ignoreCase = true) &&
    requestedOrganizationId.isNotBlank() && requestedObjectId.isNotBlank()

fun financeLookupIdIsUuid(value: String): Boolean = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
).matches(value)

private fun yesNo(value: Boolean, language: HiltechLanguage) = if (value) language.text("نعم (حسب المصدر)", "Yes (per source)") else language.text("لا (حسب المصدر)", "No (per source)")

/** Never return data from another org, a different proposal or a stale lookup. */
suspend fun financeReadSourceSnapshot(
    apis: FinanceEvidenceReadApis,
    kind: FinanceReadKind,
    organizationId: String,
    objectId: String,
    installationId: String,
    language: HiltechLanguage = HiltechLanguage.Arabic,
): FinanceSourceSnapshot {
    require(financeLookupIdIsUuid(objectId)) { "معرّف UUID صحيح مطلوب" }
    fun checked(org: String, id: String) {
        check(financeReadSourceMatches(organizationId, org, objectId, id)) {
            "FINANCE_SOURCE_SCOPE_CHANGED"
        }
    }
    return when (kind) {
        FinanceReadKind.BANK_INTAKE -> {
            val r = apis.bankIntake.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("كشف البنك", "Bank statement"), r.state, listOf(
                language.text("عدد الحركات من المصدر: ", "Source transaction count: ") + r.lineCount,
                language.text("مسجل داخليًا: ", "Recorded internally: ") + yesNo(r.internalBankStatementRecorded, language),
                language.text("مطابق بنكيًا: ", "Bank reconciled: ") + yesNo(r.bankReconciled, language),
                language.text("تحويل بدأه HILTECH: ", "Transfer initiated by HILTECH: ") + yesNo(r.bankTransactionInitiated, language),
            ), if(r.internalBankStatementRecorded && r.intakeId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.BANK_RECONCILIATION,r.intakeId!!)) else emptyList())
        }
        FinanceReadKind.BANK_RECONCILIATION -> {
            val r = apis.reconciliation.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("قرار مطابقة البنك", "Bank reconciliation decision"), r.state, listOf(
                language.text("عدد الحركات: ", "Transaction count: ") + r.lineCount,
                language.text("إجمالي المطابق في المستند: ", "Total matched in document: ") + r.totalMatched + " " + r.currency,
                language.text("مطابقة داخلية موقعة: ", "Signed internal reconciliation: ") + yesNo(r.internallyReconciled, language),
                language.text("تأكيد مزود البنك: ", "Bank provider confirmation: ") + yesNo(r.verifiedByBankProvider, language),
                language.text("تحويل بنكي جديد: ", "New bank transfer: ") + yesNo(r.bankTransferInitiated, language),
            ))
        }
        FinanceReadKind.BANK_ALLOCATION -> {
            val r = apis.allocation.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("تخصيص مالي", "Financial allocation"), r.state, listOf(
                language.text("المصدر: ", "Source: ") + r.sourceKind,
                language.text("المبلغ: ", "Amount: ") + r.amount + " " + r.currency,
                language.text("تخصيص محاسبي مسجل: ", "Accounting allocation recorded: ") + yesNo(r.cashAllocatedToAccountingSource, language),
                language.text("تحويل منفذ بواسطة النظام: ", "Transfer made by system: ") + yesNo(r.moneyTransferredByHiltech, language),
                language.text("مطابقة بنكية: ", "Bank reconciliation: ") + yesNo(r.bankReconciled, language),
            ), if(r.cashAllocatedToAccountingSource && r.recordedAllocationId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.CASH_ALLOCATION_CORRECTION,r.recordedAllocationId!!)) else emptyList())
        }
        FinanceReadKind.BANK_ALLOCATION_CORRECTION -> {
            val r = apis.allocationCorrection.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("تصحيح تخصيص", "Allocation correction"), r.state, listOf(
                language.text("المبلغ: ", "Amount: ") + r.amount + " " + r.currency,
                language.text("السجل الأصلي محفوظ: ", "Original record preserved: ") + yesNo(r.originalRecordPreserved, language),
                language.text("مطابقة السجل الأصلي ما زالت سارية: ", "Original match still effective: ") + yesNo(r.originalMatchStillEffective, language),
                language.text("عكس قيد دفتر الأستاذ: ", "Ledger reversal: ") + yesNo(r.generalLedgerReversed, language),
            ))
        }
        FinanceReadKind.CASHBOX -> {
            val r = apis.cash.balance(organizationId, objectId, installationId)
            checked(r.organizationId, r.cashboxId)
            FinanceSourceSnapshot(language.text("رصيد الخزنة المسجل", "Recorded cashbox balance"), "SOURCE_BALANCE", listOf(
                language.text("الخزنة: ", "Cashbox: ") + r.code,
                language.text("الرصيد الدفتري: ", "Ledger balance: ") + r.cashOnHand + " " + r.currency,
                language.text("عدد السندات: ", "Voucher count: ") + r.voucherCount,
                language.text("جرد فعلي مطابق: ", "Physical count reconciled: ") + yesNo(r.physicalCountReconciled, language),
            ), listOf(FinanceReviewHandoff(FinanceReviewKind.CASH_COUNT,r.cashboxId)))
        }
        FinanceReadKind.CASH_VOUCHER -> {
            val r = apis.cash.voucherStatus(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("سند خزنة", "Cash voucher"), r.state, listOf(
                language.text("نوع المصدر: ", "Source type: ") + r.sourceKind,
                language.text("المبلغ: ", "Amount: ") + r.amount + " " + r.currency,
                language.text("إثبات عهدة فعلية: ", "Physical custody confirmed: ") + yesNo(r.physicalCustodyConfirmed, language),
                language.text("تحويل بنكي: ", "Bank transfer: ") + yesNo(r.bankTransactionInitiated, language),
            ))
        }
        FinanceReadKind.CASH_COUNT -> {
            val r = apis.cashCount.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("جرد الخزنة", "Cash count"), r.state, listOf(
                language.text("المعدود: ", "Counted: ") + r.countedAmount + " " + r.currency,
                language.text("الدفتر: ", "Ledger: ") + r.ledgerAmount + " " + r.currency,
                language.text("الفرق المثبت: ", "Recorded variance: ") + r.varianceAmount + " " + r.currency,
                language.text("تعديل محاسبي منفذ: ", "Accounting adjustment made: ") + yesNo(r.ledgerAdjusted, language),
            ))
        }
        FinanceReadKind.AP_JOURNAL -> {
            val r = apis.apJournal.status(organizationId, objectId, installationId)
            // This Phase8K receipt is keyed by original proposal ID, without an
            // organization field. Endpoint itself is source/actor/organization scoped.
            check(r.proposalId.equals(objectId, ignoreCase = true)) { "FINANCE_PROPOSAL_CHANGED" }
            FinanceSourceSnapshot(language.text("قيد المورد", "Supplier journal"), r.state, listOf(
                language.text("قيمة الفاتورة: ", "Invoice amount: ") + r.invoiceAmount + " " + r.currency,
                language.text("التزام محاسبي معترف به: ", "Recognized payable: ") + yesNo(r.recognizedAccountsPayable, language),
                language.text("تحويل أموال: ", "Funds transferred: ") + yesNo(r.moneyTransferred, language),
                language.text("مطابقة بنك: ", "Bank reconciled: ") + yesNo(r.bankReconciled, language),
            ), if(r.state=="POSTED_ACCOUNTS_PAYABLE" && r.recognizedAccountsPayable) financePostedJournalHandoffs(r.postedJournalId,"AP") else emptyList())
        }
        FinanceReadKind.AR_CANDIDATE -> {
            val r = apis.arCandidate.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.candidateId)
            FinanceSourceSnapshot(language.text("مرشح فاتورة عميل", "Customer invoice candidate"), r.state, listOf(
                language.text("القيمة المطلوبة: ", "Requested amount: ") + r.requestedAmount + " " + r.currency,
                language.text("صدرت فاتورة: ", "Invoice issued: ") + yesNo(r.invoiceIssued, language),
                language.text("أثبت ذمم مدينة: ", "Receivable posted: ") + yesNo(r.receivablePosted, language),
                language.text("تم تحصيل نقدية: ", "Cash collected: ") + yesNo(r.cashCollected, language),
            ), if(r.state=="DRAFT_UNISSUED" && !r.invoiceIssued) listOf(FinanceReviewHandoff(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,r.candidateId)) else emptyList())
        }
        FinanceReadKind.AR_INVOICE -> {
            val r = apis.arIssuance.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("إثبات فاتورة عميل", "Customer invoice evidence"), r.state, listOf(
                language.text("رقم الفاتورة: ", "Invoice number: ") + r.invoiceNumber,
                language.text("سُجلت داخليًا: ", "Recorded internally: ") + yesNo(r.recordedInternally, language),
                language.text("أُرسلت للعميل: ", "Sent to customer: ") + yesNo(r.invoiceSentExternally, language),
                language.text("تم تحصيلها: ", "Payment collected: ") + yesNo(r.paymentCollected, language),
            ), if(r.recordedInternally && r.issuedInvoiceId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.AR_JOURNAL,r.issuedInvoiceId!!)) else emptyList())
        }
        FinanceReadKind.AR_JOURNAL -> {
            val r = apis.arJournal.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("قيد العميل", "Customer journal"), r.state, listOf(
                language.text("القيمة: ", "Amount: ") + r.amount + " " + r.currency,
                language.text("ذمم مدينة معترف بها: ", "Recognized receivable: ") + yesNo(r.receivableRecognized, language),
                language.text("نقدية محصلة: ", "Cash collected: ") + yesNo(r.cashCollected, language),
                language.text("مطابقة البنك: ", "Bank reconciled: ") + yesNo(r.bankReconciled, language),
            ), if(r.state=="POSTED_ACCOUNTS_RECEIVABLE" && r.receivableRecognized) financePostedJournalHandoffs(r.postedJournalId,"AR") else emptyList())
        }
        FinanceReadKind.EMPLOYEE_FUNDING -> {
            val r = apis.employeeMoney.fundingStatus(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("تمويل موظف / عهدة", "Employee funding / imprest"), r.state, listOf(
                language.text("القيمة: ", "Amount: ") + r.amount + " " + r.currency,
                language.text("إثبات التمويل محفوظ: ", "Funding evidence recorded: ") + yesNo(r.fundingEvidenceRecorded, language),
                language.text("تحويل بنك جديد: ", "New bank transfer: ") + yesNo(r.bankTransferInitiated, language),
                language.text("تسوية الموظف اكتملت: ", "Employee settlement complete: ") + yesNo(r.employeeSettled, language),
            ), if(r.fundingEvidenceRecorded && !r.employeeSettled && r.fundingRecordId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.EMPLOYEE_SETTLEMENT,r.fundingRecordId!!)) else emptyList())
        }
        FinanceReadKind.EMPLOYEE_SETTLEMENT -> {
            val r = apis.employeeMoney.settlementStatus(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("تسوية موظف", "Employee settlement"), r.state, listOf(
                language.text("العملية: ", "Operation: ") + r.action,
                language.text("القيمة: ", "Amount: ") + r.amount + " " + r.currency,
                language.text("تسوية مسجلة: ", "Settlement recorded: ") + yesNo(r.settlementRecorded, language),
                language.text("خصم راتب منفذ: ", "Payroll deduction initiated: ") + yesNo(r.payrollDeductionInitiated, language),
            ))
        }
        FinanceReadKind.EMPLOYEE_REIMBURSEMENT -> {
            val r = apis.reimbursement.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot(language.text("تعويض مصروف موظف", "Employee expense reimbursement"), r.state, listOf(
                language.text("القيمة: ", "Amount: ") + r.amount + " " + r.currency,
                language.text("خصم بنكي خصص للمصروف: ", "Bank debit allocated to expense: ") + yesNo(r.bankDebitAllocatedToEmployeeExpense, language),
                language.text("تحويل جديد بدأه النظام: ", "New transfer initiated by system: ") + yesNo(r.bankTransferInitiated, language),
                language.text("اعتراف بالمصروف في GL: ", "Expense recognized in GL: ") + yesNo(r.expenseRecognizedInGl, language),
            ))
        }
        FinanceReadKind.PERIOD_CLOSE -> {
            val r = apis.periodClose.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.periodId)
            FinanceSourceSnapshot(language.text("إقفال فترة", "Period close"), r.state, listOf(
                language.text("القيود في اللقطة: ", "Journals in snapshot: ") + r.journalCount,
                language.text("الفترة مقفولة: ", "Period closed: ") + yesNo(r.periodClosed, language),
                language.text("تحويل أموال: ", "Funds transferred: ") + yesNo(r.moneyTransferred, language),
                language.text("مطابقة بنكية: ", "Bank reconciliation: ") + yesNo(r.bankReconciled, language),
            ))
        }
    }
}

fun financeEvidenceReadNativeWorkspace(
    identityId: String,
    organizationId: String,
    installationId: String,
    apis: FinanceEvidenceReadApis,
    reviewPreparationEnabled: Boolean = false,
    newOperationId: () -> String = { error("Finance review preparation disabled") },
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "finance-evidence-read",
    label = "المراجعة المالية",
    canReadSource = {
        // Two existing source-owned read permissions may reveal the workspace.
        // Each detailed GET still checks its own domain policy on the server.
        val apAllowed = try {
            apis.apEvidence.get(organizationId, installationId)
                .organizationId.equals(organizationId, ignoreCase = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (apAllowed) true else {
            val supplierAllowed = try {
                apis.supplierInvoices.list(organizationId, installationId)
                true // an empty authorized GET is still source proof
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (supplierAllowed) true else if (!reviewPreparationEnabled) false else {
                // A Finance preparer is not necessarily a supplier-ledger reader.
                // Use only original current-org GETs backed by the actor's real authority.
                val commercialAllowed = try {
                    apis.clientAwards.list(organizationId, installationId)
                        .organizationId.equals(organizationId, ignoreCase = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (commercialAllowed) true else {
                    var journalAllowed = false
                    for (kind in listOf("AP", "AR")) {
                        try {
                            val choices = financeReadAccountingChoices(
                                apis, organizationId, kind, installationId)
                            if (choices.organizationId.equals(organizationId, ignoreCase = true)) {
                                journalAllowed = true
                                break
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { }
                    }
                    journalAllowed
                }
            }
        }
    },
    hostOwnsScroll = false,
    content = { _ ->
        var ledgerRefresh by remember(identityId, organizationId, installationId) {
            mutableIntStateOf(0)
        }
        var ledger by remember(identityId, organizationId, installationId) {
            mutableStateOf<FinanceApLedgerEvidenceReportDto?>(null)
        }
        var ledgerLoading by remember(identityId, organizationId, installationId) {
            mutableStateOf(false)
        }
        var ledgerExpanded by remember(identityId, organizationId, installationId) {
            mutableStateOf(false)
        }
        var ledgerError by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        LaunchedEffect(identityId, organizationId, installationId, ledgerRefresh) {
            if (ledgerRefresh == 0) return@LaunchedEffect
            ledgerLoading = true
            ledger = null
            ledgerError = null
            try {
                val source = apis.apEvidence.get(organizationId, installationId)
                check(source.organizationId == organizationId &&
                    !source.isCompanyWideTrialBalance &&
                    source.companyOutstandingPayables == null &&
                    source.companySupplierPayments == null &&
                    source.companyBankReconciledTotal == null
                ) { "FINANCE_LEDGER_SOURCE_MISMATCH" }
                ledger = source
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ledgerError = "مصدر دفتر الموردين غير متاح لهذا الحساب حاليًا."
            } finally {
                ledgerLoading = false
            }
        }

        var area by remember(identityId, organizationId, installationId, reviewPreparationEnabled) {
            mutableStateOf(if (reviewPreparationEnabled) FinanceReviewArea.Prepare else FinanceReviewArea.Trace)
        }
        var handoff by remember(identityId, organizationId, installationId) { mutableStateOf<FinanceReviewHandoff?>(null) }
        var lookupKind by remember(identityId, organizationId, installationId) {
            mutableStateOf<FinanceReadKind?>(null)
        }
        var lookupText by remember(identityId, organizationId, installationId, lookupKind) {
            mutableStateOf("")
        }
        var requestedId by remember(identityId, organizationId, installationId, lookupKind) {
            mutableStateOf<String?>(null)
        }
        var lookupRevision by remember(identityId, organizationId, installationId, lookupKind) {
            mutableIntStateOf(0)
        }
        var lookupLoading by remember(identityId, organizationId, installationId, lookupKind) {
            mutableStateOf(false)
        }
        var lookupResult by remember(identityId, organizationId, installationId, lookupKind) {
            mutableStateOf<FinanceSourceSnapshot?>(null)
        }
        var lookupError by remember(identityId, organizationId, installationId, lookupKind) {
            mutableStateOf<String?>(null)
        }
        val language = LocalHiltechLanguage.current
        LaunchedEffect(identityId, organizationId, installationId,
            lookupKind, requestedId, lookupRevision, language
        ) {
            val kind = lookupKind ?: return@LaunchedEffect
            val id = requestedId ?: return@LaunchedEffect
            lookupLoading = true
            lookupResult = null
            lookupError = null
            try {
                lookupResult = financeReadSourceSnapshot(
                    apis, kind, organizationId, id, installationId, language,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lookupError = "السجل غير موجود أو غير مصرح به أو تعذر قراءة المصدر الحالي."
            } finally {
                lookupLoading = false
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HiltechSectionHeading("FINANCE / REVIEW", language.text("المراجعة المالية", "Financial review"),
                language.text("راجع السجلات المحاسبية والإجراءات المرتبطة بها من مصادرها.", "Review accounting records and their related actions at source."))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceReviewArea.entries.filter { reviewPreparationEnabled || it != FinanceReviewArea.Prepare }.forEach { choice ->
                    FilterChip(selected = area == choice, onClick = { area = choice }, label = { Text(language.text(choice.ar, choice.en)) })
                }
            }
            if (reviewPreparationEnabled && area == FinanceReviewArea.Prepare) {
                FinanceReviewPreparationSection(
                    identityId = identityId,
                    organizationId = organizationId,
                    installationId = installationId,
                    apis = apis,
                    newOperationId = newOperationId,
                    handoff = handoff,
                    onHandoffConsumed = { handoff = null },
                )
            }

            if (area == FinanceReviewArea.Ledger) {
            OutlinedButton(onClick = {
                ledgerExpanded = !ledgerExpanded
                if (ledgerExpanded && ledgerRefresh == 0) ledgerRefresh += 1
            }) {
                Text(if (ledgerExpanded) hiltechText("إخفاء سجل قيود الموردين", "Hide supplier journal history") else hiltechText("عرض سجل قيود الموردين", "Show supplier journal history"))
            }
            if (ledgerExpanded) {
            Text(hiltechText("سجل الموردين · قيود تاريخية مثبتة", "Supplier ledger · recorded historical journals"),
                style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = !ledgerLoading, onClick = { ledgerRefresh += 1 }) {
                Text(if (ledgerLoading) hiltechText("جارٍ تحديث المصدر…", "Refreshing source…") else hiltechText("تحديث سجل الموردين", "Refresh supplier ledger"))
            }
            if (ledgerLoading) Text(hiltechText("جارٍ تحميل قيود الموردين المسموح بها…", "Loading accessible supplier journals…"))
            ledgerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ledger?.let { current ->
                when {
                    !current.sourceAvailable -> Text(
                        "مصدر القيود غير متاح؛ المستحقات الفعلية مجهولة وليست صفرًا.")
                    current.groups.isEmpty() -> Text(
                        "لا توجد قيود في نطاق المصدر المعروض؛ هذا لا يثبت عدم وجود التزامات.")
                    else -> {
                        HiltechRecordTable(
                            columns = listOf(
                                HiltechTableColumn(hiltechText("مرجع المورد", "Supplier reference")),
                                HiltechTableColumn(hiltechText("القيود التاريخية", "Historical gross"), 1.4f),
                                HiltechTableColumn(hiltechText("العملة", "Currency"), .7f),
                                HiltechTableColumn(hiltechText("عدد القيود", "Journal count")),
                            ),
                            records = current.groups.map { item -> HiltechTableRecord(listOf(
                                item.supplierOrganizationId.take(8), item.historicallyRecognizedGross.toString(),
                                item.currency, item.postedJournalCount.toString())) },
                            emptyMessage = hiltechText("لا توجد قيود معروضة.", "No visible journals."),
                        )
                        Text(hiltechText("المدفوع والمتبقي غير مثبتين بهذا المصدر.", "Paid and outstanding balances are not established by this source."),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (current.hasMoreGroups) {
                    Text("هذه أول " + current.queryLimit +
                        " مجموعة فقط، وليست ميزان مراجعة كاملًا.")
                }
            }

            }

            }
            if (area == FinanceReviewArea.Trace) {
            Text(hiltechText("تتبّع عملية برقم مصدرها", "Trace a source record"), style = MaterialTheme.typography.titleSmall)
            Text(hiltechText("اختر نوع السجل، ثم أدخل UUID أصليًا معروفًا من العملية. كل قراءة تعيد التحقق من صلاحية السيرفر الحالية.", "Choose a record type and enter its source record ID. Each lookup checks current access."),
                style = MaterialTheme.typography.bodySmall)
            listOf(
                language.text("البنك والتخصيص", "Bank & allocations") to listOf(
                    FinanceReadKind.BANK_INTAKE, FinanceReadKind.BANK_RECONCILIATION,
                    FinanceReadKind.BANK_ALLOCATION, FinanceReadKind.BANK_ALLOCATION_CORRECTION,
                ),
                language.text("الخزنة الفعلية", "Physical cash") to listOf(
                    FinanceReadKind.CASHBOX, FinanceReadKind.CASH_VOUCHER,
                    FinanceReadKind.CASH_COUNT,
                ),
                language.text("فواتير ودفاتر الموردين والعملاء", "Supplier & customer journals") to listOf(
                    FinanceReadKind.AP_JOURNAL, FinanceReadKind.AR_CANDIDATE,
                    FinanceReadKind.AR_INVOICE, FinanceReadKind.AR_JOURNAL,
                ),
                language.text("الموظفون والإقفال", "Employees & closing") to listOf(
                    FinanceReadKind.EMPLOYEE_FUNDING, FinanceReadKind.EMPLOYEE_SETTLEMENT,
                    FinanceReadKind.EMPLOYEE_REIMBURSEMENT, FinanceReadKind.PERIOD_CLOSE,
                ),
            ).forEach { (group, kinds) ->
                Text(group, style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    kinds.forEach { kind ->
                        OutlinedButton(
                            enabled = kind != lookupKind,
                            onClick = { lookupKind = kind },
                        ) { Text(language.text(kind.label, kind.english)) }
                    }
                }
            }
            if (lookupKind == null) {
                Text(hiltechText("اختَر نوع العملية لاستعراض دليلها من المصدر.", "Choose a record type to view its source evidence."))
            } else {
                OutlinedTextField(
                    value = lookupText,
                    onValueChange = {
                        lookupText = it.trim()
                        requestedId = null
                        lookupResult = null
                        lookupError = null
                    },
                    label = { Text(hiltechText("معرّف السجل UUID", "Source record ID (UUID)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val valid = financeLookupIdIsUuid(lookupText)
                if (lookupText.isNotEmpty() && !valid) {
                    Text(hiltechText("أدخل UUID صحيحًا من المصدر الأصلي.", "Enter a valid UUID from the original record."))
                }
                OutlinedButton(
                    enabled = valid && !lookupLoading,
                    onClick = {
                        requestedId = lookupText
                        lookupRevision += 1
                    },
                ) { Text(if (lookupLoading) hiltechText("جارٍ القراءة…", "Reading…") else hiltechText("قراءة الحالة الحالية", "Read current status")) }
                lookupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                lookupResult?.let { record ->
                    HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(record.title, style = MaterialTheme.typography.titleLarge)
                        HiltechKeyValue(hiltechText("حالة المصدر", "Source state"), record.state)
                        record.facts.forEach { fact ->
                            val split = fact.indexOf(": ")
                            if (split > 0) HiltechKeyValue(fact.substring(0, split), fact.substring(split + 2))
                            else Text(fact)
                        }
                    }
                    if(reviewPreparationEnabled) record.nextActions.forEach { action ->
                        OutlinedButton(enabled=handoff==null,onClick={handoff=action;area=FinanceReviewArea.Prepare}) {Text(hiltechText("متابعة: ", "Continue: ")+action.kind.label)}
                    }
                    Text(hiltechText("الحالة قراءة فقط من API، ولا تعني إتمام حركة بنك أو اعتماد مستقل.", "Read-only source status. It does not imply a bank transfer or independent approval."),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            }
        }
    },
)

private enum class FinanceReviewArea(val ar: String, val en: String) {
    Prepare("تجهيز ومراجعة", "Prepare & review"), Ledger("دفتر الموردين", "Supplier ledger"), Trace("تتبّع السجلات", "Trace records"),
}
