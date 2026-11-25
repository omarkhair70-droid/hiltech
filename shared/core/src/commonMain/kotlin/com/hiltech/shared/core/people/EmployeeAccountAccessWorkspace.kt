package com.hiltech.shared.core.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Credentials stay in transient memory. Saved intent contains only versioned command inputs. */
@Composable
internal fun EmployeeAccountAccessWorkspace(employee: EmployeeDetailDto, client: EmployeeAccountAccessApiClient,
    installationId: String, newOperationId: () -> String, onReauthenticate: () -> Unit,
    onCredentialVisibilityChanged: (Boolean) -> Unit, onAccountChanged: () -> Unit) {
    val jobs = rememberCoroutineScope()
    var current by remember { mutableStateOf<EmployeeAccountAccessDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var reason by rememberSaveable { mutableStateOf("") }
    var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var receiptStatus by remember { mutableStateOf<String?>(null) }
    var credential by remember { mutableStateOf<String?>(null) }
    var credentialVisible by remember { mutableStateOf(false) }
    val reissue = if (pendingKind == "REISSUE") pendingJson?.let { Json.decodeFromString(EmployeeCredentialReissueIntent.serializer(), it) } else null
    val link = if (pendingKind == "LINK") pendingJson?.let { Json.decodeFromString(LinkOwnEmployeeAccountIntent.serializer(), it) } else null

    DisposableEffect(credentialVisible) {
        onCredentialVisibilityChanged(credentialVisible)
        onDispose { if (credentialVisible) onCredentialVisibilityChanged(false) }
    }
    LaunchedEffect(employee.version, refresh) {
        loading = true; loadFailed = false
        try { current = client.current(employee.employeeId, installationId).also { require(it.organizationId == employee.organizationId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { current = null; loadFailed = true }
        finally { loading = false }
    }
    fun begin(kind: String, value: String) {
        pendingKind = kind; pendingJson = value; dialog = true; attempted = false; unknown = false; rejected = false; error = null
    }
    fun clearPending() { pendingKind = null; pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false }

    if (loading || loadFailed || current?.account != null || current?.ownAccountCandidate != null || pendingJson != null || receiptStatus != null) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hiltechText("إدارة الحساب", "Account access"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { refresh += 1 }, enabled = !loading && !submitting) { Text(hiltechText("تحديث", "Refresh")) }
            }
            if (loading) CircularProgressIndicator()
            if (loadFailed) Text(hiltechText("تعذر التحقق من إدارة الحساب. حدّث لإعادة المحاولة.", "Couldn’t check account access. Refresh to retry."), color = MaterialTheme.colorScheme.error)
            when (receiptStatus) {
                "LINKED" -> Text(hiltechText("تم ربط حسابك الحالي بملف الموظف.", "Your current account is linked to the employee record."))
                "LINK_CHANGED" -> Text(hiltechText("تم تسجيل طلب الربط ثم تغيرت حالة الحساب. راجع الحالة الحالية.", "The link request was recorded and account access has since changed. Review the current status."))
                "ISSUED" -> Text(if (credential != null) hiltechText("تم إصدار كلمة مرور مؤقتة جديدة وإبطال السابقة.", "A new temporary password was issued and the previous password replaced.")
                    else hiltechText("سبق إصدار كلمة المرور لهذا الطلب. لا تُعرض مرة أخرى؛ يمكنك إصدار بديل جديد عند الحاجة.", "A password was already issued for this request. It cannot be displayed again; you can issue a new replacement when needed."))
                "UNCONFIRMED" -> Text(hiltechText("لم يتأكد إصدار كلمة المرور السابقة. يمكنك إصدار بديل جديد بتأكيد مستقل؛ سيبطل أي كلمة مرور سبق إصدارها.",
                    "The earlier password issue is unconfirmed. You can explicitly issue a new replacement, which replaces any previously issued password."))
                "REJECTED" -> Text(hiltechText("لم تُصدر كلمة مرور جديدة. راجع حالة الحساب أو الخدمة ثم حدّث البيانات.", "A new password was not issued. Review account or service availability, then refresh."))
            }
            credential?.let { secret ->
                if (credentialVisible) {
                    SelectionContainer { Text(secret, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)) }
                    Text(hiltechText("سلّمها للموظف بشكل خاص. سيختار كلمة مروره عند أول دخول، وتختفي هذه القيمة عند مغادرة الملف.",
                        "Hand it to the employee privately. They will choose their password at first sign-in; this value disappears when you leave the record."))
                    TextButton(onClick = { credential = null; credentialVisible = false }) { Text(hiltechText("تم التسليم", "Handoff complete")) }
                } else TextButton(onClick = { credentialVisible = true }) { Text(hiltechText("إظهار كلمة المرور المؤقتة", "Show one-time password")) }
            }
            if (pendingJson != null && !dialog) TextButton(onClick = { dialog = true }) { Text(hiltechText("متابعة طلب الحساب المحفوظ", "Continue saved account request")) }
            current?.takeUnless { loading }?.let { setup ->
                setup.ownAccountCandidate?.let { candidate ->
                    val accountName = candidate.displayName ?: hiltechText("حسابي المسجل حاليًا", "My signed-in account")
                    Text(hiltechText("إذا كان هذا ملفك الوظيفي، يمكنك ربط الحساب الذي تستخدمه الآن به.",
                        "If this is your employee record, link the account you are currently using to it."))
                    HiltechRecordPicker(hiltechText("الحساب المراد ربطه", "Account to link"), "SELF", listOf(HiltechRecordChoice("SELF", accountName,
                        hiltechText("الحساب المستخدم في هذه الجلسة", "The account used in this session"))), onChange = {},
                        allowKnownReference = false, allowClear = false, enabled = pendingJson == null && !submitting)
                    Button(enabled = pendingJson == null && !submitting, onClick = {
                        begin("LINK", Json.encodeToString(LinkOwnEmployeeAccountIntent.serializer(), LinkOwnEmployeeAccountIntent(
                            newOperationId(), employee.employeeId, employee.organizationId, setup.employeeVersion, candidate.identityVersion,
                            candidate.membershipId, candidate.membershipVersion, accountName)))
                    }) { Text(hiltechText("ربط حسابي الحالي", "Link my current account")) }
                }
                if (setup.account != null) {
                    if (setup.latestReissue?.status == "UNCONFIRMED" && receiptStatus == null) Text(hiltechText(
                        "يوجد طلب إعادة إصدار سابق لم تتأكد نتيجته. أي إصدار جديد سيستبدل كلمة المرور السابقة بتأكيد منك.",
                        "An earlier replacement has an unconfirmed outcome. A new issue will replace the previous password after your explicit confirmation."))
                    if (setup.canReissue) {
                        OutlinedTextField(reason, { reason = it.take(500) }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
                            label = { Text(hiltechText("سبب إعادة إصدار كلمة المرور", "Reason for replacing the password")) },
                            enabled = pendingJson == null && !submitting)
                        Button(enabled = reason.trim().isNotEmpty() && pendingJson == null && !submitting, onClick = {
                            val account = setup.account
                            begin("REISSUE", Json.encodeToString(EmployeeCredentialReissueIntent.serializer(), EmployeeCredentialReissueIntent(
                                newOperationId(), employee.employeeId, employee.organizationId, setup.employeeVersion,
                                account.identityId, account.identityVersion, account.membershipId, account.membershipVersion,
                                setup.latestReissue?.operationId, setup.latestReissue?.version, reason.trim())))
                        }) { Text(hiltechText("إصدار كلمة مرور مؤقتة جديدة", "Issue a new temporary password")) }
                    } else Text(hiltechText("إعادة إصدار كلمة المرور غير متاحة لحالة الموظف أو الخدمة حاليًا.",
                        "Password replacement is currently unavailable for this employee or service."))
                }
            }
        }
    }
    if (dialog && (reissue != null || link != null)) {
        fun close() { dialog = false; if (!attempted || rejected) clearPending() }
        AlertDialog(onDismissRequest = { if (!submitting) close() },
            title = { Text(if (reissue != null) hiltechText("تأكيد إعادة إصدار كلمة المرور", "Confirm password replacement") else hiltechText("تأكيد ربط حسابك", "Confirm your account link")) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(employee.displayName, style = MaterialTheme.typography.titleMedium)
                if (reissue != null) {
                    Text(reissue.reason)
                    Text(hiltechText("ستبطل كلمة المرور السابقة. ستظهر كلمة مؤقتة مرة واحدة لتسليمها للموظف، ويغيّرها عند أول دخول.",
                        "The previous password will be replaced. A temporary password is shown once for private handoff; the employee changes it at first sign-in."))
                } else {
                    Text(requireNotNull(link).accountDisplayName)
                    Text(hiltechText("أؤكد أن هذا ملفي الوظيفي، وأريد ربط حسابي الحالي به. سيستخدم الحساب نفسه مسؤوليات هذا الموظف.",
                        "I confirm this is my employee record and want my current account linked to it. The same account will use this employee’s responsibilities."))
                }
                if (unknown) Text(if (reissue != null) hiltechText("نتيجة الطلب غير مؤكدة. نعيد نفس الطلب للتحقق، دون إصدار كلمة مرور أخرى تلقائيًا.",
                    "The result is unconfirmed. Retry the same request to check it; another password is not issued automatically.")
                    else hiltechText("نتيجة الربط غير مؤكدة. نعيد نفس الطلب للتحقق من ربط حسابك الحالي.",
                        "The link result is unconfirmed. Retry the same request to check your current account link."))
                if (submitting) CircularProgressIndicator()
                error?.let { Text(accountAccessError(it), color = MaterialTheme.colorScheme.error) }
                if (error == "REAUTH_REQUIRED") TextButton(onClick = onReauthenticate, enabled = !submitting) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
            } },
            confirmButton = { TextButton(enabled = !submitting && error != "REAUTH_REQUIRED", onClick = {
                if (rejected) { clearPending(); refresh += 1; return@TextButton }
                submitting = true; error = null
                val priorUnknown = unknown
                jobs.launch {
                    try {
                        if (reissue != null) {
                            val result = client.reissue(reissue, installationId) { attempted = true; unknown = true }
                            receiptStatus = result.status; credential = result.temporaryCredential; credentialVisible = false
                        } else {
                            val result = client.linkOwn(requireNotNull(link), installationId) { attempted = true; unknown = true }
                            receiptStatus = if (result.linked) "LINKED" else "LINK_CHANGED"
                        }
                        clearPending(); reason = ""; refresh += 1; onAccountChanged()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        val result = accountAccessFailureDisposition(priorUnknown, failure)
                        unknown = result.hasUnknownOutcome; rejected = result.rejectionConfirmed
                        error = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                    } finally { submitting = false }
                }
            }) { Text(when { rejected -> hiltechText("تحديث الحالة", "Refresh state"); attempted -> hiltechText("التحقق بنفس الطلب", "Check the same request")
                else -> hiltechText("تأكيد", "Confirm") }) } },
            dismissButton = { TextButton(onClick = { close() }, enabled = !submitting) { Text(if (attempted && !rejected)
                hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع", "Back")) } },
        )
    }
}

@Composable
private fun accountAccessError(code: String): String = when (code) {
    "REAUTH_REQUIRED" -> hiltechText("أكد تسجيل دخولك قبل إدارة بيانات الدخول.", "Confirm your sign-in before managing account access.")
    "PEOPLE_ACCESS_DENIED" -> hiltechText("تحتاج مسؤولية إدارة الموظفين الحالية لهذا الإجراء.", "Current People administration authority is required for this action.")
    "ACCOUNT_CREDENTIAL_SOURCE_CHANGED", "ACCOUNT_CREDENTIAL_HISTORY_CHANGED", "ACCOUNT_LINK_SOURCE_CHANGED" -> hiltechText(
        "تغير الموظف أو حسابه منذ عرضه. حدّث الحالة قبل تأكيد طلب جديد.", "The employee or account changed since it was displayed. Refresh before confirming a new request.")
    "EMPLOYEE_ACCOUNT_RESTRICTED", "ACCOUNT_LINK_CURRENT_IDENTITY_UNAVAILABLE" -> hiltechText(
        "الحساب أو عضويته غير ساريين، أو أنه مرتبط بشخص آخر. لا يمكن تغيير هذا الارتباط هنا.", "The account or membership is restricted, or the account belongs to another person. This action cannot change that ownership.")
    "ACCOUNT_LINK_CONFLICT" -> hiltechText("يوجد حساب أو طلب تجهيز مرتبط بهذا الموظف بالفعل. راجع الحالة الحالية.", "An account or setup request already belongs to this employee. Review the current status.")
    "EMPLOYEE_ACCOUNT_NOT_ELIGIBLE" -> hiltechText("حالة الموظف الحالية لا تسمح بتجهيز الحساب أو إصدار بيانات دخول جديدة.", "The employee’s current state does not permit account setup or new credentials.")
    "ACCOUNT_CREDENTIAL_PROVIDER_UNAVAILABLE" -> hiltechText("خدمة إدارة بيانات الدخول غير متاحة حاليًا. لم نبدأ طلب إصدار جديد؛ حدّث الحالة للمحاولة لاحقًا.",
        "Account credential management is unavailable. A new issue was not started; refresh the status to try again later.")
    "ACCOUNT_CREDENTIAL_COMMAND_BUSY" -> hiltechText("يجري إعداد بيانات دخول هذا الحساب. انتظر قليلًا ثم تحقّق.", "Account credentials are being prepared. Check again shortly.")
    "ACCOUNT_CREDENTIAL_ACCESS_CHANGED_AFTER_DISPATCH" -> hiltechText("سُجّل الطلب ثم تغيرت صلاحية الوصول قبل تسليم النتيجة. أعد التحقق بعد استعادة الوصول المعتمد.",
        "The request was recorded, then access changed before the result was delivered. Check again after approved access is restored.")
    else -> hiltechText("تعذر تأكيد النتيجة. تحقق من الاتصال ثم أعد نفس الطلب المحفوظ.", "Couldn’t confirm the result. Check your connection, then retry the same saved request.")
}
