package com.hiltech.shared.core.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.key
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
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Shared native composition; the host supplies the existing authenticated API/session. */
@Composable
fun EmployeeCompanyResponsibilitiesWorkspace(
    employee: EmployeeDetailDto,
    sessionScope: String,
    client: CompanyResponsibilitiesApiClient,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: (() -> Unit)? = null,
) = EmployeeCompanyResponsibilitiesWorkspace(
    EmployeeResponsibilityTargetDto(employee.employeeId, employee.organizationId, employee.version, employee.displayName),
    sessionScope, client, installationId, newOperationId, onReauthenticate,
)

@Composable
fun EmployeeCompanyResponsibilitiesWorkspace(
    employee: EmployeeResponsibilityTargetDto,
    sessionScope: String,
    client: CompanyResponsibilitiesApiClient,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: (() -> Unit)? = null,
) = key(sessionScope, employee.organizationId, employee.employeeId) {
    val jobs = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<EmployeeCompanyResponsibilitiesDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedKey by rememberSaveable { mutableStateOf("") }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknownOutcome by rememberSaveable { mutableStateOf(false) }
    var rejectionConfirmed by rememberSaveable { mutableStateOf(false) }
    var commandError by remember { mutableStateOf<String?>(null) }
    var acknowledgement by remember { mutableStateOf<String?>(null) }
    var delegationWasVisible by rememberSaveable { mutableStateOf(false) }
    val pending = pendingJson?.let { Json.decodeFromString(EmployeeCompanyResponsibilityIntent.serializer(), it) }

    LaunchedEffect(employee.version, refresh) {
        loading = true
        loadFailed = false
        try {
            val current = client.current(employee.employeeId, installationId)
            require(current.organizationId == employee.organizationId)
            snapshot = current
            if (current.canDelegateAdministration) delegationWasVisible = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snapshot = null
            loadFailed = true
        } finally {
            loading = false
        }
    }
    val current = snapshot
    val choices = current?.catalog.orEmpty().filter { item ->
        item.canGrant && item.authorityKey in employeeCompanyResponsibilityKeys &&
            current?.bindings.orEmpty().none { it.authorityKey == item.authorityKey && it.assignmentSource == "DIRECT" && it.current }
    }
    LaunchedEffect(choices) {
        if (choices.none { it.authorityKey == selectedKey }) selectedKey = choices.firstOrNull()?.authorityKey.orEmpty()
    }

    fun prepare(authorityKey: String, binding: CompanyResponsibilityBindingDto? = null) {
        val source = current ?: return
        val identity = source.linkedIdentityId ?: return
        pendingJson = Json.encodeToString(EmployeeCompanyResponsibilityIntent.serializer(), EmployeeCompanyResponsibilityIntent(
            newOperationId(), source.employeeId, source.organizationId, source.employeeVersion, identity,
            authorityKey, employee.displayName, if (binding == null) "GRANT" else "END", binding?.bindingId, binding?.version,
        ))
        attempted = false
        unknownOutcome = false
        rejectionConfirmed = false
        commandError = null
        acknowledgement = null
        dialogVisible = true
    }

    HiltechPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hiltechText("مسؤوليات الموظف", "Employee responsibilities"), style = MaterialTheme.typography.titleMedium)
                Text(hiltechText("أدوار العمل داخل الشركة", "Company-wide operational roles"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { refresh += 1 }, enabled = !loading && !submitting) { Text(hiltechText("تحديث", "Refresh")) }
        }
        Text(hiltechText(
            "يمكن جمع أكثر من مسؤولية في الحساب نفسه. تظهر تكليفات المشاريع والمخازن المحددة في مساحاتها، وتظل اعتمادات العمليات منفصلة.",
            "An account can hold several responsibilities. Project and warehouse assignments appear in their own sections; transaction approvals remain separate.",
        ), style = MaterialTheme.typography.bodyMedium)
        if (current?.viewScope == "DELEGATED_KEYS") Text(hiltechText(
            "تظهر التكليفات ضمن مسؤوليات الإدارة المفوضة لك فقط.", "Assignments are shown only within your delegated administration scope.",
        ), style = MaterialTheme.typography.bodySmall)
        if (loading) CircularProgressIndicator()
        if (loadFailed) Text(hiltechText(
            "تعذر تحميل المسؤوليات. تحقق من الاتصال وصلاحيتك ثم حدّث الصفحة.",
            "Couldn’t load responsibilities. Check your connection and access, then refresh.",
        ), color = MaterialTheme.colorScheme.error)
        acknowledgement?.let { code -> Text(when (code) {
            "GRANT_ENDED" -> hiltechText("تأكد تسجيل الإسناد، لكنه انتهى بعد ذلك. يعرض التحديث الحالة الحالية.",
                "The assignment was recorded and later ended. Refresh shows current access.")
            "END" -> hiltechText("تم إنهاء التكليف المباشر. قد يستمر وصول يأتي من فريق أو تكليف آخر.",
                "The direct assignment ended. Access inherited from a team or another assignment may remain.")
            else -> hiltechText("تم إسناد المسؤولية. يُعاد التحقق من الصلاحية عند استخدام الوظائف التابعة لها.",
                "The responsibility was assigned. Its access is checked again when the employee uses the related functions.")
        }, color = MaterialTheme.colorScheme.primary) }
        if (pending != null && !dialogVisible) {
            Text(hiltechText("يوجد طلب محفوظ يحتاج تأكيد النتيجة.", "A saved request needs its outcome confirmed."))
            TextButton(onClick = { dialogVisible = true }) { Text(hiltechText("متابعة الطلب", "Continue request")) }
        }
        if (!loading && current != null) {
            if (current.accountStatus != "CURRENT") {
                Text(hiltechText("جهّز الحساب المرتبط وعضويته أولًا، ثم أسند مسؤوليات الموظف.",
                    "Prepare the linked account and membership first, then assign responsibilities."))
            } else {
                if (current.bindings.isEmpty()) Text(hiltechText(
                    "لم تُسند لهذا الحساب مسؤوليات من أدوار الشركة المعروضة هنا بعد.",
                    "This account has no assignments from the company roles shown here yet.",
                ))
                current.bindings.forEach { binding ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(companyResponsibilityLabel(binding.authorityKey), style = MaterialTheme.typography.titleSmall)
                        Text(companyResponsibilityDescription(binding.authorityKey),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (binding.assignmentSource == "TEAM") Text(hiltechText(
                            "من عضوية فريق — تُدار من تكليف الفريق.", "Inherited from a team — managed through that team’s assignment.",
                        ), style = MaterialTheme.typography.labelMedium)
                        else if (binding.canEnd && binding.authorityKey in employeeCompanyResponsibilityKeys) {
                            TextButton(onClick = { prepare(binding.authorityKey, binding) }, enabled = pending == null && !submitting) {
                                Text(hiltechText("إنهاء التكليف", "End assignment"))
                            }
                        } else Text(hiltechText("تكليف مباشر · خارج تفويضك الحالي", "Direct assignment · outside your current delegation"),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (choices.isNotEmpty()) {
                    HorizontalDivider()
                    HiltechRecordPicker(
                        label = hiltechText("إضافة مسؤولية", "Add responsibility"), value = selectedKey,
                        choices = choices.map { HiltechRecordChoice(it.authorityKey,
                            companyResponsibilityLabel(it.authorityKey), companyResponsibilityDescription(it.authorityKey)) },
                        onChange = { selectedKey = it }, allowKnownReference = false, allowClear = false,
                        enabled = pending == null && !submitting,
                    )
                    Button(onClick = { prepare(selectedKey) }, enabled = pending == null && !submitting && choices.any { it.authorityKey == selectedKey }) {
                        Text(hiltechText("مراجعة الإسناد", "Review assignment"))
                    }
                } else if (current.catalog.none { it.canGrant } && current.bindings.none { it.canEnd }) {
                    Text(hiltechText(
                        "يعرض هذا الحساب المسؤوليات فقط. إسنادها وتعديلها يتطلب تفويضًا صريحًا بإدارة هذه الأدوار.",
                        "Your current access permits viewing these responsibilities. Assigning or changing them requires explicit delegation for these roles.",
                    ), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (delegationWasVisible) EmployeeResponsibilityDelegationWorkspace(
        employee, client.delegation, installationId, newOperationId, onReauthenticate,
    )

    pending?.takeIf { dialogVisible }?.let { intent ->
        fun close() {
            dialogVisible = false
            if (!attempted) pendingJson = null
        }
        AlertDialog(
            onDismissRequest = { if (!submitting) close() },
            title = { Text(if (intent.action == "GRANT") hiltechText("تأكيد المسؤولية", "Confirm responsibility")
                else hiltechText("إنهاء التكليف", "End assignment")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(intent.employeeDisplayName, style = MaterialTheme.typography.titleMedium)
                    Text(companyResponsibilityLabel(intent.authorityKey), style = MaterialTheme.typography.titleSmall)
                    Text(if (intent.action == "GRANT") companyResponsibilityDescription(intent.authorityKey)
                        else hiltechText("ينتهي هذا التكليف المباشر فورًا. يظل الوصول الناتج عن أي فريق أو تكليف آخر قائمًا وفق مصدره.",
                            "This direct assignment ends immediately. Access inherited from a team or another assignment remains governed by its source."))
                    if (submitting) CircularProgressIndicator()
                    commandError?.let { Text(companyResponsibilityError(it), color = MaterialTheme.colorScheme.error) }
                    if (commandError == "REAUTH_REQUIRED" && onReauthenticate != null) {
                        TextButton(onClick = onReauthenticate, enabled = !submitting) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
                    }
                    if (unknownOutcome) Text(hiltechText(
                        "لم تصل نتيجة مؤكدة. احتُفظ بنفس الطلب والحساب المختار لإعادة المحاولة دون تكرار الإسناد.",
                        "The result is not confirmed. The same request and selected account are retained for a safe retry.",
                    ))
                    if (rejectionConfirmed) Text(hiltechText("لم يُسجّل الطلب. راجع الحالة الحالية قبل إنشاء تغيير جديد.",
                        "The request was not recorded. Review current state before creating a new change."))
                }
            },
            confirmButton = {
                TextButton(enabled = !submitting, onClick = {
                    if (rejectionConfirmed) {
                        pendingJson = null
                        dialogVisible = false
                        attempted = false
                        unknownOutcome = false
                        rejectionConfirmed = false
                        commandError = null
                        refresh += 1
                        return@TextButton
                    }
                    submitting = true
                    commandError = null
                    val priorUnknown = unknownOutcome
                    jobs.launch {
                        try {
                            val result = client.apply(intent, installationId) { attempted = true; unknownOutcome = true }
                            pendingJson = null
                            dialogVisible = false
                            attempted = false
                            unknownOutcome = false
                            rejectionConfirmed = false
                            acknowledgement = if (intent.action == "GRANT" && !result.current) "GRANT_ENDED" else intent.action
                            refresh += 1
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            commandError = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                            val disposition = companyResponsibilityFailureDisposition(priorUnknown, failure)
                            unknownOutcome = disposition.hasUnknownOutcome
                            rejectionConfirmed = disposition.rejectionConfirmed
                        } finally {
                            submitting = false
                        }
                    }
                }) { Text(when {
                    rejectionConfirmed -> hiltechText("مراجعة الحالة الحالية", "Review current state")
                    attempted -> hiltechText("إعادة نفس الطلب", "Retry same request")
                    else -> hiltechText("تأكيد", "Confirm")
                }) }
            },
            dismissButton = { TextButton(onClick = { close() }, enabled = !submitting) {
                Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع", "Back"))
            } },
        )
    }
}

@Composable
internal fun companyResponsibilityLabel(key: String): String = when (key) {
    "PEOPLE_ADMIN" -> hiltechText("إدارة الموظفين", "People administration")
    "PROJECT_ADMIN" -> hiltechText("إدارة محفظة المشاريع", "Project portfolio administration")
    "SALES_VIEW" -> hiltechText("متابعة المبيعات", "Sales visibility")
    "SALES_WRITE" -> hiltechText("إعداد المبيعات والعروض", "Sales and quotation preparation")
    "SALES_COST_VIEW" -> hiltechText("عرض تكاليف العروض", "Quotation cost visibility")
    "SALES_RELEASE" -> hiltechText("إصدار العروض", "Quotation release")
    "SALES_AWARD" -> hiltechText("تسجيل ترسية المبيعات", "Sales award recording")
    "PAYROLL_READ" -> hiltechText("عرض الرواتب", "Payroll visibility")
    "PAYROLL_PREPARE" -> hiltechText("إعداد الرواتب", "Payroll preparation")
    "PAYROLL_REVIEW" -> hiltechText("مراجعة الرواتب", "Payroll review")
    "SUPPLIER_MASTER_DATA_ADMIN" -> hiltechText("إدارة سجل الموردين", "Supplier register administration")
    "APPROVAL_POLICY_ADMIN" -> hiltechText("إعداد مسارات الاعتماد", "Approval workflow setup")
    "FINANCE_PAYROLL_PREPARE" -> hiltechText("تسجيل نتائج صرف الرواتب", "Payroll disbursement recording")
    "FINANCE_PAYROLL_REVIEW" -> hiltechText("مراجعة نتائج صرف الرواتب", "Payroll disbursement review")
    else -> hiltechText("مسؤولية أخرى", "Other responsibility")
}

@Composable
private fun companyResponsibilityDescription(key: String): String = when (key) {
    "PEOPLE_ADMIN" -> hiltechText("إنشاء الموظفين وإدارة بياناتهم وتجهيز حساباتهم. إسناد الأدوار يخضع لتفويض منفصل.",
        "Create employees, manage their records, and prepare accounts. Assigning roles requires separate delegation.")
    "PROJECT_ADMIN" -> hiltechText("إنشاء المشاريع والمواقع وإدارة محفظة الشركة وفق قواعد كل عملية.",
        "Create projects and sites and administer the company portfolio under each operation’s rules.")
    "SALES_VIEW" -> hiltechText("متابعة الفرص والعروض وحالتها.", "View opportunities, quotations, and their status.")
    "SALES_WRITE" -> hiltechText("إعداد الفرص والعروض وتعديل المسودات ضمن قواعد المبيعات.",
        "Prepare opportunities and quotations and edit drafts under the sales workflow.")
    "SALES_COST_VIEW" -> hiltechText("عرض تفاصيل تكلفة العروض المسموح بها.", "View permitted quotation cost details.")
    "SALES_RELEASE" -> hiltechText("إصدار العرض بعد اكتمال مستنداته وشروطه وفحوصه.",
        "Release a quotation after its required documents, conditions, and checks are complete.")
    "SALES_AWARD" -> hiltechText("تسجيل الترسية وربطها بمصادرها وشروطها المعتمدة.",
        "Record an award against its approved sources and conditions.")
    "PAYROLL_READ" -> hiltechText("عرض بيانات الرواتب المتاحة ضمن الشركة.", "Read payroll information available within the company.")
    "PAYROLL_PREPARE" -> hiltechText("تجهيز مدخلات وكشوف الرواتب. لا يمنح الاعتماد النهائي أو تنفيذ الصرف.",
        "Prepare payroll inputs and runs. Final approval and payment remain separate.")
    "PAYROLL_REVIEW" -> hiltechText("مراجعة مدخلات وكشوف الرواتب وفق الفصل المطلوب بين الإعداد والاعتماد.",
        "Review payroll inputs and runs with the required separation between preparation and approval.")
    "SUPPLIER_MASTER_DATA_ADMIN" -> hiltechText("إضافة الموردين وتحديث سجلهم وحالتهم، دون منح اعتماد شراء أو صرف.",
        "Add suppliers and maintain their records and status, with purchasing and payment approvals separate.")
    "APPROVAL_POLICY_ADMIN" -> hiltechText("إعداد مسارات الاعتماد التي تتيحها الشركة مع الحفاظ على المراجع المستقل والاعتمادات الإلزامية.",
        "Configure the company’s available approval workflows while preserving independent reviewers and required approvals.")
    "FINANCE_PAYROLL_PREPARE" -> hiltechText("مطابقة كشف الرواتب الصادر مع أدلة الصرف البنكية وتسجيل النتيجة للمراجعة. لا ينفذ تحويلًا بنكيًا.",
        "Match released payroll to bank disbursement evidence and record the result for review. Does not execute a bank transfer.")
    "FINANCE_PAYROLL_REVIEW" -> hiltechText("مراجعة نتائج صرف الرواتب التي أعدّها شخص آخر وأدلتها البنكية المرتبطة.",
        "Review payroll disbursement results prepared by another person and their linked bank evidence.")
    else -> hiltechText("تُدار وفق قواعد الإدارة المالكة لها.", "Managed under its owning department’s rules.")
}

@Composable
private fun companyResponsibilityError(code: String): String = when (code) {
    "REAUTH_REQUIRED", "UNAUTHENTICATED", "SESSION_NOT_ACTIVE" -> hiltechText("أكد تسجيل الدخول قبل متابعة هذا التغيير.",
        "Confirm your sign-in before continuing this change.")
    "RESPONSIBILITY_ADMINISTRATION_DENIED" -> hiltechText("لم يعد تفويضك يسمح بتغيير هذه المسؤولية.",
        "Your current delegation no longer permits changing this responsibility.")
    "RESPONSIBILITY_EMPLOYEE_CHANGED", "RESPONSIBILITY_VERSION_CONFLICT" -> hiltechText("تغير الموظف أو حسابه أو التكليف. حدّث الحالة وراجعها أولًا.",
        "The employee, account, or assignment changed. Refresh and review the current state first.")
    "RESPONSIBILITY_ACCOUNT_NOT_CURRENT" -> hiltechText("الحساب أو العضوية غير نشطين. راجع إعداد الحساب قبل الإسناد.",
        "The account or membership is not current. Review account setup before assigning access.")
    "RESPONSIBILITY_ALREADY_ASSIGNED" -> hiltechText("هذه المسؤولية مسندة بالفعل. حدّث الحالة الحالية.",
        "This responsibility is already assigned. Refresh current state.")
    else -> hiltechText("تعذر تأكيد التغيير. تحقق من الاتصال ثم أعد المحاولة بنفس الطلب المحفوظ.",
        "Couldn’t confirm the change. Check your connection, then retry the same saved request.")
}
