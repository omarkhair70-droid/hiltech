package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.projects.ProjectListDto
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.projects.ProjectSiteListDto
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.projects.SiteDto
import com.hiltech.shared.core.work.ProjectCommandCenterDto
import com.hiltech.shared.core.work.WorkOrderDto
import com.hiltech.shared.core.work.WorkReadinessDto
import com.hiltech.shared.core.work.WorkQueueContextDto
import com.hiltech.shared.core.work.WorkTypeChoiceDto

data class HiltechProjectsState(
    val loading: Boolean = false,
    val list: ProjectListDto? = null,
    val selectedProject: ProjectSummaryDto? = null,
    val selectedSites: ProjectSiteListDto? = null,
    val plan: ProjectPlanDto? = null,
    val workOrders: List<WorkOrderDto> = emptyList(),
    val workTypes: List<WorkTypeChoiceDto> = emptyList(),
    val workReadinessByOrder: Map<String, WorkReadinessDto> = emptyMap(),
    val workQueueContext: List<WorkQueueContextDto> = emptyList(),
    val commandCenter: ProjectCommandCenterDto? = null,
    val lastCreatedSite: SiteDto? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

@Composable
fun ProjectControlSection(
    state: HiltechProjectsState,
    onRefresh: () -> Unit = {},
    onSelectProject: (String) -> Unit = {},
    onCreateProject:
        (
            name: String,
            clientOrganizationId: String,
            sourceType: String,
            sourceExternalReference: String?,
            explicitProjectCode: String?,
            principalType: String?,
            principalId: String?,
            startDatePlanned: String?,
            endDatePlanned: String?,
        ) -> Unit = {
            _, _, _, _, _, _, _, _, _ -> Unit
        },
    onChangeManager:
        (
            projectId: String,
            baseVersion: Long,
            principalType: String,
            principalId: String,
            reason: String?,
        ) -> Unit = {
            _, _, _, _, _ -> Unit
        },
    onStartKickoff:
        (
            projectId: String,
            baseVersion: Long,
        ) -> Unit = { _, _ -> },
    onCompleteKickoff:
        (
            projectId: String,
            baseVersion: Long,
        ) -> Unit = { _, _ -> },
    onCreateSite:
        (
            clientOrganizationId: String,
            siteCode: String,
            name: String,
            addressText: String?,
            timezone: String?,
        ) -> Unit = {
            _, _, _, _, _ -> Unit
        },
    onAttachSite:
        (
            projectId: String,
            baseProjectVersion: Long,
            siteId: String,
            projectSiteCode: String?,
            accessInstructions: String?,
            projectSpecificNotes: String?,
        ) -> Unit = {
            _, _, _, _, _, _ -> Unit
        },
    onPlanningAction: (PlanningUiAction) -> Unit = {},
    onWorkPlanningAction: (WorkPlanningUiAction) -> Unit = {},
    onWorkReadinessAction: (WorkReadinessUiAction) -> Unit = {},
    reservationPort: ReservationUiPort? = null,
    onProjectCommandCenterAction: (ProjectCommandCenterUiAction) -> Unit = {},
    employeeChoices: List<HiltechRecordChoice> = emptyList(),
    teamChoices: List<HiltechRecordChoice> = emptyList(),
    openResponsibleEmployee: ((String) -> Unit)? = null,
    readableEmployeeIds: Set<String> = emptySet(),
) {
    CompositionLocalProvider(LocalLayoutDirection provides com.hiltech.shared.core.design.LocalHiltechLanguage.current.direction) {
        val colors = LocalHiltechOperationalColors.current
        var query by remember { mutableStateOf("") }
        var creating by remember { mutableStateOf(false) }
        val selected = state.selectedProject
        var showDetail by remember(selected?.projectId) { mutableStateOf(selected != null) }
        var tab by remember(selected?.projectId) { mutableStateOf(ProjectWorkspaceTab.Overview) }
        var showManager by remember(selected?.projectId) { mutableStateOf(false) }
        var showSiteCreate by remember(selected?.projectId) { mutableStateOf(false) }
        var showSiteAttach by remember(selected?.projectId) { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    HiltechSectionHeading("PROJECTS / DELIVERY", hiltechText("المشاريع والمواقع", "Projects & sites"),
                        hiltechText("تابع المشاريع، فرق التنفيذ، والتقدم الفعلي من مكان واحد.", "Follow projects, delivery teams and actual progress in one place."))
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
            }
            if (state.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(hiltechText("جارٍ تحميل بيانات المشاريع…", "Loading project data…"))
                }
            }
            state.errorMessage?.let { error ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    HiltechStateTag(hiltechText("تعذر تحديث المشروع", "Unable to refresh project"), HiltechStateTone.ATTENTION)
                    Text(error)
                    state.errorCode?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.secondaryText) }
                }
            }
            val directory: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(hiltechText("دليل المشاريع", "Project directory"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { creating = !creating }) { Text(if (creating) hiltechText("إغلاق", "Close") else hiltechText("+ مشروع", "+ Project")) }
                    }
                    if (creating) {
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(hiltechText("إنشاء مشروع", "Create project"), style = MaterialTheme.typography.titleLarge)
                            ProjectCreateForm(onCreateProject = onCreateProject,
                                clientChoices = state.list?.items.orEmpty().map { HiltechRecordChoice(it.clientOrganizationId, it.clientDisplayName) }.distinctBy { it.id },
                                employeeChoices = employeeChoices, teamChoices = teamChoices)
                        }
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(hiltechText("ابحث بالاسم أو الكود أو العميل", "Search name, code or client")) })
                    val projects = state.list?.items.orEmpty()
                    val matching = projects.filter {
                        query.isBlank() || listOf(it.name, it.projectCode, it.clientDisplayName).any { value ->
                            value.contains(query.trim(), ignoreCase = true)
                        }
                    }
                    Text(hiltechText("المعروض: ", "Visible: ") + matching.size, color = colors.secondaryText,
                        style = MaterialTheme.typography.labelMedium)
                    if (!state.loading && matching.isEmpty()) {
                        Text(if (query.isBlank()) hiltechText("لا توجد مشاريع متاحة الآن.", "No projects available.") else hiltechText("لا توجد نتائج مطابقة.", "No matching results."))
                    }
                    matching.forEach { project ->
                        Surface(
                            onClick = { showDetail = true; creating = false; onSelectProject(project.projectId) },
                            color = if (project.projectId == selected?.projectId) colors.selected else colors.surface,
                            contentColor = colors.primaryText, shape = HiltechShape.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(project.projectCode, Modifier.weight(1f), color = colors.secondaryText,
                                        style = MaterialTheme.typography.labelMedium)
                                    HiltechStateTag(projectStateLabel(project.lifecycleState), projectStateTone(project.lifecycleState))
                                }
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text(project.clientDisplayName, color = colors.secondaryText,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(project.currentResponsibility.principalLabel ?: hiltechText("لم يُعيّن مسؤول", "No responsible person assigned"),
                                    style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                            }
                        }
                    }
                }
            }

            val detail: @Composable () -> Unit = {
                if (selected == null) {
                    HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(hiltechText("اختر مشروعًا", "Select a project"), style = MaterialTheme.typography.titleLarge)
                        Text(hiltechText("ستجد تفاصيل التنفيذ والمواقع والتخطيط والقرارات في مساحة المشروع.", "Execution, sites, planning and decisions live in the project workspace."))
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(selected.projectCode, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                            Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                            Text(selected.clientDisplayName, style = MaterialTheme.typography.bodyLarge, color = colors.secondaryText)
                            HiltechStateTag(projectStateLabel(selected.lifecycleState), projectStateTone(selected.lifecycleState))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HiltechKeyValue(hiltechText("المسؤول", "Responsible person"), selected.currentResponsibility.principalLabel ?: hiltechText("غير معيّن", "Not assigned"), Modifier.weight(1f))
                                HiltechKeyValue(hiltechText("المواقع المرتبطة", "Linked sites"), selected.siteCount.toString(), Modifier.weight(1f))
                            }
                            val responsible = selected.currentResponsibility
                            val employeeId = responsible.principalId
                            if (responsible.principalType == "EMPLOYEE" && employeeId != null &&
                                employeeId in readableEmployeeIds && openResponsibleEmployee != null) {
                                TextButton(onClick = { openResponsibleEmployee(employeeId) }) {
                                    Text(hiltechText("فتح ملف مسؤول المشروع", "Open project owner's profile"))
                                }
                            }
                            HiltechKeyValue(hiltechText("الفترة المخططة", "Planned period"), (selected.startDatePlanned ?: "غير محددة") + " — " + (selected.endDatePlanned ?: "غير محددة"))
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProjectWorkspaceTab.entries.forEach { item ->
                                FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(hiltechText(item.title, item.english)) })
                            }
                        }
                        when (tab) {
                            ProjectWorkspaceTab.Overview -> {
                                val commandCenter = state.commandCenter
                                if (commandCenter != null && commandCenter.projectCode == selected.projectCode) {
                                    ProjectCommandCenterSection(project = selected, commandCenter = commandCenter,
                                        onAction = onProjectCommandCenterAction)
                                } else {
                                    HiltechPanel(Modifier.fillMaxWidth()) {
                                        Text(hiltechText("صحة المشروع والتقدم", "Project health & progress"), style = MaterialTheme.typography.titleMedium)
                                        Text(hiltechText("لا توجد قراءة حديثة لمركز قيادة هذا المشروع. حدّث البيانات لإعادة المحاولة.", "No current project command-center reading. Refresh to try again."))
                                    }
                                }
                                HiltechPanel(Modifier.fillMaxWidth()) {
                                    Text(hiltechText("إدارة المشروع", "Project administration"), style = MaterialTheme.typography.titleMedium)
                                    if (selected.lifecycleState == "DRAFT") {
                                        Button(onClick = { onStartKickoff(selected.projectId, selected.version) }) { Text(hiltechText("بدء التجهيز", "Start kickoff")) }
                                    }
                                    if (selected.lifecycleState == "KICKOFF") {
                                        Button(onClick = { onCompleteKickoff(selected.projectId, selected.version) }) { Text(hiltechText("إكمال التجهيز", "Complete kickoff")) }
                                    }
                                    TextButton(onClick = { showManager = !showManager }) {
                                        Text(if (showManager) hiltechText("إغلاق تغيير المسؤول", "Close manager change") else hiltechText("تغيير مسؤول المشروع", "Change project manager"))
                                    }
                                    if (showManager) ProjectManagerChangeForm(project = selected, onChangeManager = onChangeManager,
                                        employeeChoices = employeeChoices, teamChoices = teamChoices)
                                    Text(hiltechText("المصدر: ", "Source: ") + selected.sourceType + (selected.sourceExternalReference?.let { " · " + it } ?: ""),
                                        style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                                }
                            }
                            ProjectWorkspaceTab.Sites -> {
                                HiltechPanel(Modifier.fillMaxWidth()) {
                                    Text(hiltechText("مواقع المشروع", "Project sites"), style = MaterialTheme.typography.titleMedium)
                                    if (state.selectedSites?.items.orEmpty().isEmpty()) Text(hiltechText("لا توجد مواقع معروضة لهذا المشروع.", "No sites are available for this project."))
                                    state.selectedSites?.items.orEmpty().forEach { relation ->
                                        HorizontalDivider(color = colors.divider)
                                        Text(relation.site.name, style = MaterialTheme.typography.titleSmall)
                                        Text(relation.site.siteCode + " · " + relation.lifecycleState, color = colors.secondaryText)
                                        relation.site.addressText?.let { Text(it) }
                                        relation.accessInstructions?.let { Text(hiltechText("تعليمات الدخول: ", "Access instructions: ") + it) }
                                    }
                                    TextButton(onClick = { showSiteCreate = !showSiteCreate }) { Text(if (showSiteCreate) hiltechText("إغلاق إنشاء الموقع", "Close site creation") else hiltechText("+ موقع جديد", "+ New site")) }
                                    if (showSiteCreate) SiteCreateForm(defaultClientOrganizationId = selected.clientOrganizationId, onCreateSite = onCreateSite)
                                    TextButton(onClick = { showSiteAttach = !showSiteAttach }) { Text(if (showSiteAttach) hiltechText("إغلاق الربط", "Close linking") else hiltechText("ربط موقع موجود", "Link existing site")) }
                                    if (showSiteAttach) ProjectSiteAttachForm(project = selected,
                                        suggestedSiteId = state.lastCreatedSite?.takeIf { it.clientOrganizationId == selected.clientOrganizationId }?.siteId,
                                        onAttachSite = onAttachSite)
                                }
                            }
                            ProjectWorkspaceTab.Plan -> {
                                state.plan?.let { ProjectPlanningSection(plan = it, onAction = onPlanningAction) }
                                    ?: Text(hiltechText("بيانات التخطيط غير متاحة حاليًا.", "Planning data is unavailable."))
                            }
                            ProjectWorkspaceTab.Work -> {
                                state.plan?.let { WorkPlanningSection(plan = it, workTypes = state.workTypes,
                                    orders = state.workOrders, onAction = onWorkPlanningAction) }
                                    ?: Text(hiltechText("بيانات الأعمال غير متاحة حاليًا.", "Work data is unavailable."))
                            }
                            ProjectWorkspaceTab.Readiness -> {
                                WorkReadinessSection(orders = state.workOrders, readinessByWorkOrder = state.workReadinessByOrder,
                                    workQueueContext = state.workQueueContext, onAction = onWorkReadinessAction,
                                    reservationPort = reservationPort)
                            }
                        }
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 900.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(0.85f)) { directory() }
                        Column(Modifier.weight(1.65f)) { detail() }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showDetail && selected != null) {
                            TextButton(onClick = { showDetail = false }) { Text(hiltechText("العودة إلى المشاريع", "Back to projects")) }
                            detail()
                        } else {
                            directory()
                        }
                    }
                }
            }
        }
    }
}

private enum class ProjectWorkspaceTab(val title: String, val english: String) {
    Overview("نظرة عامة", "Overview"), Sites("المواقع", "Sites"), Plan("التخطيط", "Planning"), Work("الأعمال", "Work"), Readiness("الجاهزية", "Readiness"),
}

@Composable
private fun projectStateLabel(state: String): String = when (state) {
    "DRAFT" -> hiltechText("مسودة", "Draft")
    "KICKOFF" -> hiltechText("التجهيز", "Kickoff")
    "ACTIVE" -> hiltechText("قيد التنفيذ", "Active")
    "ON_HOLD" -> hiltechText("متوقف", "On hold")
    "COMPLETED" -> hiltechText("مكتمل", "Completed")
    "CANCELLED" -> hiltechText("ملغى", "Cancelled")
    else -> state
}

private fun projectStateTone(state: String): HiltechStateTone = when (state) {
    "ACTIVE", "COMPLETED" -> HiltechStateTone.CURRENT
    "ON_HOLD", "CANCELLED" -> HiltechStateTone.BLOCKED
    "KICKOFF" -> HiltechStateTone.ATTENTION
    else -> HiltechStateTone.NEUTRAL
}

@Composable
private fun ProjectCreateForm(
    clientChoices: List<HiltechRecordChoice>,
    employeeChoices: List<HiltechRecordChoice>,
    teamChoices: List<HiltechRecordChoice>,
    onCreateProject:
        (
            String,
            String,
            String,
            String?,
            String?,
            String?,
            String?,
            String?,
            String?,
        ) -> Unit,
) {
    var name by remember {
        mutableStateOf("")
    }
    var clientId by remember {
        mutableStateOf("")
    }
    var sourceType by remember {
        mutableStateOf("INTERNAL")
    }
    var sourceReference by remember {
        mutableStateOf("")
    }
    var code by remember {
        mutableStateOf("")
    }
    var principalType by remember {
        mutableStateOf("")
    }
    var principalId by remember {
        mutableStateOf("")
    }
    var startDate by remember {
        mutableStateOf("")
    }
    var endDate by remember {
        mutableStateOf("")
    }

    Text(
        "إنشاء مشروع",
        style =
            MaterialTheme.typography
                .titleSmall,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(hiltechText("اسم المشروع", "Project name")) },
    )
    HiltechRecordPicker(hiltechText("العميل", "Client"), clientId, clientChoices, { clientId = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = sourceType == "INTERNAL", onClick = { sourceType = "INTERNAL"; sourceReference = "" },
            label = { Text(hiltechText("مشروع داخلي", "Internal project")) })
        FilterChip(selected = sourceType == "IMPORT", onClick = { sourceType = "IMPORT" },
            label = { Text(hiltechText("مشروع مستورد", "Imported project")) })
    }
    if (sourceType == "IMPORT") OutlinedTextField(
        value = sourceReference,
        onValueChange = {
            sourceReference = it
        },
        label = {
            Text(hiltechText("مرجع الاستيراد — لو IMPORT", "Import reference"))
        },
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = {
            Text(hiltechText("Project code يدوي — لو السياسة تسمح", "Project code (if policy permits)"))
        },
    )
    Text(hiltechText("مسؤول المشروع (اختياري)", "Project owner (optional)"), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("" to hiltechText("لاحقًا", "Later"), "EMPLOYEE" to hiltechText("موظف", "Employee"),
            "TEAM" to hiltechText("فريق", "Team")).forEach { (type, title) ->
            FilterChip(selected = principalType == type, onClick = { principalType = type; principalId = "" }, label = { Text(title) })
        }
    }
    if (principalType.isNotBlank()) HiltechRecordPicker(
        hiltechText("المسؤول", "Owner"), principalId,
        if (principalType == "EMPLOYEE") employeeChoices else teamChoices, { principalId = it },
    )
    Text(hiltechText("الجدول المخطط", "Planned schedule"), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = startDate,
        onValueChange = {
            startDate = it
        },
        label = {
            Text(hiltechText("بداية مخططة YYYY-MM-DD", "Planned start YYYY-MM-DD"))
        },
    )
    OutlinedTextField(
        value = endDate,
        onValueChange = {
            endDate = it
        },
        label = {
            Text(hiltechText("نهاية مخططة YYYY-MM-DD", "Planned end YYYY-MM-DD"))
        },
    )
    Button(
        enabled =
            name.isNotBlank() &&
                clientId.isNotBlank() &&
                (principalType.isBlank() || principalId.isNotBlank()),
        onClick = {
            onCreateProject(
                name,
                clientId,
                sourceType
                    .trim()
                    .uppercase(),
                sourceReference
                    .trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
                code.trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
                principalType
                    .trim()
                    .uppercase()
                    .takeIf {
                        it.isNotEmpty()
                    },
                principalId
                    .trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
                startDate
                    .trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
                endDate
                    .trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
            )
        },
    ) {
        Text(hiltechText("إنشاء المشروع", "Create project"))
    }
}

@Composable
private fun ProjectManagerChangeForm(
    project: ProjectSummaryDto,
    employeeChoices: List<HiltechRecordChoice>,
    teamChoices: List<HiltechRecordChoice>,
    onChangeManager:
        (
            String,
            Long,
            String,
            String,
            String?,
        ) -> Unit,
) {
    var type by remember(project.projectId) {
        mutableStateOf("EMPLOYEE")
    }
    var principalId by remember(project.projectId) {
        mutableStateOf("")
    }
    var reason by remember(project.projectId) {
        mutableStateOf("")
    }

    Text(
        "تغيير مسؤول المشروع",
        style =
            MaterialTheme.typography
                .titleSmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = type == "EMPLOYEE", onClick = { type = "EMPLOYEE"; principalId = "" },
            label = { Text(hiltechText("موظف", "Employee")) })
        FilterChip(selected = type == "TEAM", onClick = { type = "TEAM"; principalId = "" },
            label = { Text(hiltechText("فريق", "Team")) })
    }
    HiltechRecordPicker(hiltechText("المسؤول الجديد", "New owner"), principalId,
        if (type == "EMPLOYEE") employeeChoices else teamChoices, { principalId = it })
    OutlinedTextField(
        value = reason,
        onValueChange = { reason = it },
        label = {
            Text(hiltechText("سبب التغيير — اختياري", "Reason for change (optional)"))
        },
    )
    OutlinedButton(
        enabled =
            principalId.isNotBlank(),
        onClick = {
            onChangeManager(
                project.projectId,
                project.version,
                type.trim().uppercase(),
                principalId.trim(),
                reason.trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
            )
        },
    ) {
        Text(hiltechText("تغيير المسؤول", "Change owner"))
    }
}

@Composable
private fun SiteCreateForm(
    defaultClientOrganizationId: String,
    onCreateSite:
        (
            String,
            String,
            String,
            String?,
            String?,
        ) -> Unit,
) {
    var clientId by remember(
        defaultClientOrganizationId,
    ) {
        mutableStateOf(
            defaultClientOrganizationId,
        )
    }
    var siteCode by remember {
        mutableStateOf("")
    }
    var name by remember {
        mutableStateOf("")
    }
    var address by remember {
        mutableStateOf("")
    }
    var timezone by remember {
        mutableStateOf("Africa/Cairo")
    }

    Text(
        "إنشاء Site قابل لإعادة الاستخدام",
        style =
            MaterialTheme.typography
                .titleSmall,
    )
    OutlinedTextField(
        value = clientId,
        onValueChange = { clientId = it },
        label = {
            Text("Client Organization ID")
        },
    )
    OutlinedTextField(
        value = siteCode,
        onValueChange = { siteCode = it },
        label = { Text("Site code") },
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(hiltechText("اسم الموقع", "Site name")) },
    )
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = {
            Text("العنوان — Restricted")
        },
    )
    OutlinedTextField(
        value = timezone,
        onValueChange = { timezone = it },
        label = { Text("Timezone") },
    )
    OutlinedButton(
        enabled =
            clientId.isNotBlank() &&
                siteCode.isNotBlank() &&
                name.isNotBlank(),
        onClick = {
            onCreateSite(
                clientId.trim(),
                siteCode.trim(),
                name.trim(),
                address.trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
                timezone.trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
            )
        },
    ) {
        Text(hiltechText("إنشاء Site", "Create site"))
    }
}

@Composable
private fun ProjectSiteAttachForm(
    project: ProjectSummaryDto,
    suggestedSiteId: String?,
    onAttachSite:
        (
            String,
            Long,
            String,
            String?,
            String?,
            String?,
        ) -> Unit,
) {
    var siteId by remember(
        project.projectId,
        suggestedSiteId,
    ) {
        mutableStateOf(
            suggestedSiteId ?: "",
        )
    }
    var projectSiteCode by remember(
        project.projectId,
    ) {
        mutableStateOf("")
    }
    var access by remember(
        project.projectId,
    ) {
        mutableStateOf("")
    }
    var notes by remember(
        project.projectId,
    ) {
        mutableStateOf("")
    }

    Text(
        "ربط Site بالمشروع",
        style =
            MaterialTheme.typography
                .titleSmall,
    )
    OutlinedTextField(
        value = siteId,
        onValueChange = { siteId = it },
        label = { Text("Site ID") },
    )
    OutlinedTextField(
        value = projectSiteCode,
        onValueChange = {
            projectSiteCode = it
        },
        label = {
            Text("Project Site code — اختياري")
        },
    )
    OutlinedTextField(
        value = access,
        onValueChange = { access = it },
        label = {
            Text("تعليمات الدخول — Restricted")
        },
    )
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = {
            Text("ملاحظات المشروع بالموقع — Restricted")
        },
    )
    Button(
        enabled = siteId.isNotBlank(),
        onClick = {
            onAttachSite(
                project.projectId,
                project.version,
                siteId.trim(),
                projectSiteCode
                    .trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
                access.trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
                notes.trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
            )
        },
    ) {
        Text(hiltechText("ربط الموقع", "Attach site"))
    }
}
