package com.hiltech.shared.core.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private enum class AccountDelivery {
    EMAIL_ACTION_LINK,
    TEMPORARY_PASSWORD_HANDOFF,
}

@Composable
fun EmployeeAccountWorkspace(
    employee: EmployeeDetailDto,
    sessionScope: String,
    client: OnboardingApiClient,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: () -> Unit,
    onCredentialVisibilityChanged: (Boolean) -> Unit = {},
    onAccountReady: () -> Unit = {},
) = key(sessionScope, employee.organizationId, employee.employeeId) {
    val jobs = rememberCoroutineScope()
    var availability by remember { mutableStateOf<EmployeeIdentityInvitationAvailabilityDto?>(null) }
    var credential by remember { mutableStateOf<String?>(null) }
    var credentialVisible by remember { mutableStateOf(false) }
    var recoveryCredentialVisible by remember { mutableStateOf(false) }
    var deliveryState by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf(employee.email.orEmpty()) }
    var method by remember { mutableStateOf<AccountDelivery?>(null) }
    var attemptedJson by rememberSaveable { mutableStateOf<String?>(null) }
    var unknownOutcome by rememberSaveable { mutableStateOf(false) }
    val attempted = attemptedJson?.let { Json.decodeFromString(ProvisionEmployeeIdentityRequestDto.serializer(), it) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var needsReauthentication by remember { mutableStateOf(false) }

    val showingCredential = credentialVisible || recoveryCredentialVisible
    DisposableEffect(showingCredential) {
        if (showingCredential) onCredentialVisibilityChanged(true)
        onDispose { if (showingCredential) onCredentialVisibilityChanged(false) }
    }

    LaunchedEffect(client, employee.version, refresh) {
        loading = true
        availability = null
        try {
            val result = client.identityInvitationAvailability(employee.employeeId, installationId)
            require(result.employeeId == employee.employeeId && result.organizationId == employee.organizationId)
            availability = result
            val available = AccountDelivery.entries.filter { it.name in result.deliveryModes }
            val pending = result.pending
            if (result.status != "LINKED") {
                credential = null
                credentialVisible = false
                deliveryState = null
            }
            when {
                result.status == "CONTINUE_INVITATION" && pending != null -> {
                    method = available.firstOrNull { it.name == pending.deliveryMode }
                    login = pending.requestedLogin
                }
                result.status == "READY_TO_INVITE" && unknownOutcome && attempted != null -> {
                    method = available.firstOrNull { it.name == attempted.deliveryMode }
                    login = attempted.requestedLogin
                }
                else -> method = method?.takeIf { it in available } ?: available.firstOrNull()
            }
            if (message == "CHECK_FAILED") message = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            availability = null
            needsReauthentication = (failure as? HiltechApiException)?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")
            message = if (needsReauthentication) "REAUTH_REQUIRED" else "CHECK_FAILED"
        } finally {
            loading = false
        }
    }

    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("حساب HILTECH", "HILTECH account"), style = MaterialTheme.typography.titleMedium)
        if (loading) CircularProgressIndicator()
        val current = availability
        val confirmation = when (deliveryState.takeIf { !loading && current?.status == "LINKED" }) {
            "SENT" -> hiltechText("تم إرسال رابط إعداد الحساب", "Setup link sent")
            "READY" -> hiltechText("تم إنشاء الحساب", "Account created")
            "FAILED_RETRYABLE", "NOT_CONFIGURED" -> hiltechText("تم إنشاء الحساب · تسليمه يحتاج متابعة", "Account created · delivery needs attention")
            else -> null
        }
        when {
            current?.status == "ACCOUNT_RESTRICTED" -> Text(hiltechText("هذا الموظف لديه حساب موقوف أو عضوية غير سارية. راجع حالة الحساب قبل متابعة الإعداد.", "This employee has a restricted account or inactive membership. Review the account status before continuing setup."))
            confirmation != null -> HiltechStateTag(confirmation, HiltechStateTone.CURRENT)
            current?.status == "LINKED" -> HiltechStateTag(hiltechText("حساب مرتبط", "Account linked"), HiltechStateTone.CURRENT)
            current?.status in setOf("NOT_ELIGIBLE", "NOT_PREBOARDING") -> Text(hiltechText("إنشاء الحساب متاح للموظف النشط أو الجاري تجهيز انضمامه.", "Account setup is available for active employees and employees preparing to join."))
            current?.status == "ACCOUNT_ATTENTION" -> Text(hiltechText("يوجد حساب قيد الإعداد يحتاج مراجعة قبل المتابعة. لا تنشئ حسابًا بديلًا.", "An existing account setup needs attention before continuing."))
            current?.status == "DELIVERY_UNAVAILABLE" -> Text(hiltechText("تسليم الحساب غير متاح حاليًا. راجع إعدادات الخدمة مع المسؤول.", "Account delivery is currently unavailable. Review service setup with the administrator."))
        }
        if (!loading && current?.status == "LINKED" && unknownOutcome) {
            Text(hiltechText("الحساب مرتبط الآن، لكن لم يتأكد تسليم الطلب السابق. راجع إدارة الحساب أدناه.",
                "The account is linked now, but delivery of the previous request is unconfirmed. Review account access below."),
                style = MaterialTheme.typography.bodySmall)
        }
        credential?.takeIf { !loading && current?.status == "LINKED" }?.let { oneTimePassword ->
            if (credentialVisible) {
                Text(hiltechText("كلمة مرور مؤقتة", "Temporary password"), style = MaterialTheme.typography.titleSmall)
                Text(oneTimePassword, style = MaterialTheme.typography.bodyLarge)
                Text(hiltechText("سلّمها للموظف بشكل خاص. ستختفي عند مغادرة هذه الشاشة.", "Give it to the employee privately. It will disappear when you leave this screen."))
                TextButton(onClick = {
                    credentialVisible = false
                    credential = null
                }) { Text(hiltechText("تم", "Done")) }
            } else {
                TextButton(onClick = { credentialVisible = true }) { Text(hiltechText("إظهار كلمة المرور المؤقتة", "Show one-time password")) }
            }
        }
        message?.let { Text(accountSetupMessage(it), color = MaterialTheme.colorScheme.error) }
        if (needsReauthentication) {
            TextButton(enabled = !submitting, onClick = onReauthenticate) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
        }
        if (!loading && confirmation == null && current != null && accountSetupCanSubmit(current)) {
            val pending = current.pending
            val frozen = pending == null && unknownOutcome && attempted != null
            val proposed = accountSetupRequest(employee, current, attempted.takeIf { frozen },
                login, method?.name, newOperationId = { "" })
            if (frozen && proposed == null) Text(hiltechText(
                "لا يمكن متابعة الطلب السابق بالشروط الحالية. حدّث سجل الموظف وراجع حالة الحساب.",
                "The previous request cannot continue with the current source. Refresh the employee and review account access."),
                color = MaterialTheme.colorScheme.error)
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                modifier = Modifier.fillMaxWidth(),
                readOnly = pending != null || submitting || frozen,
                singleLine = true,
                label = { Text(if (method == AccountDelivery.EMAIL_ACTION_LINK) hiltechText("البريد الإلكتروني للموظف", "Employee email") else hiltechText("اسم تسجيل الدخول", "Sign-in name")) },
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountDelivery.entries.filter { choice ->
                    choice.name in current.deliveryModes && (pending == null || choice.name == pending.deliveryMode) &&
                        (!frozen || choice.name == attempted?.deliveryMode)
                }.forEach { choice ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = method == choice,
                            enabled = pending == null && !submitting && !frozen,
                            role = Role.RadioButton,
                            onClick = { method = choice },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(selected = method == choice, onClick = null)
                        Column {
                            Text(accountDeliveryTitle(choice), style = MaterialTheme.typography.titleSmall)
                            Text(accountDeliveryExplanation(choice), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (frozen) Text(hiltechText("نتيجة الطلب السابق غير مؤكدة. سنعيد نفس الطلب للتحقق منها.",
                "The previous result is unconfirmed. Retry the same saved request to check its outcome."))
            if (pending != null) Text(hiltechText("يوجد طلب إعداد محفوظ على السيرفر. ستتم متابعة نفس الطلب، وليس إنشاء حساب آخر.",
                "The server has a pending setup request. Continue that request rather than create another account."),
                style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = proposed != null && !submitting && !needsReauthentication,
                onClick = {
                    if (submitting || loading || needsReauthentication || availability != current) return@Button
                    val request = accountSetupRequest(employee, current, attempted.takeIf { frozen },
                        login, method?.name, newOperationId) ?: return@Button
                    submitting = true
                    message = null
                    attemptedJson = Json.encodeToString(ProvisionEmployeeIdentityRequestDto.serializer(), request)
                    val previouslyUnknown = unknownOutcome
                    unknownOutcome = true
                    jobs.launch {
                        try {
                            val result = client.provisionIdentity(employee.employeeId, request, installationId)
                            require(result.employeeId == employee.employeeId && result.organizationId == employee.organizationId)
                            require(result.state == "READY" && result.deliveryMode == request.deliveryMode)
                            deliveryState = result.deliveryState
                            unknownOutcome = false
                            attemptedJson = null
                            credential = result.temporaryCredential.takeIf { request.deliveryMode == AccountDelivery.TEMPORARY_PASSWORD_HANDOFF.name }
                            loading = true
                            refresh += 1
                            onAccountReady()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: HiltechApiException) {
                            needsReauthentication = failure.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")
                            message = failure.code
                            unknownOutcome = if (failure.code == "TEMPORARY_CREDENTIAL_NOT_REPLAYABLE") false
                                else companyResponsibilityFailureDisposition(previouslyUnknown, failure).hasUnknownOutcome
                            if (!unknownOutcome) attemptedJson = null
                            loading = true
                            refresh += 1
                        } catch (_: Exception) {
                            message = "OUTCOME_UNCONFIRMED"
                            loading = true
                            refresh += 1
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) { Text(if (pending != null || frozen) hiltechText("متابعة إعداد الحساب", "Continue account setup") else hiltechText("إنشاء الحساب", "Create account")) }
        }
        if (!loading && !submitting && !needsReauthentication && (availability == null || message != null)) {
            TextButton(onClick = { loading = true; refresh += 1 }) { Text(hiltechText("إعادة التحقق", "Check again")) }
        }
    }
    EmployeeAccountAccessWorkspace(employee, client.accountAccess, installationId, newOperationId, onReauthenticate,
        onCredentialVisibilityChanged = { recoveryCredentialVisible = it },
        onAccountChanged = {
            credential = null; credentialVisible = false; deliveryState = null; loading = true; refresh += 1
            onAccountReady()
        })
}

internal fun accountSetupCanSubmit(availability: EmployeeIdentityInvitationAvailabilityDto): Boolean {
    val known = AccountDelivery.entries.map { it.name }.toSet()
    return when (availability.status) {
        "READY_TO_INVITE" -> availability.pending == null && availability.deliveryModes.any { it in known }
        "CONTINUE_INVITATION" -> availability.pending?.let {
            it.deliveryMode in known && it.deliveryMode in availability.deliveryModes
        } == true
        else -> false
    }
}

/** The current server state is authoritative. Pending invitations have a source-owned
 * identity, login and delivery mode. An unconfirmed local request is only replayed
 * verbatim when the source still permits new provisioning. Neither a restricted
 * account nor an unsupported delivery method can be overridden by local intent.
 */
internal fun accountSetupRequest(
    employee: EmployeeDetailDto,
    availability: EmployeeIdentityInvitationAvailabilityDto,
    unresolved: ProvisionEmployeeIdentityRequestDto?,
    login: String,
    deliveryMode: String?,
    newOperationId: () -> String,
): ProvisionEmployeeIdentityRequestDto? {
    if (availability.employeeId != employee.employeeId || availability.organizationId != employee.organizationId ||
        employee.version < 1 || employee.employeeState !in setOf("ACTIVE", "PREBOARDING") ||
        !accountSetupCanSubmit(availability)) return null
    availability.pending?.let {
        // A pending source invitation is already bound to these fields. The
        // server checks the current employee version for continuation.
        return ProvisionEmployeeIdentityRequestDto(it.operationId, employee.version, it.requestedLogin, it.deliveryMode)
    }
    val modes = AccountDelivery.entries.map { it.name }.filter { it in availability.deliveryModes }
    if (unresolved != null) return unresolved.takeIf { it.deliveryMode in modes }
    val mode = deliveryMode?.takeIf { it in modes } ?: return null
    val requestedLogin = login.trim()
    if (requestedLogin.length < 3) return null
    if (mode == AccountDelivery.EMAIL_ACTION_LINK.name &&
        (requestedLogin.count { it == '@' } != 1 || requestedLogin.startsWith('@') ||
            requestedLogin.endsWith('@') || requestedLogin.any(Char::isWhitespace))) return null
    return ProvisionEmployeeIdentityRequestDto(newOperationId(), employee.version, requestedLogin, mode)
}

@Composable
private fun accountDeliveryTitle(delivery: AccountDelivery): String = when (delivery) {
    AccountDelivery.EMAIL_ACTION_LINK -> hiltechText("إرسال رابط إعداد الحساب", "Send setup link")
    AccountDelivery.TEMPORARY_PASSWORD_HANDOFF -> hiltechText("تسليم الحساب شخصيًا", "Set up in person")
}

@Composable
private fun accountDeliveryExplanation(delivery: AccountDelivery): String = when (delivery) {
    AccountDelivery.EMAIL_ACTION_LINK -> hiltechText("يختار الموظف كلمة مروره بنفسه من الرابط.", "The employee sets their own password from the link.")
    AccountDelivery.TEMPORARY_PASSWORD_HANDOFF -> hiltechText("أكد تسجيل دخولك لاستلام كلمة مرور مؤقتة وتسليمها للموظف بشكل خاص.", "Confirm your sign-in to receive a one-time password to hand over privately.")
}

@Composable
private fun accountSetupMessage(code: String): String = when (code) {
    "CHECK_FAILED" -> hiltechText("تعذر التحقق من إعداد الحساب.", "Couldn’t check account setup.")
    "REAUTH_REQUIRED" -> hiltechText("أكد تسجيل الدخول، ثم افتح الموظف مجددًا لإكمال إعداد حسابه.", "Confirm your sign-in, then reopen this employee to finish account setup.")
    "TEMPORARY_CREDENTIAL_NOT_REPLAYABLE" -> hiltechText("الحساب مرتبط. سبق إصدار كلمة مروره المؤقتة ولا يمكن عرضها مجددًا.", "The account is linked. Its temporary password was already issued.")
    "EMPLOYEE_VERSION_CONFLICT" -> hiltechText("تغير سجل الموظف. حدّث السجل قبل المتابعة.", "The employee changed. Refresh the record before continuing.")
    "EMPLOYEE_ACCOUNT_NOT_ELIGIBLE", "IDENTITY_EMPLOYEE_NOT_ELIGIBLE" -> hiltechText("حالة الموظف الحالية لا تسمح بإنشاء حساب. راجع سجل الموظف.", "The employee’s current status does not allow account setup. Review the employee record.")
    "EMPLOYEE_ACCOUNT_RESTRICTED", "IDENTITY_ACCOUNT_RESTRICTED", "IDENTITY_MEMBERSHIP_CONFLICT",
    "IDENTITY_LOCAL_ACTIVATION_FAILED", "IDENTITY_MEMBERSHIP_ACTIVATION_FAILED" -> hiltechText("الحساب أو عضويته غير ساريين. راجع حالة الحساب قبل المتابعة.", "The account or its membership is restricted. Review its status before continuing.")
    "EMPLOYEE_IDENTITY_ALREADY_LINKED", "IDENTITY_PERSON_ALREADY_LINKED", "IDENTITY_PROVIDER_SUBJECT_CONFLICT" -> hiltechText("يوجد حساب مرتبط بالفعل. راجع الحساب الحالي لتجنب إنشاء حساب آخر.", "An account is already linked. Review the existing account before continuing.")
    "IDENTITY_EMAIL_REQUIRED" -> hiltechText("أدخل بريدًا إلكترونيًا صالحًا.", "Enter a valid email address.")
    else -> hiltechText("تعذر تأكيد إعداد الحساب. أعد المحاولة لمتابعة نفس الطلب.", "Couldn’t confirm account setup. Try again to resume.")
}
