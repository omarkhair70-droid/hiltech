package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.administration.PurchaseOrderNumberingDto
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.people.companyResponsibilityFailureDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Current issuer source, named destination, frozen online command, visible result. */
@Composable
fun PurchaseOrderIssuerSection(
    api: PurchaseOrderIssueApiClient, organizationId: String, installationId: String, newOperationId: () -> String,
    cancellationEnabled: Boolean = false, amendmentApi: PurchaseOrderDeliveryAmendmentApiClient? = null,
    onOpenCompanySettings: (() -> Unit)? = null,
) {
    val jobs = rememberCoroutineScope()
    var loading by remember(organizationId) { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var queue by remember(organizationId) { mutableStateOf<List<PurchaseOrderIssuerQueueItemDto>>(emptyList()) }
    var numbering by remember(organizationId) { mutableStateOf<PurchaseOrderNumberingDto?>(null) }
    var selectedJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    var destinationId by rememberSaveable(organizationId) { mutableStateOf("") }
    var destinations by remember { mutableStateOf<List<PurchaseOrderDeliveryDestinationDto>>(emptyList()) }
    var destinationLoading by remember { mutableStateOf(false) }
    var destinationError by remember { mutableStateOf<String?>(null) }
    var destinationRefresh by remember { mutableIntStateOf(0) }
    var pendingJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var receiptJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    val selected = selectedJson?.let { Json.decodeFromString(PurchaseOrderIssuerQueueItemDto.serializer(), it) }
    val pending = pendingJson?.let { Json.decodeFromString(PurchaseOrderIssueIntent.serializer(), it) }
    val receipt = receiptJson?.let { Json.decodeFromString(PurchaseOrderIssueReceiptDto.serializer(), it) }

    LaunchedEffect(organizationId, refresh) {
        loading = true; loadError = null
        try {
            numbering = api.numbering(organizationId, installationId)
            queue = api.queue(organizationId, installationId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { queue = emptyList(); loadError = (failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { loading = false }
    }
    LaunchedEffect(selected?.candidateId, destinationRefresh) {
        if (selected == null || pending != null) return@LaunchedEffect
        destinationLoading = true; destinationError = null
        try {
            destinations = api.destinations(selected.candidateId, installationId)
            if (destinations.none { it.storageLocationId == destinationId }) destinationId = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { destinations = emptyList(); destinationError = (failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { destinationLoading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hiltechText("إصدار أوامر الشراء", "Issue purchase orders"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { refresh += 1 }, enabled = !loading && !busy) { Text(hiltechText("تحديث", "Refresh")) }
            }
            Text(hiltechText("اختر الطلب المعتمد ووجهة التسليم؛ ينشئ النظام رقم الأمر تلقائيًا عند تأكيد الإصدار.",
                "Choose an approved candidate and delivery destination; the order number is created automatically when issue is confirmed."))
            if (loading) CircularProgressIndicator()
            loadError?.let { Text(issuerError(it), color = MaterialTheme.colorScheme.error) }
            numbering?.let { current ->
                if (current.ready) {
                    Text(hiltechText("شكل الترقيم الحالي: ${current.exampleCode}", "Current numbering format: ${current.exampleCode}"),
                        style = MaterialTheme.typography.bodySmall)
                    Text(hiltechText("الرقم المعروض مثال؛ يحدد العداد الرقم النهائي.", "The displayed number is an example; the counter supplies the final number."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(hiltechText("يحتاج ترقيم أوامر الشراء إعدادًا من إدارة الشركة ← إعدادات التشغيل.",
                        "Purchase order numbering needs setup in Company administration → Operating settings."))
                    if (onOpenCompanySettings != null) TextButton(onClick = onOpenCompanySettings) {
                        Text(hiltechText("فتح إعدادات الشركة", "Open company settings"))
                    }
                }
            }
            receipt?.let { result ->
                HorizontalDivider()
                Text(hiltechText("تم إصدار أمر الشراء", "Purchase order issued"), color = MaterialTheme.colorScheme.primary)
                Text(result.code, style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr))
                Text(hiltechText("الأمر محفوظ داخل الشركة. إرسالُه للمورد والاستلام والصرف إجراءات مستقلة.",
                    "The order is saved in the company. Supplier dispatch, receiving, and payment are separate actions."))
            }
            if (pending != null && !dialog) TextButton(onClick = { dialog = true }) {
                Text(hiltechText("متابعة أمر الشراء المحفوظ", "Continue saved purchase order request"))
            }
            if (!loading && loadError == null && queue.isEmpty()) Text(hiltechText("لا توجد طلبات معتمدة جاهزة لإصدار أمر شراء.",
                "No approved candidates are ready for purchase order issue."))
            queue.forEach { candidate ->
                HorizontalDivider()
                Text(candidate.supplierName, style = MaterialTheme.typography.titleSmall)
                Text("${candidate.quantity} ${candidate.unitCode} · ${candidate.total} ${candidate.currency}")
                Text(hiltechText("نسخة الاعتماد ${candidate.approvalPolicyVersion}", "Approval revision ${candidate.approvalPolicyVersion}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = pending == null && !busy && !loading, onClick = {
                    selectedJson = Json.encodeToString(PurchaseOrderIssuerQueueItemDto.serializer(), candidate)
                    destinationId = ""; destinationRefresh += 1
                }) { Text(hiltechText("اختيار وجهة التسليم", "Choose delivery destination")) }
            }
        }
        selected?.let { candidate ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(candidate.supplierName, style = MaterialTheme.typography.titleMedium)
                if (destinationLoading) CircularProgressIndicator()
                destinationError?.let { Text(issuerError(it), color = MaterialTheme.colorScheme.error) }
                if (!destinationLoading && destinationError == null && destinations.isEmpty() && pending == null)
                    Text(hiltechText("لا توجد وجهة تسليم متاحة لهذا الطلب حاليًا.", "This candidate has no available delivery destination currently."))
                HiltechRecordPicker(hiltechText("مخزن أو موقع التسليم", "Delivery warehouse or site"), destinationId,
                    destinations.map { HiltechRecordChoice(it.storageLocationId, it.name, it.code) },
                    onChange = { destinationId = it }, allowKnownReference = false, allowClear = false,
                    enabled = pending == null && !busy && !destinationLoading)
                TextButton(enabled = pending == null && !busy && !destinationLoading, onClick = { destinationRefresh += 1 }) {
                    Text(hiltechText("تحديث وجهات التسليم", "Refresh delivery destinations"))
                }
                val destination = destinations.firstOrNull { it.storageLocationId == destinationId }
                Button(enabled = pending == null && !busy && !loading && loadError == null && numbering?.ready == true && destination != null && queue.any { it.candidateId == candidate.candidateId }, onClick = {
                    val target = destination ?: return@Button
                    pendingJson = Json.encodeToString(PurchaseOrderIssueIntent.serializer(), PurchaseOrderIssueIntent(
                        newOperationId(), organizationId, candidate.candidateId, target.storageLocationId,
                        target.name, candidate.supplierName))
                    attempted = false; unknown = false; rejected = false; error = null; dialog = true
                }) { Text(hiltechText("مراجعة إصدار الأمر", "Review order issue")) }
            }
        }
        if (cancellationEnabled) PurchaseOrderCancellationSection(api, organizationId, installationId, newOperationId)
        if (amendmentApi != null) PurchaseOrderDeliveryAmendmentSection(api, amendmentApi, organizationId, installationId, newOperationId)
    }
    pending?.takeIf { dialog }?.let { intent ->
        fun close() { dialog = false; if (!attempted) pendingJson = null }
        AlertDialog(onDismissRequest = { if (!busy) close() },
            title = { Text(hiltechText("تأكيد إصدار أمر الشراء", "Confirm purchase order issue")) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(intent.supplierName, style = MaterialTheme.typography.titleMedium)
                Text(hiltechText("التسليم إلى: ${intent.destinationName}", "Deliver to: ${intent.destinationName}"))
                Text(hiltechText("يُسجل الأمر بعد إعادة التحقق من الاعتماد والمصدر والصلاحية، ويُولّد رقمه تلقائيًا.",
                    "The order is recorded after rechecking approval, source, and authority, and its number is generated automatically."))
                if (busy) CircularProgressIndicator()
                error?.let { Text(issuerError(it), color = MaterialTheme.colorScheme.error) }
                if (unknown) Text(hiltechText("النتيجة غير مؤكدة. احتُفظ بنفس الطلب ووجهة التسليم؛ أعد المحاولة لتأكيد الإصدار دون تكراره.",
                    "The outcome is unconfirmed. The same request and delivery destination are retained; retry to confirm issue without duplicating it."))
                if (rejected) Text(hiltechText("لم يُسجل هذا الطلب. حدّث الحالة قبل إنشاء طلب جديد.", "This request was not recorded. Refresh before creating a new request."))
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                if (rejected) { pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false; refresh += 1; destinationRefresh += 1 }
                else {
                    busy = true; error = null
                    val priorUnknown = unknown
                    jobs.launch {
                        try {
                            val result = api.issue(intent, installationId) { attempted = true; unknown = true }
                            receiptJson = Json.encodeToString(PurchaseOrderIssueReceiptDto.serializer(), result)
                            pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false
                            selectedJson = null; destinationId = ""; refresh += 1
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            error = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                            val state = companyResponsibilityFailureDisposition(priorUnknown, failure)
                            unknown = state.hasUnknownOutcome; rejected = state.rejectionConfirmed
                        } finally { busy = false }
                    }
                }
            }) { Text(when { rejected -> hiltechText("مراجعة الحالة الحالية", "Review current state")
                attempted -> hiltechText("إعادة نفس الطلب", "Retry same request") else -> hiltechText("إصدار الأمر", "Issue order") }) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { close() }) { Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back")
                else hiltechText("رجوع", "Back")) } })
    }
}

@Composable
private fun issuerError(code: String): String = when (code) {
    "PROCUREMENT_PO_NUMBERING_REQUIRED" -> hiltechText("جهّز ترقيم أوامر الشراء من إعدادات التشغيل في إدارة الشركة.", "Set up purchase order numbering in company operating settings.")
    "PROCUREMENT_PO_NUMBERING_CHANGED" -> hiltechText("تغير إعداد الترقيم؛ حدّث الحالة وراجع الطلب.", "Numbering changed; refresh and review the request.")
    "PROCUREMENT_PO_SOURCE_CHANGED" -> hiltechText("تغير العرض أو مصدر الاحتياج. راجع النسخة الحالية والاعتماد.", "The quotation or need source changed. Review the current version and approval.")
    "PROCUREMENT_PO_ALREADY_ISSUED" -> hiltechText("يوجد أمر شراء صادر لهذا الطلب. راجع الأوامر الصادرة.", "This candidate already has an issued order. Review issued orders.")
    "PROCUREMENT_PO_ISSUE_UNAVAILABLE" -> hiltechText("تحقق من مسؤولية إصدار الأوامر وإعداد المراجع والاعتماد الحالي لهذا الطلب.", "Check the order issuing responsibility, reviewer setup, and the candidate’s current approval.")
    else -> hiltechText("تعذر تأكيد أمر الشراء. تحقق من الاتصال ثم أعد نفس الطلب المحفوظ.", "Couldn’t confirm the purchase order. Check the connection, then retry the same saved request.")
}
