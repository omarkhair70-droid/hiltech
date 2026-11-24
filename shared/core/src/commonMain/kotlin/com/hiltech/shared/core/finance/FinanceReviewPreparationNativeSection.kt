package com.hiltech.shared.core.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
    INVOICE_ISSUANCE_REVIEW("مراجعة فاتورة موجودة"),
    INVOICE_ISSUANCE_PREPARE("تجهيز إصدار فاتورة عميل"),
}

data class FinanceReviewDraft(
    val sourceId: String = "",
    val bankLineId: String = "",
    val sourceKind: String = "AR",
    val amount: String = "",
    val reference: String = "",
    val documentEvidenceId: String = "",
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
        FinanceReviewKind.INVOICE_ISSUANCE_REVIEW -> true
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
        FinanceReviewKind.CASH_COUNT ->
            financeReviewIsAmount(draft.amount, allowZero = true) &&
                draft.reference.trim().length in 8..180
    }
}

fun financeReviewCanRequest(state: String, approvalRequestId: String?): Boolean =
    state == "PROPOSED" && approvalRequestId.isNullOrBlank()

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
        FinanceReviewKind.BANK_STATEMENT_REVIEW ->
            apis.bankIntake.status(org, proposalId, install).review()
        FinanceReviewKind.BANK_RECONCILIATION ->
            apis.reconciliation.status(org, proposalId, install).review()
        FinanceReviewKind.CASH_ALLOCATION ->
            apis.allocation.status(org, proposalId, install).review()
        FinanceReviewKind.CASH_ALLOCATION_CORRECTION ->
            apis.allocationCorrection.status(org, proposalId, install).review()
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
        FinanceReviewKind.INVOICE_ISSUANCE_REVIEW ->
            return financeReadReviewProposal(apis, kind, org, form.sourceId, install)
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
    check(financeReviewCanRequest(current.state, current.approvalRequestId)) {
        "FINANCE_REVIEW_NOT_PROPOSED"
    }
    val response = when (kind) {
        FinanceReviewKind.BANK_STATEMENT_REVIEW ->
            apis.bankIntake.requestReview(org, proposalId, operationId, install).review()
        FinanceReviewKind.BANK_RECONCILIATION ->
            apis.reconciliation.requestReview(org, proposalId, operationId, install).review()
        FinanceReviewKind.CASH_ALLOCATION ->
            apis.allocation.requestApproval(org, proposalId, operationId, install).review()
        FinanceReviewKind.CASH_ALLOCATION_CORRECTION ->
            apis.allocationCorrection.requestReview(org, proposalId, operationId, install).review()
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
) {
    // A keyed state means an account/company/installation switch removes any
    // outstanding request, source evidence, error and in-memory draft.
    var expanded by remember(identityId, organizationId, installationId) {
        mutableStateOf(false)
    }
    var kind by remember(identityId, organizationId, installationId) {
        mutableStateOf(FinanceReviewKind.BANK_RECONCILIATION)
    }
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
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FinanceReviewKind.entries.forEach { option ->
                OutlinedButton(enabled = !preparing && !requestingReview &&
                    option != kind, onClick = { kind = option }) {
                    Text(option.label)
                }
            }
        }
        Text("1. المصدر   ←   2. تجهيز المقترح   ←   3. طلب مراجعة مستقلة",
            style = MaterialTheme.typography.labelMedium)
        Text(kind.label, style = MaterialTheme.typography.titleSmall)
        val existing = kind == FinanceReviewKind.BANK_STATEMENT_REVIEW ||
            kind == FinanceReviewKind.INVOICE_ISSUANCE_REVIEW
        val sourceLabel = when (kind) {
            FinanceReviewKind.BANK_STATEMENT_REVIEW -> "معرّف طلب كشف البنك (Proposal UUID)"
            FinanceReviewKind.BANK_RECONCILIATION -> "معرّف كشف البنك المسجل (Intake UUID)"
            FinanceReviewKind.CASH_ALLOCATION -> "معرّف قيد AP/AR المرحّل (Journal UUID)"
            FinanceReviewKind.CASH_ALLOCATION_CORRECTION -> "معرّف التخصيص الأصلي (Allocation UUID)"
            FinanceReviewKind.CASH_COUNT -> "معرّف الخزنة الحالي (Cashbox UUID)"
            FinanceReviewKind.INVOICE_ISSUANCE_REVIEW -> "معرّف طلب إصدار فاتورة قائم (Proposal UUID)"
            FinanceReviewKind.INVOICE_ISSUANCE_PREPARE -> "معرّف مسودة فاتورة العميل (Candidate UUID)"
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
            kind == FinanceReviewKind.CASH_ALLOCATION
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
        if (kind == FinanceReviewKind.INVOICE_ISSUANCE_PREPARE) {
            OutlinedTextField(
                value = draft.documentEvidenceId,
                onValueChange = { draft = draft.copy(documentEvidenceId = it.trim()); resetForFormChange() },
                label = { Text("معرّف مستند الفاتورة المحفوظ والجاهز (Evidence UUID)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !preparing && !requestingReview && prepared == null,
            )
        }
        if (!existing) {
            OutlinedTextField(
                value = draft.reference,
                onValueChange = {
                    draft = draft.copy(reference = it); resetForFormChange()
                },
                label = { Text(when (kind) {
                    FinanceReviewKind.BANK_RECONCILIATION -> "مرجع المطابقة المعتمد من الشركة"
                    FinanceReviewKind.INVOICE_ISSUANCE_PREPARE -> "رقم فاتورة الشركة"
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
                financeReviewCanRequest(current.state, current.approvalRequestId)
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
