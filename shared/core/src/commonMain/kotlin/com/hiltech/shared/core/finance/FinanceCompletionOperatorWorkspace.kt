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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class FinanceCompletionKind(val label: String, val terminal: String) {
    AR_INVOICE("إصدار فاتورة عميل","RECORDED"),
    AP_JOURNAL("ترحيل قيد المورد","POSTED_ACCOUNTS_PAYABLE"),
    AR_JOURNAL("ترحيل قيد العميل","POSTED_ACCOUNTS_RECEIVABLE"),
    FUNDING("تسجيل سلفة أو عهدة","RECORDED"),
    SETTLEMENT("تسجيل تسوية موظف","RECORDED"),
    REIMBURSEMENT("تسجيل رد مصروف","RECORDED"),
    PERIOD_CLOSE("إقفال فترة محاسبية","CLOSED"),
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
        available
    },
    hostOwnsScroll = false,
    content = { _ ->
        key(identityId, organizationId, installationId) {
            CompletionOperatorSection(organizationId,installationId,apis,newOperationId)
        }
    },
)

@Composable
private fun CompletionOperatorSection(
    org: String, installation: String, apis: FinanceEvidenceReadApis, newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(FinanceCompletionKind.AR_INVOICE) }
    var items by remember { mutableStateOf<List<FinanceCompletionSource>>(emptyList()) }
    var selected by remember { mutableStateOf<FinanceCompletionSource?>(null) }
    var lookup by remember { mutableStateOf("") }
    var operationId by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(kind,revision) {
        busy = true
        items = emptyList()
        error = null
        try { items = financeCompletionQueue(apis,org,installation,kind) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "القائمة غير متاحة للدور الحالي أو تعذر الاتصال." }
        finally { busy = false }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "لم يؤكد المصدر العملية. حدّث الحالة قبل إعادة المحاولة." }
            finally { busy = false }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("إتمام العمليات المالية المعتمدة",style=MaterialTheme.typography.titleMedium)
        Text("تسجيل داخلي وترحيل محاسبي وفق موافقة الشركة. لا ينشئ تحويلًا بنكيًا أو إرسال فاتورة خارجيًا.")
        Row(modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FinanceCompletionKind.entries.forEach { option ->
                OutlinedButton(enabled=!busy && kind!=option,onClick={
                    kind=option; selected=null; lookup=""; operationId=null; confirmation=false
                }) { Text(option.label) }
            }
        }
        OutlinedButton(enabled=!busy,onClick={revision++}) { Text("تحديث قائمة العمليات المعتمدة") }
        if (busy) Text("جارٍ الاتصال بالمصدر…")
        if (items.isEmpty() && !busy && error==null) Text("لا توجد عمليات جاهزة في القائمة (حتى 100 عملية).")
        items.forEach { item ->
            OutlinedButton(enabled=!busy,onClick={
                selected=item; lookup=item.id; operationId=null; confirmation=false
            }) { Text(item.details.firstOrNull().orEmpty()+" · "+item.id.take(8)) }
        }
        OutlinedTextField(value=lookup,onValueChange={
            lookup=it.trim(); selected=null; operationId=null; confirmation=false
        },enabled=!busy,singleLine=true,label={Text("معرّف العملية للمتابعة (UUID)")},
            modifier=Modifier.fillMaxWidth())
        OutlinedButton(enabled=!busy && financeLookupIdIsUuid(lookup),onClick={
            perform {
                val fresh=financeCompletionRead(apis,org,installation,kind,lookup)
                if (fresh!=selected) operationId=null
                selected=fresh; confirmation=false
            }
        }) { Text("قراءة الحالة الحالية من المصدر") }
        selected?.let { source ->
            Text(source.kind.label,style=MaterialTheme.typography.titleSmall)
            Text("الحالة: "+source.state)
            source.details.forEach { Text(it) }
            source.approvalId?.let {Text("الموافقة: "+it)}
            source.resultId?.let {Text("معرّف العملية المسجلة: "+it)}
            if (financeCompletionReady(source,org)) {
                if (!confirmation) {
                    OutlinedButton(enabled=!busy,onClick={confirmation=true}) {
                        Text("مراجعة قبل "+source.kind.label)
                    }
                } else {
                    Text("سيتم تنفيذ "+source.kind.label+" داخليًا وفق الموافقة الظاهرة. تحقق من المصدر قبل التأكيد.")
                    OutlinedButton(enabled=!busy,onClick={confirmation=false}) {Text("رجوع")}
                    OutlinedButton(enabled=!busy,onClick={
                        val op=operationId ?: newOperationId().also {operationId=it}
                        perform {
                            selected=financeCompleteApprovedSource(apis,org,installation,source,op)
                            items=items.filterNot {it.id==source.id}
                            confirmation=false
                        }
                    }) {Text("تأكيد "+source.kind.label)}
                }
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
