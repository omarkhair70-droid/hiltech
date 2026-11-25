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
    var deliveryState by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf(employee.email.orEmpty()) }
    var method by remember { mutableStateOf<AccountDelivery?>(null) }
    var attempted by remember { mutableStateOf<ProvisionEmployeeIdentityRequestDto?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var needsReauthentication by remember { mutableStateOf(false) }

    val showingCredential = credentialVisible
    DisposableEffect(showingCredential) {
        if (showingCredential) onCredentialVisibilityChanged(true)
        onDispose { if (showingCredential) onCredentialVisibilityChanged(false) }
    }

    LaunchedEffect(employee.version, refresh) {
        loading = true
        message = null
        try {
            val result = client.identityInvitationAvailability(employee.employeeId, installationId)
            require(result.employeeId == employee.employeeId && result.organizationId == employee.organizationId)
            availability = result
            val available = AccountDelivery.entries.filter { it.name in result.deliveryModes }
            method = method?.takeIf { it in available } ?: available.firstOrNull()
            result.pending?.let { login = it.requestedLogin }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            availability = null
            message = "CHECK_FAILED"
        } finally {
            loading = false
        }
    }

    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("حساب HILTECH", "HILTECH account"), style = MaterialTheme.typography.titleMedium)
        if (loading) CircularProgressIndicator()
        val current = availability
        val confirmation = when (deliveryState) {
            "SENT" -> hiltechText("تم إرسال رابط إعداد الحساب", "Setup link sent")
            "READY" -> hiltechText("تم إنشاء الحساب", "Account created")
            "FAILED_RETRYABLE", "NOT_CONFIGURED" -> hiltechText("تم إنشاء الحساب · تسليمه يحتاج متابعة", "Account created · delivery needs attention")
            else -> null
        }
        when {
            confirmation != null -> HiltechStateTag(confirmation, HiltechStateTone.CURRENT)
            current?.status == "LINKED" -> HiltechStateTag(hiltechText("حساب مرتبط", "Account linked"), HiltechStateTone.CURRENT)
            current?.status == "NOT_PREBOARDING" -> Text(hiltechText("إنشاء الحساب متاح خلال تجهيز انضمام الموظف.", "Create accounts during preboarding."))
            current?.status == "DELIVERY_UNAVAILABLE" -> Text(hiltechText("تسليم الحساب غير متاح حاليًا. راجع إعدادات الخدمة مع المسؤول.", "Account delivery is currently unavailable. Review service setup with the administrator."))
        }
        credential?.let { oneTimePassword ->
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
            TextButton(onClick = onReauthenticate) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
        }
        if (!loading && confirmation == null && current != null && current.deliveryModes.isNotEmpty()) {
            val pending = current.pending
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                modifier = Modifier.fillMaxWidth(),
                readOnly = pending != null || submitting,
                singleLine = true,
                label = { Text(if (method == AccountDelivery.EMAIL_ACTION_LINK) hiltechText("البريد الإلكتروني للموظف", "Employee email") else hiltechText("اسم تسجيل الدخول", "Sign-in name")) },
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountDelivery.entries.filter { it.name in current.deliveryModes }.forEach { choice ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = method == choice,
                            enabled = pending == null && !submitting,
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
            val selected = method
            Button(
                enabled = selected != null && login.trim().length >= 3 && !submitting && !needsReauthentication,
                onClick = {
                    if (selected == null) return@Button
                    submitting = true
                    message = null
                    val requestedLogin = login.trim()
                    val previous = attempted?.takeIf {
                        it.requestedLogin == requestedLogin && it.deliveryMode == selected.name
                    }
                    val request = ProvisionEmployeeIdentityRequestDto(
                        operationId = pending?.operationId ?: previous?.operationId ?: newOperationId(),
                        baseEmployeeVersion = employee.version,
                        requestedLogin = requestedLogin,
                        deliveryMode = selected.name,
                    )
                    attempted = request
                    jobs.launch {
                        try {
                            val result = client.provisionIdentity(employee.employeeId, request, installationId)
                            require(result.employeeId == employee.employeeId && result.organizationId == employee.organizationId)
                            require(result.state == "READY" && result.deliveryMode == request.deliveryMode)
                            deliveryState = result.deliveryState
                            credential = result.temporaryCredential.takeIf { selected == AccountDelivery.TEMPORARY_PASSWORD_HANDOFF }
                            onAccountReady()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: HiltechApiException) {
                            needsReauthentication = failure.code == "REAUTH_REQUIRED"
                            message = failure.code
                        } catch (_: Exception) {
                            message = "OUTCOME_UNCONFIRMED"
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) { Text(if (pending != null) hiltechText("متابعة إعداد الحساب", "Continue account setup") else hiltechText("إنشاء الحساب", "Create account")) }
        }
        if (!loading && !submitting && !needsReauthentication && (availability == null || message != null)) {
            TextButton(onClick = { refresh += 1 }) { Text(hiltechText("إعادة التحقق", "Check again")) }
        }
    }
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
    "IDENTITY_EMAIL_REQUIRED" -> hiltechText("أدخل بريدًا إلكترونيًا صالحًا.", "Enter a valid email address.")
    else -> hiltechText("تعذر تأكيد إعداد الحساب. أعد المحاولة لمتابعة نفس الطلب.", "Couldn’t confirm account setup. Try again to resume.")
}
