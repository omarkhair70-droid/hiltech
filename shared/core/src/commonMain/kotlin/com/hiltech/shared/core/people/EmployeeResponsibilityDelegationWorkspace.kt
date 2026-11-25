package com.hiltech.shared.core.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
internal fun EmployeeResponsibilityDelegationWorkspace(employee: EmployeeResponsibilityTargetDto,
    client: ResponsibilityDelegationApiClient, installationId: String, newOperationId: () -> String, onReauthenticate: (() -> Unit)?) {
    val jobs = rememberCoroutineScope()
    var current by remember { mutableStateOf<EmployeeResponsibilityDelegationDto?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedCsv by rememberSaveable { mutableStateOf("") }
    var onward by rememberSaveable { mutableStateOf(false) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    val selected = selectedCsv.split(',').filter { it in employeeCompanyResponsibilityKeys }.toSet()
    val pending = pendingJson?.let { Json.decodeFromString(ResponsibilityDelegationIntent.serializer(), it) }
    LaunchedEffect(employee.version, refresh) {
        loading = true; loadError = null
        try { current = client.current(employee.employeeId, installationId).also { require(it.organizationId == employee.organizationId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { current = null; loadError = (failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { loading = false }
    }
    fun prepare(keys: List<String>, binding: ResponsibilityDelegationBindingDto? = null) {
        val setup = current ?: return
        val identity = setup.linkedIdentityId ?: return
        pendingJson = Json.encodeToString(ResponsibilityDelegationIntent.serializer(), ResponsibilityDelegationIntent(
            newOperationId(), setup.employeeId, setup.organizationId, identity, setup.employeeVersion, employee.displayName,
            keys.sorted(), if (binding == null) onward else false, if (binding == null) "GRANT" else "END", binding?.bindingId, binding?.version))
        attempted = false; unknown = false; rejected = false; error = null; acknowledged = false; dialog = true
    }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(hiltechText("تفويض إدارة المسؤوليات", "Delegate responsibility administration"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refresh += 1 }, enabled = !loading && !submitting) { Text(hiltechText("تحديث", "Refresh")) }
        }
        Text(hiltechText("هذا التفويض يسمح بإسناد أدوار محددة للموظفين وإنهائها. لا يمنح تنفيذ تلك الأدوار تلقائيًا.",
            "This delegation permits assigning and ending specified employee roles. It does not automatically grant the roles themselves."))
        if (loading) CircularProgressIndicator()
        loadError?.let { Text(delegationError(it), color = MaterialTheme.colorScheme.error) }
        if (acknowledged) Text(hiltechText("تم تسجيل تغيير التفويض. يعرض التحديث الحالة الحالية.",
            "The delegation change was recorded. Refresh shows the current state."), color = MaterialTheme.colorScheme.primary)
        if (pending != null && !dialog) TextButton(onClick = { dialog = true }) { Text(hiltechText("متابعة طلب التفويض المحفوظ", "Continue saved delegation request")) }
        current?.takeUnless { loading }?.let { setup ->
            setup.bindings.forEach { binding ->
                HorizontalDivider()
                Text(companyResponsibilityLabel(binding.authorityKey), style = MaterialTheme.typography.titleSmall)
                Text(if (binding.canDelegateAdministration) hiltechText("يمكنه إدارة هذا الدور وتفويض إدارته لغيره.",
                    "May manage this role and delegate its administration to others.") else hiltechText("يمكنه إدارة هذا الدور دون تفويض إدارته لغيره.",
                    "May manage this role without delegating its administration to others."), style = MaterialTheme.typography.bodySmall)
                if (binding.canEnd) TextButton(onClick = { prepare(listOf(binding.authorityKey), binding) }, enabled = pending == null && !submitting) {
                    Text(hiltechText("إنهاء التفويض", "End delegation"))
                } else Text(hiltechText("فوّض إدارة هذا الدور لموظف آخر أولًا، حتى تظل للشركة قدرة على إدارته.",
                    "Delegate this role’s administration to another employee first so the company can continue managing it."), style = MaterialTheme.typography.bodySmall)
            }
            val choices = setup.delegableAuthorityKeys.filter { it in employeeCompanyResponsibilityKeys && setup.bindings.none { row -> row.authorityKey == it } }
            if (setup.canGrant && choices.isNotEmpty()) {
                HorizontalDivider()
                Text(hiltechText("الأدوار التي سيتولى إدارتها", "Roles this person may administer"), style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = pending == null && !submitting, onClick = { selectedCsv = choices.sorted().joinToString(",") }) {
                    Text(hiltechText("اختيار الأدوار المتاحة", "Select available roles"))
                }
                choices.forEach { role -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(role in selected, onCheckedChange = { checked -> selectedCsv = (if (checked) selected + role else selected - role).sorted().joinToString(",") },
                        enabled = pending == null && !submitting)
                    Text(companyResponsibilityLabel(role))
                } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onward, onCheckedChange = { onward = it }, enabled = pending == null && !submitting)
                    Text(hiltechText("السماح له بتفويض إدارة هذه الأدوار لغيره", "Allow onward delegation of these roles’ administration"))
                }
                Button(onClick = { prepare(selected.filter { it in choices }) },
                    enabled = pending == null && !submitting && selected.any { it in choices }) { Text(hiltechText("مراجعة التفويض", "Review delegation")) }
            } else if (!setup.canGrant && setup.linkedIdentityId == null) Text(hiltechText("جهّز الحساب المرتبط أولًا قبل تفويض الإدارة.",
                "Prepare the linked account before delegating administration."))
            else if (!setup.canGrant) Text(hiltechText("لا يمكن زيادة تفويضك الإداري بنفسك. يمكن إنهاؤه بعد نقل الإدارة لموظف مفوض آخر.",
                "You cannot increase your own administration delegation. It can be ended after transferring administration to another authorized employee."))
        }
    }
    pending?.takeIf { dialog }?.let { intent ->
        fun close() { dialog = false; if (!attempted) pendingJson = null }
        AlertDialog(onDismissRequest = { if (!submitting) close() }, title = { Text(hiltechText("تأكيد تغيير التفويض", "Confirm administration change")) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(intent.employeeDisplayName, style = MaterialTheme.typography.titleMedium)
                intent.authorityKeys.forEach { Text(companyResponsibilityLabel(it)) }
                Text(if (intent.action == "END") hiltechText("ينتهي حق إدارة هذه الأدوار. تبقى الأدوار التنفيذية المسندة له خاضعة لتكليفاتها المنفصلة.",
                    "The right to administer these roles ends. Operational roles remain governed by their separate assignments.")
                else if (intent.canDelegateAdministration) hiltechText("سيستطيع إسناد هذه الأدوار للموظفين وتفويض إدارتها لغيره ضمن النطاق نفسه.",
                    "May assign these roles to employees and delegate their administration within the same scope.")
                else hiltechText("سيستطيع إسناد هذه الأدوار للموظفين، دون تفويض إدارتها لغيره.",
                    "May assign these roles to employees, without delegating their administration further."))
                if (submitting) CircularProgressIndicator()
                error?.let { Text(delegationError(it), color = MaterialTheme.colorScheme.error) }
                if (error == "REAUTH_REQUIRED" && onReauthenticate != null) TextButton(onClick = onReauthenticate) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
                if (unknown) Text(hiltechText("النتيجة غير مؤكدة. أعد نفس الطلب المحفوظ لتأكيد ما حدث.", "The outcome is unconfirmed. Retry the same saved request."))
                if (rejected) Text(hiltechText("لم يُسجّل الطلب. حدّث الحالة قبل إعداد تغيير جديد.", "The request was not recorded. Refresh before preparing a new change."))
            } }, confirmButton = { TextButton(enabled = !submitting, onClick = {
                if (rejected) { pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false; refresh += 1; return@TextButton }
                submitting = true; error = null
                val priorUnknown = unknown
                jobs.launch {
                    try {
                        client.apply(intent, installationId) { attempted = true; unknown = true }
                        pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false; selectedCsv = ""; acknowledged = true; refresh += 1
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                        val state = companyResponsibilityFailureDisposition(priorUnknown, failure); unknown = state.hasUnknownOutcome; rejected = state.rejectionConfirmed }
                    finally { submitting = false }
                }
            }) { Text(if (rejected) hiltechText("تحديث الحالة", "Refresh state") else if (attempted) hiltechText("إعادة نفس الطلب", "Retry same request") else hiltechText("تأكيد", "Confirm")) } },
            dismissButton = { TextButton(onClick = { close() }, enabled = !submitting) { Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع", "Back")) } })
    }
}

@Composable
private fun delegationError(code: String): String = when (code) {
    "RESPONSIBILITY_DELEGATION_TRANSFER_REQUIRED" -> hiltechText("هذا آخر مفوض يمكنه إدارة هذا الدور. فوّض موظفًا آخر أولًا ثم أعد المحاولة.",
        "This is the last delegating administrator for this role. Transfer administration to another employee first.")
    "RESPONSIBILITY_ADMINISTRATION_DELEGATION_DENIED" -> hiltechText("تفويضك الحالي لا يسمح بتغيير تفويض الإدارة في هذا النطاق.",
        "Your current authority does not permit changing administration delegation in this scope.")
    "RESPONSIBILITY_EMPLOYEE_CHANGED", "RESPONSIBILITY_VERSION_CONFLICT" -> hiltechText("تغير الحساب أو التفويض. حدّث الحالة أولًا.", "The account or delegation changed. Refresh first.")
    "REAUTH_REQUIRED" -> hiltechText("أكد تسجيل الدخول ثم أعد المحاولة.", "Confirm your sign-in, then retry.")
    else -> hiltechText("تعذر تأكيد التفويض. راجع الاتصال والصلاحية ثم أعد نفس الطلب المحفوظ.",
        "Couldn’t confirm delegation. Check connection and authority, then retry the same saved request.")
}
