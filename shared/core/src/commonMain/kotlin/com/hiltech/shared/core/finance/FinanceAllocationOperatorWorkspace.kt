package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

enum class AllocationOperatorKind { ALLOCATION, CORRECTION }

data class AllocationOperatorSource(
    val kind: AllocationOperatorKind,
    val proposalId: String,
    val organizationId: String,
    val state: String,
    val approvalId: String?,
    val sourceKind: String,
    val journalId: String,
    val bankLineId: String,
    val amount: String,
    val currency: String,
    val originalId: String?,
    val recordedId: String?,
)

private fun FinanceCashAllocationReceiptDto.operatorSource() =
    AllocationOperatorSource(
        AllocationOperatorKind.ALLOCATION, proposalId, organizationId, state,
        approvalRequestId, sourceKind, sourceJournalId, bankStatementLineId,
        amount, currency, null, recordedAllocationId,
    )

private fun FinanceCashVoidReceiptDto.operatorSource() =
    AllocationOperatorSource(
        AllocationOperatorKind.CORRECTION, proposalId, organizationId, state,
        approvalRequestId, sourceKind, sourceJournalId, bankStatementLineId,
        amount, currency, originalAllocationId, correctionRecordId,
    )

fun allocationOperatorSourceReady(source: AllocationOperatorSource, org: String): Boolean =
    source.organizationId.equals(org, ignoreCase = true) &&
        financeLookupIdIsUuid(source.proposalId) &&
        source.state == "APPROVED" &&
        source.approvalId?.let(::financeLookupIdIsUuid) == true &&
        source.recordedId == null

/** The original server validates role, distinct actors, current approval and locks again. */
suspend fun recordApprovedAllocation(
    apis: FinanceEvidenceReadApis,
    org: String,
    installation: String,
    expected: AllocationOperatorSource,
    operationId: String,
): AllocationOperatorSource {
    require(financeLookupIdIsUuid(operationId))
    require(allocationOperatorSourceReady(expected, org))
    val before = when (expected.kind) {
        AllocationOperatorKind.ALLOCATION ->
            apis.allocation.status(org, expected.proposalId, installation).operatorSource()
        AllocationOperatorKind.CORRECTION ->
            apis.allocationCorrection.status(org, expected.proposalId, installation).operatorSource()
    }
    check(before == expected && allocationOperatorSourceReady(before, org)) {
        "FINANCE_APPROVED_SOURCE_CHANGED"
    }
    val result = when (before.kind) {
        AllocationOperatorKind.ALLOCATION ->
            apis.allocation.record(org, before.proposalId, operationId, installation).operatorSource()
        AllocationOperatorKind.CORRECTION ->
            apis.allocationCorrection.record(org, before.proposalId, operationId, installation).operatorSource()
    }
    check(result.organizationId.equals(org, ignoreCase = true) &&
        result.proposalId == before.proposalId &&
        result.approvalId == before.approvalId &&
        result.sourceKind == before.sourceKind &&
        result.journalId == before.journalId &&
        result.bankLineId == before.bankLineId &&
        result.amount == before.amount && result.currency == before.currency &&
        result.originalId == before.originalId &&
        result.state == (if (before.kind == AllocationOperatorKind.ALLOCATION) "RECORDED" else "CORRECTED") &&
        result.recordedId?.let(::financeLookupIdIsUuid) == true
    ) { "FINANCE_RECORD_UNCONFIRMED" }
    return result
}

fun financeAllocationOperatorWorkspace(
    identityId: String,
    organizationId: String,
    installationId: String,
    apis: FinanceEvidenceReadApis,
    newOperationId: () -> String,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "finance-allocation-operator",
    label = "تسجيل التخصيصات المالية",
    canReadSource = {
        try {
            apis.allocation.operatorQueue(organizationId, installationId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            apis.allocationCorrection.operatorQueue(organizationId, installationId)
            true
        }
    },
    hostOwnsScroll = false,
    content = { _ ->
        key(identityId, organizationId, installationId) {
            AllocationOperatorSection(organizationId, installationId, apis, newOperationId)
        }
    },
)

@Composable
private fun AllocationOperatorSection(
    org: String,
    installation: String,
    apis: FinanceEvidenceReadApis,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(AllocationOperatorKind.ALLOCATION) }
    var items by remember { mutableStateOf<List<AllocationOperatorSource>>(emptyList()) }
    var selected by remember { mutableStateOf<AllocationOperatorSource?>(null) }
    var operation by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(kind, revision) {
        busy = true
        items = emptyList()
        error = null
        try {
            val fetched = when (kind) {
                AllocationOperatorKind.ALLOCATION ->
                    apis.allocation.operatorQueue(org, installation).map { it.operatorSource() }
                AllocationOperatorKind.CORRECTION ->
                    apis.allocationCorrection.operatorQueue(org, installation).map { it.operatorSource() }
            }
            check(fetched.all { allocationOperatorSourceReady(it, org) })
            items = fetched
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "تعذر قراءة قائمة التسجيل أو ليست لديك صلاحية هذا النوع."
        } finally {
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("تسجيل التخصيصات المعتمدة", style = MaterialTheme.typography.titleMedium)
        Text("تسجيل محاسبي لحركة بنك موجودة فقط. لا يُنشئ تحويلًا أو استردادًا بنكيًا.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AllocationOperatorKind.entries.forEach { option ->
                OutlinedButton(enabled = !busy && kind != option, onClick = {
                    selected = null
                    operation = null
                    kind = option
                }) { Text(if (option == AllocationOperatorKind.ALLOCATION) "تخصيص" else "تصحيح") }
            }
        }
        OutlinedButton(enabled = !busy, onClick = { revision++ }) { Text("تحديث القائمة") }
        if (busy) Text("جارٍ الاتصال بالمصدر…")
        if (!busy && items.isEmpty() && error == null) {
            Text("لا توجد عمليات جاهزة ضمن أحدث 100 مقترح غير مسجل.")
        }
        items.forEach { item ->
            OutlinedButton(enabled = !busy, onClick = {
                selected = item
                operation = null
            }) {
                Text(item.sourceKind + " · " + item.amount + " " + item.currency +
                    " · " + item.proposalId.take(8))
            }
        }
        selected?.let { current ->
            Text("المقترح: " + current.proposalId)
            Text("الحالة: " + current.state)
            Text("قيد: " + current.journalId)
            Text("حركة البنك: " + current.bankLineId)
            Text("القيمة: " + current.amount + " " + current.currency)
            current.originalId?.let { Text("التخصيص الأصلي: " + it) }
            current.approvalId?.let { Text("الموافقة المستقلة: " + it) }
            current.recordedId?.let { Text("معرّف التسجيل المؤكد: " + it) }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val fresh = when (current.kind) {
                            AllocationOperatorKind.ALLOCATION ->
                                apis.allocation.status(org, current.proposalId, installation).operatorSource()
                            AllocationOperatorKind.CORRECTION ->
                                apis.allocationCorrection.status(org, current.proposalId, installation).operatorSource()
                        }
                        check(fresh.organizationId.equals(org, ignoreCase = true) &&
                            fresh.proposalId == current.proposalId)
                        if (fresh != current) operation = null
                        selected = fresh
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "تعذر تأكيد الحالة الحالية من المصدر."
                    } finally { busy = false }
                }
            }) { Text("تحقق من حالة المقترح") }
            if (allocationOperatorSourceReady(current, org)) {
                OutlinedButton(enabled = !busy, onClick = {
                    val op = operation ?: newOperationId().also { operation = it }
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            selected = recordApprovedAllocation(apis, org, installation, current, op)
                            items = items.filterNot { it.proposalId == current.proposalId }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "لم يتأكد التسجيل. حدّث الحالة قبل إعادة المحاولة بنفس العملية."
                        } finally { busy = false }
                    }
                }) { Text("تسجيل محاسبي معتمد") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
