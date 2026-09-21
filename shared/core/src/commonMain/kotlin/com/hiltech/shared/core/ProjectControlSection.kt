package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
    onProjectCommandCenterAction: (ProjectCommandCenterUiAction) -> Unit = {},
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides
            LayoutDirection.Rtl,
    ) {
        Column(
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "المشروعات والمواقع",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            Text(
                "حقيقة التنفيذ الحالية فقط — بدون نسب تقدم أو صحة مشروع مصطنعة.",
            )

            if (state.loading) {
                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("جارٍ تحميل واقع المشروعات…")
                }
            }

            state.errorMessage?.let {
                Text(
                    buildString {
                        state.errorCode?.let { code ->
                            append(code)
                            append(" · ")
                        }
                        append(it)
                    },
                )
            }

            state.list?.let { list ->
                Text(
                    "مشروعاتي: " +
                        list.items.size,
                    style =
                        MaterialTheme.typography
                            .titleSmall,
                )
                if (list.items.isEmpty()) {
                    Text(
                        "لا توجد مشروعات متاحة لهذه الهوية حاليًا.",
                    )
                }
                list.items.forEach { project ->
                    OutlinedButton(
                        onClick = {
                            onSelectProject(
                                project.projectId,
                            )
                        },
                    ) {
                        Text(
                            buildString {
                                append(project.projectCode)
                                append(" · ")
                                append(project.name)
                                append(" · ")
                                append(project.clientDisplayName)
                                append(" · ")
                                append(project.lifecycleState)
                                append(" · ")
                                append(
                                    project
                                        .currentResponsibility
                                        .principalLabel
                                        ?: "بدون مسؤول",
                                )
                                if (
                                    project.startDatePlanned !=
                                    null ||
                                    project.endDatePlanned !=
                                    null
                                ) {
                                    append(" · ")
                                    append(
                                        project.startDatePlanned
                                            ?: "؟",
                                    )
                                    append(" → ")
                                    append(
                                        project.endDatePlanned
                                            ?: "؟",
                                    )
                                }
                            },
                        )
                    }
                }
            }

            ProjectCreateForm(
                onCreateProject =
                    onCreateProject,
            )

            state.selectedProject?.let { project ->
                Text(
                    "المشروع المفتوح",
                    style =
                        MaterialTheme.typography
                            .titleSmall,
                )
                Text(
                    project.projectCode +
                        " · " +
                        project.name,
                )
                Text(
                    "العميل: " +
                        project.clientDisplayName,
                )
                Text(
                    "الحالة: " +
                        project.lifecycleState +
                        " · v" +
                        project.version,
                )
                Text(
                    "المصدر: " +
                        project.sourceType +
                        (
                            project.sourceExternalReference
                                ?.let {
                                    " · " + it
                                }
                                ?: ""
                        ),
                )
                Text(
                    "المسؤول: " +
                        (
                            project
                                .currentResponsibility
                                .principalLabel
                                ?: "غير معيّن"
                        ) +
                        " · " +
                        project
                            .currentResponsibility
                            .resolutionState,
                )
                Text(
                    "الفترة المخططة: " +
                        (
                            project.startDatePlanned
                                ?: "غير محددة"
                        ) +
                        " → " +
                        (
                            project.endDatePlanned
                                ?: "غير محددة"
                        ),
                )
                Text(
                    "المواقع المرتبطة: " +
                        project.siteCount,
                )

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    if (
                        project.lifecycleState ==
                        "DRAFT"
                    ) {
                        Button(
                            onClick = {
                                onStartKickoff(
                                    project.projectId,
                                    project.version,
                                )
                            },
                        ) {
                            Text("بدء Kickoff")
                        }
                    }
                    if (
                        project.lifecycleState ==
                        "KICKOFF"
                    ) {
                        Button(
                            onClick = {
                                onCompleteKickoff(
                                    project.projectId,
                                    project.version,
                                )
                            },
                        ) {
                            Text("إكمال Kickoff")
                        }
                    }
                }

                ProjectManagerChangeForm(
                    project = project,
                    onChangeManager =
                        onChangeManager,
                )

                state.selectedSites?.let {
                    sites ->
                    Text(
                        "مواقع المشروع",
                        style =
                            MaterialTheme.typography
                                .titleSmall,
                    )
                    if (sites.items.isEmpty()) {
                        Text(
                            "لا توجد مواقع مرتبطة بعد.",
                        )
                    }
                    sites.items.forEach {
                        relation ->
                        Text(
                            "• " +
                                relation.site.siteCode +
                                " · " +
                                relation.site.name +
                                " · " +
                                relation.lifecycleState,
                        )
                        relation.site
                            .addressText
                            ?.let {
                                Text(
                                    "  العنوان: " +
                                        it,
                                )
                            }
                        relation
                            .accessInstructions
                            ?.let {
                                Text(
                                    "  تعليمات الدخول: " +
                                        it,
                                )
                            }
                    }
                }

                SiteCreateForm(
                    defaultClientOrganizationId =
                        project
                            .clientOrganizationId,
                    onCreateSite =
                        onCreateSite,
                )

                ProjectSiteAttachForm(
                    project = project,
                    suggestedSiteId =
                        state.lastCreatedSite
                            ?.takeIf {
                                it.clientOrganizationId ==
                                    project.clientOrganizationId
                            }
                            ?.siteId,
                    onAttachSite =
                        onAttachSite,
                )

                state.plan?.let { plan ->
                    ProjectPlanningSection(
                        plan = plan,
                        onAction = onPlanningAction,
                    )
                    WorkPlanningSection(
                        plan = plan,
                        workTypes = state.workTypes,
                        orders = state.workOrders,
                        onAction = onWorkPlanningAction,
                    )
                    WorkReadinessSection(
                        orders = state.workOrders,
                        readinessByWorkOrder =
                            state.workReadinessByOrder,
                        workQueueContext =
                            state.workQueueContext,
                        onAction =
                            onWorkReadinessAction,
                    )
                    state.commandCenter?.let {
                        ProjectCommandCenterSection(
                            project = project,
                            commandCenter = it,
                            onAction =
                                onProjectCommandCenterAction,
                        )
                    }
                }
            }

            state.lastCreatedSite?.let {
                Text(
                    "آخر موقع تم إنشاؤه: " +
                        it.siteCode +
                        " · " +
                        it.name +
                        " · " +
                        it.siteId,
                )
            }

            OutlinedButton(
                onClick = onRefresh,
            ) {
                Text("تحديث المشروعات")
            }
        }
    }
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
