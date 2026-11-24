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
import com.hiltech.shared.core.design.HiltechPanel
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
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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
                    HiltechSectionHeading("PROJECTS / DELIVERY", "المشاريع والمواقع",
                        "تابع المشاريع، فرق التنفيذ، والتقدم الفعلي من مكان واحد.")
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("تحديث") }
            }
            if (state.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("جارٍ تحميل بيانات المشاريع…")
                }
            }
            state.errorMessage?.let { error ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    HiltechStateTag("تعذر تحديث المشروع", HiltechStateTone.ATTENTION)
                    Text(error)
                    state.errorCode?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.secondaryText) }
                }
            }
            val directory: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("دليل المشاريع", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { creating = !creating }) { Text(if (creating) "إغلاق" else "+ مشروع") }
                    }
                    if (creating) {
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text("إنشاء مشروع", style = MaterialTheme.typography.titleLarge)
                            ProjectCreateForm(onCreateProject = onCreateProject)
                        }
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("ابحث بالاسم أو الكود أو العميل") })
                    val projects = state.list?.items.orEmpty()
                    val matching = projects.filter {
                        query.isBlank() || listOf(it.name, it.projectCode, it.clientDisplayName).any { value ->
                            value.contains(query.trim(), ignoreCase = true)
                        }
                    }
                    Text("المعروض: " + matching.size, color = colors.secondaryText,
                        style = MaterialTheme.typography.labelMedium)
                    if (!state.loading && matching.isEmpty()) {
                        Text(if (query.isBlank()) "لا توجد مشاريع متاحة الآن." else "لا توجد نتائج مطابقة.")
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
                                Text(project.currentResponsibility.principalLabel ?: "لم يُعيّن مسؤول",
                                    style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                            }
                        }
                    }
                }
            }

            val detail: @Composable () -> Unit = {
                if (selected == null) {
                    HiltechPanel(Modifier.fillMaxWidth()) {
                        Text("اختر مشروعًا", style = MaterialTheme.typography.titleLarge)
                        Text("ستجد تفاصيل التنفيذ والمواقع والتخطيط والقرارات في مساحة المشروع.")
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(selected.projectCode, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                            Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                            Text(selected.clientDisplayName, style = MaterialTheme.typography.bodyLarge, color = colors.secondaryText)
                            HiltechStateTag(projectStateLabel(selected.lifecycleState), projectStateTone(selected.lifecycleState))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HiltechKeyValue("المسؤول", selected.currentResponsibility.principalLabel ?: "غير معيّن", Modifier.weight(1f))
                                HiltechKeyValue("المواقع المرتبطة", selected.siteCount.toString(), Modifier.weight(1f))
                            }
                            HiltechKeyValue("الفترة المخططة", (selected.startDatePlanned ?: "غير محددة") + " — " + (selected.endDatePlanned ?: "غير محددة"))
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProjectWorkspaceTab.entries.forEach { item ->
                                FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item.title) })
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
                                        Text("صحة المشروع والتقدم", style = MaterialTheme.typography.titleMedium)
                                        Text("لا توجد قراءة حديثة لمركز قيادة هذا المشروع. حدّث البيانات لإعادة المحاولة.")
                                    }
                                }
                                HiltechPanel(Modifier.fillMaxWidth()) {
                                    Text("إدارة المشروع", style = MaterialTheme.typography.titleMedium)
                                    if (selected.lifecycleState == "DRAFT") {
                                        Button(onClick = { onStartKickoff(selected.projectId, selected.version) }) { Text("بدء التجهيز") }
                                    }
                                    if (selected.lifecycleState == "KICKOFF") {
                                        Button(onClick = { onCompleteKickoff(selected.projectId, selected.version) }) { Text("إكمال التجهيز") }
                                    }
                                    TextButton(onClick = { showManager = !showManager }) {
                                        Text(if (showManager) "إغلاق تغيير المسؤول" else "تغيير مسؤول المشروع")
                                    }
                                    if (showManager) ProjectManagerChangeForm(project = selected, onChangeManager = onChangeManager)
                                    Text("المصدر: " + selected.sourceType + (selected.sourceExternalReference?.let { " · " + it } ?: ""),
                                        style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                                }
                            }
                            ProjectWorkspaceTab.Sites -> {
                                HiltechPanel(Modifier.fillMaxWidth()) {
                                    Text("مواقع المشروع", style = MaterialTheme.typography.titleMedium)
                                    if (state.selectedSites?.items.orEmpty().isEmpty()) Text("لا توجد مواقع معروضة لهذا المشروع.")
                                    state.selectedSites?.items.orEmpty().forEach { relation ->
                                        HorizontalDivider(color = colors.divider)
                                        Text(relation.site.name, style = MaterialTheme.typography.titleSmall)
                                        Text(relation.site.siteCode + " · " + relation.lifecycleState, color = colors.secondaryText)
                                        relation.site.addressText?.let { Text(it) }
                                        relation.accessInstructions?.let { Text("تعليمات الدخول: " + it) }
                                    }
                                    TextButton(onClick = { showSiteCreate = !showSiteCreate }) { Text(if (showSiteCreate) "إغلاق إنشاء الموقع" else "+ موقع جديد") }
                                    if (showSiteCreate) SiteCreateForm(defaultClientOrganizationId = selected.clientOrganizationId, onCreateSite = onCreateSite)
                                    TextButton(onClick = { showSiteAttach = !showSiteAttach }) { Text(if (showSiteAttach) "إغلاق الربط" else "ربط موقع موجود") }
                                    if (showSiteAttach) ProjectSiteAttachForm(project = selected,
                                        suggestedSiteId = state.lastCreatedSite?.takeIf { it.clientOrganizationId == selected.clientOrganizationId }?.siteId,
                                        onAttachSite = onAttachSite)
                                }
                            }
                            ProjectWorkspaceTab.Plan -> {
                                state.plan?.let { ProjectPlanningSection(plan = it, onAction = onPlanningAction) }
                                    ?: Text("بيانات التخطيط غير متاحة حاليًا.")
                            }
                            ProjectWorkspaceTab.Work -> {
                                state.plan?.let { WorkPlanningSection(plan = it, workTypes = state.workTypes,
                                    orders = state.workOrders, onAction = onWorkPlanningAction) }
                                    ?: Text("بيانات الأعمال غير متاحة حاليًا.")
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
                            TextButton(onClick = { showDetail = false }) { Text("العودة إلى المشاريع") }
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

private enum class ProjectWorkspaceTab(val title: String) {
    Overview("نظرة عامة"), Sites("المواقع"), Plan("التخطيط"), Work("الأعمال"), Readiness("الجاهزية"),
}

private fun projectStateLabel(state: String): String = when (state) {
    "DRAFT" -> "مسودة"
    "KICKOFF" -> "التجهيز"
    "ACTIVE" -> "قيد التنفيذ"
    "ON_HOLD" -> "متوقف"
    "COMPLETED" -> "مكتمل"
    "CANCELLED" -> "ملغى"
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
        label = { Text("اسم المشروع") },
    )
    OutlinedTextField(
        value = clientId,
        onValueChange = { clientId = it },
        label = {
            Text(
                "Client Organization ID",
            )
        },
    )
    OutlinedTextField(
        value = sourceType,
        onValueChange = {
            sourceType = it
        },
        label = {
            Text("Source: INTERNAL / IMPORT")
        },
    )
    OutlinedTextField(
        value = sourceReference,
        onValueChange = {
            sourceReference = it
        },
        label = {
            Text("مرجع الاستيراد — لو IMPORT")
        },
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = {
            Text("Project code يدوي — لو السياسة تسمح")
        },
    )
    OutlinedTextField(
        value = principalType,
        onValueChange = {
            principalType = it
        },
        label = {
            Text("المسؤول: EMPLOYEE / TEAM — اختياري")
        },
    )
    OutlinedTextField(
        value = principalId,
        onValueChange = {
            principalId = it
        },
        label = {
            Text("Responsibility principal ID")
        },
    )
    OutlinedTextField(
        value = startDate,
        onValueChange = {
            startDate = it
        },
        label = {
            Text("بداية مخططة YYYY-MM-DD")
        },
    )
    OutlinedTextField(
        value = endDate,
        onValueChange = {
            endDate = it
        },
        label = {
            Text("نهاية مخططة YYYY-MM-DD")
        },
    )
    Button(
        enabled =
            name.isNotBlank() &&
                clientId.isNotBlank(),
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
        Text("إنشاء المشروع")
    }
}

@Composable
private fun ProjectManagerChangeForm(
    project: ProjectSummaryDto,
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
    OutlinedTextField(
        value = type,
        onValueChange = { type = it },
        label = {
            Text("EMPLOYEE / TEAM")
        },
    )
    OutlinedTextField(
        value = principalId,
        onValueChange = {
            principalId = it
        },
        label = {
            Text("Principal ID")
        },
    )
    OutlinedTextField(
        value = reason,
        onValueChange = { reason = it },
        label = {
            Text("سبب التغيير — اختياري")
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
        Text("تغيير المسؤول")
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
        label = { Text("اسم الموقع") },
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
        Text("إنشاء Site")
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
        Text("ربط الموقع")
    }
}
