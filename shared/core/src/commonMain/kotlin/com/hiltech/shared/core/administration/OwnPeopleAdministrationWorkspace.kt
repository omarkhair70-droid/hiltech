package com.hiltech.shared.core.administration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.people.companyResponsibilityFailureDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
internal fun OwnPeopleAdministrationWorkspace(organizationId: String, status: String, client: CompanyAdministrationApiClient,
    installationId: String, newOperationId: () -> String, onChanged: () -> Unit, onReauthenticate: (() -> Unit)?, onOpenPeople: (() -> Unit)?) {
    val jobs = rememberCoroutineScope()
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var acknowledgement by remember { mutableStateOf<Boolean?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = pendingJson?.let { Json.decodeFromString(OwnPeopleAdministrationIntent.serializer(), it) }
    if (status in setOf("AVAILABLE", "ASSIGNED") || pending != null || acknowledgement != null) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("إدارة الموظفين من حسابك", "Manage employees from your account"), style = MaterialTheme.typography.titleMedium)
            Text(hiltechText("تتيح لك مسؤولية إدارة الموظفين إنشاء سجلاتهم وتجهيز حساباتهم وإدارة بياناتهم الوظيفية.",
                "People administration lets you create employee records, prepare their accounts, and manage their employment information."))
            if (status == "ASSIGNED") Text(hiltechText("تم إسناد المسؤولية. جارٍ تحديث الوصول؛ حدّث للتحقق ثم افتح الموظفين.",
                "The responsibility is assigned. Access is updating; refresh to check, then open People."))
            if (acknowledgement == false) Text(hiltechText("تم العثور على نتيجة الطلب السابق، لكن المسؤولية لم تعد سارية. حدّث الحالة.",
                "The earlier request is confirmed, but its assignment is no longer current. Refresh the status."))
            if (status == "CURRENT" && acknowledgement == true) Text(hiltechText("إدارة الموظفين متاحة الآن لحسابك.", "People administration is now available to your account."))
            if (pending != null) TextButton(onClick = { dialog = true }) { Text(hiltechText("مراجعة الطلب المحفوظ", "Review saved request")) }
            if (status == "AVAILABLE" && pending == null) Button(onClick = {
                pendingJson = Json.encodeToString(OwnPeopleAdministrationIntent.serializer(), OwnPeopleAdministrationIntent(newOperationId(), organizationId))
                attempted = false; unknown = false; rejected = false; error = null; dialog = true
            }) { Text(hiltechText("تفعيل إدارة الموظفين لحسابي", "Activate People administration for me")) }
            if (status == "CURRENT" && onOpenPeople != null) Button(onClick = onOpenPeople) { Text(hiltechText("فتح الموظفين", "Open People")) }
            if (status == "ASSIGNED") TextButton(onClick = onChanged) { Text(hiltechText("إعادة التحقق", "Check access")) }
        }
    }
    if (dialog && pending != null) {
        fun close() {
            dialog = false
            if (!attempted || rejected) { pendingJson = null; attempted = false; unknown = false; rejected = false }
        }
        AlertDialog(onDismissRequest = { if (!submitting) close() },
            title = { Text(hiltechText("تفعيل إدارة الموظفين", "Activate People administration")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hiltechText("ستُسند مسؤولية إدارة الموظفين إلى حسابك ضمن التفويض المعتمد لك.",
                    "The People administration responsibility will be assigned to your account within your approved authority."))
                if (unknown) Text(hiltechText("النتيجة السابقة غير مؤكدة. سنعيد نفس الطلب للتحقق منها.",
                    "The previous result is unconfirmed. Retry the same saved request to check it."))
                error?.let { code ->
                    Text(when (code) {
                        "REAUTH_REQUIRED" -> hiltechText("أكد تسجيل دخولك لإكمال الإسناد.", "Confirm your sign-in to finish the assignment.")
                        "RESPONSIBILITY_ADMINISTRATION_DENIED" -> hiltechText("تفويضك الحالي لا يسمح بهذا الإسناد. حدّث صلاحياتك.", "Your current authority does not permit this assignment. Refresh your access.")
                        else -> hiltechText("تعذر تأكيد الإسناد. تحقق من الاتصال ثم أعد نفس الطلب.", "Couldn’t confirm the assignment. Check your connection, then retry the same request.")
                    }, color = MaterialTheme.colorScheme.error)
                    if (code == "REAUTH_REQUIRED" && onReauthenticate != null) TextButton(onClick = onReauthenticate) {
                        Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in"))
                    }
                }
                if (submitting) CircularProgressIndicator()
            } },
            confirmButton = { TextButton(enabled = !submitting && error != "REAUTH_REQUIRED", onClick = {
                submitting = true; error = null
                val previouslyUnknown = unknown
                jobs.launch {
                    try {
                        val receipt = client.activateOwnPeopleAdministration(pending, installationId) { attempted = true; unknown = true }
                        acknowledgement = receipt.current
                        pendingJson = null; unknown = false; rejected = false; dialog = false
                        onChanged()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: HiltechApiException) {
                        val disposition = companyResponsibilityFailureDisposition(previouslyUnknown, failure)
                        unknown = disposition.hasUnknownOutcome; rejected = disposition.rejectionConfirmed; error = failure.code
                    } catch (_: Exception) { unknown = attempted; error = "OUTCOME_UNCONFIRMED" }
                    finally { submitting = false }
                }
            }) { Text(hiltechText("تأكيد الإسناد", "Confirm assignment")) } },
            dismissButton = { TextButton(onClick = { close() }, enabled = !submitting) { Text(hiltechText("إغلاق", "Close")) } },
        )
    }
}
