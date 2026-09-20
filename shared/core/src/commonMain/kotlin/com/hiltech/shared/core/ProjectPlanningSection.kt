package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.projects.AreaDto
import com.hiltech.shared.core.projects.MilestoneDto
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.projects.WorkPackageDto

sealed interface PlanningUiAction {
    data class CreateArea(
        val projectId: String,
        val projectSiteId: String,
        val parentAreaId: String?,
        val typeCode: String,
        val code: String,
        val name: String,
        val sequence: Int?,
        val restrictedAccess: Boolean,
        val baseProjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class UpdateArea(
        val projectId: String,
        val areaId: String,
        val parentAreaId: String?,
        val typeCode: String,
        val code: String,
        val name: String,
        val sequence: Int?,
        val restrictedAccess: Boolean,
        val baseProjectVersion: Long,
        val baseObjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class CreateMilestone(
        val projectId: String,
        val code: String,
        val name: String,
        val plannedDate: String?,
        val sequence: Int?,
        val clientVisible: Boolean,
        val acceptanceRequirement: String?,
        val baseProjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class UpdateMilestone(
        val projectId: String,
        val milestoneId: String,
        val code: String,
        val name: String,
        val plannedDate: String?,
        val sequence: Int?,
        val clientVisible: Boolean,
        val acceptanceRequirement: String?,
        val baseProjectVersion: Long,
        val baseObjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class CreateWorkPackage(
        val projectId: String,
        val projectSiteId: String?,
        val siteId: String?,
        val milestoneId: String?,
        val code: String,
        val name: String,
        val description: String?,
        val ownerType: String?,
        val ownerId: String?,
        val plannedStart: String?,
        val plannedEnd: String?,
        val sequence: Int?,
        val baseProjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class UpdateWorkPackage(
        val projectId: String,
        val workPackageId: String,
        val projectSiteId: String?,
        val siteId: String?,
        val milestoneId: String?,
        val code: String,
        val name: String,
        val description: String?,
        val ownerType: String?,
        val ownerId: String?,
        val plannedStart: String?,
        val plannedEnd: String?,
        val sequence: Int?,
        val baseProjectVersion: Long,
        val baseObjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class AddDependency(
        val projectId: String,
        val predecessorType: String,
        val predecessorId: String,
        val successorType: String,
        val successorId: String,
        val dependencyType: String,
        val lagMinutes: Long,
        val baseProjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class RemoveDependency(
        val projectId: String,
        val dependencyId: String,
        val baseProjectVersion: Long,
        val baseObjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction

    data class MarkReady(
        val projectId: String,
        val baseProjectVersion: Long,
        val expectedBaselineVersion: Int,
    ) : PlanningUiAction
}

@Composable
fun ProjectPlanningSection(
    plan: ProjectPlanDto,
    onAction: (PlanningUiAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("هيكل تخطيط المشروع", style = MaterialTheme.typography.titleMedium)
        Text(
            "Baseline ${plan.project.baselineVersion} · v${plan.project.version} · " +
                if (plan.canEditPlan) "قابل للتعديل" else "مجمّد للقراءة",
        )
        if (!plan.validationValid) {
            Text("مخالفات الخطة: ${plan.validationReasonCodes.joinToString()}")
        }

        Text("بوابة READY", style = MaterialTheme.typography.titleSmall)
        Text(
            if (plan.readyGateReady) {
                "كل متطلبات التجميد متحققة."
            } else {
                "غير جاهز: ${plan.readyGateReasonCodes.joinToString()}"
            },
        )
        if (plan.canEditPlan) {
            Button(
                enabled = plan.readyGateReady,
                onClick = {
                    onAction(
                        PlanningUiAction.MarkReady(
                            plan.project.projectId,
                            plan.project.version,
                            plan.project.baselineVersion,
                        ),
                    )
                },
            ) { Text("تجميد الخطة والانتقال إلى READY") }
        }

        Text("المواقع والمناطق", style = MaterialTheme.typography.titleSmall)
        plan.projectSites.forEach { site ->
            Text("${site.siteCode} · ${site.name} · ${site.lifecycleState}")
            plan.areas.filter { it.siteId == site.siteId }.forEach { area ->
                AreaRow(plan, area, onAction)
            }
        }
        if (plan.canEditPlan && plan.projectSites.isNotEmpty()) {
            CreateAreaForm(plan, onAction)
        }

        Text("المعالم", style = MaterialTheme.typography.titleSmall)
        if (plan.milestones.isEmpty()) Text("لا توجد معالم مخططة بعد.")
        plan.milestones.forEach { milestone -> MilestoneRow(plan, milestone, onAction) }
        if (plan.canEditPlan) CreateMilestoneForm(plan, onAction)

        Text("حزم العمل", style = MaterialTheme.typography.titleSmall)
        if (plan.workPackages.isEmpty()) Text("لا توجد حزم عمل مخططة بعد.")
        plan.workPackages.forEach { workPackage -> WorkPackageRow(plan, workPackage, onAction) }
        if (plan.canEditPlan) CreateWorkPackageForm(plan, onAction)

        Text("اعتماديات الخطة", style = MaterialTheme.typography.titleSmall)
        if (plan.dependencies.isEmpty()) Text("لا توجد اعتماديات.")
        plan.dependencies.forEach { dependency ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("من ${dependency.predecessor.type} · ${dependency.predecessor.id.take(8)}")
                Text(
                    "إلى ${dependency.successor.type} · ${dependency.successor.id.take(8)} · " +
                        "${dependency.dependencyType} · ${dependency.lagMinutes} دقيقة",
                )
            }
            if (plan.canEditPlan) {
                OutlinedButton(onClick = {
                    onAction(
                        PlanningUiAction.RemoveDependency(
                            projectId = plan.project.projectId,
                            dependencyId = dependency.dependencyId,
                            baseProjectVersion = plan.project.version,
                            baseObjectVersion = dependency.version,
                            expectedBaselineVersion = plan.project.baselineVersion,
                        ),
                    )
                }) { Text("إزالة") }
            }
        }
        if (plan.canEditPlan) AddDependencyForm(plan, onAction)

    }
}

@Composable
private fun AreaRow(plan: ProjectPlanDto, area: AreaDto, onAction: (PlanningUiAction) -> Unit) {
    var editing by remember(area.areaId) { mutableStateOf(false) }
    Text("  • ${area.code} · ${area.name} · ${area.typeCode} · v${area.version}")
    if (plan.canEditPlan) {
        OutlinedButton(onClick = { editing = !editing }) { Text(if (editing) "إلغاء" else "تعديل المنطقة") }
    }
    if (editing) {
        AreaFields(area.code, area.name, area.typeCode, area.parentAreaId.orEmpty(), area.sequence?.toString().orEmpty()) {
                code, name, type, parent, sequence ->
            onAction(
                PlanningUiAction.UpdateArea(
                    plan.project.projectId, area.areaId, parent.blankToNull(), type.uppercase(), code, name,
                    sequence.toIntOrNull(), area.restrictedAccess, plan.project.version, area.version,
                    plan.project.baselineVersion,
                ),
            )
            editing = false
        }
    }
}

@Composable
private fun CreateAreaForm(plan: ProjectPlanDto, onAction: (PlanningUiAction) -> Unit) {
    var projectSiteId by remember(plan.project.projectId) { mutableStateOf(plan.projectSites.first().projectSiteId) }
    AreaFields("", "", "ZONE", "", "") { code, name, type, parent, sequence ->
        onAction(
            PlanningUiAction.CreateArea(
                plan.project.projectId, projectSiteId, parent.blankToNull(), type.uppercase(), code, name,
                sequence.toIntOrNull(), false, plan.project.version, plan.project.baselineVersion,
            ),
        )
    }
    OutlinedTextField(projectSiteId, { projectSiteId = it }, label = { Text("Project Site ID للمنطقة الجديدة") })
}

@Composable
private fun AreaFields(
    initialCode: String,
    initialName: String,
    initialType: String,
    initialParent: String,
    initialSequence: String,
    submit: (String, String, String, String, String) -> Unit,
) {
    var code by remember(initialCode) { mutableStateOf(initialCode) }
    var name by remember(initialName) { mutableStateOf(initialName) }
    var type by remember(initialType) { mutableStateOf(initialType) }
    var parent by remember(initialParent) { mutableStateOf(initialParent) }
    var sequence by remember(initialSequence) { mutableStateOf(initialSequence) }
    OutlinedTextField(code, { code = it }, label = { Text("كود المنطقة") })
    OutlinedTextField(name, { name = it }, label = { Text("اسم المنطقة") })
    OutlinedTextField(type, { type = it }, label = { Text("نوع المنطقة") })
    OutlinedTextField(parent, { parent = it }, label = { Text("Parent Area ID — اختياري") })
    OutlinedTextField(sequence, { sequence = it }, label = { Text("الترتيب — اختياري") })
    Button(enabled = code.isNotBlank() && name.isNotBlank() && type.isNotBlank(), onClick = {
        submit(code.trim(), name.trim(), type.trim(), parent.trim(), sequence.trim())
    }) { Text(if (initialCode.isBlank()) "إضافة منطقة" else "حفظ المنطقة") }
}

@Composable
private fun MilestoneRow(plan: ProjectPlanDto, item: MilestoneDto, onAction: (PlanningUiAction) -> Unit) {
    var editing by remember(item.milestoneId) { mutableStateOf(false) }
    Text("• ${item.code} · ${item.name} · ${item.state} · ${item.plannedDate ?: "دون تاريخ"} · v${item.version}")
    if (plan.canEditPlan) OutlinedButton(onClick = { editing = !editing }) { Text("تعديل المعلم") }
    if (editing) {
        MilestoneFields(item.code, item.name, item.plannedDate.orEmpty(), item.acceptanceRequirement.orEmpty()) {
                code, name, date, acceptance ->
            onAction(
                PlanningUiAction.UpdateMilestone(
                    plan.project.projectId, item.milestoneId, code, name, date.blankToNull(), item.sequence,
                    item.clientVisible, acceptance.blankToNull(), plan.project.version, item.version,
                    plan.project.baselineVersion,
                ),
            )
            editing = false
        }
    }
}

@Composable
private fun CreateMilestoneForm(plan: ProjectPlanDto, onAction: (PlanningUiAction) -> Unit) {
    MilestoneFields("", "", "", "") { code, name, date, acceptance ->
        onAction(
            PlanningUiAction.CreateMilestone(
                plan.project.projectId, code, name, date.blankToNull(), null, false, acceptance.blankToNull(),
                plan.project.version, plan.project.baselineVersion,
            ),
        )
    }
}

@Composable
private fun MilestoneFields(
    initialCode: String,
    initialName: String,
    initialDate: String,
    initialAcceptance: String,
    submit: (String, String, String, String) -> Unit,
) {
    var code by remember(initialCode) { mutableStateOf(initialCode) }
    var name by remember(initialName) { mutableStateOf(initialName) }
    var date by remember(initialDate) { mutableStateOf(initialDate) }
    var acceptance by remember(initialAcceptance) { mutableStateOf(initialAcceptance) }
    OutlinedTextField(code, { code = it }, label = { Text("كود المعلم") })
    OutlinedTextField(name, { name = it }, label = { Text("اسم المعلم") })
    OutlinedTextField(date, { date = it }, label = { Text("تاريخ YYYY-MM-DD — اختياري") })
    OutlinedTextField(acceptance, { acceptance = it }, label = { Text("شرط قبول — اختياري") })
    Button(enabled = code.isNotBlank() && name.isNotBlank(), onClick = {
        submit(code.trim(), name.trim(), date.trim(), acceptance.trim())
    }) { Text(if (initialCode.isBlank()) "إضافة معلم" else "حفظ المعلم") }
}

@Composable
private fun WorkPackageRow(plan: ProjectPlanDto, item: WorkPackageDto, onAction: (PlanningUiAction) -> Unit) {
    var editing by remember(item.workPackageId) { mutableStateOf(false) }
    Text(
        "• ${item.code} · ${item.name} · ${item.state} · " +
            (item.owner?.principalLabel ?: "دون مسؤول") + " · v${item.version}",
    )
    if (plan.canEditPlan) OutlinedButton(onClick = { editing = !editing }) { Text("تعديل حزمة العمل") }
    if (editing) {
        WorkPackageFields(item) { values ->
            onAction(
                PlanningUiAction.UpdateWorkPackage(
                    plan.project.projectId, item.workPackageId, values.projectSiteId, values.siteId,
                    values.milestoneId, values.code, values.name, values.description, values.ownerType,
                    values.ownerId, values.start, values.end, values.sequence, plan.project.version,
                    item.version, plan.project.baselineVersion,
                ),
            )
            editing = false
        }
    }
}

@Composable
private fun CreateWorkPackageForm(plan: ProjectPlanDto, onAction: (PlanningUiAction) -> Unit) {
    WorkPackageFields(null, plan.projectSites.firstOrNull()?.projectSiteId.orEmpty()) { values ->
        onAction(
            PlanningUiAction.CreateWorkPackage(
                plan.project.projectId, values.projectSiteId, values.siteId, values.milestoneId,
                values.code, values.name, values.description, values.ownerType, values.ownerId,
                values.start, values.end, values.sequence, plan.project.version, plan.project.baselineVersion,
            ),
        )
    }
}

private data class WorkPackageValues(
    val projectSiteId: String?, val siteId: String?, val milestoneId: String?, val code: String,
    val name: String, val description: String?, val ownerType: String?, val ownerId: String?,
    val start: String?, val end: String?, val sequence: Int?,
)

@Composable
private fun WorkPackageFields(
    item: WorkPackageDto?,
    suggestedProjectSiteId: String = item?.projectSiteId.orEmpty(),
    submit: (WorkPackageValues) -> Unit,
) {
    var projectSiteId by remember(item?.workPackageId) { mutableStateOf(suggestedProjectSiteId) }
    var siteId by remember(item?.workPackageId) { mutableStateOf(item?.siteId.orEmpty()) }
    var milestoneId by remember(item?.workPackageId) { mutableStateOf(item?.milestoneId.orEmpty()) }
    var code by remember(item?.workPackageId) { mutableStateOf(item?.code.orEmpty()) }
    var name by remember(item?.workPackageId) { mutableStateOf(item?.name.orEmpty()) }
    var description by remember(item?.workPackageId) { mutableStateOf(item?.description.orEmpty()) }
    var ownerType by remember(item?.workPackageId) { mutableStateOf(item?.owner?.principalType.orEmpty()) }
    var ownerId by remember(item?.workPackageId) { mutableStateOf(item?.owner?.principalId.orEmpty()) }
    var start by remember(item?.workPackageId) { mutableStateOf(item?.plannedStart.orEmpty()) }
    var end by remember(item?.workPackageId) { mutableStateOf(item?.plannedEnd.orEmpty()) }
    var sequence by remember(item?.workPackageId) { mutableStateOf(item?.sequence?.toString().orEmpty()) }
    OutlinedTextField(code, { code = it }, label = { Text("كود حزمة العمل") })
    OutlinedTextField(name, { name = it }, label = { Text("اسم حزمة العمل") })
    OutlinedTextField(description, { description = it }, label = { Text("الوصف — اختياري") })
    OutlinedTextField(projectSiteId, { projectSiteId = it }, label = { Text("Project Site ID — اختياري") })
    OutlinedTextField(siteId, { siteId = it }, label = { Text("Site ID — اختياري") })
    OutlinedTextField(milestoneId, { milestoneId = it }, label = { Text("Milestone ID — اختياري") })
    OutlinedTextField(ownerType, { ownerType = it }, label = { Text("المسؤول EMPLOYEE / TEAM — اختياري") })
    OutlinedTextField(ownerId, { ownerId = it }, label = { Text("Owner ID — اختياري") })
    OutlinedTextField(start, { start = it }, label = { Text("بداية مخططة — اختياري") })
    OutlinedTextField(end, { end = it }, label = { Text("نهاية مخططة — اختياري") })
    OutlinedTextField(sequence, { sequence = it }, label = { Text("الترتيب — اختياري") })
    Button(enabled = code.isNotBlank() && name.isNotBlank(), onClick = {
        submit(
            WorkPackageValues(
                projectSiteId.blankToNull(), siteId.blankToNull(), milestoneId.blankToNull(), code.trim(),
                name.trim(), description.blankToNull(), ownerType.uppercase().blankToNull(), ownerId.blankToNull(),
                start.blankToNull(), end.blankToNull(), sequence.toIntOrNull(),
            ),
        )
    }) { Text(if (item == null) "إضافة حزمة عمل" else "حفظ حزمة العمل") }
}

@Composable
private fun AddDependencyForm(plan: ProjectPlanDto, onAction: (PlanningUiAction) -> Unit) {
    var predecessorType by remember(plan.project.projectId) { mutableStateOf("WORK_PACKAGE") }
    var predecessorId by remember(plan.project.projectId) { mutableStateOf("") }
    var successorType by remember(plan.project.projectId) { mutableStateOf("WORK_PACKAGE") }
    var successorId by remember(plan.project.projectId) { mutableStateOf("") }
    var dependencyType by remember(plan.project.projectId) { mutableStateOf("FINISH_TO_START") }
    var lag by remember(plan.project.projectId) { mutableStateOf("0") }
    OutlinedTextField(predecessorType, { predecessorType = it }, label = { Text("نوع السابق MILESTONE / WORK_PACKAGE") })
    OutlinedTextField(predecessorId, { predecessorId = it }, label = { Text("ID السابق") })
    OutlinedTextField(successorType, { successorType = it }, label = { Text("نوع اللاحق MILESTONE / WORK_PACKAGE") })
    OutlinedTextField(successorId, { successorId = it }, label = { Text("ID اللاحق") })
    OutlinedTextField(dependencyType, { dependencyType = it }, label = { Text("نوع الاعتمادية") })
    OutlinedTextField(lag, { lag = it }, label = { Text("Lag بالدقائق") })
    Button(enabled = predecessorId.isNotBlank() && successorId.isNotBlank(), onClick = {
        onAction(
            PlanningUiAction.AddDependency(
                plan.project.projectId, predecessorType.uppercase(), predecessorId.trim(),
                successorType.uppercase(), successorId.trim(), dependencyType.uppercase(), lag.toLongOrNull() ?: 0,
                plan.project.version, plan.project.baselineVersion,
            ),
        )
    }) { Text("إضافة اعتمادية") }
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }
