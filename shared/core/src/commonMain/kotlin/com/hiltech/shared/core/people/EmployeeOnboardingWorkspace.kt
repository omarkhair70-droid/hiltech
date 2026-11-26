package com.hiltech.shared.core.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** People administration only. The server decides every allowed action and recomputes activation blockers. */
@Composable
fun EmployeeOnboardingWorkspace(
    employee: EmployeeDetailDto,
    sessionScope: String,
    client: OnboardingApiClient,
    installationId: String,
    newOperationId: () -> String,
    isSessionCurrent: () -> Boolean,
    submitCommand: OnboardingWorkspaceSubmit,
    onEmployeeChanged: () -> Unit = {},
) = key(sessionScope, employee.organizationId, employee.employeeId) {
    val jobs = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<OnboardingWorkspaceDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var commandError by remember { mutableStateOf<String?>(null) }
    var policyId by rememberSaveable { mutableStateOf("") }
    var waiverReason by rememberSaveable { mutableStateOf("") }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var hasUnknownOutcome by rememberSaveable { mutableStateOf(false) }
    var rejectionConfirmed by rememberSaveable { mutableStateOf(false) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var refreshEmployeeAfterRead by rememberSaveable { mutableStateOf(false) }
    val pending = pendingJson?.let { Json.decodeFromString(OnboardingWorkspaceIntent.serializer(), it) }

    LaunchedEffect(employee.version, refresh) {
        loading = true
        loadError = null
        try {
            if (!isSessionCurrent()) throw CancellationException("Onboarding session changed")
            val value = client.workspace(employee.employeeId, installationId)
            currentCoroutineContext().ensureActive()
            if (!isSessionCurrent()) throw CancellationException("Onboarding session changed")
            check(onboardingWorkspaceMatchesEmployee(value, employee.employeeId, employee.organizationId))
            snapshot = value
            if (pending == null && value.policies.none { it.configRevisionId == policyId }) policyId = ""
            if (refreshEmployeeAfterRead) {
                refreshEmployeeAfterRead = false
                onEmployeeChanged()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            snapshot = null
            loadError = (failure as? HiltechApiException)?.code ?: "SOURCE_CHECK_FAILED"
        } finally {
            loading = false
        }
    }

    fun prepare(intent: OnboardingWorkspaceIntent) {
        pendingJson = Json.encodeToString(OnboardingWorkspaceIntent.serializer(), intent)
        attempted = false
        hasUnknownOutcome = false
        rejectionConfirmed = false
        commandError = null
        acknowledged = false
        dialogVisible = true
    }

    val current = snapshot
    val case = current?.currentCase
    val canPrepare = !loading && !submitting && pending == null && isSessionCurrent()
    HiltechPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(hiltechText("تجهيز انضمام الموظف", "Employee onboarding"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refresh += 1 }, enabled = !loading && !submitting) {
                Text(hiltechText("تحديث", "Refresh"))
            }
        }
        Text(hiltechText(
            "راجع المتطلبات الحالية قبل تفعيل الموظف. إنشاء حسابه لا يفعّل حالة توظيفه تلقائيًا.",
            "Review current requirements before activating this employee. Creating an account does not activate employment automatically.",
        ))
        if (loading) CircularProgressIndicator()
        loadError?.let { Text(onboardingMessage(it, sourceRead = true), color = MaterialTheme.colorScheme.error) }
        if (acknowledged) Text(hiltechText(
            "تأكد تسجيل العملية.",
            "The operation was confirmed.",
        ))
        if (pending != null && !dialogVisible) {
            Text(if (hasUnknownOutcome) hiltechText("هناك عملية لم تُحسم نتيجتها بعد.", "An operation still has an unconfirmed outcome.")
                else hiltechText("هناك مراجعة محفوظة تحتاج متابعة.", "A saved review needs attention."))
            TextButton(onClick = { dialogVisible = true }) { Text(hiltechText("متابعة المراجعة", "Continue review")) }
        }
        if (!loading && current != null) {
            when {
                case != null -> {
                    Text(hiltechText("سياسة التجهيز: ", "Onboarding policy: ") + case.policyCode +
                        hiltechText(" · الإصدار ", " · Revision ") + case.policyRevisionNumber)
                    HiltechStateTag(
                        if (case.state == "ACTIVATED") hiltechText("اكتمل التفعيل", "Activation completed")
                        else hiltechText("التجهيز جارٍ", "Onboarding in progress"),
                        if (case.state == "ACTIVATED") HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION,
                    )
                    if (case.state == "ACTIVATED") Text(hiltechText(
                        "هذه حالة تجهيز مكتملة. راجع ملف الموظف لمعرفة حالة توظيفه الحالية ومسؤولياته.",
                        "This onboarding case is complete. The employee record holds current employment status and responsibilities.",
                    ))
                    val groups = listOf(
                        setOf("NEEDS_EMPLOYEE") to hiltechText("يحتاج إجراءً من الموظف", "Needs the employee"),
                        setOf("WAITING_HILTECH", "BLOCKED") to hiltechText("يحتاج متابعة من الإدارة", "Needs administration attention"),
                        setOf("SATISFIED", "WAIVED") to hiltechText("المتطلبات المكتملة أو المستثناة", "Completed or waived requirements"),
                    )
                    for ((states, title) in groups) {
                        val rows = case.requirements.filter { it.status in states }.sortedBy { it.sortOrder }
                        if (rows.isNotEmpty()) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            rows.forEach { requirement ->
                                OnboardingRequirementReview(requirement, canPrepare) { resolution ->
                                    prepare(OnboardingWorkspaceIntent(
                                        operationId = newOperationId(), employeeId = current.employeeId,
                                        organizationId = current.organizationId, employeeDisplayName = current.employeeDisplayName,
                                        employeeVersion = current.employeeVersion, policyConfigRevisionId = case.policyConfigRevisionId,
                                        action = OnboardingWorkspaceAction.RESOLVE, policyLabel = case.policyCode,
                                        caseId = case.caseId, caseVersion = case.caseVersion,
                                        requirementKey = requirement.requirementKey, requirementLabel = requirement.label,
                                        resolution = resolution, reason = waiverReason.takeIf { resolution == "WAIVED" },
                                    ))
                                }
                            }
                        }
                    }
                    if (case.requirements.isEmpty()) Text(hiltechText(
                        "لا توجد متطلبات في هذه النسخة من السياسة. لا تضف شروطًا بديلة من هذه الشاشة.",
                        "This policy revision contains no requirements. This screen does not add substitute rules.",
                    ))
                    if (case.state == "OPEN") {
                        if (!case.canActivate) Text(hiltechText(
                            "التفعيل غير متاح الآن. أكمل متطلبات التفعيل الموضحة أعلاه ثم حدّث بيانات التجهيز.",
                            "Activation is unavailable. Complete the requirements for activation shown above, then refresh the onboarding details.",
                        ))
                        Button(enabled = canPrepare && case.canActivate, onClick = {
                            prepare(OnboardingWorkspaceIntent(
                                newOperationId(), current.employeeId, current.organizationId, current.employeeDisplayName,
                                current.employeeVersion, case.policyConfigRevisionId, OnboardingWorkspaceAction.ACTIVATE,
                                case.policyCode, case.caseId, case.caseVersion,
                            ))
                        }) { Text(hiltechText("مراجعة التفعيل", "Review activation")) }
                    }
                }
                current.startAvailability == "AVAILABLE" -> {
                    val revisionLabel = hiltechText(" · الإصدار ", " · Revision ")
                    HiltechRecordPicker(
                        label = hiltechText("سياسة تجهيز الانضمام", "Onboarding policy"), value = policyId,
                        choices = current.policies.map { HiltechRecordChoice(it.configRevisionId, it.name,
                            it.code + revisionLabel + it.revisionNumber) },
                        onChange = { policyId = it }, allowKnownReference = false, enabled = canPrepare,
                    )
                    val selected = current.policies.firstOrNull { it.configRevisionId == policyId }
                    Button(enabled = canPrepare && selected != null, onClick = {
                        if (selected != null) prepare(OnboardingWorkspaceIntent(
                            newOperationId(), current.employeeId, current.organizationId, current.employeeDisplayName,
                            current.employeeVersion, selected.configRevisionId, OnboardingWorkspaceAction.START,
                            selected.name + revisionLabel + selected.revisionNumber,
                        ))
                    }) { Text(hiltechText("مراجعة بدء التجهيز", "Review onboarding start")) }
                }
                current.startAvailability == "POLICY_NOT_CONFIGURED" -> Text(hiltechText(
                    "لا توجد سياسة تجهيز فعالة تنطبق على هذا الموظف. يلزم إعداد سياسة الشركة المعتمدة قبل البدء.",
                    "No active onboarding policy applies to this employee. An authorized company policy must be configured before starting.",
                ))
                else -> Text(hiltechText(
                    "بدء التجهيز متاح للموظف في حالة ما قبل الانضمام فقط. لم تُغيّر حالة الموظف.",
                    "Onboarding can start only for a preboarding employee. The employee state has not been changed.",
                ))
            }
        }
    }

    if (pending != null && dialogVisible) {
        val needsReason = pending.resolution == "WAIVED"
        val reasonValid = !needsReason || (!pending.reason.isNullOrBlank() && (pending.reason?.length ?: 0) <= 500)
        AlertDialog(
            onDismissRequest = { if (!submitting) dialogVisible = false },
            title = { Text(onboardingActionTitle(pending.action, pending.resolution)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pending.employeeDisplayName, style = MaterialTheme.typography.titleMedium)
                    Text(pending.policyLabel)
                    pending.requirementLabel?.let { Text(it) }
                    Text(when (pending.action) {
                        OnboardingWorkspaceAction.START -> hiltechText("ستُثبّت هذه النسخة من السياسة لهذه الحالة.", "This policy revision will be pinned to this case.")
                        OnboardingWorkspaceAction.RESOLVE -> hiltechText(
                            "يسجل هذا القرار استيفاءً يدويًا أو استثناءً مسموحًا. لا يتحول المستند أو المؤهل إلى موثّق بهذا القرار.",
                            "This records a permitted manual confirmation or waiver. It does not verify the underlying document or certification.")
                        OnboardingWorkspaceAction.ACTIVATE -> hiltechText(
                            "يتحقق HILTECH من اكتمال متطلبات التفعيل. إصدار المعدات ومنح المسؤوليات لهما إجراءات منفصلة.",
                            "HILTECH checks that the requirements for activation are complete. Equipment issue and responsibility assignments are handled separately.")
                    })
                    if (needsReason) OutlinedTextField(
                        value = pending.reason.orEmpty(),
                        onValueChange = {
                            if (!attempted && !submitting) {
                                waiverReason = it
                                pendingJson = Json.encodeToString(OnboardingWorkspaceIntent.serializer(), pending.copy(reason = it))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), readOnly = attempted || submitting,
                        label = { Text(hiltechText("سبب الاستثناء", "Waiver reason")) },
                        isError = pending.reason?.length?.let { it > 500 } == true,
                        supportingText = { Text(hiltechText("سبب واضح مطلوب، حتى 500 حرف.", "A clear reason is required, up to 500 characters.")) },
                    )
                    if (hasUnknownOutcome) Text(hiltechText(
                        "قد يكون الطلب نُفّذ، ولم تتأكد نتيجته بعد. عند إعادة المحاولة سنراجع نتيجة الطلب نفسه؛ انتظر تأكيدها قبل إجراء طلب آخر.",
                        "The request may have completed, but its outcome is not confirmed yet. Retrying will check the same request. Wait for confirmation before making another request.",
                    ), color = MaterialTheme.colorScheme.error)
                    commandError?.let { Text(onboardingMessage(it,
                        confirmedRejection = rejectionConfirmed && !hasUnknownOutcome), color = MaterialTheme.colorScheme.error) }
                    if (rejectionConfirmed && !hasUnknownOutcome) Text(hiltechText(
                        "تأكد عدم تنفيذ هذه المحاولة. يمكنك مراجعة الحالة الحالية مع الاحتفاظ بسبب الاستثناء.",
                        "This attempt was confirmed unsuccessful. Review the current state; the waiver reason is retained.",
                    ))
                    if (submitting) CircularProgressIndicator()
                }
            },
            confirmButton = {
                if (rejectionConfirmed && !hasUnknownOutcome) {
                    TextButton(enabled = !submitting, onClick = {
                        pendingJson = null; dialogVisible = false; attempted = false; rejectionConfirmed = false
                        commandError = null; refresh += 1
                    }) { Text(hiltechText("مراجعة الحالة الحالية", "Review current state")) }
                } else Button(enabled = !submitting && reasonValid && isSessionCurrent(), onClick = {
                    val captured = pending
                    val priorUnknown = hasUnknownOutcome
                    submitting = true
                    commandError = null
                    jobs.launch {
                        try {
                            val response = submitCommand(captured, priorUnknown) {
                                attempted = true
                                hasUnknownOutcome = true
                            }
                            currentCoroutineContext().ensureActive()
                            if (!isSessionCurrent()) throw CancellationException("Onboarding session changed")
                            check(onboardingReceiptMatches(captured, response))
                            acknowledged = true
                            refreshEmployeeAfterRead = captured.action == OnboardingWorkspaceAction.ACTIVATE
                            pendingJson = null; dialogVisible = false; attempted = false
                            hasUnknownOutcome = false; rejectionConfirmed = false
                            snapshot = null
                            refresh += 1
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            val disposition = onboardingFailureDisposition(priorUnknown, failure)
                            hasUnknownOutcome = disposition.hasUnknownOutcome
                            rejectionConfirmed = disposition.rejectionConfirmed
                            commandError = when (failure) {
                                is OnboardingPreparationException -> failure.safeCode
                                is HiltechApiException -> failure.code
                                else -> "OUTCOME_UNCONFIRMED"
                            }
                        } finally {
                            submitting = false
                        }
                    }
                }) { Text(if (hasUnknownOutcome) hiltechText("إعادة نفس المحاولة", "Retry same operation") else hiltechText("تأكيد", "Confirm")) }
            },
            dismissButton = {
                TextButton(enabled = !submitting, onClick = {
                    dialogVisible = false
                    if (!attempted && !hasUnknownOutcome) pendingJson = null
                }) { Text(if (attempted || hasUnknownOutcome) hiltechText("إغلاق المراجعة", "Close review") else hiltechText("إلغاء", "Cancel")) }
            },
        )
    }
}

@Composable
private fun OnboardingRequirementReview(row: OnboardingRequirementDto, enabled: Boolean, onResolve: (String) -> Unit) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(row.label, style = MaterialTheme.typography.titleSmall)
        Text(if (row.blocking) hiltechText("مطلوب قبل التفعيل", "Required before activation") else hiltechText("غير مانع للتفعيل", "Does not block activation"))
        Text(when (row.status) {
            "SATISFIED" -> hiltechText("مستوفى من المصدر", "Satisfied by the source")
            "WAIVED" -> hiltechText("استثناء مسجل", "Recorded waiver")
            "NEEDS_EMPLOYEE" -> hiltechText("يحتاج إجراءً من الموظف", "Needs employee action")
            "WAITING_HILTECH" -> hiltechText("بانتظار متابعة الإدارة", "Waiting for administration")
            else -> hiltechText("يحتاج مراجعة", "Needs review")
        })
        when (row.sourceStateCode) {
            "WAITING_VERIFICATION" -> Text(hiltechText("المستند موجود وينتظر التحقق؛ لا تطلب رفعه مرة أخرى.", "The document is awaiting verification; do not ask for another upload."))
            "REJECTED" -> Text(hiltechText("رُفض المستند؛ راجع التصحيح المطلوب في مصدره.", "The document was rejected; review the required correction at its source."))
            "EVIDENCE_MISSING", "PENDING_UPLOAD", "UPLOADING" -> Text(hiltechText("لم يكتمل دليل المستند في مصدره.", "Document evidence is not complete at its source."))
            "NOT_VALID" -> Text(hiltechText("المؤهل غير مستوفٍ أو انتهت صلاحيته؛ راجع سجل المؤهل.", "The qualification is unverified or expired; review its source record."))
        }
        row.allowedResolutions.filter { it in setOf("SATISFIED", "WAIVED") }.forEach { resolution ->
            TextButton(enabled = enabled, onClick = { onResolve(resolution) }) {
                Text(if (resolution == "WAIVED") hiltechText("مراجعة استثناء مسموح", "Review permitted waiver")
                    else hiltechText("تأكيد المتطلب اليدوي", "Confirm manual requirement"))
            }
        }
    }
}

@Composable
private fun onboardingActionTitle(action: OnboardingWorkspaceAction, resolution: String?): String = when (action) {
    OnboardingWorkspaceAction.START -> hiltechText("بدء تجهيز الانضمام", "Start onboarding")
    OnboardingWorkspaceAction.ACTIVATE -> hiltechText("تفعيل الموظف", "Activate employee")
    OnboardingWorkspaceAction.RESOLVE -> if (resolution == "WAIVED") hiltechText("استثناء من متطلب التجهيز", "Waive onboarding requirement")
        else hiltechText("تأكيد متطلب يدوي", "Confirm manual requirement")
}

@Composable
private fun onboardingMessage(code: String, confirmedRejection: Boolean = false, sourceRead: Boolean = false): String = when (code) {
    "REAUTH_REQUIRED", "UNAUTHENTICATED", "PEOPLE_ACCESS_DENIED", "SESSION_CHANGED" -> hiltechText(
        "تعذر تأكيد جلسة إدارة الموظفين. أعد تأكيد الدخول والصلاحية قبل المتابعة.",
        "People administration access could not be confirmed. Confirm your sign-in and authority before continuing.")
    "SOURCE_CHANGED", "ONBOARDING_VERSION_CONFLICT", "EMPLOYEE_VERSION_CONFLICT", "ONBOARDING_CASE_NOT_OPEN", "EMPLOYEE_NOT_PREBOARDING" -> hiltechText(
        "تغيرت حالة الموظف أو التجهيز. تبقى المراجعة محفوظة حتى تتأكد نتيجة الطلب ثم تراجع المصدر.",
        "The employee or onboarding state changed. This review is retained until the outcome is confirmed and the source is reviewed.")
    "ONBOARDING_BLOCKERS_REMAIN" -> hiltechText("ما زالت متطلبات مانعة غير مستوفاة في المصدر.", "Blocking source requirements remain incomplete.")
    "OPEN_ONBOARDING_CASE_EXISTS", "ONBOARDING_CASE_CONFLICT" -> hiltechText(
        "توجد حالة تجهيز أو تغيير يتعارض مع هذا الطلب. راجع الحالة الحالية قبل إعداد طلب جديد.",
        "An existing onboarding case or change conflicts with this request. Review the current state before preparing a new request.")
    "EMPLOYEE_NOT_FOUND", "ONBOARDING_CASE_NOT_FOUND" -> hiltechText(
        "لم يعد سجل الموظف أو التجهيز متاحًا بهذا السياق. راجع الاختيار وصلاحيتك.",
        "The employee or onboarding record is unavailable in this context. Review the selection and your authority.")
    "ONBOARDING_POLICY_NOT_FOUND", "ONBOARDING_POLICY_NOT_ACTIVE", "ONBOARDING_POLICY_ORGANIZATION_MISMATCH" -> hiltechText(
        "السياسة المختارة غير متاحة لبدء هذا التجهيز. راجع السياسات الحالية المسموحة.",
        "The selected policy is unavailable for this onboarding start. Review the current permitted policies.")
    "ONBOARDING_WAIVER_NOT_ALLOWED", "ONBOARDING_MANUAL_RESOLUTION_NOT_ALLOWED" -> hiltechText("السياسة لا تسمح بهذا القرار لهذا المتطلب.", "The policy does not allow this decision for the requirement.")
    "ONBOARDING_WAIVER_REASON_REQUIRED", "INVALID_TEXT" -> hiltechText("راجع سبب الاستثناء وطوله قبل إعادة إعداد الطلب.", "Review the waiver reason and its length before preparing the request again.")
    "OPERATION_PREVIOUSLY_FAILED" -> hiltechText("أكد الخادم فشل المحاولة المحفوظة. راجع المصدر قبل إعداد طلب جديد.", "The server confirmed the stored attempt failed. Review the source before preparing a new request.")
    "SOURCE_CHECK_FAILED" -> hiltechText("تعذر قراءة مصدر التجهيز. تحقق من الاتصال والصلاحية ثم حدّث العرض.", "Couldn’t read onboarding source data. Check your connection and authority, then refresh.")
    else -> when {
        sourceRead -> hiltechText("تعذر قراءة بيانات التجهيز الحالية. تحقق من الاتصال والصلاحية ثم حدّث العرض.",
            "Couldn’t read current onboarding data. Check your connection and authority, then refresh.")
        confirmedRejection -> hiltechText("أكد الخادم عدم تنفيذ الطلب. راجع البيانات والحالة الحالية قبل إعداد طلب جديد.",
            "The server confirmed the request was unsuccessful. Review the entries and current state before preparing a new request.")
        else -> hiltechText("لم تتأكد نتيجة العملية. احتفظ بالمراجعة وأعد نفس المحاولة للتحقق.", "The outcome is unconfirmed. Keep this review and retry the same operation to verify it.")
    }
}
