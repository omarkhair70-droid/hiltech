package com.hiltech.shared.core.administration

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
import androidx.compose.material3.OutlinedTextField
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
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.hiltechDateText
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.people.companyResponsibilityFailureDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Company PO numbering is ordinary operating configuration with a separate current source authority. */
@Composable
fun CompanyOperationalSettingsWorkspace(
    organizationId: String, client: CompanyOperationalSettingsApiClient, installationId: String,
    newOperationId: () -> String, onReauthenticate: (() -> Unit)? = null,
) {
    val jobs = rememberCoroutineScope()
    var snapshot by remember(organizationId) { mutableStateOf<PurchaseOrderNumberingDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var prefix by rememberSaveable(organizationId) { mutableStateOf("") }
    var reason by rememberSaveable(organizationId) { mutableStateOf("") }
    var pendingJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<CompanyPurchaseOrderNumberingReceiptDto?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val pending = pendingJson?.let { Json.decodeFromString(CompanyPurchaseOrderNumberingIntent.serializer(), it) }
    LaunchedEffect(organizationId, refresh) {
        loading = true; loadError = null
        try { snapshot = client.purchaseOrderNumbering(organizationId, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { snapshot = null; loadError = (failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { loading = false }
    }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(hiltechText("ترقيم أوامر الشراء", "Purchase order numbering"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refresh += 1 }, enabled = !loading && !busy) { Text(hiltechText("تحديث", "Refresh")) }
        }
        Text(hiltechText("يُنشئ HILTECH رقم أمر الشراء تلقائيًا عند الإصدار. اختر بادئة واضحة للشركة؛ يستمر العداد نفسه وتبقى الأرقام الصادرة محفوظة.",
            "HILTECH creates each purchase order number when it is issued. Choose a recognizable company prefix; the same counter continues and issued numbers stay unchanged."))
        if (loading) CircularProgressIndicator()
        loadError?.let { Text(numberingError(it), color = MaterialTheme.colorScheme.error) }
        receipt?.let { saved ->
            Text(if (saved.current) hiltechText("تم حفظ ترقيم أوامر الشراء: ${saved.prefix}", "Purchase order numbering saved: ${saved.prefix}")
                else hiltechText("تأكد حفظ الطلب السابق، ثم تغير الإعداد بعده. راجع النسخة الحالية.", "The earlier request was saved, and the configuration has since changed. Review the current revision."),
                color = MaterialTheme.colorScheme.primary)
        }
        if (pending != null && !dialog) TextButton(onClick = { dialog = true }) {
            Text(hiltechText("متابعة طلب الترقيم المحفوظ", "Continue saved numbering request"))
        }
        snapshot?.takeUnless { loading }?.let { current ->
            HorizontalDivider()
            Text(when (current.source) {
                "COMPANY" -> hiltechText("إعداد الشركة الحالي · النسخة ${current.version}", "Current company configuration · revision ${current.version}")
                "DEPLOYMENT" -> hiltechText("الإعداد السابق المستخدم حاليًا", "Existing configuration currently in use")
                else -> hiltechText("يحتاج الترقيم إعدادًا قبل إصدار أول أمر شراء", "Set up numbering before issuing the first purchase order")
            }, style = MaterialTheme.typography.titleSmall)
            current.exampleCode?.let { code -> Text(code, style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr)) }
            if (current.ready) Text(hiltechText("مثال على الشكل؛ يحدد العداد الرقم النهائي عند الإصدار.", "Format example; the counter supplies the final number at issue."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (current.source == "DEPLOYMENT") Text(hiltechText("يمكن اعتماد البادئة الحالية داخل إعدادات الشركة أو اختيار بادئة جديدة مع تسجيل سبب التغيير.",
                "You can adopt the existing prefix in company settings or choose a new prefix and record the reason."))
            OutlinedTextField(prefix, { prefix = it.uppercase().take(24) }, Modifier.fillMaxWidth(), singleLine = true,
                enabled = pending == null && !busy,
                label = { Text(hiltechText("بادئة أمر الشراء", "Purchase order prefix")) },
                supportingText = { Text(hiltechText("حروف إنجليزية كبيرة وأرقام، ويمكن فصلها بشرطة؛ مثال HT-PO.", "Uppercase letters and digits, optionally separated by a hyphen; for example HT-PO.")) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                isError = prefix.isNotEmpty() && !validCompanyPurchaseOrderPrefix(prefix))
            if (current.prefix != null && prefix != current.prefix && pending == null) TextButton(onClick = { prefix = current.prefix }) {
                Text(hiltechText("استخدام البادئة الحالية", "Use current prefix"))
            }
            OutlinedTextField(reason, { reason = it.take(1000) }, Modifier.fillMaxWidth(), enabled = pending == null && !busy,
                label = { Text(hiltechText("سبب الإعداد أو التغيير", "Reason for setup or change")) }, minLines = 2, maxLines = 4)
            if (validCompanyPurchaseOrderPrefix(prefix)) Text(hiltechText("الشكل الجديد: $prefix-000001", "New format: $prefix-000001"),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
            Button(enabled = pending == null && !busy && validCompanyPurchaseOrderPrefix(prefix) && reason.trim().length in 3..1000 &&
                (current.source != "COMPANY" || prefix != current.prefix), onClick = {
                pendingJson = Json.encodeToString(CompanyPurchaseOrderNumberingIntent.serializer(), CompanyPurchaseOrderNumberingIntent(
                    newOperationId(), organizationId, current.version, current.source, current.prefix, prefix, reason.trim()))
                attempted = false; unknown = false; rejected = false; error = null; receipt = null; dialog = true
            }) { Text(hiltechText("مراجعة إعداد الترقيم", "Review numbering setup")) }
            if (current.history.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) hiltechText("إخفاء سجل التغييرات", "Hide change history")
                    else hiltechText("عرض سجل التغييرات", "View change history")) }
                if (showHistory) current.history.forEach { item ->
                    HorizontalDivider()
                    Text(hiltechText("النسخة ${item.version} · ${item.prefix}", "Revision ${item.version} · ${item.prefix}"), style = MaterialTheme.typography.titleSmall)
                    Text(hiltechDateText(item.changedAt.take(10)) + if (item.changedByDisplayName.isNotBlank()) " · ${item.changedByDisplayName}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    pending?.takeIf { dialog }?.let { intent ->
        fun close() { dialog = false; if (!attempted) pendingJson = null }
        AlertDialog(onDismissRequest = { if (!busy) close() },
            title = { Text(hiltechText("تأكيد ترقيم أوامر الشراء", "Confirm purchase order numbering")) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${intent.prefix}-000001", style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr))
                Text(hiltechText("ستطبق البادئة على الأوامر الجديدة. الأرقام السابقة والعداد الحالي لا يتغيران.",
                    "The prefix applies to new orders. Previous numbers and the current counter stay unchanged."))
                Text(intent.reason)
                if (busy) CircularProgressIndicator()
                error?.let { Text(numberingError(it), color = MaterialTheme.colorScheme.error) }
                if (error == "REAUTH_REQUIRED" && onReauthenticate != null) TextButton(onClick = onReauthenticate, enabled = !busy) {
                    Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in"))
                }
                if (unknown) Text(hiltechText("لم تصل نتيجة مؤكدة. أعد نفس الطلب المحفوظ للتحقق منها دون تكرار التغيير.",
                    "The outcome is not confirmed. Retry the same saved request to verify it without repeating the change."))
                if (rejected) Text(hiltechText("لم يُسجل هذا التغيير. حدّث الإعداد وراجعه قبل إنشاء طلب جديد.",
                    "This change was not recorded. Refresh and review the configuration before creating a new request."))
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                if (rejected) { pendingJson = null; dialog = false; attempted = false; rejected = false; unknown = false; refresh += 1 }
                else {
                    busy = true; error = null
                    val priorUnknown = unknown
                    jobs.launch {
                        try {
                            receipt = client.configurePurchaseOrderNumbering(intent, installationId) { attempted = true; unknown = true }
                            pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false; reason = ""; refresh += 1
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            error = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                            val state = companyResponsibilityFailureDisposition(priorUnknown, failure)
                            unknown = state.hasUnknownOutcome; rejected = state.rejectionConfirmed
                        } finally { busy = false }
                    }
                }
            }) { Text(when { rejected -> hiltechText("مراجعة الإعداد الحالي", "Review current configuration")
                attempted -> hiltechText("إعادة نفس الطلب", "Retry same request") else -> hiltechText("حفظ الإعداد", "Save configuration") }) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { close() }) { Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back")
                else hiltechText("رجوع", "Back")) } })
    }
}

@Composable
private fun numberingError(code: String): String = when (code) {
    "REAUTH_REQUIRED", "UNAUTHENTICATED", "SESSION_NOT_ACTIVE" -> hiltechText("أكد تسجيل الدخول قبل حفظ إعداد الشركة.", "Confirm sign-in before saving company settings.")
    "COMPANY_SETTINGS_DENIED" -> hiltechText("يتطلب التغيير مسؤولية إعدادات الشركة الحالية.", "This change requires current company settings authority.")
    "COMPANY_NUMBERING_CHANGED" -> hiltechText("تغير إعداد الترقيم. حدّث النسخة الحالية وراجعها.", "Numbering changed. Refresh and review the current revision.")
    "COMPANY_NUMBERING_UNCHANGED" -> hiltechText("هذه البادئة مستخدمة بالفعل؛ لا يوجد تغيير لحفظه.", "This prefix is already in use; there is no change to save.")
    "COMPANY_NUMBERING_INVALID" -> hiltechText("راجع صيغة البادئة واكتب سببًا واضحًا للإعداد.", "Check the prefix format and enter a clear reason.")
    else -> hiltechText("تعذر تأكيد إعداد الترقيم. تحقق من الاتصال ثم أعد المحاولة بنفس الطلب.", "Couldn’t confirm numbering setup. Check the connection, then retry the same request.")
}
