package com.hiltech.shared.core.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal data class InitialAssignmentChoices(
    val roles: List<WorkforceAssignmentDto>,
    val teams: List<Pair<String, String>>,
    val managers: List<Pair<String, String>>,
)

internal fun initialAssignmentChoices(employee: EmployeeDetailDto, rows: List<WorkforceAssignmentDto>): InitialAssignmentChoices {
    val active = rows.filter { it.organizationId == employee.organizationId && it.state == "ACTIVE" &&
        it.effectiveTo == null && it.employeeId != employee.employeeId }
    return InitialAssignmentChoices(
        roles = active.filter { Regex("^[A-Z][A-Z0-9_-]{0,79}$").matches(it.roleCode) }.distinctBy { it.roleCode },
        teams = active.mapNotNull { row -> row.teamId?.let { id ->
            row.teamName?.takeIf { it.isNotBlank() }?.let { id to it }
        } }.distinctBy { it.first },
        managers = active.filter { it.employeeDisplayName.isNotBlank() }.map {
            it.employeeId to it.employeeDisplayName
        }.distinctBy { it.first },
    )
}

internal fun initialAssignmentAbsent(error: Throwable): Boolean =
    error is HiltechApiException && error.httpStatus == 404 && error.code == "WORKFORCE_ASSIGNMENT_NOT_FOUND"

internal fun initialAssignmentReceiptMatches(employee: EmployeeDetailDto, request: CreateWorkforceAssignmentRequestDto,
    assignment: WorkforceAssignmentDto): Boolean =
    assignment.organizationId == employee.organizationId && assignment.employeeId == employee.employeeId &&
        assignment.state == "ACTIVE" && assignment.roleCode == request.roleCode &&
        assignment.teamId == request.teamId && assignment.reportsToEmployeeId == request.reportsToEmployeeId &&
        assignment.roleLabel == request.roleLabel

@Composable
fun EmployeeInitialAssignmentWorkspace(
    employee: EmployeeDetailDto,
    sessionScope: String,
    client: WorkforceAssignmentApiClient,
    installationId: String,
    newOperationId: () -> String,
    nowUtc: () -> String,
    onAssignmentReady: () -> Unit,
    onReauthenticate: () -> Unit,
) = key(sessionScope, employee.organizationId, employee.employeeId) {
    val jobs = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf<WorkforceAssignmentDto?>(null) }
    var choices by remember { mutableStateOf<InitialAssignmentChoices?>(null) }
    var error by remember { mutableStateOf(false) }
    var needsAuthentication by remember { mutableStateOf(false) }
    var roleCode by rememberSaveable { mutableStateOf<String?>(null) }
    var teamId by rememberSaveable { mutableStateOf<String?>(null) }
    var managerId by rememberSaveable { mutableStateOf<String?>(null) }
    // This contains source identifiers and intent, never credentials or employee contact details.
    var attemptedJson by rememberSaveable { mutableStateOf<String?>(null) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    val attempted = attemptedJson?.let { Json.decodeFromString(CreateWorkforceAssignmentRequestDto.serializer(), it) }

    LaunchedEffect(client, employee.version, refresh) {
        loading = true
        current = null
        choices = null
        error = false
        try {
            try {
                val source = client.currentForEmployee(employee.employeeId, installationId)
                check(source.employeeId == employee.employeeId && source.organizationId == employee.organizationId)
                current = source
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!initialAssignmentAbsent(failure)) throw failure
                val structure = client.structure(employee.organizationId, installationId)
                check(structure.items.all { it.organizationId == employee.organizationId })
                choices = initialAssignmentChoices(employee, structure.items)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = true
            needsAuthentication = needsAuthentication ||
                (failure as? HiltechApiException)?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")
        } finally {
            loading = false
        }
    }

    fun submit(request: CreateWorkforceAssignmentRequestDto) {
        if (sending) return
        attemptedJson = Json.encodeToString(CreateWorkforceAssignmentRequestDto.serializer(), request)
        sending = true
        rejected = false
        jobs.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val receipt = client.create(employee.employeeId, request, installationId).assignment
                check(initialAssignmentReceiptMatches(employee, request, receipt))
                current = receipt
                attemptedJson = null
                unknown = false
                needsAuthentication = false
                onAssignmentReady()
            } catch (cancelled: CancellationException) {
                unknown = true
                throw cancelled
            } catch (failure: Exception) {
                val api = failure as? HiltechApiException
                needsAuthentication = api?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")
                val definite = api != null && api.httpStatus?.let { it in 400..499 } == true &&
                    api.envelope?.code == api.code && !api.retryable && api.httpStatus !in setOf(408, 429) &&
                    api.code !in setOf("IDEMPOTENCY_KEY_REUSED", "REAUTH_REQUIRED", "UNAUTHENTICATED")
                if (definite && (!unknown || api?.code == "OPERATION_PREVIOUSLY_FAILED")) {
                    attemptedJson = null
                    unknown = false
                    rejected = true
                } else {
                    unknown = true
                }
                // Re-read the source before offering any new assignment or replay.
                loading = true
                refresh++
            } finally {
                sending = false
            }
        }
    }

    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("التعيين الوظيفي الأول", "Initial workforce assignment"), style = MaterialTheme.typography.titleMedium)
        when {
            loading -> Text(hiltechText("جارٍ التحقق من التعيين وسجل الفريق…", "Checking assignment and team records…"))
            error -> Text(hiltechText("تعذر التحقق من المصدر. لم يُنشأ تعيين جديد.", "The source could not be verified. No new assignment is available."),
                color = MaterialTheme.colorScheme.error)
            current != null -> {
                Text(hiltechText("يوجد تعيين حالي لهذا الموظف.", "This employee has a current assignment."))
                Text(current?.roleLabel ?: current?.roleCode.orEmpty())
                if (attempted != null) Text(hiltechText("راجع التعيين الموجود؛ لا يُرسل طلب إنشاء جديد.", "Review the existing assignment; no new create request will be sent."))
                Button(onClick = onAssignmentReady) { Text(hiltechText("فتح التعيين الحالي", "Open current assignment")) }
            }
            choices != null -> {
                val source = choices!!
                if (attempted != null) {
                    Text(hiltechText("الطلب السابق غير محسوم. ستتم إعادة نفس الطلب دون تغييره.",
                        "The earlier request is unresolved. Retry the exact same request without changing it."))
                    Button(enabled = !sending, onClick = { submit(attempted) }) {
                        Text(hiltechText("متابعة الطلب السابق", "Resume previous request"))
                    }
                } else if (source.roles.isEmpty()) {
                    Text(hiltechText("لا توجد مسميات من سجل التعيينات الحالي للاختيار منها. لا ننشئ مسمى افتراضيًا.",
                        "No titles are available from the current assignment records. No default title is invented."))
                } else {
                    val role = source.roles.firstOrNull { it.roleCode == roleCode }
                    val teamValid = teamId == null || source.teams.any { it.first == teamId }
                    val managerValid = managerId == null || source.managers.any { it.first == managerId }
                    AssignmentSourceChoice(hiltechText("المسمى الوظيفي", "Job title"),
                        source.roles.map { it.roleCode to (it.roleLabel ?: it.roleCode) }, roleCode, false, !sending) { roleCode = it }
                    AssignmentSourceChoice(hiltechText("الفريق", "Team"), source.teams, teamId, true, !sending) { teamId = it }
                    AssignmentSourceChoice(hiltechText("المدير المباشر", "Reports to"), source.managers, managerId, true, !sending) { managerId = it }
                    Text(hiltechText("اختيارات من التعيينات السارية. التعيين لا يمنح تلقائيًا صلاحيات مالية أو مسؤوليات المشاريع.",
                        "Choices come from current assignments. This does not automatically grant financial or project responsibilities."),
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !sending && role != null && teamValid && managerValid &&
                        employee.employeeState in setOf("ACTIVE", "PREBOARDING"), onClick = {
                        role?.let { submit(CreateWorkforceAssignmentRequestDto(newOperationId(), employee.version,
                            teamId, it.roleCode, it.roleLabel, managerId, nowUtc())) }
                    }) { Text(hiltechText("حفظ التعيين", "Save assignment")) }
                }
            }
        }
        if (sending) Text(hiltechText("جارٍ تسجيل التعيين…", "Recording assignment…"))
        if (rejected) Text(hiltechText("لم يُقبل الطلب. راجع الاختيارات أو حدّث البيانات.", "The request was rejected. Review choices or refresh."),
            color = MaterialTheme.colorScheme.error)
        if (needsAuthentication) TextButton(enabled = !sending, onClick = onReauthenticate) {
            Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in"))
        }
        TextButton(enabled = !loading && !sending, onClick = { loading = true; refresh++ }) {
            Text(hiltechText("تحديث المصدر", "Refresh source"))
        }
    }
}

@Composable
private fun AssignmentSourceChoice(label: String, options: List<Pair<String, String>>, selected: String?,
    optional: Boolean, enabled: Boolean, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
                Text(options.firstOrNull { it.first == selected }?.second ?: hiltechText(
                    if (optional) "بدون تحديد" else "اختر من السجل", if (optional) "Not specified" else "Choose from records"))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (optional) DropdownMenuItem(text = { Text(hiltechText("بدون تحديد", "Not specified")) },
                    onClick = { onSelect(null); expanded = false })
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
                }
            }
        }
    }
}
