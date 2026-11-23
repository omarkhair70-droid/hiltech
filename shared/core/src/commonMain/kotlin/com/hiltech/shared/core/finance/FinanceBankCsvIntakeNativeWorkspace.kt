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

private data class BankCsvNativeSelection(
    val accountId: String,
    val bankIntakeOperationId: String,
    val proposalId: String,
    val reserveOperationId: String,
    val finalizeOperationId: String,
    val file: FinanceBankCsvSelectedFile,
)

fun financeBankMoneyIsValid(value: String): Boolean =
    Regex("^(0|[1-9][0-9]{0,23})(\\.[0-9]{1,6})?$").matches(value)

fun financeBankDateRangeIsValid(start: String, end: String): Boolean =
    Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(start) &&
        Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(end) &&
        start <= end

/**
 * Dedicated authorized Finance bank workflow, not hidden behind AP/supplier
 * invoice permissions. The original bank-register GET grants visibility.
 * Server still authorizes EVERY command for its specific Finance role.
 */
fun financeBankCsvIntakeNativeWorkspace(
    identityId: String,
    organizationId: String,
    installationId: String,
    api: FinanceBankStatementIntakeApiClient,
    uploader: FinanceBankCsvEvidenceUploader,
    pickCsvFile: suspend () -> FinanceBankCsvSelectedFile?,
    newOperationId: () -> String,
    deriveProposalId: (String) -> String,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "finance-bank-statement",
    label = "كشف البنك والتحصيلات",
    canReadSource = {
        api.listApprovedAccounts(organizationId, installationId)
        true // An empty approved-bank list is still a successful authorized GET.
    },
    hostOwnsScroll = false,
    content = { _ ->
        key(identityId, organizationId, installationId) {
            FinanceBankCsvIntakeSection(
                organizationId = organizationId,
                installationId = installationId,
                api = api,
                uploader = uploader,
                pickCsvFile = pickCsvFile,
                newOperationId = newOperationId,
                deriveProposalId = deriveProposalId,
            )
        }
    },
)

@Composable
private fun FinanceBankCsvIntakeSection(
    organizationId: String,
    installationId: String,
    api: FinanceBankStatementIntakeApiClient,
    uploader: FinanceBankCsvEvidenceUploader,
    pickCsvFile: suspend () -> FinanceBankCsvSelectedFile?,
    newOperationId: () -> String,
    deriveProposalId: (String) -> String,
) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<FinanceBankApprovedAccountDto>>(emptyList()) }
    var loadingAccounts by remember { mutableStateOf(true) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var selectedAccount by remember { mutableStateOf<FinanceBankApprovedAccountDto?>(null) }
    var selection by remember { mutableStateOf<BankCsvNativeSelection?>(null) }
    var verified by remember { mutableStateOf<VerifiedFinanceBankCsvEvidence?>(null) }
    var receipt by remember { mutableStateOf<FinanceBankIntakeReceiptDto?>(null) }
    var periodStart by remember { mutableStateOf("") }
    var periodEnd by remember { mutableStateOf("") }
    var openingBalance by remember { mutableStateOf("") }
    var closingBalance by remember { mutableStateOf("") }
    var existingProposal by remember { mutableStateOf("") }
    var reviewOperation by remember { mutableStateOf<String?>(null) }
    var recordOperation by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(organizationId, installationId) {
        loadingAccounts = true
        sourceError = null
        try {
            val result = api.listApprovedAccounts(organizationId, installationId)
            check(result.all { it.state == "APPROVED" })
            accounts = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            accounts = emptyList()
            sourceError = "مصدر الحسابات البنكية غير متاح أو ليس لديك صلاحية."
        } finally {
            loadingAccounts = false
        }
    }

    fun doAction(name: String, action: suspend () -> Unit) {
        if (busy != null) return
        scope.launch {
            busy = name
            error = null
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "لم تؤكد الخدمة تنفيذ الخطوة. راجع الحالة من المصدر قبل المحاولة مجددًا."
            } finally {
                busy = null
            }
        }
    }

    fun verifyReceipt(candidate: FinanceBankIntakeReceiptDto, expected: String) {
        check(candidate.organizationId.equals(organizationId, ignoreCase = true) &&
            candidate.proposalId.equals(expected, ignoreCase = true)
        ) { "FINANCE_BANK_ORG_PROPOSAL_CHANGED" }
        receipt = candidate
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("كشف البنك · استيراد ومراجعة وتسجيل",
            style = MaterialTheme.typography.titleMedium)
        Text("مستند البنك أولًا، ثم طلب الاستيراد، ثم مراجع مستقل، ثم تسجيل داخلي بواسطة صاحب الصلاحية. التسجيل لا يحوّل أموالًا.",
            style = MaterialTheme.typography.bodySmall)

        Text("1. الحساب البنكي المعتمد", style = MaterialTheme.typography.titleSmall)
        if (loadingAccounts) Text("جارٍ قراءة الحسابات المصرح بها…")
        sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!loadingAccounts && sourceError == null && accounts.isEmpty()) {
            Text("لا توجد حسابات بنكية معتمدة في هذا النطاق.")
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            accounts.forEach { account ->
                OutlinedButton(
                    enabled = busy == null && account.id != selectedAccount?.id,
                    onClick = {
                        selectedAccount = account
                        // A different account must never inherit old Evidence
                        // reservations, PDF/CSV bytes, idempotency or receipts.
                        selection = null
                        verified = null
                        receipt = null
                        reviewOperation = null
                        recordOperation = null
                        error = null
                    },
                ) {
                    Text(account.bankName + " · " + account.maskedAccountReference +
                        " · " + account.currency)
                }
            }
        }

        selectedAccount?.let { bank ->
            Text("2. كشف CSV الأصلي", style = MaterialTheme.typography.titleSmall)
            Text("الحساب المختار: " + bank.bankName + " (" +
                bank.maskedAccountReference + ")")
            OutlinedButton(enabled = busy == null, onClick = {
                doAction("pick") {
                    val file = pickCsvFile() ?: return@doAction
                    require(file.bytes.size in 1..(2 * 1024 * 1024) &&
                        file.sha256.matches(Regex("^[a-f0-9]{64}$")) &&
                        file.originalFileName.endsWith(".csv", ignoreCase = true))
                    val intakeOperation = newOperationId()
                    val proposalId = deriveProposalId(intakeOperation)
                    check(financeLookupIdIsUuid(proposalId))
                    selection = BankCsvNativeSelection(
                        bank.id, intakeOperation, proposalId,
                        newOperationId(), newOperationId(), file,
                    )
                    verified = null
                    receipt = null
                    reviewOperation = null
                    recordOperation = null
                }
            }) { Text(if (busy == "pick") "جارٍ اختيار الملف…" else "اختيار كشف CSV من الجهاز") }
            selection?.let { file ->
                Text(file.file.originalFileName +
                    " · " + file.file.bytes.size + " بايت",
                    style = MaterialTheme.typography.bodySmall)
                Text("SHA-256: " + file.file.sha256.take(14) + "…",
                    style = MaterialTheme.typography.bodySmall)
            }

            Text("3. الفترة والأرصدة من نفس الكشف",
                style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = periodStart,
                onValueChange = { periodStart = it.trim(); receipt = null },
                label = { Text("بداية الكشف YYYY-MM-DD") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = busy == null && receipt == null,
            )
            OutlinedTextField(
                value = periodEnd,
                onValueChange = { periodEnd = it.trim(); receipt = null },
                label = { Text("نهاية الكشف YYYY-MM-DD") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = busy == null && receipt == null,
            )
            OutlinedTextField(
                value = openingBalance,
                onValueChange = { openingBalance = it.trim(); receipt = null },
                label = { Text("الرصيد الافتتاحي " + bank.currency) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = busy == null && receipt == null,
            )
            OutlinedTextField(
                value = closingBalance,
                onValueChange = { closingBalance = it.trim(); receipt = null },
                label = { Text("الرصيد الختامي " + bank.currency) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = busy == null && receipt == null,
            )
            val valid = financeBankDateRangeIsValid(periodStart, periodEnd) &&
                financeBankMoneyIsValid(openingBalance) &&
                financeBankMoneyIsValid(closingBalance)
            if (selection != null) {
                OutlinedButton(
                    enabled = busy == null && verified == null,
                    onClick = {
                        doAction("upload") {
                            val selected = requireNotNull(selection)
                            val ready = uploader.upload(
                                FinanceBankCsvEvidenceUploadInput(
                                    organizationId, bank.id,
                                    selected.bankIntakeOperationId, selected.proposalId,
                                    selected.reserveOperationId, selected.finalizeOperationId,
                                    selected.file,
                                ), installationId,
                            )
                            verified = ready
                        }
                    },
                ) {
                    Text(if (busy == "upload") "جارٍ التحقق من الملف…" else
                        "رفع الكشف وتوثيق Evidence الخاص")
                }
                if (verified != null) {
                    Text("المستند الأصلي: READY · محفوظ ومطابق للبصمة")
                }
                OutlinedButton(
                    enabled = busy == null && valid && verified != null &&
                        receipt == null,
                    onClick = {
                        doAction("prepare") {
                            val selected = requireNotNull(selection)
                            val bound = requireNotNull(verified)
                            check(selected.accountId == bank.id)
                            verifyReceipt(
                                uploader.prepareSource(
                                    bound, selected.file, periodStart, periodEnd,
                                    openingBalance, closingBalance, installationId,
                                ), selected.proposalId,
                            )
                        }
                    },
                ) {
                    Text(if (busy == "prepare") "جارٍ تجهيز الاستيراد…" else
                        "تحليل CSV وتجهيز طلب الاستيراد")
                }
            }
        }

        Text("4. متابعة طلب موجود / مراجعة مستقلة / تسجيل",
            style = MaterialTheme.typography.titleSmall)
        Text("صاحب صلاحية التسجيل يفتح معرّف الطلب الموافق عليه. لا تُنشئ هذه الخطوة حركة بنكية.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = existingProposal,
            onValueChange = { existingProposal = it.trim() },
            label = { Text("معرّف طلب الكشف (UUID)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = busy == null && financeLookupIdIsUuid(existingProposal),
            onClick = {
                doAction("status") {
                    val source = api.status(organizationId, existingProposal, installationId)
                    verifyReceipt(source, existingProposal)
                    reviewOperation = null
                    recordOperation = null
                }
            },
        ) { Text("فتح الطلب من المصدر") }

        receipt?.let { current ->
            Text("الحالة المؤكدة: " + current.state,
                style = MaterialTheme.typography.titleSmall)
            Text("عدد حركات كشف البنك: " + current.lineCount)
            Text("طلب: " + current.proposalId, style = MaterialTheme.typography.bodySmall)
            Text("مراجعة مستقلة: " +
                (current.approvalRequestId ?: "لم تطلب بعد"))
            Text("تسجيل داخلي: " +
                if (current.internalBankStatementRecorded) "نعم" else "لا")
            if (current.state == "PROPOSED" && current.approvalRequestId == null) {
                OutlinedButton(enabled = busy == null, onClick = {
                    if (reviewOperation == null) reviewOperation = newOperationId()
                    doAction("review") {
                        val before = api.status(organizationId, current.proposalId, installationId)
                        check(before.state == "PROPOSED" &&
                            before.approvalRequestId == null &&
                            before.organizationId.equals(organizationId, ignoreCase = true))
                        verifyReceipt(
                            api.requestReview(
                                organizationId, before.proposalId,
                                requireNotNull(reviewOperation), installationId,
                            ), before.proposalId,
                        )
                    }
                }) { Text("طلب مراجعة مستقلة — ليس اعتمادًا") }
            }
            if (current.state == "APPROVED" &&
                !current.internalBankStatementRecorded
            ) {
                OutlinedButton(enabled = busy == null, onClick = {
                    if (recordOperation == null) recordOperation = newOperationId()
                    doAction("record") {
                        val before = api.status(organizationId, current.proposalId, installationId)
                        check(before.state == "APPROVED" &&
                            !before.internalBankStatementRecorded &&
                            before.organizationId.equals(organizationId, ignoreCase = true))
                        verifyReceipt(
                            api.record(
                                organizationId, before.proposalId,
                                requireNotNull(recordOperation), installationId,
                            ), before.proposalId,
                        )
                    }
                }) {
                    Text("تسجيل الكشف المعتمد في دفاتر الشركة")
                }
            }
            if (current.state == "PENDING") {
                Text("المراجعة ما زالت معلقة؛ لا يجوز تسجيل الكشف قبل الاعتماد.")
            }
            OutlinedButton(enabled = busy == null, onClick = {
                doAction("refresh") {
                    verifyReceipt(
                        api.status(organizationId, current.proposalId, installationId),
                        current.proposalId,
                    )
                }
            }) { Text("تحديث حالة الطلب من المصدر") }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Text("إذا فشل الاتصال بعد أمر، حدّث الحالة أولًا. معرفات المحاولة تظل ثابتة.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
