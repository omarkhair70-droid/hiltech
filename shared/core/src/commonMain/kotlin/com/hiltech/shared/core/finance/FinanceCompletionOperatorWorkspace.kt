package com.hiltech.shared.core.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class FinanceCompletionKind(val label: String, val terminal: String) {
    AR_INVOICE("إصدار فاتورة عميل","RECORDED"),
    AP_JOURNAL("ترحيل قيد المورد","POSTED_ACCOUNTS_PAYABLE"),
    AR_JOURNAL("ترحيل قيد العميل","POSTED_ACCOUNTS_RECEIVABLE"),
    FUNDING("تسجيل سلفة أو عهدة","RECORDED"),
    SETTLEMENT("تسجيل تسوية موظف","RECORDED"),
    REIMBURSEMENT("تسجيل رد مصروف","RECORDED"),
    PERIOD_CLOSE("إقفال فترة محاسبية","CLOSED"),
    CASH_ALLOCATION("تسجيل تخصيص تحصيل أو سداد","RECORDED"),
    CASH_CORRECTION("تسجيل تصحيح تخصيص","CORRECTED"),
}

/** Facts are from typed authoritative receipts, never editable client balances. */
data class FinanceCompletionSource(
    val kind: FinanceCompletionKind,
    val id: String,
    val org: String,
    val state: String,
    val approvalId: String?,
    val resultId: String?,
    val fingerprintParts: List<String>,
    val details: List<String>,
)

private fun FinanceArInvoiceIssuanceReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.AR_INVOICE, proposalId, organizationId, state, approvalRequestId, issuedInvoiceId,
    listOf(candidateId.toString(), invoiceNumber.toString()),
    listOf("مرشح الفاتورة: " + candidateId, "رقم الفاتورة: " + invoiceNumber),
)

private fun FinanceApPostingReceiptDto.completionSource(org: String) = FinanceCompletionSource(
    FinanceCompletionKind.AP_JOURNAL, proposalId, org, state, postingReviewRequestId, postedJournalId,
    listOf(payableCandidateId.toString(), sourceVersion.toString(), ledgerFingerprintSha256.toString(), invoiceAmount.toString(), currency.toString()),
    listOf("المصدر: " + payableCandidateId, "القيمة: " + invoiceAmount + " " + currency, "بصمة القيد: " + ledgerFingerprintSha256),
)

private fun FinanceArJournalReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.AR_JOURNAL, proposalId, organizationId, state, approvalRequestId, postedJournalId,
    listOf(issuedInvoiceId.toString(), sourceVersion.toString(), ledgerFingerprintSha256.toString(), amount.toString(), currency.toString()),
    listOf("الفاتورة: " + issuedInvoiceId, "القيمة: " + amount + " " + currency, "بصمة القيد: " + ledgerFingerprintSha256),
)

private fun FinanceEmployeeFundingReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.FUNDING, proposalId, organizationId, state, approvalRequestId, fundingRecordId,
    listOf(kind.toString(), draftId.toString(), employeeId.toString(), bankStatementLineId.toString(), amount.toString(), currency.toString()),
    listOf("الموظف: " + employeeId, "النوع: " + kind, "القيمة: " + amount + " " + currency, "حركة البنك: " + bankStatementLineId),
)

private fun FinanceEmployeeSettlementReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.SETTLEMENT, proposalId, organizationId, state, approvalRequestId, settlementRecordId,
    listOf(fundingRecordId.toString(), employeeId.toString(), action.toString(), amount.toString(), currency.toString()),
    listOf("الموظف: " + employeeId, "العهدة/السلفة: " + fundingRecordId, "نوع التسوية: " + action, "القيمة: " + amount + " " + currency),
)

private fun FinanceExpenseReimbursementReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.REIMBURSEMENT, proposalId, organizationId, state, approvalRequestId, reimbursementRecordId,
    listOf(expenseDraftId.toString(), employeeId.toString(), bankStatementLineId.toString(), amount.toString(), currency.toString()),
    listOf("الموظف: " + employeeId, "المصروف: " + expenseDraftId, "القيمة: " + amount + " " + currency, "حركة البنك: " + bankStatementLineId),
)

private fun FinanceGlPeriodCloseReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.PERIOD_CLOSE, periodId, organizationId, state, approvalRequestId, periodClosureId,
    listOf(ledgerSnapshotSha256.toString(), journalCount.toString(), sourceVersion.toString()),
    listOf("عدد القيود: " + journalCount, "بصمة الدفتر: " + ledgerSnapshotSha256, "نسخة المصدر: " + sourceVersion),
)

private fun FinanceCashAllocationReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.CASH_ALLOCATION,proposalId,organizationId,state,approvalRequestId,recordedAllocationId,
    listOf(sourceKind,sourceJournalId,bankStatementLineId,amount,currency),
    listOf(if(sourceKind=="AR") "تحصيل عميل" else "سداد مورد","المبلغ: $amount $currency","القيد: $sourceJournalId","حركة البنك: $bankStatementLineId"),
)

private fun FinanceCashVoidReceiptDto.completionSource() = FinanceCompletionSource(
    FinanceCompletionKind.CASH_CORRECTION,proposalId,organizationId,state,approvalRequestId,correctionRecordId,
    listOf(originalAllocationId,sourceKind,sourceJournalId,bankStatementLineId,amount,currency),
    listOf("تصحيح تخصيص سابق","المبلغ: $amount $currency","التخصيص الأصلي: $originalAllocationId","حركة البنك: $bankStatementLineId"),
)

fun financeCompletionReady(source: FinanceCompletionSource, org: String): Boolean =
    source.org.equals(org, ignoreCase = true) &&
        financeLookupIdIsUuid(source.id) &&
        source.approvalId?.let(::financeLookupIdIsUuid) == true &&
        source.state == "APPROVED" && source.resultId == null

suspend fun financeCompletionQueue(
    apis: FinanceEvidenceReadApis, org: String, installation: String, kind: FinanceCompletionKind,
): List<FinanceCompletionSource> {
    val result = when (kind) {
        FinanceCompletionKind.AR_INVOICE -> apis.arIssuance.operatorQueue(org, installation).map { it.completionSource() }
        FinanceCompletionKind.AP_JOURNAL -> apis.apJournal.operatorQueue(org, installation).map { it.completionSource(org) }
        FinanceCompletionKind.AR_JOURNAL -> apis.arJournal.operatorQueue(org, installation).map { it.completionSource() }
        FinanceCompletionKind.FUNDING -> apis.employeeMoney.fundingOperatorQueue(org, installation).map { it.completionSource() }
        FinanceCompletionKind.SETTLEMENT -> apis.employeeMoney.settlementOperatorQueue(org, installation).map { it.completionSource() }
        FinanceCompletionKind.REIMBURSEMENT -> apis.reimbursement.operatorQueue(org, installation).map { it.completionSource() }
        FinanceCompletionKind.PERIOD_CLOSE -> apis.periodClose.operatorQueue(org, installation).map { it.completionSource() }
        FinanceCompletionKind.CASH_ALLOCATION -> apis.allocation.operatorQueue(org,installation).map {it.completionSource()}
        FinanceCompletionKind.CASH_CORRECTION -> apis.allocationCorrection.operatorQueue(org,installation).map {it.completionSource()}
    }
    check(result.all { it.kind == kind && financeCompletionReady(it,org) })
    return result
}

suspend fun financeCompletionRead(
    apis: FinanceEvidenceReadApis, org: String, installation: String,
    kind: FinanceCompletionKind, id: String,
): FinanceCompletionSource {
    require(financeLookupIdIsUuid(id))
    val source = when (kind) {
        FinanceCompletionKind.AR_INVOICE -> apis.arIssuance.status(org,id,installation).completionSource()
        FinanceCompletionKind.AP_JOURNAL -> apis.apJournal.status(org,id,installation).completionSource(org)
        FinanceCompletionKind.AR_JOURNAL -> apis.arJournal.status(org,id,installation).completionSource()
        FinanceCompletionKind.FUNDING -> apis.employeeMoney.fundingStatus(org,id,installation).completionSource()
        FinanceCompletionKind.SETTLEMENT -> apis.employeeMoney.settlementStatus(org,id,installation).completionSource()
        FinanceCompletionKind.REIMBURSEMENT -> apis.reimbursement.status(org,id,installation).completionSource()
        FinanceCompletionKind.PERIOD_CLOSE -> apis.periodClose.status(org,id,installation).completionSource()
        FinanceCompletionKind.CASH_ALLOCATION -> apis.allocation.status(org,id,installation).completionSource()
        FinanceCompletionKind.CASH_CORRECTION -> apis.allocationCorrection.status(org,id,installation).completionSource()
    }
    check(source.org.equals(org,ignoreCase=true) && source.id.equals(id,ignoreCase=true) &&
        source.kind == kind)
    return source
}

/** No role bypass: existing backend operator, exact independent approval and source locking. */
suspend fun financeCompleteApprovedSource(
    apis: FinanceEvidenceReadApis, org: String, installation: String,
    expected: FinanceCompletionSource, operationId: String,
): FinanceCompletionSource {
    require(financeLookupIdIsUuid(operationId))
    require(financeCompletionReady(expected,org))
    val current = financeCompletionRead(apis,org,installation,expected.kind,expected.id)
    check(current == expected && financeCompletionReady(current,org)) { "FINANCE_SOURCE_CHANGED" }
    val result = when (expected.kind) {
        FinanceCompletionKind.AR_INVOICE -> apis.arIssuance.record(org,expected.id,operationId,installation).completionSource()
        FinanceCompletionKind.AP_JOURNAL -> apis.apJournal.commitReviewedJournal(org,expected.id,operationId,installation).completionSource(org)
        FinanceCompletionKind.AR_JOURNAL -> apis.arJournal.post(org,expected.id,operationId,installation).completionSource()
        FinanceCompletionKind.FUNDING -> apis.employeeMoney.recordFunding(org,expected.id,operationId,installation).completionSource()
        FinanceCompletionKind.SETTLEMENT -> apis.employeeMoney.recordSettlement(org,expected.id,operationId,installation).completionSource()
        FinanceCompletionKind.REIMBURSEMENT -> apis.reimbursement.record(org,expected.id,operationId,installation).completionSource()
        FinanceCompletionKind.PERIOD_CLOSE -> apis.periodClose.close(org,expected.id,operationId,installation).completionSource()
        FinanceCompletionKind.CASH_ALLOCATION -> apis.allocation.record(org,expected.id,operationId,installation).also {
            check(it.cashAllocatedToAccountingSource && !it.moneyTransferredByHiltech && !it.bankReconciled)
        }.completionSource()
        FinanceCompletionKind.CASH_CORRECTION -> apis.allocationCorrection.record(org,expected.id,operationId,installation).also {
            check(it.originalRecordPreserved && !it.originalMatchStillEffective && !it.bankRefundInitiated && !it.generalLedgerReversed)
        }.completionSource()
    }
    check(result.kind == expected.kind && result.org.equals(org,ignoreCase=true) &&
        result.id == expected.id && result.approvalId == expected.approvalId &&
        result.fingerprintParts == expected.fingerprintParts &&
        result.state == expected.kind.terminal &&
        result.resultId?.let(::financeLookupIdIsUuid) == true
    ) { "FINANCE_FINAL_RECEIPT_UNCONFIRMED" }
    return result
}

fun financeCompletionOperatorWorkspace(
    identityId: String, organizationId: String, installationId: String,
    apis: FinanceEvidenceReadApis, newOperationId: () -> String,
    settlementLedger: FinanceSettlementLedgerApiClient? = null,
    onReauthenticate: (() -> Unit)? = null,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "finance-completion-operator",
    label = "إتمام العمليات المالية",
    canReadSource = {
        var available = false
        for (kind in FinanceCompletionKind.entries) {
            try {
                financeCompletionQueue(apis,organizationId,installationId,kind)
                available = true
                break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        if(!available) for(kind in listOf(FinanceReviewKind.CASH_VOUCHER,FinanceReviewKind.CASH_COUNT,FinanceReviewKind.BANK_RECONCILIATION)) {
            try {financeSignedQueue(apis,organizationId,installationId,kind);available=true;break}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){}
        }
        if(!available && settlementLedger!=null) {
            try {settlementLedger.queue(organizationId,0,installationId);available=true}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){}
        }
        available
    },
    hostOwnsScroll = false,
    content = { _ ->
        key(identityId, organizationId, installationId) {
            CompletionOperatorSection(identityId,organizationId,installationId,apis,newOperationId,settlementLedger,onReauthenticate)
        }
    },
)

/** A denied queue is not work for this account. An unreadable queue is not empty. */
internal data class FinanceDailyQueueSnapshot(
    val available: Map<FinanceCompletionKind, List<FinanceCompletionSource>>,
    val unavailable: Set<FinanceCompletionKind>,
) {
    val ready: List<FinanceCompletionSource> get() = available.values.flatten()
}

internal suspend fun financeDailyQueues(
    organizationId: String,
    read: suspend (FinanceCompletionKind) -> List<FinanceCompletionSource>,
): FinanceDailyQueueSnapshot = coroutineScope {
    val results = FinanceCompletionKind.entries.map { kind ->
        async {
            try {
                val rows = read(kind)
                check(rows.all { it.kind == kind && financeCompletionReady(it, organizationId) })
                check(rows.map { it.id.lowercase() }.distinct().size == rows.size)
                Triple(kind, rows, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val denied = (failure as? HiltechApiException)?.httpStatus in setOf(401, 403)
                Triple(kind, null, !denied)
            }
        }
    }.awaitAll()
    FinanceDailyQueueSnapshot(
        results.mapNotNull { (kind, rows, _) -> rows?.let { kind to it } }.toMap(),
        results.filter { it.third }.map { it.first }.toSet(),
    )
}

internal fun financeDailyAmount(source: FinanceCompletionSource): String? {
    val index = when (source.kind) {
        FinanceCompletionKind.AP_JOURNAL, FinanceCompletionKind.AR_JOURNAL -> 3
        FinanceCompletionKind.FUNDING -> 4
        FinanceCompletionKind.SETTLEMENT, FinanceCompletionKind.REIMBURSEMENT,
        FinanceCompletionKind.CASH_ALLOCATION -> 3
        FinanceCompletionKind.CASH_CORRECTION -> 4
        else -> return null
    }
    val amount = source.fingerprintParts.getOrNull(index) ?: return null
    val currency = source.fingerprintParts.getOrNull(index + 1) ?: return null
    if (!Regex("^[0-9]+([.][0-9]+)?$").matches(amount) || !Regex("^[A-Z]{3}$").matches(currency)) return null
    return "$amount $currency"
}

internal fun financeRecordedSourceMatches(expected: FinanceCompletionSource, actual: FinanceCompletionSource): Boolean =
    expected.kind == actual.kind && expected.id.equals(actual.id, true) &&
        expected.org.equals(actual.org, true) && expected.approvalId == actual.approvalId &&
        expected.fingerprintParts == actual.fingerprintParts && actual.state == expected.kind.terminal &&
        actual.resultId?.let(::financeLookupIdIsUuid) == true

private data class FinanceCompletionAttempt(val source: FinanceCompletionSource, val operationId: String)

@Composable
private fun financeDailyTitle(kind: FinanceCompletionKind): String = when (kind) {
    FinanceCompletionKind.AR_INVOICE -> hiltechText("إصدار فاتورة عميل", "Issue customer invoice")
    FinanceCompletionKind.AP_JOURNAL -> hiltechText("ترحيل قيد مورد", "Post supplier journal")
    FinanceCompletionKind.AR_JOURNAL -> hiltechText("ترحيل قيد عميل", "Post customer journal")
    FinanceCompletionKind.FUNDING -> hiltechText("تسجيل سلفة أو عهدة", "Record employee funding")
    FinanceCompletionKind.SETTLEMENT -> hiltechText("تسوية عهدة أو سلفة", "Record employee settlement")
    FinanceCompletionKind.REIMBURSEMENT -> hiltechText("رد مصروف", "Record reimbursement")
    FinanceCompletionKind.PERIOD_CLOSE -> hiltechText("إقفال فترة محاسبية", "Close accounting period")
    FinanceCompletionKind.CASH_ALLOCATION -> hiltechText("تخصيص تحصيل أو سداد", "Record cash allocation")
    FinanceCompletionKind.CASH_CORRECTION -> hiltechText("تصحيح تخصيص مالي", "Record allocation correction")
}

@Composable
private fun financeDailyState(source: FinanceCompletionSource): String = when {
    source.state == source.kind.terminal && source.resultId != null -> hiltechText("مسجل في المصدر", "Recorded at source")
    source.state == "APPROVED" -> hiltechText("معتمد · جاهز للتسجيل", "Approved · ready to record")
    source.state == "PENDING" -> hiltechText("بانتظار القرار", "Awaiting decision")
    source.state == "REJECTED" -> hiltechText("مرفوض", "Rejected")
    else -> hiltechText("غير جاهز للتسجيل", "Not ready to record")
}

@Composable
private fun CompletionOperatorSection(
    identityId: String, org: String, installation: String, apis: FinanceEvidenceReadApis, newOperationId: () -> String,
    settlementLedger: FinanceSettlementLedgerApiClient?,onReauthenticate:(()->Unit)?,
) {
    val scope = rememberCoroutineScope()
    val areaState=rememberSaveableStateHolder()
    var settlementAvailable by remember(settlementLedger) {mutableStateOf(false)}
    var settlementLocked by remember {mutableStateOf(false)}
    var area by remember { mutableStateOf("ACCOUNTING") }
    var snapshot by remember { mutableStateOf<FinanceDailyQueueSnapshot?>(null) }
    var filter by remember { mutableStateOf<FinanceCompletionKind?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FinanceCompletionSource?>(null) }
    var pending by remember { mutableStateOf<FinanceCompletionAttempt?>(null) }
    var retryAllowed by remember { mutableStateOf(false) }
    var sourceChanged by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var recordedNotice by remember { mutableStateOf(false) }
    var lookupOpen by remember { mutableStateOf(false) }
    var lookupKind by remember { mutableStateOf(FinanceCompletionKind.AR_INVOICE) }
    var lookup by remember { mutableStateOf("") }

    LaunchedEffect(settlementLedger,org,installation,revision) {
        settlementAvailable=false
        if(settlementLedger!=null) {
            try {settlementLedger.queue(org,0,installation);settlementAvailable=true}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){}
        }
    }
    LaunchedEffect(apis, org, installation, revision, area) {
        if (area != "ACCOUNTING") return@LaunchedEffect
        loading = true
        snapshot = null
        try {
            val data = financeDailyQueues(org) { kind -> financeCompletionQueue(apis, org, installation, kind) }
            snapshot = data
            if (filter !in data.available.keys) filter = null
            if (lookupKind !in data.available.keys) data.available.keys.firstOrNull()?.let { lookupKind = it }
            if (data.available.isEmpty() && pending == null) {
                for (kind in listOf(FinanceReviewKind.CASH_VOUCHER, FinanceReviewKind.CASH_COUNT, FinanceReviewKind.BANK_RECONCILIATION)) {
                    try {
                        financeSignedQueue(apis, org, installation, kind)
                        area = "SIGNED"
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
                if(area=="ACCOUNTING" && settlementLedger!=null) {
                    try {settlementLedger.queue(org,0,installation);settlementAvailable=true;area="SETTLEMENT_LEDGER"}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){}
                }
            }
        } finally { loading = false }
    }

    fun perform(action: suspend () -> Unit) {
        if (busy || loading) return
        busy = true
        error = false
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }

    fun acceptRecorded(source: FinanceCompletionSource) {
        selected = source
        pending = null
        retryAllowed = false
        sourceChanged = false
        recordedNotice = true
        snapshot = snapshot?.let { current ->
            current.copy(available = current.available.mapValues { (_, items) ->
                items.filterNot { it.kind == source.kind && it.id == source.id }
            })
        }
    }

    fun record(attempt: FinanceCompletionAttempt) {
        if (busy || loading) return
        pending = attempt
        retryAllowed = false
        sourceChanged = false
        recordedNotice = false
        perform {
            val receipt = financeCompleteApprovedSource(apis, org, installation, attempt.source, attempt.operationId)
            acceptRecorded(receipt)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(hiltechText("العمل المالي اليومي", "Daily finance desk"), style = MaterialTheme.typography.titleLarge)
        Text(hiltechText("راجع العمليات المعتمدة وسجّل أثرها الحقيقي. التسجيل هنا لا يرسل أموالًا أو فواتير خارج النظام.",
            "Review approved work and record its real accounting effect. Recording here does not send money or invoices externally."),
            style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = area == "ACCOUNTING", enabled = !busy && pending == null && !settlementLocked, onClick = {
                if (area != "ACCOUNTING") { area = "ACCOUNTING"; selected = null; recordedNotice = false }
            }, label = { Text(hiltechText("الترحيل والتسجيل", "Accounting & recording")) })
            FilterChip(selected = area == "SIGNED", enabled = !busy && pending == null && !settlementLocked, onClick = {
                if (area != "SIGNED") { area = "SIGNED"; selected = null; recordedNotice = false }
            }, label = { Text(hiltechText("الخزنة والجرد والمطابقة", "Cashbox, counts & reconciliation")) })
            if(settlementLedger!=null && (settlementAvailable || area=="SETTLEMENT_LEDGER"))
                FilterChip(selected=area=="SETTLEMENT_LEDGER",enabled=!busy && pending==null && !settlementLocked,
                    onClick={area="SETTLEMENT_LEDGER";selected=null;recordedNotice=false},
                    label={Text(hiltechText("قيود السداد والتحصيل","Settlement journals"))})
        }
        if(area=="SETTLEMENT_LEDGER" && settlementLedger!=null) {
            areaState.SaveableStateProvider("SETTLEMENT_LEDGER") {
                FinanceSettlementLedgerSection(settlementLedger,org,installation,newOperationId,onReauthenticate){settlementLocked=it}
            }
        } else if (area == "SIGNED") {
            FinanceSignedCompletionSection(identityId, org, installation, apis, newOperationId, embedded = true)
        } else {
            val data = snapshot
            val enabled = !busy && !loading && pending == null
            if (loading) Text(hiltechText("جارٍ تحديث قوائم العمل…", "Refreshing work queues…"))
            if (!loading && data != null) {
                Text(hiltechText("عمليات جاهزة في القوائم المتاحة", "Ready records in available queues") + " · " + data.ready.size,
                    style = MaterialTheme.typography.titleMedium)
                if (data.unavailable.isNotEmpty()) Text(hiltechText(
                    "بعض المصادر لم تؤكد حالتها؛ العدد يعبر فقط عن القوائم التي تم تحميلها.",
                    "Some sources could not be confirmed; the count covers loaded queues only."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (data.available.isEmpty()) Text(hiltechText("لا توجد قائمة مالية متاحة حاليًا لحسابك.", "No accounting queue is currently available to your account."))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = filter == null, enabled = enabled, onClick = { filter = null },
                        label = { Text(hiltechText("كل العمل", "All work")) })
                    data.available.forEach { (kind, rows) ->
                        FilterChip(selected = filter == kind, enabled = enabled, onClick = { filter = kind },
                            label = { Text(financeDailyTitle(kind) + " · " + rows.size) })
                    }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
                    label = { Text(hiltechText("بحث في القوائم المحملة", "Search loaded records")) })
                val visible = data.ready.filter { item ->
                    (filter == null || item.kind == filter) && (query.isBlank() ||
                        (item.details + listOf(item.kind.label, item.id)).any { it.contains(query.trim(), ignoreCase = true) })
                }
                if (visible.isEmpty() && data.available.isNotEmpty()) Text(hiltechText(
                    "لا توجد عمليات مطابقة في القوائم المحملة.", "No matching work in the loaded queues."))
                visible.forEach { item ->
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = {
                        selected = item; recordedNotice = false; error = false; sourceChanged = false
                    }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(financeDailyTitle(item.kind), style = MaterialTheme.typography.titleMedium)
                            financeDailyAmount(item)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            if (item.kind == FinanceCompletionKind.AR_INVOICE) {
                                item.fingerprintParts.getOrNull(1)?.takeUnless { it == "null" }?.let {
                                    Text(hiltechText("رقم الفاتورة: ", "Invoice number: ") + it)
                                }
                            }
                            Text(hiltechText("معتمد · افتح التفاصيل", "Approved · open details"), style = MaterialTheme.typography.bodySmall)
                            Text(item.id.take(8), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            TextButton(enabled = enabled, onClick = {
                selected = null; recordedNotice = false; error = false; loading = true; revision++
            }) { Text(hiltechText("تحديث قوائم العمل", "Refresh work queues")) }
            if (data != null && data.available.isNotEmpty()) {
                TextButton(enabled = enabled, onClick = { lookupOpen = !lookupOpen }) {
                    Text(if (lookupOpen) hiltechText("إخفاء البحث بمرجع العملية", "Hide reference lookup")
                        else hiltechText("متابعة عملية سابقة بمرجعها", "Find an earlier record by reference"))
                }
                if (lookupOpen) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        data.available.keys.forEach { kind ->
                            FilterChip(selected = lookupKind == kind, enabled = enabled, onClick = { lookupKind = kind },
                                label = { Text(financeDailyTitle(kind)) })
                        }
                    }
                    OutlinedTextField(lookup, { lookup = it.trim() }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                        label = { Text(hiltechText("مرجع العملية", "Record reference")) })
                    OutlinedButton(enabled = enabled && lookupKind in data.available.keys && financeLookupIdIsUuid(lookup),
                        onClick = { perform { selected = financeCompletionRead(apis, org, installation, lookupKind, lookup); recordedNotice = false } }) {
                        Text(hiltechText("قراءة السجل الحالي", "Read current record"))
                    }
                }
            }

            selected?.let { source ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(financeDailyTitle(source.kind), style = MaterialTheme.typography.titleMedium)
                    Text(financeDailyState(source))
                    financeDailyAmount(source)?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                    source.details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    source.approvalId?.let { Text(hiltechText("مرجع الاعتماد: ", "Approval reference: ") + it, style = MaterialTheme.typography.bodySmall) }
                    source.resultId?.let { Text(hiltechText("مرجع التسجيل: ", "Recorded reference: ") + it, style = MaterialTheme.typography.bodySmall) }
                    if (recordedNotice) Text(hiltechText("تأكد وجود التسجيل المطابق في المصدر.", "The matching recorded result is confirmed at source."),
                        color = MaterialTheme.colorScheme.primary)
                    val attempt = pending
                    if (attempt != null) {
                        Text(hiltechText("نتيجة الطلب السابق غير محسومة. تحقق من المصدر قبل أي إعادة إرسال.",
                            "The earlier request is unresolved. Check the source before any replay."))
                        OutlinedButton(enabled = !busy && !loading, onClick = {
                            perform {
                                retryAllowed = false
                                sourceChanged = false
                                val current = financeCompletionRead(apis, org, installation, attempt.source.kind, attempt.source.id)
                                when {
                                    financeRecordedSourceMatches(attempt.source, current) -> acceptRecorded(current)
                                    current == attempt.source && financeCompletionReady(current, org) -> retryAllowed = true
                                    else -> sourceChanged = true
                                }
                            }
                        }) { Text(hiltechText("التحقق من نتيجة الطلب", "Check request outcome")) }
                        if (retryAllowed) OutlinedButton(enabled = !busy && !loading, onClick = { record(attempt) }) {
                            Text(hiltechText("إعادة نفس الطلب", "Replay the same request"))
                        }
                        if (sourceChanged) Text(hiltechText("تغير المصدر. لن يعاد التسجيل بالبيانات القديمة.",
                            "The source changed. The earlier data will not be recorded again."), color = MaterialTheme.colorScheme.error)
                    } else if (financeCompletionReady(source, org)) {
                        Text(hiltechText("تأكيدك يسجل هذه العملية وفق الاعتماد الحالي؛ راجع تفاصيل المصدر أعلاه.",
                            "Your confirmation records this operation under its current approval; review the source above."))
                        OutlinedButton(enabled = enabled, onClick = { record(FinanceCompletionAttempt(source, newOperationId())) }) {
                            Text(hiltechText("تأكيد التسجيل", "Confirm recording"))
                        }
                    }
                    TextButton(enabled = enabled, onClick = { selected = null; recordedNotice = false; error = false }) {
                        Text(hiltechText("إغلاق التفاصيل", "Close details"))
                    }
                }
            }
            if (busy) Text(hiltechText("جارٍ تنفيذ الطلب…", "Processing request…"))
            if (error) Text(hiltechText("لم يؤكد المصدر النتيجة. تحقق من السجل الحالي.",
                "The source did not confirm the result. Check the current record."), color = MaterialTheme.colorScheme.error)
        }
    }
}
