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
    var selectedCategory by rememberSaveable { mutableStateOf("") }
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
    val categories = choices.map { companyResponsibilityCategory(it.authorityKey) }.distinct()
    LaunchedEffect(categories) {
        if (selectedCategory !in categories) selectedCategory = categories.firstOrNull().orEmpty()
    }
    val categoryChoices = choices.filter { companyResponsibilityCategory(it.authorityKey) == selectedCategory }
    LaunchedEffect(categoryChoices) {
        if (categoryChoices.none { it.authorityKey == selectedKey }) selectedKey = categoryChoices.firstOrNull()?.authorityKey.orEmpty()
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
            "END_REMAINING" -> hiltechText("تم إنهاء هذا التكليف المباشر. لا يزال للحساب وصول من فريق أو مصدر سابق ظاهر في القائمة.",
                "This direct assignment ended. The account still has access from a team or an existing source shown below.")
            "END" -> hiltechText("تم إنهاء هذا التكليف المباشر. يعرض التحديث المسؤوليات الحالية.",
                "This direct assignment ended. Refresh shows current responsibilities.")
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
                        if (binding.assignmentSource == "LEGACY") Text(hiltechText(
                            "مصدر قائم · قد يتيح بعض عمليات هذه المسؤولية. إنهاء تكليف جديد هنا لا يغيّر هذا المصدر أو اعتمادات المعاملات المرتبطة به.",
                            "Existing source · may cover some operations in this responsibility. Ending a new assignment here does not change this source or its transaction approvals.",
                        ), style = MaterialTheme.typography.labelMedium)
                        else if (binding.assignmentSource == "TEAM") Text(hiltechText(
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
                        label = hiltechText("مجال المسؤولية", "Responsibility area"), value = selectedCategory,
                        choices = categories.map { HiltechRecordChoice(it, companyResponsibilityCategoryLabel(it)) },
                        onChange = { selectedCategory = it }, allowKnownReference = false, allowClear = false,
                        enabled = pending == null && !submitting,
                    )
                    HiltechRecordPicker(
                        label = hiltechText("إضافة مسؤولية", "Add responsibility"), value = selectedKey,
                        choices = categoryChoices.map { HiltechRecordChoice(it.authorityKey,
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
                            acknowledgement = when {
                                intent.action == "GRANT" && !result.current -> "GRANT_ENDED"
                                intent.action == "END" && result.effectiveAccessRemaining -> "END_REMAINING"
                                else -> intent.action
                            }
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
    "COMPANY_SETTINGS_ADMIN" -> hiltechText("إعدادات الشركة", "Company settings")
    "FINANCE_PAYROLL_PREPARE" -> hiltechText("تسجيل نتائج صرف الرواتب", "Payroll disbursement recording")
    "FINANCE_PAYROLL_REVIEW" -> hiltechText("مراجعة نتائج صرف الرواتب", "Payroll disbursement review")
    "PROCUREMENT_BUYER" -> hiltechText("إعداد المشتريات وعروض الموردين", "Purchasing and supplier quotations")
    "PROCUREMENT_TECHNICAL_REVIEW" -> hiltechText("المراجعة الفنية للمشتريات", "Purchasing technical review")
    "PROCUREMENT_NEED_REQUEST" -> hiltechText("طلب احتياجات الفرق والخدمات", "Team and service requests")
    "PROCUREMENT_NEED_REVIEW" -> hiltechText("مراجعة الاحتياجات وإحالتها للشراء", "Need review and purchasing handoff")
    "PROCUREMENT_ORDER_ISSUE" -> hiltechText("إصدار أوامر الشراء المعتمدة", "Approved purchase order issue")
    "PROCUREMENT_SERVICE_ACCEPT" -> hiltechText("فحص الخدمات المستلمة", "Received service inspection")
    "FINANCE_SUPPLIER_REVIEW" -> hiltechText("مراجعة فواتير الموردين", "Supplier invoice review")
    "FINANCE_EMPLOYEE_REVIEW" -> hiltechText("مراجعة مصروفات الموظفين", "Employee expense review")
    "FINANCE_COMMERCIAL_REVIEW" -> hiltechText("مراجعة مصادر مستحقات العملاء", "Customer source review")
    "FINANCE_PAYABLE_PREPARE" -> hiltechText("إعداد قيود الموردين وتصحيحاتها", "Supplier entry preparation")
    "FINANCE_PAYABLE_POST" -> hiltechText("ترحيل قيود الموردين المعتمدة", "Approved supplier entry posting")
    "FINANCE_RECEIVABLE_PREPARE" -> hiltechText("إعداد فواتير وقيود العملاء", "Customer invoice and entry preparation")
    "FINANCE_RECEIVABLE_EXECUTE" -> hiltechText("إصدار فواتير العملاء وترحيل قيودها", "Customer invoice issue and posting")
    "FINANCE_EMPLOYEE_PREPARE" -> hiltechText("إعداد العهد وتسويات الموظفين", "Employee funding and settlement preparation")
    "FINANCE_EMPLOYEE_EXECUTE" -> hiltechText("تسجيل العهد والتسويات المعتمدة", "Approved employee settlement recording")
    "FINANCE_BANK_STATEMENT_PREPARE" -> hiltechText("تجهيز كشوف البنك وأدلتها", "Bank statement preparation")
    "FINANCE_BANK_STATEMENT_IMPORT" -> hiltechText("استيراد كشوف البنك المعتمدة", "Approved bank statement import")
    "FINANCE_BANK_RECONCILIATION_PREPARE" -> hiltechText("إعداد التسوية البنكية", "Bank reconciliation preparation")
    "FINANCE_BANK_RECONCILIATION_FINALIZE" -> hiltechText("إقفال التسوية البنكية المعتمدة", "Approved bank reconciliation completion")
    "FINANCE_CASH_PREPARE" -> hiltechText("إعداد الخزائن والحركات النقدية", "Cashbox and cash entry preparation")
    "FINANCE_CASH_EXECUTE" -> hiltechText("تسجيل الحركات النقدية المعتمدة", "Approved cash entry recording")
    "FINANCE_PERIOD_CLOSE_PREPARE" -> hiltechText("إعداد إقفال الفترات المالية", "Financial period close preparation")
    "FINANCE_PERIOD_CLOSE_EXECUTE" -> hiltechText("تنفيذ إقفال الفترات المعتمد", "Approved financial period close")
    "FINANCE_REPORT_READ" -> hiltechText("عرض التقارير والأرصدة المالية", "Financial reports and balances")
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
    "COMPANY_SETTINGS_ADMIN" -> hiltechText("إدارة إعدادات التشغيل المتاحة للشركة، ومنها ترقيم أوامر الشراء. لا يمنح توقيعًا ماليًا أو سلطة اعتماد.",
        "Manage supported company operating settings, including purchase order numbering. Does not grant financial signing or approval authority.")
    "FINANCE_PAYROLL_PREPARE" -> hiltechText("مطابقة كشف الرواتب الصادر مع أدلة الصرف البنكية وتسجيل النتيجة للمراجعة. لا ينفذ تحويلًا بنكيًا.",
        "Match released payroll to bank disbursement evidence and record the result for review. Does not execute a bank transfer.")
    "FINANCE_PAYROLL_REVIEW" -> hiltechText("مراجعة نتائج صرف الرواتب التي أعدّها شخص آخر وأدلتها البنكية المرتبطة.",
        "Review payroll disbursement results prepared by another person and their linked bank evidence.")
    "PROCUREMENT_BUYER" -> hiltechText("إعداد طلبات عروض أسعار المواد والخدمات، ومراجعة مستندات الموردين واختيار العرض المقترح. اعتماد الشراء وإصدار الأمر منفصلان.",
        "Prepare material and service quotations, review supplier documents, and select a proposed quotation. Purchase approval and order issue remain separate.")
    "PROCUREMENT_TECHNICAL_REVIEW" -> hiltechText("تقييم مطابقة عرض المورد لمتطلبات الشراء الفنية. لا يمنح اعتماد تكلفة الشراء.",
        "Assess supplier quotations against technical requirements. Purchase cost approval remains separate.")
    "PROCUREMENT_NEED_REQUEST" -> hiltechText("إعداد احتياجات المواد والخدمات للفرق التي ينتمي إليها الموظف حاليًا، مع اختيار وجهة التسليم.",
        "Prepare material and service needs for the employee’s current teams and select a delivery destination.")
    "PROCUREMENT_NEED_REVIEW" -> hiltechText("مراجعة احتياجات الفرق وتجهيزها لطلب عروض الموردين وفق شروط المصدر.",
        "Review team needs and release them for supplier sourcing under the source requirements.")
    "PROCUREMENT_ORDER_ISSUE" -> hiltechText("إصدار أوامر شراء المواد والخدمات بعد الاعتماد المستقل، ومتابعة دورة الأمر. تسجيل الإرسال للمورد لا يرسل رسالة تلقائيًا.",
        "Issue material and service orders after independent approval and manage their lifecycle. Recording supplier dispatch does not send a message automatically.")
    "PROCUREMENT_SERVICE_ACCEPT" -> hiltechText("رفع أدلة تسليم الخدمة وتسجيل قبولها أو رفضها استنادًا إلى أمر الخدمة وشروطه.",
        "Upload service delivery evidence and record acceptance or rejection against the service order and its criteria.")
    "FINANCE_SUPPLIER_REVIEW" -> hiltechText("مطابقة فواتير المواد والخدمات وأدلتها مع أوامر الشراء والاستلام، وتجهيزها للمراجعة المحاسبية.",
        "Match material and service invoices and their evidence against orders and receipts, then prepare them for accounting review.")
    "FINANCE_EMPLOYEE_REVIEW" -> hiltechText("فحص مطالبات مصروفات الموظفين وإيصالاتها وتسجيل المراجعة قبل التسوية.",
        "Inspect employee expense claims and their receipts and record review before settlement.")
    "FINANCE_COMMERCIAL_REVIEW" -> hiltechText("فحص الترسية التجارية ومصادرها قبل تجهيز مستحقات العميل.",
        "Review commercial awards and their source records before preparing customer receivables.")
    "FINANCE_PAYABLE_PREPARE" -> hiltechText("إعداد قيود مستحقات الموردين وعكس القيد للتصحيح، مع الحسابات والفترة والمصدر المرتبط. الاعتماد والترحيل منفصلان.",
        "Prepare supplier payable entries and reversal corrections with their accounts, period, and source. Approval and posting remain separate.")
    "FINANCE_PAYABLE_POST" -> hiltechText("ترحيل قيود الموردين أو عكسها بعد اعتماد المقترح المطابق ومراجعة المصدر والفترة.",
        "Post or reverse supplier entries after approval of the exact proposal and validation of its source and period.")
    "FINANCE_RECEIVABLE_PREPARE" -> hiltechText("إعداد مقترحات فواتير العملاء وقيودها من المصادر التجارية المتاحة. لا يصدر الفاتورة أو يرحّل القيد.",
        "Prepare customer invoice and journal proposals from available commercial sources. Invoice issue and posting remain separate.")
    "FINANCE_RECEIVABLE_EXECUTE" -> hiltechText("إصدار فاتورة العميل أو ترحيل قيدها بعد الاعتماد المستقل واكتمال شروط المصدر.",
        "Issue a customer invoice or post its entry after independent approval and source validation.")
    "FINANCE_EMPLOYEE_PREPARE" -> hiltechText("إعداد تمويل عهد الموظفين وتسوياتها وتعويض المصروفات مع الأدلة المطلوبة للمراجعة.",
        "Prepare employee funding, settlements, and expense reimbursement with the evidence required for review.")
    "FINANCE_EMPLOYEE_EXECUTE" -> hiltechText("تسجيل التمويل والتسوية والتعويض بعد الاعتماد المطلوب. تسجيل العملية لا ينفذ تحويلًا بنكيًا.",
        "Record funding, settlement, and reimbursement after required approval. Recording does not execute a bank transfer.")
    "FINANCE_BANK_STATEMENT_PREPARE" -> hiltechText("رفع كشف البنك وربطه ببيانات الحساب وتجهيز الاستيراد للمراجعة المستقلة.",
        "Upload bank statements, link account details, and prepare imports for independent review.")
    "FINANCE_BANK_STATEMENT_IMPORT" -> hiltechText("استيراد النسخة المعتمدة من كشف البنك إلى سجلات الشركة. لا يسمح بتحويل الأموال.",
        "Import the approved bank statement revision into company records. Does not permit transferring funds.")
    "FINANCE_BANK_RECONCILIATION_PREPARE" -> hiltechText("إعداد التسوية بين الحركات المسجلة وكشف البنك وتقديمها للمراجعة.",
        "Prepare reconciliation between recorded transactions and bank statements for review.")
    "FINANCE_BANK_RECONCILIATION_FINALIZE" -> hiltechText("إتمام التسوية البنكية المعتمدة بعد إعادة فحص الحركات والأدلة المرتبطة.",
        "Complete an approved bank reconciliation after rechecking its transactions and evidence.")
    "FINANCE_CASH_PREPARE" -> hiltechText("إعداد افتتاح الخزائن والسندات والجرد وتخصيص المتحصلات وتصحيح التخصيص. كل إجراء يحتفظ بمراجعته المطلوبة.",
        "Prepare cashbox opening, vouchers, counts, receipt allocation, and allocation corrections. Each action retains its required review.")
    "FINANCE_CASH_EXECUTE" -> hiltechText("تسجيل السندات والجرد والتخصيص أو إلغائه بعد الاعتماد المطابق. لا يثبت تسليم النقد خارج النظام تلقائيًا.",
        "Record vouchers, counts, allocations, and allocation reversals after exact approval. Does not automatically attest to physical cash delivery.")
    "FINANCE_PERIOD_CLOSE_PREPARE" -> hiltechText("مراجعة أدلة الفترة وتجهيز طلب إقفالها للاعتماد المستقل.",
        "Review period evidence and prepare its close proposal for independent approval.")
    "FINANCE_PERIOD_CLOSE_EXECUTE" -> hiltechText("إقفال الفترة المالية المعتمدة بعد إعادة التحقق من القيود والمصادر.",
        "Close an approved financial period after rechecking its entries and sources.")
    "FINANCE_REPORT_READ" -> hiltechText("عرض أرصدة العملاء والموردين وتقارير القيود ومصادر المشاريع والخزائن والفروق البنكية ضمن الشركة.",
        "Read company customer and supplier balances, entry reports, project sources, cashbox records, and bank variances.")
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

internal fun companyResponsibilityCategory(key: String): String = when (key) {
    "PEOPLE_ADMIN" -> "PEOPLE"
    "PROJECT_ADMIN" -> "PROJECTS"
    "SUPPLIER_MASTER_DATA_ADMIN" -> "SUPPLIERS"
    "APPROVAL_POLICY_ADMIN", "COMPANY_SETTINGS_ADMIN" -> "ADMINISTRATION"
    "FINANCE_PAYROLL_PREPARE", "FINANCE_PAYROLL_REVIEW", "PAYROLL_READ", "PAYROLL_PREPARE", "PAYROLL_REVIEW" -> "PAYROLL"
    "FINANCE_SUPPLIER_REVIEW", "FINANCE_PAYABLE_PREPARE", "FINANCE_PAYABLE_POST" -> "FINANCE_SUPPLIERS"
    "FINANCE_COMMERCIAL_REVIEW", "FINANCE_RECEIVABLE_PREPARE", "FINANCE_RECEIVABLE_EXECUTE" -> "FINANCE_CUSTOMERS"
    "FINANCE_EMPLOYEE_REVIEW", "FINANCE_EMPLOYEE_PREPARE", "FINANCE_EMPLOYEE_EXECUTE" -> "FINANCE_EMPLOYEES"
    "FINANCE_BANK_STATEMENT_PREPARE", "FINANCE_BANK_STATEMENT_IMPORT", "FINANCE_BANK_RECONCILIATION_PREPARE", "FINANCE_BANK_RECONCILIATION_FINALIZE" -> "FINANCE_BANK"
    "FINANCE_CASH_PREPARE", "FINANCE_CASH_EXECUTE" -> "FINANCE_CASH"
    "FINANCE_PERIOD_CLOSE_PREPARE", "FINANCE_PERIOD_CLOSE_EXECUTE", "FINANCE_REPORT_READ" -> "FINANCE_REPORTS"
    "PROCUREMENT_BUYER", "PROCUREMENT_TECHNICAL_REVIEW", "PROCUREMENT_NEED_REQUEST", "PROCUREMENT_NEED_REVIEW", "PROCUREMENT_ORDER_ISSUE", "PROCUREMENT_SERVICE_ACCEPT" -> "PROCUREMENT"
    "SALES_VIEW", "SALES_WRITE", "SALES_COST_VIEW", "SALES_RELEASE", "SALES_AWARD" -> "COMMERCIAL"
    else -> "OTHER"
}

@Composable
internal fun companyResponsibilityCategoryLabel(category: String): String = when (category) {
    "PEOPLE" -> hiltechText("الموظفون", "People")
    "PROJECTS" -> hiltechText("المشاريع", "Projects")
    "SUPPLIERS" -> hiltechText("سجل الموردين", "Supplier register")
    "ADMINISTRATION" -> hiltechText("إعدادات الشركة", "Company setup")
    "PAYROLL" -> hiltechText("الرواتب ونتائج الصرف", "Payroll and disbursement results")
    "PROCUREMENT" -> hiltechText("المشتريات والخدمات", "Purchasing and services")
    "COMMERCIAL" -> hiltechText("المبيعات والعروض", "Sales and quotations")
    "FINANCE_SUPPLIERS" -> hiltechText("المالية · الموردون", "Finance · suppliers")
    "FINANCE_CUSTOMERS" -> hiltechText("المالية · العملاء", "Finance · customers")
    "FINANCE_EMPLOYEES" -> hiltechText("المالية · الموظفون", "Finance · employees")
    "FINANCE_BANK" -> hiltechText("المالية · البنوك", "Finance · banks")
    "FINANCE_CASH" -> hiltechText("المالية · الخزائن", "Finance · cashboxes")
    "FINANCE_REPORTS" -> hiltechText("المالية · التقارير والإقفال", "Finance · reporting and close")
    else -> hiltechText("مسؤوليات أخرى", "Other responsibilities")
}
