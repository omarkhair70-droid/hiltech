package com.hiltech.shared.core

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import com.hiltech.shared.core.design.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
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

@Suppress("UNUSED_PARAMETER")
@Composable
fun ProjectPlanningSection(
    plan: ProjectPlanDto,
    onAction: (PlanningUiAction) -> Unit,
    formHost: PlanningFormHost? = null,
    employeeChoices: List<HiltechRecordChoice> = emptyList(),
    teamChoices: List<HiltechRecordChoice> = emptyList(),
) {
    val scopeKey = formHost?.scopeKey ?: "planning-unavailable"
    val forms = remember(scopeKey, plan.project.projectId) { PlanningFormRegistry(scopeKey) }
    DisposableEffect(forms) { onDispose { forms.invalidate() } }
    var tab by remember(scopeKey, plan.project.projectId) { mutableStateOf(PlanningArea.Sites) }
    val canStart = plan.canEditPlan && formHost != null && !forms.hasUnresolvedRequest
    val createKey = "create:" + tab.name
    val ready = forms.current("ready")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HiltechSectionHeading("PROJECT / PLAN", hiltechText("هيكل تخطيط المشروع", "Project plan"),
            hiltechText("المواقع والمعالم وحزم العمل والاعتماديات.", "Sites, milestones, work packages and dependencies."))
        Text(hiltechText("خط الأساس", "Baseline") + " ${plan.project.baselineVersion} · v${plan.project.version} · " +
            if (plan.canEditPlan && formHost != null) hiltechText("قابل للتعديل", "Editable") else hiltechText("للقراءة", "Read only"))
        if (plan.canEditPlan && formHost == null) {
            Text(hiltechText("الحفظ غير متاح من هذه المساحة حاليًا. يمكنك مراجعة الخطة.",
                "Saving is unavailable in this workspace. You can review the plan."))
        }
        forms.detachedSessions(plan).forEach { session ->
            key(session) {
                PlanningFormFrame(session, forms, formHost, false, plan.formBaseline(), emptyMap()) {
                    Text(hiltechText("السجل لم يعد ظاهرًا في الخطة الحالية. هذا لا يؤكد نتيجة الطلب؛ احتُفظ بالمسودة هنا.",
                        "The record is no longer visible in the current plan. This does not confirm the command result; the draft is retained here."))
                    if (session.value("code").isNotBlank()) Text(session.value("code"))
                    if (session.value("name").isNotBlank()) Text(session.value("name"))
                }
            }
        }
        if (!plan.validationValid) Text(hiltechText("ملاحظات الخطة: ", "Plan issues: ") + plan.validationReasonCodes.joinToString())
        Text(hiltechText("جاهزية الخطة", "Plan readiness"), style = MaterialTheme.typography.titleSmall)
        Text(if (plan.readyGateReady) hiltechText("كل متطلبات اعتماد الخطة متحققة.", "The plan is ready to be finalized.")
            else hiltechText("غير جاهز: ", "Not ready: ") + plan.readyGateReasonCodes.joinToString())
        if (plan.canEditPlan && formHost != null) {
            Button(enabled = canStart && plan.readyGateReady && ready == null, onClick = {
                forms.open("ready", plan.formBaseline(), emptyMap())
            }) { Text(hiltechText("تجميد الخطة", "Finalize plan")) }
        }
        if (ready != null) {
            PlanningFormFrame(ready, forms, formHost, plan.canEditPlan, plan.formBaseline(), emptyMap()) { enabled ->
                Text(hiltechText(
                    "سيُجمَّد آخر هيكل تخطيط محفوظ وينتقل المشروع إلى جاهز. أي تعديلات لم تحفظها في النماذج لن تدخل ضمن الخطة المجمدة. احفظ ما تريد إدراجه، ثم راجع المعالم وحزم العمل والاعتماديات قبل التأكيد.",
                    "This freezes the latest saved planning structure and moves the project to Ready. Unsaved edits in the forms are not included. Save any changes you want included, then review milestones, work packages and dependencies before confirming."))
                Button(enabled = enabled && plan.readyGateReady && !ready.requiresSourceReview, onClick = {
                    formHost?.let { forms.submit(ready, PlanningUiAction.MarkReady(
                        plan.project.projectId, ready.baseline.projectVersion, ready.baseline.baselineVersion), it) }
                }) { Text(hiltechText("تأكيد تجميد الخطة", "Confirm finalization")) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlanningArea.entries.forEach { area ->
                FilterChip(selected = tab == area, enabled = !forms.hasUnresolvedRequest,
                    onClick = { tab = area }, label = { Text(hiltechText(area.ar, area.en)) })
            }
        }
        if (canStart) {
            TextButton(enabled = forms.current(createKey) == null &&
                (tab != PlanningArea.Sites || plan.projectSites.any { it.lifecycleState != "CLOSED" }), onClick = {
                val initial = when (tab) {
                    PlanningArea.Sites -> areaValues(null) + ("projectSiteId" to plan.projectSites.first { it.lifecycleState != "CLOSED" }.projectSiteId)
                    PlanningArea.Milestones -> milestoneValues(null)
                    PlanningArea.Packages -> packageValues(null) + ("projectSiteId" to plan.projectSites.firstOrNull { it.lifecycleState != "CLOSED" }?.projectSiteId.orEmpty())
                    PlanningArea.Dependencies -> mapOf("predecessorType" to "WORK_PACKAGE", "predecessorId" to "",
                        "successorType" to "WORK_PACKAGE", "successorId" to "", "dependencyType" to "FINISH_TO_START", "lag" to "0")
                }
                forms.open(createKey, plan.formBaseline(), initial)
            }) { Text(hiltechText("+ إضافة", "+ Add")) }
        }
        when (tab) {
            PlanningArea.Sites -> HiltechPanel(Modifier.fillMaxWidth()) {
                Text(hiltechText("المواقع والمناطق", "Sites & areas"), style = MaterialTheme.typography.titleSmall)
                plan.projectSites.forEach { site ->
                    key(site.projectSiteId) {
                        Text("${site.siteCode} · ${site.name} · ${planningSiteStateLabel(site.lifecycleState)}")
                        plan.areas.filter { it.siteId == site.siteId }.forEach { area ->
                            key(area.areaId) { AreaRow(plan, area, forms, formHost) }
                        }
                    }
                }
                forms.current(createKey)?.let { AreaForm(plan, null, it, forms, formHost) }
            }
            PlanningArea.Milestones -> HiltechPanel(Modifier.fillMaxWidth()) {
                Text(hiltechText("المعالم", "Milestones"), style = MaterialTheme.typography.titleSmall)
                if (plan.milestones.isEmpty()) Text(hiltechText("لا توجد معالم مخططة بعد.", "No planned milestones."))
                plan.milestones.forEach { item -> key(item.milestoneId) { MilestoneRow(plan, item, forms, formHost) } }
                forms.current(createKey)?.let { MilestoneForm(plan, null, it, forms, formHost) }
            }
            PlanningArea.Packages -> HiltechPanel(Modifier.fillMaxWidth()) {
                Text(hiltechText("حزم العمل", "Work packages"), style = MaterialTheme.typography.titleSmall)
                if (plan.workPackages.isEmpty()) Text(hiltechText("لا توجد حزم عمل مخططة بعد.", "No planned work packages."))
                plan.workPackages.forEach { item -> key(item.workPackageId) { WorkPackageRow(plan, item, forms, formHost, employeeChoices, teamChoices) } }
                forms.current(createKey)?.let { WorkPackageForm(plan, null, it, forms, formHost, employeeChoices, teamChoices) }
            }
            PlanningArea.Dependencies -> HiltechPanel(Modifier.fillMaxWidth()) {
                Text(hiltechText("اعتماديات الخطة", "Plan dependencies"), style = MaterialTheme.typography.titleSmall)
                if (plan.dependencies.isEmpty()) Text(hiltechText("لا توجد اعتماديات.", "No dependencies."))
                plan.dependencies.forEach { dependency ->
                    key(dependency.dependencyId) {
                        Text(hiltechText("السابق: ", "Predecessor: ") + planningNodeLabel(plan, dependency.predecessor.type, dependency.predecessor.id))
                        Text(hiltechText("اللاحق: ", "Successor: ") + planningNodeLabel(plan, dependency.successor.type, dependency.successor.id))
                        Text(hiltechText("من النهاية إلى البداية · الانتظار بالدقائق: ", "Finish to start · waiting time in minutes: ") + dependency.lagMinutes)
                        val sessionKey = "remove:" + dependency.dependencyId
                        val removing = forms.current(sessionKey)
                        if (plan.canEditPlan && formHost != null) {
                            OutlinedButton(enabled = canStart && removing == null, onClick = {
                                forms.open(sessionKey, plan.formBaseline(dependency.version), emptyMap())
                            }) { Text(hiltechText("إزالة", "Remove")) }
                        }
                        if (removing != null) PlanningFormFrame(removing, forms, formHost, plan.canEditPlan,
                            plan.formBaseline(dependency.version), emptyMap()) { enabled ->
                            Text(hiltechText("إزالة هذا الرابط بين خطوتي الخطة؟", "Remove this link between the two planning steps?"))
                            Button(enabled = enabled && !removing.requiresSourceReview, onClick = {
                                formHost?.let { forms.submit(removing, PlanningUiAction.RemoveDependency(
                                    plan.project.projectId, dependency.dependencyId, removing.baseline.projectVersion,
                                    requireNotNull(removing.baseline.objectVersion), removing.baseline.baselineVersion), it) }
                            }) { Text(hiltechText("تأكيد الإزالة", "Confirm removal")) }
                        }
                    }
                }
                forms.current(createKey)?.let { AddDependencyForm(plan, it, forms, formHost) }
            }
        }
    }
}

@Composable
private fun planningSiteStateLabel(state: String): String = when (state) {
    "PLANNED" -> hiltechText("مخطط", "Planned")
    "ACTIVE" -> hiltechText("نشط", "Active")
    "ON_HOLD" -> hiltechText("متوقف", "On hold")
    "COMPLETED" -> hiltechText("مكتمل", "Completed")
    "CLOSED" -> hiltechText("مغلق", "Closed")
    else -> state
}

@Composable
private fun planningNodeStateLabel(state: String): String = when (state) {
    "PLANNED" -> hiltechText("مخطط", "Planned")
    "ACTIVE" -> hiltechText("قيد التنفيذ", "Active")
    "ACHIEVED" -> hiltechText("متحقق", "Achieved")
    "COMPLETED" -> hiltechText("مكتمل", "Completed")
    "CANCELLED" -> hiltechText("ملغى", "Cancelled")
    else -> state
}

private enum class PlanningArea(val ar: String, val en: String) {
    Sites("المواقع والمناطق", "Sites & areas"),
    Milestones("المعالم", "Milestones"),
    Packages("حزم العمل", "Work packages"),
    Dependencies("الاعتماديات", "Dependencies"),
}

private fun ProjectPlanDto.formBaseline(objectVersion: Long? = null) =
    PlanningFormBaseline(project.version, project.baselineVersion, objectVersion)

@Composable
private fun AreaRow(plan: ProjectPlanDto, area: AreaDto, forms: PlanningFormRegistry, host: PlanningFormHost?) {
    val key = "area:" + area.areaId
    val session = forms.current(key)
    Text("• ${area.code} · ${area.name} · ${area.typeCode} · v${area.version}")
    if (plan.canEditPlan && host != null) OutlinedButton(
        enabled = session == null && !forms.hasUnresolvedRequest,
        onClick = { forms.open(key, plan.formBaseline(area.version), areaValues(area)) },
    ) { Text(hiltechText("تعديل المنطقة", "Edit area")) }
    if (session != null) AreaForm(plan, area, session, forms, host)
}

private fun areaValues(area: AreaDto?) = mapOf(
    "code" to area?.code.orEmpty(), "name" to area?.name.orEmpty(), "type" to (area?.typeCode ?: "ZONE"),
    "parent" to area?.parentAreaId.orEmpty(), "sequence" to area?.sequence?.toString().orEmpty(),
    "restrictedAccess" to (area?.restrictedAccess ?: false).toString(),
)

@Composable
private fun AreaForm(plan: ProjectPlanDto, area: AreaDto?, session: PlanningFormSession, forms: PlanningFormRegistry, host: PlanningFormHost?) {
    val sequence = parsePlanningSequence(session.value("sequence"))
    val latest = areaValues(area)
    val selectedSite = if (area != null) area.siteId else planningPackageSite(plan, session.value("projectSiteId"))?.siteId
    val parents = planningParentAreas(plan.areas, selectedSite, area?.areaId)
    val parentValid = session.value("parent").isBlank() || parents.any { it.areaId == session.value("parent") }
    PlanningFormFrame(session, forms, host, plan.canEditPlan, plan.formBaseline(area?.version), latest,
        uneditedKeys = setOf("restrictedAccess"), sourceDisplay = planningSourceDisplay(latest, plan)) { enabled ->
        if (area == null) {
            HiltechRecordPicker(hiltechText("الموقع", "Project site"), session.value("projectSiteId"),
                plan.projectSites.filter { it.lifecycleState != "CLOSED" }.map { HiltechRecordChoice(it.projectSiteId, it.name, it.siteCode) },
                { if (enabled) { session.update("projectSiteId", it); session.update("parent", "") } },
                allowKnownReference = false, enabled = enabled, allowClear = false)
        }
        PlanningTextField(session, "code", enabled)
        PlanningTextField(session, "name", enabled)
        PlanningTextField(session, "type", enabled)
        HiltechRecordPicker(hiltechText("المنطقة الرئيسية — اختياري", "Parent area (optional)"), session.value("parent"),
            parents.map { HiltechRecordChoice(it.areaId, it.name, it.code) }, { session.update("parent", it) },
            allowKnownReference = false, enabled = enabled)
        if (!parentValid) Text(hiltechText("المنطقة الرئيسية لم تعد متاحة لهذا الاختيار. اختر منطقة أخرى أو امسح الاختيار.",
            "The parent area is no longer available for this selection. Choose another area or clear it."), color = MaterialTheme.colorScheme.error)
        PlanningNumberField(session, "sequence", sequence, enabled)
        Button(enabled = enabled && !session.requiresSourceReview && sequence.valid && parentValid &&
            listOf("code", "name", "type").all { session.value(it).isNotBlank() } &&
            (area != null || selectedSite != null), onClick = {
            val numeric = parsePlanningSequence(session.value("sequence"))
            if (!numeric.valid) return@Button
            val baseline = session.baseline
            val action = if (area == null) PlanningUiAction.CreateArea(
                plan.project.projectId, session.value("projectSiteId"), session.value("parent").blankToNull(),
                session.value("type").trim().uppercase(), session.value("code").trim(), session.value("name").trim(),
                numeric.value?.toInt(), false, baseline.projectVersion, baseline.baselineVersion,
            ) else PlanningUiAction.UpdateArea(
                plan.project.projectId, area.areaId, session.value("parent").blankToNull(), session.value("type").trim().uppercase(),
                session.value("code").trim(), session.value("name").trim(), numeric.value?.toInt(),
                session.value("restrictedAccess").toBoolean(), baseline.projectVersion,
                requireNotNull(baseline.objectVersion), baseline.baselineVersion,
            )
            host?.let { forms.submit(session, action, it) }
        }) { Text(if (area == null) hiltechText("إضافة منطقة", "Add area") else hiltechText("حفظ المنطقة", "Save area")) }
    }
}

@Composable
private fun MilestoneRow(plan: ProjectPlanDto, item: MilestoneDto, forms: PlanningFormRegistry, host: PlanningFormHost?) {
    val key = "milestone:" + item.milestoneId
    val session = forms.current(key)
    Text("• ${item.code} · ${item.name} · ${planningNodeStateLabel(item.state)} · ${hiltechDateText(item.plannedDate)} · v${item.version}")
    if (plan.canEditPlan && host != null) OutlinedButton(
        enabled = session == null && !forms.hasUnresolvedRequest,
        onClick = { forms.open(key, plan.formBaseline(item.version), milestoneValues(item)) },
    ) { Text(hiltechText("تعديل المعلم", "Edit milestone")) }
    if (session != null) MilestoneForm(plan, item, session, forms, host)
}

private fun milestoneValues(item: MilestoneDto?) = mapOf(
    "code" to item?.code.orEmpty(), "name" to item?.name.orEmpty(), "date" to item?.plannedDate.orEmpty(),
    "acceptance" to item?.acceptanceRequirement.orEmpty(), "sequence" to item?.sequence?.toString().orEmpty(),
    "clientVisible" to (item?.clientVisible ?: false).toString(),
)

@Composable
private fun MilestoneForm(plan: ProjectPlanDto, item: MilestoneDto?, session: PlanningFormSession, forms: PlanningFormRegistry, host: PlanningFormHost?) {
    PlanningFormFrame(session, forms, host, plan.canEditPlan, plan.formBaseline(item?.version), milestoneValues(item),
        uneditedKeys = setOf("sequence", "clientVisible")) { enabled ->
        PlanningTextField(session, "code", enabled)
        PlanningTextField(session, "name", enabled)
        HiltechDateField(hiltechText("تاريخ المعلم", "Milestone date"), session.value("date"),
            { session.update("date", it) }, enabled = enabled)
        PlanningTextField(session, "acceptance", enabled)
        Button(enabled = enabled && !session.requiresSourceReview && session.value("code").isNotBlank() &&
            session.value("name").isNotBlank() && isValidOptionalHiltechDate(session.value("date")), onClick = {
            val baseline = session.baseline
            val action = if (item == null) PlanningUiAction.CreateMilestone(
                plan.project.projectId, session.value("code").trim(), session.value("name").trim(),
                session.value("date").blankToNull(), null, false, session.value("acceptance").blankToNull(),
                baseline.projectVersion, baseline.baselineVersion,
            ) else PlanningUiAction.UpdateMilestone(
                plan.project.projectId, item.milestoneId, session.value("code").trim(), session.value("name").trim(),
                session.value("date").blankToNull(), parsePlanningSequence(session.value("sequence")).value?.toInt(),
                session.value("clientVisible").toBoolean(), session.value("acceptance").blankToNull(),
                baseline.projectVersion, requireNotNull(baseline.objectVersion), baseline.baselineVersion,
            )
            host?.let { forms.submit(session, action, it) }
        }) { Text(if (item == null) hiltechText("إضافة معلم", "Add milestone") else hiltechText("حفظ المعلم", "Save milestone")) }
    }
}

@Composable
private fun WorkPackageRow(
    plan: ProjectPlanDto, item: WorkPackageDto, forms: PlanningFormRegistry, host: PlanningFormHost?,
    employeeChoices: List<HiltechRecordChoice>, teamChoices: List<HiltechRecordChoice>,
) {
    val key = "package:" + item.workPackageId
    val session = forms.current(key)
    Text("• ${item.code} · ${item.name} · ${planningNodeStateLabel(item.state)} · " +
        (item.owner?.principalLabel ?: hiltechText("دون مسؤول", "No owner")) + " · v${item.version}")
    if (plan.canEditPlan && host != null) OutlinedButton(
        enabled = session == null && !forms.hasUnresolvedRequest,
        onClick = { forms.open(key, plan.formBaseline(item.version), packageValues(item)) },
    ) { Text(hiltechText("تعديل حزمة العمل", "Edit work package")) }
    if (session != null) WorkPackageForm(plan, item, session, forms, host, employeeChoices, teamChoices)
}

private fun packageValues(item: WorkPackageDto?) = mapOf(
    "projectSiteId" to item?.projectSiteId.orEmpty(), "siteId" to item?.siteId.orEmpty(),
    "milestoneId" to item?.milestoneId.orEmpty(), "code" to item?.code.orEmpty(), "name" to item?.name.orEmpty(),
    "description" to item?.description.orEmpty(), "ownerType" to item?.owner?.principalType.orEmpty(),
    "ownerId" to item?.owner?.principalId.orEmpty(), "start" to item?.plannedStart.orEmpty(),
    "end" to item?.plannedEnd.orEmpty(), "sequence" to item?.sequence?.toString().orEmpty(),
    "originalOwnerType" to item?.owner?.principalType.orEmpty(), "originalOwnerId" to item?.owner?.principalId.orEmpty(),
    "originalOwnerLabel" to item?.owner?.principalLabel.orEmpty(), "ownerLabel" to item?.owner?.principalLabel.orEmpty(),
)

@Composable
private fun WorkPackageForm(
    plan: ProjectPlanDto, item: WorkPackageDto?, session: PlanningFormSession, forms: PlanningFormRegistry, host: PlanningFormHost?,
    employeeChoices: List<HiltechRecordChoice>, teamChoices: List<HiltechRecordChoice>,
) {
    val sequence = parsePlanningSequence(session.value("sequence"))
    val selectedSite = planningPackageSite(plan, session.value("projectSiteId"))
    val siteValid = session.value("projectSiteId").isBlank() || selectedSite != null
    val milestones = plan.milestones.filter { it.baselineVersion == plan.project.baselineVersion }
    val milestoneValid = session.value("milestoneId").isBlank() || milestones.any { it.milestoneId == session.value("milestoneId") }
    val directoryOwners = when (session.value("ownerType")) { "EMPLOYEE" -> employeeChoices; "TEAM" -> teamChoices; else -> emptyList() }
    val originalOwner = if (session.value("ownerType") == session.value("originalOwnerType") &&
        session.value("ownerId") == session.value("originalOwnerId") && session.value("ownerId").isNotBlank())
        listOf(HiltechRecordChoice(session.value("originalOwnerId"),
            session.value("originalOwnerLabel").ifBlank { hiltechText("المسؤول الحالي", "Current owner") },
            hiltechText("المسؤول المسجل على حزمة العمل", "Owner recorded on this work package"))) else emptyList()
    val owners = (directoryOwners + originalOwner).distinctBy { it.id }
    val ownerValid = (session.value("ownerType").isBlank() && session.value("ownerId").isBlank()) ||
        (session.value("ownerType") in setOf("EMPLOYEE", "TEAM") && owners.any { it.id == session.value("ownerId") })
    val latest = packageValues(item)
    PlanningFormFrame(session, forms, host, plan.canEditPlan, plan.formBaseline(item?.version), latest,
        uneditedKeys = setOf("originalOwnerType", "originalOwnerId", "originalOwnerLabel"),
        sourceDisplay = planningSourceDisplay(latest, plan)) { enabled ->
        listOf("code", "name", "description").forEach {
            PlanningTextField(session, it, enabled)
        }
        HiltechRecordPicker(hiltechText("الموقع — اختياري", "Site (optional)"), session.value("projectSiteId"),
            plan.projectSites.filter { it.lifecycleState != "CLOSED" }.map { HiltechRecordChoice(it.projectSiteId, it.name, it.siteCode) },
            { selected -> session.update("projectSiteId", selected); session.update("siteId", planningPackageSite(plan, selected)?.siteId.orEmpty()) },
            allowKnownReference = false, enabled = enabled)
        if (!siteValid) Text(hiltechText("الموقع المختار غير متاح للتخطيط. اختر موقعًا آخر أو امسح الاختيار.",
            "The selected site is unavailable for planning. Choose another site or clear it."), color = MaterialTheme.colorScheme.error)
        HiltechRecordPicker(hiltechText("المعلم — اختياري", "Milestone (optional)"), session.value("milestoneId"),
            milestones.map { HiltechRecordChoice(it.milestoneId, it.name, it.code) }, { session.update("milestoneId", it) },
            allowKnownReference = false, enabled = enabled)
        if (!milestoneValid) Text(hiltechText("المعلم المختار لم يعد في هذه الخطة. اختر معلمًا آخر أو امسح الاختيار.",
            "The selected milestone is no longer in this plan. Choose another milestone or clear it."), color = MaterialTheme.colorScheme.error)
        Text(hiltechText("المسؤول عن حزمة العمل", "Work package owner"), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to hiltechText("دون مسؤول", "Unassigned"), "EMPLOYEE" to hiltechText("موظف", "Employee"),
                "TEAM" to hiltechText("فريق", "Team")).forEach { (type, label) ->
                FilterChip(selected = session.value("ownerType") == type, enabled = enabled,
                    onClick = { if (session.value("ownerType") != type) {
                        session.update("ownerType", type); session.update("ownerId", ""); session.update("ownerLabel", "")
                    } }, label = { Text(label) })
            }
        }
        if (session.value("ownerType").isNotBlank()) {
            HiltechRecordPicker(hiltechText("اختر المسؤول", "Choose an owner"), session.value("ownerId"), owners,
                { selected -> session.update("ownerId", selected); session.update("ownerLabel", owners.firstOrNull { it.id == selected }?.title.orEmpty()) },
                allowKnownReference = false, enabled = enabled, allowClear = false)
            if (owners.isEmpty()) Text(hiltechText("لا توجد اختيارات متاحة حاليًا. يمكنك ترك المسؤول غير معين.",
                "No choices are currently available. You can leave the owner unassigned."))
            if (!ownerValid) Text(hiltechText("اختر مسؤولًا من القائمة، أو اختر «دون مسؤول».",
                "Choose an owner from the list, or select Unassigned."), color = MaterialTheme.colorScheme.error)
        }
        HiltechPlannedDateRange(session.value("start"), session.value("end"),
            { session.update("start", it) }, { session.update("end", it) }, enabled = enabled)
        PlanningNumberField(session, "sequence", sequence, enabled)
        Button(enabled = enabled && !session.requiresSourceReview && session.value("code").isNotBlank() &&
            session.value("name").isNotBlank() && sequence.valid && ownerValid && siteValid && milestoneValid &&
            hiltechDateRangeProblem(session.value("start"), session.value("end")) == null, onClick = {
            val numeric = parsePlanningSequence(session.value("sequence"))
            if (!numeric.valid) return@Button
            val baseline = session.baseline
            val action = if (item == null) PlanningUiAction.CreateWorkPackage(
                plan.project.projectId, selectedSite?.projectSiteId, selectedSite?.siteId,
                session.value("milestoneId").blankToNull(), session.value("code").trim(), session.value("name").trim(),
                session.value("description").blankToNull(), session.value("ownerType").uppercase().blankToNull(),
                session.value("ownerId").blankToNull(), session.value("start").blankToNull(), session.value("end").blankToNull(),
                numeric.value?.toInt(), baseline.projectVersion, baseline.baselineVersion,
            ) else PlanningUiAction.UpdateWorkPackage(
                plan.project.projectId, item.workPackageId, selectedSite?.projectSiteId, selectedSite?.siteId,
                session.value("milestoneId").blankToNull(), session.value("code").trim(), session.value("name").trim(),
                session.value("description").blankToNull(), session.value("ownerType").uppercase().blankToNull(),
                session.value("ownerId").blankToNull(), session.value("start").blankToNull(), session.value("end").blankToNull(),
                numeric.value?.toInt(), baseline.projectVersion, requireNotNull(baseline.objectVersion), baseline.baselineVersion,
            )
            host?.let { forms.submit(session, action, it) }
        }) { Text(if (item == null) hiltechText("إضافة حزمة عمل", "Add work package") else hiltechText("حفظ حزمة العمل", "Save work package")) }
    }
}

@Composable
private fun AddDependencyForm(plan: ProjectPlanDto, session: PlanningFormSession, forms: PlanningFormRegistry, host: PlanningFormHost?) {
    val lag = parsePlanningLag(session.value("lag"))
    val nodes = planningDependencyNodes(plan)
    val predecessorKey = if (session.value("predecessorId").isBlank()) "" else session.value("predecessorType") + ":" + session.value("predecessorId")
    val successorKey = if (session.value("successorId").isBlank()) "" else session.value("successorType") + ":" + session.value("successorId")
    val predecessor = nodes.firstOrNull { it.key == predecessorKey }
    val successor = nodes.firstOrNull { it.key == successorKey }
    val choices = nodes.map { node ->
        HiltechRecordChoice(node.key, node.name,
            (if (node.type == "MILESTONE") hiltechText("معلم", "Milestone") else hiltechText("حزمة عمل", "Work package")) + " · " + node.code)
    }
    PlanningFormFrame(session, forms, host, plan.canEditPlan, plan.formBaseline(), emptyMap()) { enabled ->
        HiltechRecordPicker(hiltechText("الخطوة السابقة", "Predecessor"), predecessorKey, choices, { selected ->
            nodes.firstOrNull { it.key == selected }?.let { node ->
                session.update("predecessorType", node.type); session.update("predecessorId", node.id)
                if (selected == successorKey) session.update("successorId", "")
            }
        }, allowKnownReference = false, enabled = enabled, allowClear = false)
        HiltechRecordPicker(hiltechText("الخطوة اللاحقة", "Successor"), successorKey,
            choices.filter { it.id != predecessorKey }, { selected ->
                nodes.firstOrNull { it.key == selected }?.let { node ->
                    session.update("successorType", node.type); session.update("successorId", node.id)
                }
            }, allowKnownReference = false, enabled = enabled, allowClear = false)
        Text(hiltechText("من النهاية إلى البداية: تبدأ الخطوة اللاحقة بعد نهاية السابقة ومدة الانتظار.",
            "Finish to start: the successor starts after the predecessor finishes and the waiting time has elapsed."))
        if (nodes.isEmpty()) Text(hiltechText("أضف معلمًا أو حزمة عمل قبل إنشاء اعتماديات.", "Add a milestone or work package before creating dependencies."))
        if ((predecessorKey.isNotEmpty() && predecessor == null) || (successorKey.isNotEmpty() && successor == null)) {
            Text(hiltechText("إحدى الخطوات المختارة لم تعد متاحة. اختر خطوة من الخطة الحالية.",
                "A selected step is no longer available. Choose a step from the current plan."), color = MaterialTheme.colorScheme.error)
        }
        PlanningNumberField(session, "lag", lag, enabled)
        Button(enabled = enabled && !session.requiresSourceReview && lag.valid &&
            predecessor != null && successor != null && predecessor.key != successor.key, onClick = {
            val numeric = parsePlanningLag(session.value("lag"))
            val minutes = numeric.value
            if (!numeric.valid || minutes == null || predecessor == null || successor == null) return@Button
            host?.let { forms.submit(session, PlanningUiAction.AddDependency(
                plan.project.projectId, predecessor.type, predecessor.id, successor.type, successor.id,
                "FINISH_TO_START", minutes, session.baseline.projectVersion,
                session.baseline.baselineVersion), it) }
        }) { Text(hiltechText("إضافة اعتمادية", "Add dependency")) }
    }
}

@Composable
private fun PlanningFormFrame(
    session: PlanningFormSession,
    forms: PlanningFormRegistry,
    host: PlanningFormHost?,
    canEdit: Boolean,
    latestBaseline: PlanningFormBaseline,
    latestValues: Map<String, String>,
    uneditedKeys: Set<String> = emptySet(),
    sourceDisplay: Map<String, String> = latestValues,
    content: @Composable (enabled: Boolean) -> Unit,
) {
    var confirmDiscard by remember(session) { mutableStateOf(false) }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("مسودة التخطيط", "Planning draft"), style = MaterialTheme.typography.titleSmall)
        when (session.phase) {
            PlanningFormPhase.SUBMITTING -> Text(hiltechText("جارٍ الحفظ… انتظر تأكيد النتيجة.", "Saving… waiting for confirmation."))
            PlanningFormPhase.UNKNOWN -> {
                Text(hiltechText(
                    "لم تتأكد نتيجة الحفظ. بقيت المسودة كما أرسلتها. أعد التحقق بنفس الطلب قبل إجراء تغيير آخر.",
                    "The save result is unconfirmed. Your submitted draft is preserved. Check the same request before making another change."),
                    color = MaterialTheme.colorScheme.error)
                Text(hiltechText("اترك هذه المساحة مفتوحة حتى تتأكد النتيجة؛ المسودة محفوظة في هذه الجلسة فقط.",
                    "Keep this workspace open until the result is confirmed; this draft is kept only in this session."))
                Button(enabled = host != null, onClick = { host?.let { forms.retry(session, it) } }) {
                    Text(hiltechText("التحقق من الطلب نفسه", "Check the same request"))
                }
            }
            PlanningFormPhase.REJECTED -> {
                val failure = session.result as? PlanningFormResult.Rejected
                Text(planningFailureMessage(failure), color = MaterialTheme.colorScheme.error)
            }
            else -> Unit
        }
        if (session.requiresSourceReview) {
            if (latestBaseline != session.baseline) {
                Text(hiltechText("نسخة المصدر الحالية", "Current source version"), style = MaterialTheme.typography.titleSmall)
                Text("v${latestBaseline.projectVersion} · baseline ${latestBaseline.baselineVersion}")
                sourceDisplay.filterKeys { it !in uneditedKeys }.forEach { (field, value) ->
                    Text(planningFieldLabel(field) + ": " + value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = canEdit && host != null && !forms.hasUnresolvedRequest, onClick = {
                    session.adoptReviewedSource(latestBaseline, latestValues.filterKeys { it in uneditedKeys })
                }) { Text(hiltechText("راجعت المصدر؛ احتفظ بمسودتي على النسخة الحالية", "I reviewed the source; keep my draft on this version")) }
            } else {
                Text(hiltechText("حدّث بيانات المشروع من زر «تحديث»، ثم راجع نسخة المصدر هنا.",
                    "Refresh the project, then review the current source here."))
            }
        }
        content(canEdit && host != null && !session.locked && !forms.hasUnresolvedRequest)
        TextButton(enabled = !session.locked, onClick = {
            if (session.dirty) confirmDiscard = true else session.discard()
        }) { Text(hiltechText("إلغاء المسودة", "Discard draft")) }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(hiltechText("تجاهل المسودة؟", "Discard this draft?")) },
        text = { Text(hiltechText("ستُحذف التعديلات التي لم تُحفظ في هذه المسودة.", "Unsaved changes in this draft will be removed.")) },
        confirmButton = { TextButton(onClick = { session.discard(); confirmDiscard = false }) { Text(hiltechText("تجاهل المسودة", "Discard draft")) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(hiltechText("العودة للمسودة", "Keep editing")) } },
    )
}

@Composable
private fun planningNodeLabel(plan: ProjectPlanDto, type: String, id: String): String {
    val label = when (type) {
        "MILESTONE" -> plan.milestones.firstOrNull { it.milestoneId == id }?.let { it.name + " · " + it.code }
        "WORK_PACKAGE" -> plan.workPackages.firstOrNull { it.workPackageId == id }?.let { it.name + " · " + it.code }
        else -> null
    }
    return label ?: hiltechText("خطوة لم تعد متاحة", "Step no longer available")
}

@Composable
private fun planningSourceDisplay(values: Map<String, String>, plan: ProjectPlanDto): Map<String, String> {
    val unavailable = hiltechText("السجل لم يعد متاحًا", "Record no longer available")
    return values.filterKeys { it !in setOf("siteId", "ownerLabel", "originalOwnerType", "originalOwnerId", "originalOwnerLabel") }
        .mapValues { (field, value) -> if (value.isBlank()) value else when (field) {
            "parent" -> plan.areas.firstOrNull { it.areaId == value }?.let { it.name + " · " + it.code } ?: unavailable
            "projectSiteId" -> plan.projectSites.firstOrNull { it.projectSiteId == value }?.let { it.name + " · " + it.siteCode } ?: unavailable
            "milestoneId" -> plan.milestones.firstOrNull { it.milestoneId == value }?.let { it.name + " · " + it.code } ?: unavailable
            "ownerId" -> values["ownerLabel"]?.takeIf { it.isNotBlank() } ?: unavailable
            "ownerType" -> if (value == "EMPLOYEE") hiltechText("موظف", "Employee") else if (value == "TEAM") hiltechText("فريق", "Team") else unavailable
            else -> value
        } }
}

@Composable
private fun planningFailureMessage(failure: PlanningFormResult.Rejected?): String = when (failure?.code) {
    "VERSION_CONFLICT", "PROJECT_VERSION_CONFLICT", "STATE_CONFLICT" ->
        hiltechText("تغيرت نسخة المشروع. مسودتك محفوظة؛ راجع نسخة المصدر قبل المحاولة.",
            "The project version changed. Your draft is preserved; review the source before retrying.")
    "PLAN_FROZEN", "REJECTED_STATE" ->
        hiltechText("حالة المشروع الحالية لا تسمح بهذا التغيير. مسودتك محفوظة؛ حدّث بيانات المشروع.",
            "The current project state does not allow this change. Your draft is preserved; refresh the project.")
    "PLAN_DEPENDENCY_SELF_EDGE", "PLAN_DEPENDENCY_CYCLE" ->
        hiltechText("الرابط يعيد خطوة إلى نفسها أو يُنشئ دورة. عدّل السابق أو اللاحق؛ مسودتك محفوظة.",
            "The link points a step to itself or creates a cycle. Change the predecessor or successor; your draft is preserved.")
    "AREA_CYCLE", "AREA_PARENT_CONTEXT_INVALID" ->
        hiltechText("اختر منطقة رئيسية صالحة في الموقع نفسه دون إنشاء دورة. مسودتك محفوظة.",
            "Choose a valid parent in the same site without creating a cycle. Your draft is preserved.")
    "PLANNING_CODE_INVALID" ->
        hiltechText("الكود يقبل الحروف الإنجليزية والأرقام والنقطة والشرطة والشرطة السفلية، ويبدأ بحرف أو رقم.",
            "Use Latin letters, digits, dots, hyphens or underscores for the code, starting with a letter or digit.")
    "WORK_PACKAGE_OWNER_INVALID" ->
        hiltechText("المسؤول المحدد غير متاح لهذه المؤسسة. راجع نوعه ومرجعه؛ مسودتك محفوظة.",
            "The selected owner is unavailable in this organization. Check the owner type and reference; your draft is preserved.")
    "PLANNING_CONSTRAINT_CONFLICT" ->
        hiltechText("تتعارض القيم مع سجل موجود. راجع الكود والروابط قبل إعادة الحفظ؛ مسودتك محفوظة.",
            "The entries conflict with an existing record. Review the code and links before saving again; your draft is preserved.")
    else -> when (failure?.httpStatus) {
        401, 403 -> hiltechText("لم يُقبل الحفظ بصلاحيات الحساب الحالية. مسودتك محفوظة.",
            "The account cannot save this change. Your draft is preserved.")
        else -> hiltechText("لم يُقبل الحفظ. راجع المدخلات ثم أعد المحاولة؛ مسودتك محفوظة.",
            "The save was rejected. Review the entries and retry; your draft is preserved.")
    }
}

@Composable
private fun PlanningTextField(session: PlanningFormSession, field: String, enabled: Boolean) {
    OutlinedTextField(value = session.value(field), onValueChange = { if (enabled) session.update(field, it) },
        readOnly = !enabled, modifier = Modifier.fillMaxWidth(), label = { Text(planningFieldLabel(field)) })
}

@Composable
private fun PlanningNumberField(session: PlanningFormSession, field: String, input: PlanningIntegerInput, enabled: Boolean) {
    val message = when (input.problem) {
        PlanningIntegerProblem.REQUIRED -> hiltechText("أدخل عدد الدقائق. اكتب 0 إذا لم توجد مدة انتظار.", "Enter minutes. Use 0 when no waiting time is needed.")
        PlanningIntegerProblem.WHOLE_NUMBER_REQUIRED -> hiltechText("أدخل عددًا صحيحًا من 0 فأكثر، دون فواصل أو كسور.", "Enter a whole number of 0 or more, without separators or decimals.")
        PlanningIntegerProblem.OUT_OF_RANGE -> if (field == "sequence")
            hiltechText("أدخل قيمة من 0 إلى 2147483647.", "Enter a value from 0 to 2147483647.")
            else hiltechText("أدخل قيمة من 0 إلى 9223372036854775807.", "Enter a value from 0 to 9223372036854775807.")
        null -> if (field == "sequence") hiltechText("اتركه فارغًا إذا لم تحدد ترتيبًا.", "Leave blank when no sequence is specified.")
            else hiltechText("0 يعني عدم وجود مدة انتظار.", "0 means no waiting time.")
    }
    OutlinedTextField(value = session.value(field), onValueChange = { if (enabled) session.update(field, it) },
        readOnly = !enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(planningFieldLabel(field)) }, isError = input.problem != null,
        supportingText = { Text(message) })
}

@Composable
private fun planningFieldLabel(field: String): String = when (field) {
    "code" -> hiltechText("الكود", "Code")
    "name" -> hiltechText("الاسم", "Name")
    "type" -> hiltechText("نوع المنطقة", "Area type")
    "parent" -> hiltechText("المنطقة الرئيسية — اختياري", "Parent area (optional)")
    "sequence" -> hiltechText("الترتيب — اختياري", "Sequence (optional)")
    "date" -> hiltechText("تاريخ المعلم", "Milestone date")
    "acceptance" -> hiltechText("شرط القبول — اختياري", "Acceptance requirement (optional)")
    "description" -> hiltechText("الوصف — اختياري", "Description (optional)")
    "projectSiteId", "siteId" -> hiltechText("الموقع — اختياري", "Site (optional)")
    "milestoneId" -> hiltechText("المعلم — اختياري", "Milestone (optional)")
    "ownerType" -> hiltechText("نوع المسؤول", "Owner type")
    "ownerId" -> hiltechText("المسؤول — اختياري", "Owner (optional)")
    "start" -> hiltechText("البداية المخططة", "Planned start")
    "end" -> hiltechText("النهاية المخططة", "Planned end")
    "predecessorType" -> hiltechText("نوع السابق MILESTONE / WORK_PACKAGE", "Predecessor type MILESTONE / WORK_PACKAGE")
    "predecessorId" -> hiltechText("مرجع السابق", "Predecessor reference")
    "successorType" -> hiltechText("نوع اللاحق MILESTONE / WORK_PACKAGE", "Successor type MILESTONE / WORK_PACKAGE")
    "successorId" -> hiltechText("مرجع اللاحق", "Successor reference")
    "dependencyType" -> hiltechText("نوع الاعتمادية", "Dependency type")
    "lag" -> hiltechText("مدة الانتظار بالدقائق", "Waiting time in minutes")
    else -> field
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }
