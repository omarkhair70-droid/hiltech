package com.hiltech.shared.core.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.PlanningFormRequest
import com.hiltech.shared.core.PlanningFormHost
import com.hiltech.shared.core.PlanningFormOperation
import com.hiltech.shared.core.PlanningFormResult
import com.hiltech.shared.core.ProjectPlanningSection
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.planningFormResponse
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.planningFormFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A compact source-backed project route. Planning commands keep the original server guards. */
fun projectPlanningNativeWorkspace(
    projects: ProjectsApiClient,
    planning: PlanningApiClient,
    sessionScope: String,
    organizationId: String,
    installationId: String,
    newOperation: () -> PlanningFormOperation,
    submit: suspend (PlanningFormRequest, () -> Boolean) -> PlanningMutationResponseDto,
    employeeChoices: List<HiltechRecordChoice> = emptyList(),
    teamChoices: List<HiltechRecordChoice> = emptyList(),
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "project-planning",
    label = "المشاريع وخطط التنفيذ",
    canReadSource = {
        projects.list(organizationId, installationId, limit = 1)
        true
    },
    hostOwnsScroll = false,
    content = {
        ProjectPlanningWorkspace(projects, planning, sessionScope, organizationId, installationId, newOperation, submit, employeeChoices, teamChoices)
    },
)

@Composable
private fun ProjectPlanningWorkspace(
    projects: ProjectsApiClient,
    planning: PlanningApiClient,
    sessionScope: String,
    organizationId: String,
    installationId: String,
    newOperation: () -> PlanningFormOperation,
    submit: suspend (PlanningFormRequest, () -> Boolean) -> PlanningMutationResponseDto,
    employeeChoices: List<HiltechRecordChoice>,
    teamChoices: List<HiltechRecordChoice>,
) {
    val key = "$sessionScope:$organizationId:$installationId"
    val scope = rememberCoroutineScope()
    var alive by remember(key) { mutableStateOf(true) }
    DisposableEffect(key) { onDispose { alive = false } }
    var revision by remember(key) { mutableIntStateOf(0) }
    var directory by remember(key) { mutableStateOf<List<ProjectSummaryDto>>(emptyList()) }
    var selectedId by remember(key) { mutableStateOf<String?>(null) }
    var plan by remember(key) { mutableStateOf<ProjectPlanDto?>(null) }
    var query by remember(key) { mutableStateOf("") }
    var loading by remember(key) { mutableStateOf(true) }
    var failure by remember(key) { mutableStateOf<Throwable?>(null) }
    var pendingOperation by remember(key, selectedId) { mutableStateOf<String?>(null) }
    var hadUnknownOutcome by remember(key, selectedId) { mutableStateOf(false) }

    LaunchedEffect(key, revision, selectedId) {
        loading = true
        failure = null
        try {
            val id = selectedId
            if (id == null) {
                directory = projects.list(organizationId, installationId, limit = 200).items
            } else {
                // A new read rechecks current source authority. It does not resolve an unknown command outcome.
                plan = planning.plan(id, installationId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            failure = problem
            if ((problem as? HiltechApiException)?.httpStatus in setOf(401, 403, 404)) {
                plan = null
                directory = emptyList()
            }
        } finally {
            loading = false
        }
    }

    val host = remember(key, selectedId, planning) {
        PlanningFormHost(
            scopeKey = "$key:${selectedId.orEmpty()}",
            newOperation = newOperation,
            submit = { request, completed ->
                val projectId = request.action.projectId()
                if (!alive || selectedId != projectId ||
                    (pendingOperation != null && pendingOperation != request.operation.operationId)
                ) {
                    completed(PlanningFormResult.Rejected("PLANNING_SESSION_CHANGED", null, null))
                } else {
                    if (pendingOperation == null) hadUnknownOutcome = false
                    pendingOperation = request.operation.operationId
                    scope.launch {
                        val result = try {
                            val response = submit(request) { alive && selectedId == projectId }
                            planningFormResponse(request, response)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (problem: Exception) {
                            planningFormFailure(problem)
                        }
                        if (!alive || selectedId != projectId) return@launch
                        when (result) {
                            is PlanningFormResult.Succeeded -> {
                                plan = result.response.plan
                                pendingOperation = null
                                hadUnknownOutcome = false
                            }
                            is PlanningFormResult.Rejected -> {
                                if (!hadUnknownOutcome || result.code == "OPERATION_PREVIOUSLY_FAILED") pendingOperation = null
                                if (result.httpStatus == 409 && !hadUnknownOutcome) revision += 1
                            }
                            is PlanningFormResult.Unknown -> hadUnknownOutcome = true
                        }
                        completed(result)
                    }
                }
            },
        )
    }
    val colors = LocalHiltechOperationalColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (selectedId != null) TextButton(
                enabled = pendingOperation == null,
                onClick = { selectedId = null; plan = null; query = "" },
            ) { Text(hiltechText("العودة إلى المشاريع", "Back to projects")) }
            TextButton(enabled = !loading && pendingOperation == null, onClick = { revision += 1 }) {
                Text(hiltechText("تحديث", "Refresh"))
            }
        }
        if (loading) CircularProgressIndicator()
        failure?.let { problem ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                val denied = (problem as? HiltechApiException)?.httpStatus in setOf(401, 403, 404)
                Text(if (denied) hiltechText("لم تعد بيانات المشروع متاحة لهذا الحساب.", "Project data is no longer available to this account.")
                    else hiltechText("تعذر تحديث بيانات المشاريع. تحقق من الاتصال وحاول مرة أخرى.", "Could not refresh project data. Check your connection and retry."))
                (problem as? HiltechApiException)?.correlationId?.let {
                    Text(hiltechText("مرجع المتابعة: ", "Support reference: ") + it, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (selectedId == null) {
            HiltechSectionHeading("PROJECTS", hiltechText("مشاريعي", "My projects"),
                hiltechText("اختر مشروعًا لمراجعة المواقع وخطة التنفيذ.", "Choose a project to review its sites and delivery plan."))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(hiltechText("ابحث بالاسم أو الكود أو العميل", "Search name, code or client")) })
            val matching = directory.filter { project -> query.isBlank() ||
                listOf(project.name, project.projectCode, project.clientDisplayName).any { it.contains(query.trim(), true) } }
            if (!loading && failure == null && matching.isEmpty()) Text(
                if (query.isBlank()) hiltechText("لا توجد مشاريع متاحة الآن.", "No projects are available yet.")
                else hiltechText("لا توجد نتائج مطابقة.", "No matching projects."))
            matching.forEach { project ->
                Surface(
                    onClick = { plan = null; failure = null; selectedId = project.projectId },
                    shape = HiltechShape.medium, color = colors.surface, contentColor = colors.primaryText,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(project.projectCode, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text(project.clientDisplayName, color = colors.secondaryText)
                        Text(project.currentResponsibility.principalLabel ?: hiltechText("لم يُعيّن مسؤول", "No responsible person assigned"))
                        Text(hiltechText("فتح خطة التنفيذ", "Open delivery plan"), color = colors.accent)
                    }
                }
            }
            if (directory.size == 200) Text(hiltechText("يعرض هذا الدليل أول ٢٠٠ مشروع متاح.", "This directory shows the first 200 available projects."),
                style = MaterialTheme.typography.bodySmall)
        } else {
            plan?.takeIf { it.project.projectId == selectedId }?.let { current ->
                Text(current.project.name, style = MaterialTheme.typography.headlineSmall)
                ProjectPlanningSection(plan = current, onAction = {}, formHost = host,
                    employeeChoices = employeeChoices, teamChoices = teamChoices)
            }
        }
    }
}
