package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.ProtectedDocumentPageViewer
import com.hiltech.shared.core.design.hiltechDateText
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A decision is made on the exact source-owned proposal, never on an inbox summary. */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun PurchaseOrderReviewWorkspace(
    client: PurchaseOrderReviewApiClient,
    candidateId: String,
    approvalRequestId: String,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: () -> Unit = {},
    onRecorded: (PurchaseReviewDecisionReceiptDto) -> Unit = {},
) = key(client, candidateId, approvalRequestId, installationId) {
    val jobs = rememberCoroutineScope()
    val commands = client.commands
    var detail by remember { mutableStateOf<PurchaseOrderReviewDto?>(null) }
    var completed by remember { mutableStateOf<PurchaseReviewCompletedDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Pair<PurchaseOrderReviewDto, String>?>(null) }
    var viewerSource by remember { mutableStateOf<PurchaseOrderReviewDto?>(null) }
    var page by remember { mutableStateOf<PurchaseQuotePageDto?>(null) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var pageLoading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var requestedPage by remember { mutableStateOf(0) }
    val receipt = commands.receipt?.takeIf { it.candidateId == candidateId && it.approvalRequestId == approvalRequestId }
    val pending = commands.pending?.takeIf { it.candidateId == candidateId && it.requestId == approvalRequestId }
    val locked = commands.pending != null || commands.busy

    fun clearPage() { viewerSource = null; page = null; image = null; pageError = null }

    suspend fun refresh() {
        loading = true; error = null
        try {
            val current = client.read(candidateId, approvalRequestId, installationId)
            val displayed = viewerSource
            if (displayed != null && (!current.canDecide || current.exactSource() != displayed.exactSource())) clearPage()
            detail = current
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            detail = null; clearPage()
            try { completed = client.completed(approvalRequestId, installationId); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = (failure as? HiltechApiException)?.code ?: "PURCHASE_REVIEW_LOAD_FAILED" }
        }
        finally { loading = false }
    }

    suspend fun execute(command: PurchaseReviewCommand) {
        try {
            when (command) {
                is PurchaseReviewCommand.Open -> {
                    client.recordOpened(command.candidateId, command.requestId, command.payload, installationId)
                    commands.succeeded(command)
                    refresh()
                }
                is PurchaseReviewCommand.Decide -> {
                    val result = client.decide(command.candidateId, command.requestId, command.payload, installationId)
                    commands.succeeded(command, result)
                    confirmation = null; clearPage(); detail = null
                    onRecorded(result)
                }
            }
        } catch (cancelled: CancellationException) {
            commands.failed(command, cancelled)
            throw cancelled
        } catch (failure: Exception) {
            commands.failed(command, failure)
            clearPage()
            if (commands.pending == null) refresh()
        }
    }

    fun showPage(source: PurchaseOrderReviewDto, index: Int) {
        if (pageLoading || commands.pending is PurchaseReviewCommand.Decide) return
        viewerSource = source; requestedPage = index; page = null; image = null; pageError = null; pageLoading = true
        jobs.launch {
            try {
                val delivered = client.quotationPage(source, index, installationId)
                val current = client.read(candidateId, approvalRequestId, installationId)
                if (!current.canDecide || current.exactSource() != delivered.source) {
                    detail = current; clearPage(); error = "PROCUREMENT_REVIEW_CONTEXT_CHANGED"
                } else if (viewerSource?.exactSource() == source.exactSource() && requestedPage == index) {
                    val decoded = Base64.Default.decode(delivered.imagePngBase64)
                    require(decoded.size in 1..16_777_216)
                    detail = current; page = delivered; image = decoded
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                image = null; page = null
                pageError = (failure as? HiltechApiException)?.code ?: "PROCUREMENT_REVIEW_DOCUMENT_UNAVAILABLE"
                if ((failure as? HiltechApiException)?.httpStatus in setOf(401, 403, 404, 409)) {
                    clearPage(); error = "PROCUREMENT_REVIEW_CONTEXT_CHANGED"; detail = null
                }
            } finally { pageLoading = false }
        }
    }

    LaunchedEffect(candidateId, approvalRequestId) { if (receipt == null) refresh() else loading = false }
    // A displayed page is temporary session memory. Revalidate while it remains open,
    // and remove it if the source, assignment or policy is no longer available.
    LaunchedEffect(viewerSource?.approvalRequestId, viewerSource?.approvalRequestVersion) {
        while (viewerSource != null) {
            delay(20_000)
            if (viewerSource != null) refresh()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(hiltechText("مراجعة مقترح الشراء", "Purchase proposal review"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(enabled = !loading && !locked && receipt == null && completed == null, onClick = { jobs.launch { refresh() } }) {
                Text(hiltechText("تحديث", "Refresh"))
            }
        }
        if (loading) CircularProgressIndicator()
        receipt?.let {
            HiltechStateTag(purchaseReviewState(it.approvalState), if (it.approvalState == "APPROVED") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
            Text(hiltechText("تم تسجيل القرار في السجل. إصدار أمر الشراء يتم من مساحة المشتريات بواسطة المسؤول المخوّل.",
                "The decision is recorded. An authorized purchaser can issue the purchase order from the purchasing workspace."))
        }
        completed?.let {
            HiltechStateTag(purchaseReviewState(it.approvalState), HiltechStateTone.CURRENT)
            Text(hiltechText("تم استرجاع الحالة المسجلة من الخادم بعد التحقق من حسابك. لا يلزم إرسال قرار جديد.",
                "The recorded status was recovered from the server for your account. No new decision is needed."))
        }
        error?.let { Text(purchaseReviewError(it), color = MaterialTheme.colorScheme.error) }
        commands.errorCode?.let { Text(purchaseReviewError(it), color = MaterialTheme.colorScheme.error) }
        if (commands.requiresReauthentication || error == "REAUTH_REQUIRED") {
            OutlinedButton(enabled = !commands.busy, onClick = onReauthenticate) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
        }
        pending?.let { command ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(hiltechText("تأكيد نتيجة العملية", "Confirm operation result"), style = MaterialTheme.typography.titleMedium)
                Text(if (command is PurchaseReviewCommand.Open) hiltechText("نحتفظ بتأكيد فتح المستند نفسه حتى يصل رد واضح من الخادم.",
                    "The same document-opening confirmation is retained until the server returns a definite result.")
                else hiltechText("نحتفظ بالقرار والسبب والنسخة نفسها. إعادة المحاولة لا تُنشئ قرارًا جديدًا.",
                    "The exact decision, reason and source version are retained. Retry does not create another decision."))
                if (commands.busy) CircularProgressIndicator()
                else Button(onClick = { commands.retry()?.let { jobs.launch { execute(it) } } }) {
                    Text(hiltechText("التحقق من نتيجة العملية", "Check operation result"))
                }
            }
        }
        if (commands.pending != null && pending == null) Text(hiltechText(
            "هناك عملية أخرى تنتظر تأكيد النتيجة. عُد إلى طلبها في الوارد للتحقق منها قبل تسجيل قرار جديد.",
            "Another operation is waiting for confirmation. Open its inbox request to check the result before recording another decision."))
        detail?.takeIf { receipt == null }?.let { source ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(source.supplierName, style = MaterialTheme.typography.titleLarge)
                Text(source.supplierCode, style = MaterialTheme.typography.labelMedium)
                Text(source.itemName, style = MaterialTheme.typography.titleMedium)
                HiltechKeyValue(hiltechText("كود الصنف", "Item code"), source.itemCode)
                HiltechKeyValue(hiltechText("الجهة الطالبة", "Requested for"), source.sourceLabel)
                HiltechKeyValue(hiltechText("الكمية", "Quantity"), "${source.quantity} ${source.unitCode}")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val amounts: @Composable () -> Unit = {
                        HiltechKeyValue(hiltechText("سعر الوحدة", "Unit price"), "${source.unitPrice} ${source.currency}")
                        HiltechKeyValue(hiltechText("الضريبة", "Tax"), "${source.taxAmount} ${source.currency}")
                    }
                    if (maxWidth < 540.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { amounts() }
                    else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { amounts() }
                }
                HiltechKeyValue(hiltechText("الإجمالي", "Total"), "${source.totalAmount} ${source.currency}")
                HorizontalDivider()
                HiltechKeyValue(hiltechText("مرجع عرض السعر", "Quotation reference"), source.quoteReference)
                HiltechKeyValue(hiltechText("صالح حتى", "Valid until"), hiltechDateText(source.quoteValidUntil))
                source.deliveryDays?.let { HiltechKeyValue(hiltechText("مدة التوريد بالأيام", "Delivery days"), it.toString()) }
                source.deliveryTerms?.takeIf(String::isNotBlank)?.let { HiltechKeyValue(hiltechText("شروط التوريد", "Delivery terms"), it) }
                source.paymentTerms?.takeIf(String::isNotBlank)?.let { HiltechKeyValue(hiltechText("شروط السداد", "Payment terms"), it) }
                source.warrantyTerms?.takeIf(String::isNotBlank)?.let { HiltechKeyValue(hiltechText("شروط الضمان", "Warranty terms"), it) }
                HiltechKeyValue(hiltechText("سبب الاختيار", "Selection reason"), source.selectionReason)
                HiltechKeyValue(hiltechText("أعدّه", "Prepared by"), source.preparedByName.ifBlank { hiltechText("الاسم غير متاح", "Name unavailable") })
                HiltechKeyValue(hiltechText("تاريخ الإعداد", "Prepared on"), hiltechDateText(source.preparedAt.take(10)))
                Text(hiltechText("نسخة المقترح ${source.candidateRevision} · عرض السعر ${source.quoteRevision} · الاحتياج ${source.requirementRevision}",
                    "Proposal revision ${source.candidateRevision} · quotation ${source.quoteRevision} · requirement ${source.requirementRevision}"),
                    style = MaterialTheme.typography.bodySmall)
            }
            when {
                !source.sourceCurrent -> Text(hiltechText("تغيّر الاحتياج أو عرض السعر. أغلق هذه المراجعة واطلب من المشتريات تجهيز نسخة حالية.",
                    "The requirement or quotation changed. Close this review and ask purchasing to prepare a current revision."))
                !source.independentReviewer -> Text(hiltechText("هذا المقترح يحتاج مراجعًا مستقلًا عن الشخص الذي أعدّه أو طلب اعتماده.",
                    "This proposal needs a reviewer independent from its preparer and requester."))
                !source.canDecide -> Text(hiltechText("تغيّر تعيين المراجع أو إعداد الموافقة. راجع إعداد المراجعين في إدارة الشركة.",
                    "The reviewer assignment or approval setup changed. Check review setup in company administration."))
            }
            if (source.sourceCurrent && source.canDecide) {
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("عرض السعر الأصلي", "Original quotation"), style = MaterialTheme.typography.titleMedium)
                    if (source.quotationOpened) HiltechStateTag(hiltechText("تم فتح النسخة المرتبطة بالطلب", "Linked version opened"), HiltechStateTone.CURRENT)
                    Text(hiltechText("افتح المستند وراجع الأسعار والشروط قبل الموافقة. يُسجّل فتح النسخة فقط، وليس قرار الموافقة.",
                        "Open the document and review its prices and terms before approving. Opening the version is recorded separately from approval."))
                    if (source.quotation?.available != true) Text(hiltechText("المستند الأصلي غير متاح حاليًا. يمكنك طلب التصحيح أو الرفض مع توضيح السبب.",
                        "The original document is currently unavailable. You can request a correction or reject with a reason."))
                    OutlinedButton(enabled = !locked && !loading && source.quotation?.available == true, onClick = { showPage(source, 0) }) {
                        Text(hiltechText("فتح عرض السعر", "Open quotation"))
                    }
                }
            }
            if (source.canDecide || source.canCloseStale) {
                OutlinedTextField(reason, { reason = it.take(1000) }, Modifier.fillMaxWidth(), enabled = !locked,
                    label = { Text(hiltechText("سبب القرار أو ملاحظة للمشتريات", "Decision reason or note for purchasing")) },
                    minLines = 2, maxLines = 5)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source.canCloseStale) OutlinedButton(enabled = !locked && reason.isNotBlank(), onClick = { confirmation = source to "CLOSE_STALE" }) {
                        Text(hiltechText("إغلاق المراجعة القديمة", "Close outdated review"))
                    } else {
                        Button(enabled = !locked && !loading && source.canApprove, onClick = { confirmation = source to "APPROVE" }) {
                            Text(hiltechText("الموافقة على المقترح", "Approve proposal"))
                        }
                        OutlinedButton(enabled = !locked && !loading && source.canDecide && reason.isNotBlank(), onClick = { confirmation = source to "REQUEST_CHANGE" }) {
                            Text(hiltechText("طلب تصحيح", "Request correction"))
                        }
                        TextButton(enabled = !locked && !loading && source.canDecide && reason.isNotBlank(), onClick = { confirmation = source to "REJECT" }) {
                            Text(hiltechText("رفض المقترح", "Reject proposal"))
                        }
                    }
                }
            }
        }
    }

    confirmation?.let { (source, decision) ->
        AlertDialog(onDismissRequest = { if (!commands.busy) confirmation = null },
            title = { Text(purchaseReviewDecisionLabel(decision)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(source.supplierName); Text("${source.quantity} ${source.unitCode} · ${source.totalAmount} ${source.currency}")
                Text(source.quoteReference)
                if (reason.isNotBlank()) Text(reason.trim())
                Text(hiltechText("سيسجَّل القرار على النسخة المعروضة وسيظهر لمسؤول المشتريات.",
                    "The decision will be recorded against the displayed revision and shown to purchasing."))
            } },
            confirmButton = { Button(enabled = !locked, onClick = {
                val command = PurchaseReviewCommand.Decide(candidateId, approvalRequestId,
                    DecidePurchaseReviewDto(newOperationId(), source.exactSource(), decision, reason.trim().takeIf(String::isNotBlank)))
                if (commands.begin(command)) { confirmation = null; jobs.launch { execute(command) } }
            }) { Text(hiltechText("تأكيد القرار", "Confirm decision")) } },
            dismissButton = { TextButton(enabled = !commands.busy, onClick = { confirmation = null }) { Text(hiltechText("عودة", "Back")) } })
    }
    viewerSource?.let { source ->
        ProtectedDocumentPageViewer(
            title = hiltechText("عرض السعر", "Quotation") + " · " + source.quoteReference,
            pageIndex = page?.pageIndex ?: requestedPage, pageCount = page?.pageCount ?: 0, imagePng = image,
            loading = pageLoading, error = pageError?.let { purchaseReviewError(it) },
            onPage = { showPage(source, it) }, onRetry = { showPage(source, requestedPage) }, onClose = { clearPage() },
            onDisplayed = {
                val displayed = page
                if (displayed != null && detail?.quotationOpened != true && commands.pending == null &&
                    detail?.exactSource() == displayed.source) {
                    val command = PurchaseReviewCommand.Open(candidateId, approvalRequestId,
                        OpenPurchaseReviewQuoteDto(newOperationId(), displayed.deliveryId, displayed.source))
                    if (commands.begin(command)) jobs.launch { execute(command) }
                }
            },
        )
    }
}

@Composable
internal fun purchaseReviewState(state: String): String = when (state) {
    "APPROVED" -> hiltechText("تمت الموافقة", "Approved")
    "CHANGE_REQUESTED" -> hiltechText("مطلوب تصحيح", "Correction requested")
    "REJECTED" -> hiltechText("تم الرفض", "Rejected")
    "SUPERSEDED" -> hiltechText("أُغلقت النسخة القديمة", "Outdated review closed")
    "PENDING" -> hiltechText("بانتظار المراجعة", "Awaiting review")
    "NOT_REQUESTED" -> hiltechText("جاهز للإرسال للمراجعة", "Ready to submit for review")
    else -> hiltechText("تم تسجيل الحالة", "Status recorded")
}

@Composable
private fun purchaseReviewDecisionLabel(decision: String): String = when (decision) {
    "APPROVE" -> hiltechText("الموافقة على مقترح الشراء", "Approve purchase proposal")
    "REQUEST_CHANGE" -> hiltechText("طلب تصحيح المقترح", "Request proposal correction")
    "REJECT" -> hiltechText("رفض مقترح الشراء", "Reject purchase proposal")
    else -> hiltechText("إغلاق المراجعة القديمة", "Close outdated review")
}

@Composable
internal fun purchaseReviewError(code: String): String = when (code) {
    "PURCHASE_REVIEW_COMMAND_UNCERTAIN" -> hiltechText("لم يصل تأكيد نهائي من الخادم. تحقّق من نتيجة العملية نفسها قبل أي تعديل.",
        "The server has not confirmed the result. Check the same operation before changing it.")
    "REAUTH_REQUIRED", "RECENT_REAUTH_REQUIRED", "RECENT_AUTH_REQUIRED" -> hiltechText("أعِد تأكيد الدخول ثم أعد محاولة العملية نفسها.",
        "Confirm your sign-in again, then retry the same operation.")
    "PROCUREMENT_REVIEW_CONTEXT_CHANGED", "APPROVAL_SOURCE_REVIEW_NOT_ALLOWED", "APPROVAL_SUBJECT_NOT_CURRENT" -> hiltechText(
        "تغيّرت النسخة أو صلاحية المراجعة. حدّث الطلب قبل اتخاذ قرار.", "The source or review authority changed. Refresh the request before deciding.")
    "PROCUREMENT_REVIEW_NOT_AVAILABLE", "APPROVAL_REQUEST_NOT_VISIBLE" -> hiltechText(
        "لم يعد هذا الطلب متاحًا للمراجعة في حسابك. حدّث الوارد لمعرفة حالته.", "This request is no longer available for your review. Refresh the inbox to check its status.")
    "PROCUREMENT_REVIEW_DOCUMENT_UNAVAILABLE", "EVIDENCE_STORAGE_UNAVAILABLE" -> hiltechText(
        "تعذّر عرض مستند عرض السعر. أعد المحاولة أو اطلب نسخة مصححة من المشتريات.", "The quotation document could not be displayed. Retry or request a corrected copy from purchasing.")
    else -> hiltechText("تعذّر إتمام العملية الآن. حدّث الطلب ثم حاول مجددًا.", "The operation could not be completed. Refresh the request and try again.")
}
