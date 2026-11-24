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
    val arCandidate = FinanceArInvoiceCandidateApiClient(api)
    val arIssuance = FinanceArInvoiceIssuanceApiClient(api)
    val arJournal = FinanceArJournalPostingApiClient(api)
    val employeeMoney = FinanceEmployeeMoneyApiClient(api)
    val reimbursement = FinanceExpenseReimbursementApiClient(api)
    val periodClose = FinanceGlPeriodCloseApiClient(api)
}

enum class FinanceReadKind(val label: String) {
    BANK_INTAKE("كشف البنك"),
    BANK_RECONCILIATION("مطابقة البنك"),
    BANK_ALLOCATION("تخصيص التحصيل/السداد"),
    BANK_ALLOCATION_CORRECTION("تصحيح التخصيص"),
    CASHBOX("رصيد خزنة"),
    CASH_VOUCHER("سند خزنة"),
    CASH_COUNT("جرد الخزنة"),
    AP_JOURNAL("قيد المورد"),
    AR_CANDIDATE("مرشح فاتورة عميل"),
    AR_INVOICE("إثبات فاتورة عميل"),
    AR_JOURNAL("قيد العميل"),
    EMPLOYEE_FUNDING("عهدة/سلفة"),
    EMPLOYEE_SETTLEMENT("تسوية موظف"),
    EMPLOYEE_REIMBURSEMENT("رد مصروف موظف"),
    PERIOD_CLOSE("إقفال الفترة"),
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

private fun yesNo(value: Boolean) = if (value) "نعم (حسب المصدر)" else "لا (حسب المصدر)"

/** Never return data from another org, a different proposal or a stale lookup. */
suspend fun financeReadSourceSnapshot(
    apis: FinanceEvidenceReadApis,
    kind: FinanceReadKind,
    organizationId: String,
    objectId: String,
    installationId: String,
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
            FinanceSourceSnapshot("كشف البنك", r.state, listOf(
                "عدد الحركات من المصدر: " + r.lineCount,
                "مسجل داخليًا: " + yesNo(r.internalBankStatementRecorded),
                "مطابق بنكيًا: " + yesNo(r.bankReconciled),
                "تحويل بدأه HILTECH: " + yesNo(r.bankTransactionInitiated),
            ), if(r.internalBankStatementRecorded && r.intakeId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.BANK_RECONCILIATION,r.intakeId!!)) else emptyList())
        }
        FinanceReadKind.BANK_RECONCILIATION -> {
            val r = apis.reconciliation.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("قرار مطابقة البنك", r.state, listOf(
                "عدد الحركات: " + r.lineCount,
                "إجمالي المطابق في المستند: " + r.totalMatched + " " + r.currency,
                "مطابقة داخلية موقعة: " + yesNo(r.internallyReconciled),
                "تأكيد مزود البنك: " + yesNo(r.verifiedByBankProvider),
                "تحويل بنكي جديد: " + yesNo(r.bankTransferInitiated),
            ))
        }
        FinanceReadKind.BANK_ALLOCATION -> {
            val r = apis.allocation.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("تخصيص مالي", r.state, listOf(
                "المصدر: " + r.sourceKind,
                "المبلغ: " + r.amount + " " + r.currency,
                "تخصيص محاسبي مسجل: " + yesNo(r.cashAllocatedToAccountingSource),
                "تحويل منفذ بواسطة النظام: " + yesNo(r.moneyTransferredByHiltech),
                "مطابقة بنكية: " + yesNo(r.bankReconciled),
            ), if(r.cashAllocatedToAccountingSource && r.recordedAllocationId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.CASH_ALLOCATION_CORRECTION,r.recordedAllocationId!!)) else emptyList())
        }
        FinanceReadKind.BANK_ALLOCATION_CORRECTION -> {
            val r = apis.allocationCorrection.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("تصحيح تخصيص", r.state, listOf(
                "المبلغ: " + r.amount + " " + r.currency,
                "السجل الأصلي محفوظ: " + yesNo(r.originalRecordPreserved),
                "مطابقة السجل الأصلي ما زالت سارية: " + yesNo(r.originalMatchStillEffective),
                "عكس قيد دفتر الأستاذ: " + yesNo(r.generalLedgerReversed),
            ))
        }
        FinanceReadKind.CASHBOX -> {
            val r = apis.cash.balance(organizationId, objectId, installationId)
            checked(r.organizationId, r.cashboxId)
            FinanceSourceSnapshot("رصيد الخزنة المسجل", "SOURCE_BALANCE", listOf(
                "الخزنة: " + r.code,
                "الرصيد الدفتري: " + r.cashOnHand + " " + r.currency,
                "عدد السندات: " + r.voucherCount,
                "جرد فعلي مطابق: " + yesNo(r.physicalCountReconciled),
            ), listOf(FinanceReviewHandoff(FinanceReviewKind.CASH_COUNT,r.cashboxId)))
        }
        FinanceReadKind.CASH_VOUCHER -> {
            val r = apis.cash.voucherStatus(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("سند خزنة", r.state, listOf(
                "نوع المصدر: " + r.sourceKind,
                "المبلغ: " + r.amount + " " + r.currency,
                "إثبات عهدة فعلية: " + yesNo(r.physicalCustodyConfirmed),
                "تحويل بنكي: " + yesNo(r.bankTransactionInitiated),
            ))
        }
        FinanceReadKind.CASH_COUNT -> {
            val r = apis.cashCount.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("جرد الخزنة", r.state, listOf(
                "المعدود: " + r.countedAmount + " " + r.currency,
                "الدفتر: " + r.ledgerAmount + " " + r.currency,
                "الفرق المثبت: " + r.varianceAmount + " " + r.currency,
                "تعديل محاسبي منفذ: " + yesNo(r.ledgerAdjusted),
            ))
        }
        FinanceReadKind.AP_JOURNAL -> {
            val r = apis.apJournal.status(organizationId, objectId, installationId)
            // This Phase8K receipt is keyed by original proposal ID, without an
            // organization field. Endpoint itself is source/actor/organization scoped.
            check(r.proposalId.equals(objectId, ignoreCase = true)) { "FINANCE_PROPOSAL_CHANGED" }
            FinanceSourceSnapshot("قيد المورد", r.state, listOf(
                "قيمة الفاتورة: " + r.invoiceAmount + " " + r.currency,
                "التزام محاسبي معترف به: " + yesNo(r.recognizedAccountsPayable),
                "تحويل أموال: " + yesNo(r.moneyTransferred),
                "مطابقة بنك: " + yesNo(r.bankReconciled),
            ), if(r.state=="POSTED_ACCOUNTS_PAYABLE" && r.recognizedAccountsPayable) financePostedJournalHandoffs(r.postedJournalId,"AP") else emptyList())
        }
        FinanceReadKind.AR_CANDIDATE -> {
            val r = apis.arCandidate.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.candidateId)
            FinanceSourceSnapshot("مرشح فاتورة عميل", r.state, listOf(
                "القيمة المطلوبة: " + r.requestedAmount + " " + r.currency,
                "صدرت فاتورة: " + yesNo(r.invoiceIssued),
                "أثبت ذمم مدينة: " + yesNo(r.receivablePosted),
                "تم تحصيل نقدية: " + yesNo(r.cashCollected),
            ), if(r.state=="DRAFT_UNISSUED" && !r.invoiceIssued) listOf(FinanceReviewHandoff(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,r.candidateId)) else emptyList())
        }
        FinanceReadKind.AR_INVOICE -> {
            val r = apis.arIssuance.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("إثبات فاتورة عميل", r.state, listOf(
                "رقم الفاتورة: " + r.invoiceNumber,
                "سُجلت داخليًا: " + yesNo(r.recordedInternally),
                "أُرسلت للعميل: " + yesNo(r.invoiceSentExternally),
                "تم تحصيلها: " + yesNo(r.paymentCollected),
            ), if(r.recordedInternally && r.issuedInvoiceId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.AR_JOURNAL,r.issuedInvoiceId!!)) else emptyList())
        }
        FinanceReadKind.AR_JOURNAL -> {
            val r = apis.arJournal.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("قيد العميل", r.state, listOf(
                "القيمة: " + r.amount + " " + r.currency,
                "ذمم مدينة معترف بها: " + yesNo(r.receivableRecognized),
                "نقدية محصلة: " + yesNo(r.cashCollected),
                "مطابقة البنك: " + yesNo(r.bankReconciled),
            ), if(r.state=="POSTED_ACCOUNTS_RECEIVABLE" && r.receivableRecognized) financePostedJournalHandoffs(r.postedJournalId,"AR") else emptyList())
        }
        FinanceReadKind.EMPLOYEE_FUNDING -> {
            val r = apis.employeeMoney.fundingStatus(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("تمويل موظف / عهدة", r.state, listOf(
                "القيمة: " + r.amount + " " + r.currency,
                "إثبات التمويل محفوظ: " + yesNo(r.fundingEvidenceRecorded),
                "تحويل بنك جديد: " + yesNo(r.bankTransferInitiated),
                "تسوية الموظف اكتملت: " + yesNo(r.employeeSettled),
            ), if(r.fundingEvidenceRecorded && !r.employeeSettled && r.fundingRecordId?.let(::financeLookupIdIsUuid)==true)
                listOf(FinanceReviewHandoff(FinanceReviewKind.EMPLOYEE_SETTLEMENT,r.fundingRecordId!!)) else emptyList())
        }
        FinanceReadKind.EMPLOYEE_SETTLEMENT -> {
            val r = apis.employeeMoney.settlementStatus(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("تسوية موظف", r.state, listOf(
                "العملية: " + r.action,
                "القيمة: " + r.amount + " " + r.currency,
                "تسوية مسجلة: " + yesNo(r.settlementRecorded),
                "خصم راتب منفذ: " + yesNo(r.payrollDeductionInitiated),
            ))
        }
        FinanceReadKind.EMPLOYEE_REIMBURSEMENT -> {
            val r = apis.reimbursement.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.proposalId)
            FinanceSourceSnapshot("تعويض مصروف موظف", r.state, listOf(
                "القيمة: " + r.amount + " " + r.currency,
                "خصم بنكي خصص للمصروف: " + yesNo(r.bankDebitAllocatedToEmployeeExpense),
                "تحويل جديد بدأه النظام: " + yesNo(r.bankTransferInitiated),
                "اعتراف بالمصروف في GL: " + yesNo(r.expenseRecognizedInGl),
            ))
        }
        FinanceReadKind.PERIOD_CLOSE -> {
            val r = apis.periodClose.status(organizationId, objectId, installationId)
            checked(r.organizationId, r.periodId)
            FinanceSourceSnapshot("إقفال فترة", r.state, listOf(
                "القيود في اللقطة: " + r.journalCount,
                "الفترة مقفولة: " + yesNo(r.periodClosed),
                "تحويل أموال: " + yesNo(r.moneyTransferred),
                "مطابقة بنكية: " + yesNo(r.bankReconciled),
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
        if (apAllowed) true else try {
            apis.supplierInvoices.list(organizationId, installationId)
            true // even an empty list proves this original current-org GET succeeded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
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
            mutableStateOf(true)
        }
        var ledgerError by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        LaunchedEffect(identityId, organizationId, installationId, ledgerRefresh) {
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
        LaunchedEffect(identityId, organizationId, installationId,
            lookupKind, requestedId, lookupRevision
        ) {
            val kind = lookupKind ?: return@LaunchedEffect
            val id = requestedId ?: return@LaunchedEffect
            lookupLoading = true
            lookupResult = null
            lookupError = null
            try {
                lookupResult = financeReadSourceSnapshot(
                    apis, kind, organizationId, id, installationId,
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
            Text("المراجعة المالية · بيانات مصدرية",
                style = MaterialTheme.typography.titleMedium)
            Text(if (reviewPreparationEnabled) "قراءة وتجهيز ومتابعة التسجيل الداخلي المعتمد حسب صلاحيات المصدر." else "قراءة داخلية فقط.",
                style = MaterialTheme.typography.bodySmall)
            Text("سجل الموردين · قيود تاريخية مثبتة",
                style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = !ledgerLoading, onClick = { ledgerRefresh += 1 }) {
                Text(if (ledgerLoading) "جارٍ تحديث المصدر…" else "تحديث سجل الموردين")
            }
            if (ledgerLoading) Text("جارٍ تحميل قيود الموردين المسموح بها…")
            ledgerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ledger?.let { current ->
                when {
                    !current.sourceAvailable -> Text(
                        "مصدر القيود غير متاح؛ المستحقات الفعلية مجهولة وليست صفرًا.")
                    current.groups.isEmpty() -> Text(
                        "لا توجد قيود في نطاق المصدر المعروض؛ هذا لا يثبت عدم وجود التزامات.")
                    else -> current.groups.forEach { item ->
                        Text("مورد " + item.supplierOrganizationId.take(8) +
                            " · إجمالي قيود تاريخية: " +
                            item.historicallyRecognizedGross + " " + item.currency)
                        Text("عدد القيود: " + item.postedJournalCount +
                            " · المدفوع والمتبقي غير مثبتين بهذا المصدر.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (current.hasMoreGroups) {
                    Text("هذه أول " + current.queryLimit +
                        " مجموعة فقط، وليست ميزان مراجعة كاملًا.")
                }
            }

            if (reviewPreparationEnabled) {
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

            Text("تتبّع عملية برقم مصدرها", style = MaterialTheme.typography.titleSmall)
            Text("اختر نوع السجل، ثم أدخل UUID أصليًا معروفًا من العملية. كل قراءة تعيد التحقق من صلاحية السيرفر الحالية.",
                style = MaterialTheme.typography.bodySmall)
            listOf(
                "البنك والتخصيص" to listOf(
                    FinanceReadKind.BANK_INTAKE, FinanceReadKind.BANK_RECONCILIATION,
                    FinanceReadKind.BANK_ALLOCATION, FinanceReadKind.BANK_ALLOCATION_CORRECTION,
                ),
                "الخزنة الفعلية" to listOf(
                    FinanceReadKind.CASHBOX, FinanceReadKind.CASH_VOUCHER,
                    FinanceReadKind.CASH_COUNT,
                ),
                "فواتير ودفاتر الموردين والعملاء" to listOf(
                    FinanceReadKind.AP_JOURNAL, FinanceReadKind.AR_CANDIDATE,
                    FinanceReadKind.AR_INVOICE, FinanceReadKind.AR_JOURNAL,
                ),
                "الموظفون والإقفال" to listOf(
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
                        ) { Text(kind.label) }
                    }
                }
            }
            if (lookupKind == null) {
                Text("اختَر نوع العملية لاستعراض دليلها من المصدر.")
            } else {
                OutlinedTextField(
                    value = lookupText,
                    onValueChange = {
                        lookupText = it.trim()
                        requestedId = null
                        lookupResult = null
                        lookupError = null
                    },
                    label = { Text("معرّف السجل UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val valid = financeLookupIdIsUuid(lookupText)
                if (lookupText.isNotEmpty() && !valid) {
                    Text("أدخل UUID صحيحًا من المصدر الأصلي.")
                }
                OutlinedButton(
                    enabled = valid && !lookupLoading,
                    onClick = {
                        requestedId = lookupText
                        lookupRevision += 1
                    },
                ) { Text(if (lookupLoading) "جارٍ القراءة…" else "قراءة الحالة الحالية") }
                lookupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                lookupResult?.let { record ->
                    Text(record.title + " · " + record.state,
                        style = MaterialTheme.typography.titleSmall)
                    record.facts.forEach { Text(it) }
                    if(reviewPreparationEnabled) record.nextActions.forEach { action ->
                        OutlinedButton(enabled=handoff==null,onClick={handoff=action}) {Text("متابعة: "+action.kind.label)}
                    }
                    Text("الحالة قراءة فقط من API، ولا تعني إتمام حركة بنك أو اعتماد مستقل.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    },
)
