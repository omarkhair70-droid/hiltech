package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Buyer preparation and correction resume from the current immutable source. */
@Composable
fun PurchaseOrderCandidateAction(
    api: PurchaseOrderCandidateApiClient,
    rfqId: String,
    quote: SupplierQuoteComparisonRowDto,
    installationId: String,
    newOperationId: () -> String,
    approvalRequestEnabled: Boolean = true,
) = key(api, rfqId, quote.quoteId, installationId) {
    val scope = rememberCoroutineScope()
    val commands = api.commands
    var preview by remember { mutableStateOf<PurchaseOrderCandidatePreviewDto?>(null) }
    var rationale by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var replacing by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<PurchaseCandidateCommand?>(null) }
    val pending = commands.pending?.takeIf { it.rfqId == rfqId && it.quoteId == quote.quoteId }
    val locked = commands.pending != null || commands.busy || loading

    suspend fun refresh() {
        loading = true; error = null
        try { preview = api.preview(rfqId, quote.quoteId, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { preview = null; error = (failure as? HiltechApiException)?.code ?: "CANDIDATE_LOAD_FAILED" }
        finally { loading = false }
    }
    suspend fun execute(command: PurchaseCandidateCommand) {
        try {
            when (command) {
                is PurchaseCandidateCommand.Propose -> commands.receipts[command.rfqId] = api.propose(command.rfqId, command.payload, installationId)
                is PurchaseCandidateCommand.Request -> commands.approvals[command.candidateId] = api.requestApproval(command.candidateId, command.payload, installationId)
            }
            commands.succeeded(command); replacing = false; rationale = ""; refresh()
        } catch (cancelled: CancellationException) { commands.failed(command, cancelled); throw cancelled }
        catch (failure: Exception) { commands.failed(command, failure); if (commands.pending == null) refresh() }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(enabled = !locked, onClick = { scope.launch { refresh() } }) {
            Text(if (preview == null) hiltechText("تجهيز مقترح الشراء", "Prepare purchase proposal") else hiltechText("تحديث حالة المقترح", "Refresh proposal status"))
        }
        if (loading) CircularProgressIndicator()
        pending?.let { command ->
            Text(hiltechText("نحتفظ بالطلب نفسه حتى يتم تأكيد النتيجة. لا تغيّر الاختيار أو السبب أثناء التحقق.",
                "The exact request is retained until its result is confirmed. Its selection and reason stay fixed while checking."))
            if (command is PurchaseCandidateCommand.Propose) Text(command.payload.rationale)
            if (commands.busy) CircularProgressIndicator()
            else Button(onClick = { commands.retry()?.let { scope.launch { execute(it) } } }) {
                Text(hiltechText("التحقق من نتيجة العملية", "Check operation result"))
            }
        }
        preview?.let { gate ->
            val existing = gate.existingCandidate
            existing?.let { current ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("مقترح الشراء · النسخة ${current.revision}", "Purchase proposal · revision ${current.revision}"), style = MaterialTheme.typography.titleMedium)
                    HiltechStateTag(purchaseReviewState(current.approvalState),
                        if (current.approvalState == "APPROVED") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                    current.reviewComment?.takeIf(String::isNotBlank)?.let {
                        Text(hiltechText("ملاحظة المراجع", "Reviewer note"), style = MaterialTheme.typography.labelMedium)
                        Text(it)
                    }
                    if (current.quoteId != quote.quoteId) Text(hiltechText("المقترح الحالي مرتبط بعرض سعر آخر. يمكنك إنشاء نسخة بديلة من العرض المحدد بعد انتهاء المراجعة الحالية.",
                        "The current proposal uses another quotation. You can create a replacement from this quotation after the current review ends."))
                    if (current.approvalState == "APPROVED") Text(hiltechText("المقترح معتمد. انتقل إلى إصدار أوامر الشراء لاستكمال الطلب بالصلاحية المخصصة لذلك.",
                        "The proposal is approved. Continue in purchase order issuance with the required authority."))
                    val locallyRequested = commands.approvals[current.candidateId]
                    if (locallyRequested != null && current.approvalState == "NOT_REQUESTED") Text(hiltechText(
                        "تم تأكيد إرسال الطلب. حدّث الحالة لقراءة نتيجة المراجعة الحالية.", "Submission was confirmed. Refresh to read its current review state."))
                    if (current.quoteId == quote.quoteId && current.approvalState == "NOT_REQUESTED" && !replacing && gate.sourceCurrent && gate.quoteLatest) {
                        if (!approvalRequestEnabled) Text(hiltechText("إرسال المراجعات موقوف مؤقتًا على الخادم. المقترح محفوظ ويمكنك العودة إليه بعد استعادة الخدمة.",
                            "Review submission is temporarily paused on the server. The proposal is saved and can be resumed when service returns."))
                        else Button(enabled = !locked, onClick = {
                            confirmation = PurchaseCandidateCommand.Request(rfqId, quote.quoteId, current.candidateId,
                                RequestPurchaseCandidateApprovalDto(newOperationId()))
                        }) { Text(hiltechText("إرسال للمراجعة المستقلة", "Submit for independent review")) }
                    }
                    if (gate.canReplace && !replacing) TextButton(enabled = !locked, onClick = { replacing = true }) {
                        Text(hiltechText("إعداد نسخة مصححة", "Prepare corrected revision"))
                    }
                }
            }
            gate.blockers.forEach { Text(purchaseCandidateBlocker(it), color = MaterialTheme.colorScheme.error) }
            if (gate.canPropose && (existing == null || replacing)) {
                Text(if (existing == null) hiltechText("وضّح سبب اختيار العرض ليتمكن المراجع من اتخاذ القرار.",
                    "Explain the quotation choice so the reviewer can make an informed decision.") else hiltechText(
                    "ستُحفظ نسخة جديدة ويظل المقترح السابق وقراره في السجل. النسخة الجديدة تحتاج مراجعة مستقلة.",
                    "A new revision will preserve the previous proposal and decision in history. The new revision needs independent review."))
                OutlinedTextField(value = (pending as? PurchaseCandidateCommand.Propose)?.payload?.rationale ?: rationale,
                    onValueChange = { rationale = it.take(1000) }, enabled = !locked,
                    label = { Text(hiltechText("سبب اختيار المورد والعرض", "Reason for choosing supplier and quotation")) },
                    supportingText = { Text(hiltechText("اكتب سببًا واضحًا من ١٢ إلى ١٠٠٠ حرف.", "Use a clear reason of 12–1000 characters.")) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                Button(enabled = !locked && rationale.trim().length in 12..1000 && rationale.none { it.code < 32 }, onClick = {
                    confirmation = PurchaseCandidateCommand.Propose(rfqId, quote.quoteId,
                        ProposePurchaseOrderCandidateDto(newOperationId(), quote.quoteId, quote.revisionNumber, rationale.trim(),
                            existing?.candidateId, existing?.revision))
                }) { Text(if (existing == null) hiltechText("حفظ مقترح الشراء", "Save purchase proposal") else hiltechText("حفظ النسخة المصححة", "Save corrected revision")) }
                if (replacing) TextButton(enabled = !locked, onClick = { replacing = false; rationale = "" }) { Text(hiltechText("عودة إلى المقترح", "Back to proposal")) }
            }
        }
        if (preview == null && commands.receipts[rfqId]?.quoteId == quote.quoteId) Text(hiltechText(
            "تم حفظ المقترح؛ تعذّر تحديث العرض الحالي. حدّث الحالة لاستكمال الإرسال.",
            "The proposal was saved; its current view could not be refreshed. Refresh the status to continue submission."))
        error?.let { Text(purchaseCandidateBlocker(it), color = MaterialTheme.colorScheme.error) }
        commands.errorCode?.let { Text(if (it == "PURCHASE_REVIEW_COMMAND_UNCERTAIN") purchaseReviewError(it) else purchaseCandidateBlocker(it),
            color = MaterialTheme.colorScheme.error) }
    }
    confirmation?.let { command -> AlertDialog(
        onDismissRequest = { confirmation = null },
        title = { Text(if (command is PurchaseCandidateCommand.Request) hiltechText("إرسال للمراجعة", "Submit for review") else hiltechText("حفظ مقترح الشراء", "Save purchase proposal")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hiltechText("مرجع عرض السعر", "Quotation reference") + " · " + quote.quoteReference)
            Text("${quote.total} ${quote.currency}")
            if (command is PurchaseCandidateCommand.Propose) {
                Text(command.payload.rationale)
                if (command.payload.replacesCandidateId != null) Text(hiltechText("سيحل هذا المقترح محل النسخة السابقة للطلبات الجديدة مع حفظ تاريخها.",
                    "This proposal replaces the previous revision for future actions while preserving its history."))
            } else Text(hiltechText("سيرى المراجع العرض الأصلي والتفاصيل والسبب، ثم يصدر قراره من الوارد.",
                "The reviewer will see the original quotation, details and reason, then decide from their inbox."))
        } },
        confirmButton = { Button(enabled = !locked, onClick = {
            if (commands.begin(command)) { confirmation = null; scope.launch { execute(command) } }
        }) { Text(hiltechText("تأكيد", "Confirm")) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(hiltechText("عودة", "Back")) } },
    ) }
}

@Composable
private fun purchaseCandidateBlocker(code: String): String = when (code) {
    "TECHNICAL_ASSESSMENT_REQUIRED" -> hiltechText("يلزم استكمال المراجعة الفنية الحالية", "Complete the current technical review.")
    "CANDIDATE_REVIEW_PENDING" -> hiltechText("المقترح الحالي ينتظر قرار المراجع. حدّث الحالة بعد صدور القرار.", "The current proposal is waiting for a review decision. Refresh after the decision.")
    "PURCHASE_ORDER_ALREADY_ISSUED" -> hiltechText("صدر أمر شراء لهذا الطلب. تابع التوريد أو التصحيح من سجل أمر الشراء.", "A purchase order was issued. Continue delivery or correction from its order record.")
    "SOURCE_NOT_CURRENT", "PROCUREMENT_PO_CANDIDATE_STALE", "PROCUREMENT_PO_CANDIDATE_SOURCE_CHANGED", "PROCUREMENT_APPROVAL_SOURCE_CHANGED" -> hiltechText("تغيّر الاحتياج أو المقترح. حدّث المقارنة وحدد النسخة الحالية.", "The requirement or proposal changed. Refresh the comparison and select the current revision.")
    "QUOTE_NOT_LATEST", "QUOTE_REVISION_CHANGED", "QUOTE_EXPIRED" -> hiltechText("تحتاج إلى أحدث عرض سعر صالح من المورد.", "A current, valid quotation from the supplier is required.")
    "QUOTE_DOCUMENT_UNVERIFIED", "MISSING_VERIFIED_EVIDENCE" -> hiltechText("أكمل رفع المستند الأصلي والتحقق منه قبل إرسال المقترح.", "Complete the original quotation upload and verification before submitting the proposal.")
    "SUPPLIER_NOT_LINKED" -> hiltechText("راجع حالة المورد في سجل الموردين.", "Check the supplier's status in the supplier registry.")
    "TECHNICAL_REVIEW_REQUIRED", "TECHNICAL_REVIEW_NOT_ACCEPTED" -> hiltechText("المراجعة الفنية مطلوبة قبل اختيار هذا العرض.", "A technical review is required before choosing this quotation.")
    "APPROVAL_POLICY_NOT_FOUND", "APPROVAL_AUTHORITY_UNRESOLVED", "PROCUREMENT_APPROVAL_POLICY_NOT_CONFIGURED" -> hiltechText(
        "حدد مراجع مقترحات الشراء من إدارة الشركة ثم أعد الإرسال.", "Select a purchase proposal reviewer in company administration, then submit again.")
    "REAUTH_REQUIRED", "RECENT_REAUTH_REQUIRED" -> hiltechText("أعد تأكيد تسجيل الدخول ثم أعد المحاولة.", "Confirm your sign-in again, then retry.")
    else -> hiltechText("تعذّر تجهيز المقترح حاليًا. حدّث المقارنة وتحقق من صلاحية العرض والمراجعة الفنية.",
        "The proposal is not ready. Refresh the comparison and check quotation validity and technical review.")
}
