package com.hiltech.shared.core.administration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.people.companyResponsibilityFailureDisposition
import com.hiltech.shared.core.people.EmployeeCompanyResponsibilitiesWorkspace
import com.hiltech.shared.core.people.EmployeeResponsibilityTargetDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
fun CompanyAdministrationWorkspace(
    organizationId: String,
    sessionScope: String,
    client: CompanyAdministrationApiClient,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: (() -> Unit)? = null,
    onOpenPeople: (() -> Unit)? = null,
) = key(sessionScope, organizationId) {
    var workflow by rememberSaveable { mutableStateOf("PAYROLL_SOURCE") }
    var section by rememberSaveable { mutableStateOf("RESPONSIBILITIES") }
    var capabilities by remember { mutableStateOf<CompanyAdministrationCapabilitiesDto?>(null) }
    var checking by remember { mutableStateOf(true) }
    var capabilityError by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val state = rememberSaveableStateHolder()
    LaunchedEffect(refresh) {
        checking = true; capabilityError = false
        try { capabilities = client.capabilities(organizationId, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { capabilities = null; capabilityError = true }
        finally { checking = false }
    }
    val options = buildList {
        if (capabilities?.canViewEmployeeResponsibilities == true) add("RESPONSIBILITIES")
        if (capabilities?.canManageApprovalPolicies == true) add("REVIEW_WORKFLOWS")
        if (capabilities?.canManageCompanySettings == true) add("OPERATIONAL_SETTINGS")
    }
    LaunchedEffect(options) { if (section !in options) section = options.firstOrNull().orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hiltechText("إدارة الشركة", "Company administration"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { refresh += 1 }, enabled = !checking) { Text(hiltechText("تحديث", "Refresh")) }
            }
            Text(hiltechText("إسناد مسؤوليات الموظفين ونقل إدارتها وإعداد المراجعين وإعدادات التشغيل، كلٌّ ضمن تفويضك الحالي.",
                "Assign employee responsibilities, transfer their administration, configure reviewers, and manage operating settings within your current authority."))
            if (checking) CircularProgressIndicator()
            if (capabilityError) Text(hiltechText("تعذر التحقق من صلاحيات الإدارة. حدّث الصفحة لإعادة المحاولة.",
                "Couldn’t verify administration access. Refresh to retry."), color = MaterialTheme.colorScheme.error)
            if (!checking && capabilities?.available == false) Text(hiltechText("لا توجد مسؤوليات إدارة شركة مخوّلة لهذا الحساب حاليًا.",
                "This account currently has no company administration responsibilities."))
            if (options.size > 1) HiltechRecordPicker(hiltechText("مساحة الإدارة", "Administration area"), section,
                options.map { HiltechRecordChoice(it, when (it) {
                    "RESPONSIBILITIES" -> hiltechText("الموظفون والمسؤوليات", "Employees and responsibilities")
                    "OPERATIONAL_SETTINGS" -> hiltechText("إعدادات التشغيل", "Operating settings")
                    else -> hiltechText("مسارات الاعتماد", "Approval workflows")
                }) }, onChange = { section = it }, allowKnownReference = false, allowClear = false)
            if (section == "REVIEW_WORKFLOWS" && section in options) HiltechRecordPicker(hiltechText("مسار الاعتماد", "Approval workflow"), workflow,
                companyReviewWorkflows.map { HiltechRecordChoice(it, workflowLabel(it)) }, onChange = { workflow = it }, allowKnownReference = false, allowClear = false)
        }
        OwnPeopleAdministrationWorkspace(organizationId, capabilities?.ownPeopleAdministrationStatus ?: "CHECKING",
            client, installationId, newOperationId, { refresh += 1 }, onReauthenticate, onOpenPeople)
        if (section == "RESPONSIBILITIES" && section in options) state.SaveableStateProvider("EMPLOYEE_RESPONSIBILITIES") {
            CompanyEmployeeAdministrationWorkspace(organizationId, sessionScope, client, installationId, newOperationId, onReauthenticate)
        }
        if (section == "REVIEW_WORKFLOWS" && section in options) state.SaveableStateProvider("REVIEW:$workflow") {
            CompanyReviewSetupWorkspace(organizationId, workflow, client, installationId, newOperationId, onReauthenticate)
        }
        if (section == "OPERATIONAL_SETTINGS" && section in options) state.SaveableStateProvider("OPERATIONAL_SETTINGS") {
            CompanyOperationalSettingsWorkspace(organizationId, client.operationalSettings, installationId, newOperationId, onReauthenticate)
        }
    }
}

@Composable
private fun CompanyEmployeeAdministrationWorkspace(organizationId: String, sessionScope: String,
    client: CompanyAdministrationApiClient, installationId: String, newOperationId: () -> String, onReauthenticate: (() -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedJson by rememberSaveable { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<ResponsibilityEmployeeDirectoryDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val state = rememberSaveableStateHolder()
    val selected = selectedJson?.let { Json.decodeFromString(EmployeeResponsibilityTargetDto.serializer(), it) }
    LaunchedEffect(query, refresh) {
        loading = true; failed = false
        try { delay(250); rows = client.employees(organizationId, query, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { rows = null; failed = true }
        finally { loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hiltechText("اختيار موظف", "Choose an employee"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { refresh += 1 }, enabled = !loading) { Text(hiltechText("تحديث", "Refresh")) }
            }
            OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(hiltechText("ابحث بالاسم أو كود الموظف", "Search by name or employee code")) })
            if (loading) CircularProgressIndicator()
            if (failed) Text(hiltechText("تعذر تحميل الموظفين. تحقق من الاتصال والتفويض ثم أعد المحاولة.",
                "Couldn’t load employees. Check your connection and delegation, then retry."), color = MaterialTheme.colorScheme.error)
            if (!loading && rows?.items?.isEmpty() == true) Text(hiltechText("لا يوجد موظف مطابق للبحث.", "No employee matches this search."))
            rows?.let { directory ->
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(directory.items, key = { it.employeeId }) { employee ->
                        TextButton(onClick = { selectedJson = Json.encodeToString(EmployeeResponsibilityTargetDto.serializer(), employee) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(employee.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(employee.employeeCode + " · " + if (employee.accountStatus == "CURRENT") hiltechText("حساب نشط", "Current account")
                                    else hiltechText("الحساب يحتاج تجهيزًا", "Account setup needed"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (directory.hasMore) Text(hiltechText("اكتب اسمًا أدق للوصول إلى بقية الموظفين.", "Use a more specific name to find other employees."))
            }
        }
        selected?.let { employee ->
            state.SaveableStateProvider(employee.employeeId) {
                Text(employee.displayName, style = MaterialTheme.typography.headlineSmall)
                EmployeeCompanyResponsibilitiesWorkspace(employee, sessionScope, client.responsibilities, installationId, newOperationId, onReauthenticate)
            }
        }
    }
}

@Composable
private fun CompanyReviewSetupWorkspace(
    organizationId: String, workflow: String, client: CompanyAdministrationApiClient, installationId: String,
    newOperationId: () -> String, onReauthenticate: (() -> Unit)?,
) {
    val jobs = rememberCoroutineScope()
    var current by remember { mutableStateOf<CompanyReviewSetupDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<CompanyReviewerCandidatesDto?>(null) }
    var candidateError by remember { mutableStateOf(false) }
    var selectedJson by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknownOutcome by rememberSaveable { mutableStateOf(false) }
    var rejectionConfirmed by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var commandError by remember { mutableStateOf<String?>(null) }
    var acknowledgement by remember { mutableStateOf<Boolean?>(null) }
    val selected = selectedJson?.let { Json.decodeFromString(CompanyReviewerCandidateDto.serializer(), it) }
    val pending = pendingJson?.let { Json.decodeFromString(CompanyReviewSetupIntent.serializer(), it) }

    LaunchedEffect(refresh) {
        loading = true
        loadError = null
        try { current = client.reviewSetup(organizationId, workflow, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { current = null; loadError = (failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { loading = false }
    }
    LaunchedEffect(query, refresh, current != null) {
        if (current?.canConfigure != true) { candidates = null; return@LaunchedEffect }
        searching = true
        candidateError = false
        try {
            delay(250)
            candidates = client.reviewerCandidates(organizationId, workflow, query, installationId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { candidates = null; candidateError = true }
        finally { searching = false }
    }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(workflowLabel(workflow), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refresh += 1 }, enabled = !loading && !submitting) { Text(hiltechText("تحديث", "Refresh")) }
        }
        Text(hiltechText("يتطلب كل طلب مراجعة مستقلة. لا يستطيع من أعد السجل اعتماد السجل نفسه.",
            "Each request requires an independent review. The person who prepared a record cannot approve that same record."))
        if (loading) CircularProgressIndicator()
        loadError?.let { Text(setupError(it), color = MaterialTheme.colorScheme.error) }
        acknowledgement?.let { stillCurrent -> Text(if (stillCurrent)
            hiltechText("تم تأكيد إعداد المراجع. يعرض التحديث المسار الحالي.", "The reviewer setup was confirmed. Refresh shows the current workflow.")
            else hiltechText("تأكد تنفيذ الطلب السابق، ثم تغير الإعداد بعده. راجع المسار الحالي.",
                "The earlier request was confirmed, and the setup has since changed. Review the current workflow."),
            color = MaterialTheme.colorScheme.primary) }
        if (pending != null && !dialogVisible) {
            Text(hiltechText("يوجد إعداد محفوظ يحتاج تأكيد النتيجة.", "A saved setup request needs its outcome confirmed."))
            TextButton(onClick = { dialogVisible = true }) { Text(hiltechText("متابعة الطلب", "Continue request")) }
        }
        current?.takeUnless { loading }?.let { setup ->
            Text(when (setup.status) {
                "READY" -> hiltechText("المسار جاهز", "Workflow ready")
                "NOT_CONFIGURED" -> hiltechText("لم يُحدّد المراجع بعد", "Reviewer not configured")
                "REVIEWER_UNAVAILABLE" -> hiltechText("المراجع الحالي غير متاح", "Current reviewer unavailable")
                "PROTECTED_EXISTING_AUTHORITY" -> hiltechText("مسار اعتماد قائم بسلطة مستقلة", "Existing independently governed approval workflow")
                else -> hiltechText("إعداد الاعتماد يحتاج تحديثًا", "Approval setup needs an update")
            }, style = MaterialTheme.typography.titleSmall)
            setup.reviewerDisplayName?.let { name ->
                Text((if (setup.reviewerPrincipalType == "TEAM") hiltechText("فريق المراجعة: ", "Review team: ")
                    else hiltechText("المراجع الحالي: ", "Current reviewer: ")) + name)
            }
            setup.policyRevision?.let { Text(hiltechText("نسخة المسار $it", "Workflow revision $it"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!setup.canConfigure) Text(hiltechText(
                "هذا المسار مرتبط بسلطة اعتماد مستقلة سبق تحديدها للشركة. يظل إعدادها محفوظًا ولا يُستبدل من هذه الشاشة.",
                "This workflow uses an independently governed approval authority already set for the company. Its configuration is preserved and cannot be replaced here.",
            ))
            if (setup.pendingReviews > 0) Text(hiltechText(
                "هناك ${setup.pendingReviews} طلبات معلقة. قبل تغيير المراجع ستؤكد إنهاء هذه الطلبات وإعادة إرسال مصادرها للمراجعة الجديدة. تبقى الاعتمادات المكتملة محفوظة.",
                "There are ${setup.pendingReviews} pending requests. Before replacing the reviewer, confirm ending these requests so their sources can be resubmitted. Completed approvals are preserved.",
            ), style = MaterialTheme.typography.bodySmall)
            if (setup.canConfigure) {
            HorizontalDivider()
            Text(hiltechText("اختيار موظف مراجع", "Choose a reviewer"), style = MaterialTheme.typography.titleSmall)
            Text(if (workflow in setOf("PAYROLL_SOURCE", "PAYROLL_RUN")) hiltechText(
                "يظهر الموظفون النشطون الذين لديهم مسؤولية مراجعة الرواتب. يمكنك إسناد هذه المسؤولية من سجل الموظف أولًا.",
                "Active employees with Payroll review responsibility appear here. You can assign that responsibility from the employee record first.",
            ) else if (workflow == "FINANCE_PAYROLL_RESULT") hiltechText(
                "يظهر الموظفون النشطون الذين لديهم مسؤولية مراجعة نتائج صرف الرواتب.",
                "Active employees with Payroll disbursement review responsibility appear here.",
            ) else hiltechText(
                "اختر موظفًا نشطًا لديه حساب مرتبط بالشركة. هذا التعيين يتيح مراجعة الطلبات المسندة له فقط.",
                "Choose an active employee with a linked company account. This designation allows review only of requests assigned to them.",
            ), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(hiltechText("ابحث بالاسم أو كود الموظف", "Search by name or employee code")) },
                enabled = pending == null && !submitting)
            if (searching) CircularProgressIndicator()
            if (candidateError) Text(hiltechText("تعذر تحميل الموظفين. حدّث الصفحة لإعادة المحاولة.",
                "Couldn’t load employees. Refresh to retry."), color = MaterialTheme.colorScheme.error)
            if (!searching && candidates?.items?.isEmpty() == true) Text(hiltechText("لا يوجد موظف مطابق مستوفٍ لمتطلبات هذا المسار.",
                "No matching employee meets this workflow’s current requirements."))
            candidates?.let { options ->
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(options.items, key = { it.employeeId }) { candidate ->
                        TextButton(onClick = { selectedJson = Json.encodeToString(CompanyReviewerCandidateDto.serializer(), candidate) },
                            enabled = pending == null && !submitting, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(candidate.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(candidate.employeeCode, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (options.hasMore) Text(hiltechText("اكتب اسمًا أدق للوصول إلى بقية الموظفين.",
                    "Use a more specific name to find other employees."), style = MaterialTheme.typography.bodySmall)
            }
            selected?.let { chosen ->
                val needsPendingClosure = setup.pendingReviews > 0 && (setup.status != "READY" ||
                    setup.reviewerPrincipalType != "USER" || setup.reviewerEmployeeId != chosen.employeeId)
                var pendingClosureConfirmed by remember(setup.policyId, setup.policyVersion, chosen.employeeId, setup.pendingPolicies) { mutableStateOf(false) }
                var pendingClosureReason by remember(setup.policyId, setup.policyVersion, chosen.employeeId, setup.pendingPolicies) { mutableStateOf("") }
                Text(hiltechText("الاختيار: ", "Selected: ") + chosen.displayName, style = MaterialTheme.typography.titleSmall)
                if (needsPendingClosure) {
                    Text(hiltechText("الطلبات التي ستنتهي: ${setup.pendingReviews}", "Requests that will be ended: ${setup.pendingReviews}"), style = MaterialTheme.typography.titleSmall)
                    setup.pendingPolicies.forEach { scope -> Text(hiltechText(
                        "نسخة المسار ${scope.policyRevision}: ${scope.pendingReviews} طلبات",
                        "Workflow revision ${scope.policyRevision}: ${scope.pendingReviews} requests")) }
                    OutlinedTextField(pendingClosureReason, { pendingClosureReason = it.take(1000) }, Modifier.fillMaxWidth(),
                        enabled = pending == null && !submitting, label = { Text(hiltechText("سبب تغيير مسار الطلبات المعلقة", "Reason for replacing the pending review workflow")) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pendingClosureConfirmed, { pendingClosureConfirmed = it }, enabled = pending == null && !submitting)
                        Text(hiltechText("أوافق على إنهاء هذه الطلبات المعلقة وإعادة إرسال مصادرها للمراجع الجديد.",
                            "I confirm ending these pending requests and resubmitting their sources to the new reviewer."))
                    }
                }
                val impactConfirmed = !needsPendingClosure || (pendingClosureConfirmed && pendingClosureReason.trim().length in 3..1000 &&
                    setup.pendingPolicies.isNotEmpty() && setup.pendingPolicies.sumOf { it.pendingReviews } == setup.pendingReviews)
                Button(enabled = pending == null && !submitting && impactConfirmed, onClick = {
                    pendingJson = Json.encodeToString(CompanyReviewSetupIntent.serializer(), CompanyReviewSetupIntent(
                        newOperationId(), organizationId, workflow, chosen.employeeId, chosen.identityId, chosen.employeeVersion,
                        chosen.displayName, setup.policyId, setup.policyVersion, setup.bindingId, setup.bindingVersion, setup.pendingReviews,
                        if (needsPendingClosure) setup.pendingPolicies else emptyList(),
                        pendingClosureReason.trim().takeIf { needsPendingClosure },
                    ))
                    attempted = false; unknownOutcome = false; rejectionConfirmed = false
                    commandError = null; acknowledgement = null; dialogVisible = true
                }) { Text(hiltechText("مراجعة الإعداد", "Review setup")) }
            }
            }
        }
    }
    pending?.takeIf { dialogVisible }?.let { intent ->
        fun close() { dialogVisible = false; if (!attempted) pendingJson = null }
        AlertDialog(onDismissRequest = { if (!submitting) close() },
            title = { Text(hiltechText("تأكيد المراجع", "Confirm reviewer")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(workflowLabel(intent.workflow), style = MaterialTheme.typography.titleSmall)
                Text(intent.reviewerDisplayName, style = MaterialTheme.typography.titleMedium)
                Text(hiltechText("يظل الاعتماد مطلوبًا، مع مراجع مستقل عن مُعدّ السجل. يتم حفظ تاريخ الإعداد السابق.",
                    "Approval remains required from a reviewer independent of the preparer. The prior setup history is retained."))
                if (intent.pendingPoliciesToSupersede.isNotEmpty()) {
                    Text(hiltechText("ستنتهي ${intent.pendingReviews} طلبات معلقة. يلزم إعادة إرسال مصادرها للمراجع الجديد.",
                        "${intent.pendingReviews} pending requests will be ended. Their sources must be resubmitted to the new reviewer."))
                    Text(intent.supersedeReason.orEmpty())
                }
                if (submitting) CircularProgressIndicator()
                commandError?.let { Text(setupError(it), color = MaterialTheme.colorScheme.error) }
                if (commandError == "REAUTH_REQUIRED" && onReauthenticate != null) TextButton(onClick = onReauthenticate, enabled = !submitting) {
                    Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in"))
                }
                if (unknownOutcome) Text(hiltechText("لم تصل نتيجة مؤكدة. أعد نفس الطلب المحفوظ لتأكيد ما حدث.",
                    "The outcome is not confirmed. Retry the same saved request to confirm what happened."))
                if (rejectionConfirmed) Text(hiltechText("لم يُسجّل هذا الطلب. حدّث الحالة قبل اختيار إعداد جديد.",
                    "This request was not recorded. Refresh before choosing a new setup."))
            } },
            confirmButton = { TextButton(enabled = !submitting, onClick = {
                if (rejectionConfirmed) {
                    pendingJson = null; dialogVisible = false; attempted = false; unknownOutcome = false
                    rejectionConfirmed = false; commandError = null; selectedJson = null; refresh += 1
                    return@TextButton
                }
                submitting = true; commandError = null
                val priorUnknown = unknownOutcome
                jobs.launch {
                    try {
                        val result = client.configureReview(intent, installationId) { attempted = true; unknownOutcome = true }
                        pendingJson = null; dialogVisible = false; attempted = false; unknownOutcome = false
                        rejectionConfirmed = false; acknowledgement = result.current; selectedJson = null; refresh += 1
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        commandError = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                        val disposition = companyResponsibilityFailureDisposition(priorUnknown, failure)
                        unknownOutcome = disposition.hasUnknownOutcome; rejectionConfirmed = disposition.rejectionConfirmed
                    } finally { submitting = false }
                }
            }) { Text(when { rejectionConfirmed -> hiltechText("تحديث الحالة", "Refresh state")
                attempted -> hiltechText("إعادة نفس الطلب", "Retry same request") else -> hiltechText("تأكيد", "Confirm") }) } },
            dismissButton = { TextButton(onClick = { close() }, enabled = !submitting) {
                Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع", "Back"))
            } },
        )
    }
}

@Composable
private fun workflowLabel(workflow: String): String = when (workflow) {
    "PAYROLL_SOURCE" -> hiltechText("مراجعة مدخلات الرواتب", "Payroll input review")
    "PAYROLL_RUN" -> hiltechText("اعتماد كشف الرواتب", "Payroll run approval")
    "FINANCE_PAYROLL_RESULT" -> hiltechText("مراجعة نتائج صرف الرواتب", "Payroll disbursement review")
    "PROCUREMENT_PO" -> hiltechText("اعتماد مقترحات الشراء", "Purchase proposal approval")
    "SERVICE_HANDOVER" -> hiltechText("اعتماد التسليم", "Handover approval")
    "SERVICE_WARRANTY" -> hiltechText("اعتماد الضمان", "Warranty approval")
    else -> hiltechText("مسار المراجعة", "Review workflow")
}

@Composable
private fun setupError(code: String): String = when (code) {
    "APPROVAL_POLICY_ADMINISTRATION_DENIED" -> hiltechText("تحتاج مسؤولية إعداد مسارات الاعتماد لعرض هذا الإعداد وتغييره.",
        "Approval workflow setup responsibility is required to view and change this configuration.")
    "REAUTH_REQUIRED", "SESSION_NOT_ACTIVE", "UNAUTHENTICATED" -> hiltechText("أكد تسجيل الدخول ثم أعد المحاولة.",
        "Confirm your sign-in, then retry.")
    "PAYROLL_REVIEW_SETUP_CHANGED" -> hiltechText("تغير المسار منذ عرضه. حدّث الحالة قبل حفظ اختيار جديد.",
        "The workflow changed since it was displayed. Refresh before saving another selection.")
    "PAYROLL_REVIEWER_CHANGED" -> hiltechText("تغير حساب الموظف أو مسؤوليته في المراجعة. حدّث القائمة واختر مراجعًا حاليًا.",
        "The employee’s account or review responsibility changed. Refresh and choose a current reviewer.")
    "REVIEW_WORKFLOW_PROTECTED_AUTHORITY" -> hiltechText("هذا المسار يستخدم سلطة اعتماد مستقلة لا يمكن استبدالها من هذه الشاشة.",
        "This workflow uses independently governed approval authority that cannot be replaced here.")
    "APPROVAL_POLICY_PENDING_CONFIRMATION_REQUIRED", "APPROVAL_POLICY_PENDING_CHANGED", "APPROVAL_POLICY_CHANGED" -> hiltechText(
        "تغيرت الطلبات المعلقة أو نسخة المسار. حدّث الحالة وراجع عدد الطلبات التي ستنتهي قبل التأكيد.",
        "Pending requests or the workflow version changed. Refresh and review the affected request count before confirming.")
    "APPROVAL_POLICY_MAINTENANCE_REASON_REQUIRED" -> hiltechText("اكتب سبب إنهاء الطلبات المعلقة قبل تغيير المراجع.",
        "Enter a reason for ending pending requests before replacing the reviewer.")
    else -> hiltechText("تعذر تأكيد الإعداد. تحقق من الاتصال والصلاحية وأعد نفس الطلب إن كان محفوظًا.",
        "Couldn’t confirm the setup. Check your connection and authority, and retry the same saved request if present.")
}
