package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.projects.ProjectPlanSiteDto
import com.hiltech.shared.core.work.WorkOrderDto
import com.hiltech.shared.core.work.WorkTaskDto
import com.hiltech.shared.core.work.WorkTypeChoiceDto

sealed interface WorkPlanningUiAction {
    data class Create(
        val projectId: String,
        val siteId: String,
        val projectSiteId: String,
        val areaId: String?,
        val workPackageId: String?,
        val workTypeCode: String,
        val workTypeRevision: Int,
        val title: String,
        val description: String?,
        val plannedStart: String?,
        val plannedEnd: String?,
        val priorityCode: String?,
        val baseProjectVersion: Long,
        val baselineVersion: Int,
    ) : WorkPlanningUiAction

    data class Update(
        val workOrderId: String,
        val areaId: String?,
        val workPackageId: String?,
        val title: String,
        val description: String?,
        val plannedStart: String?,
        val plannedEnd: String?,
        val priorityCode: String,
        val baseVersion: Long,
    ) : WorkPlanningUiAction

    data class Plan(
        val workOrderId: String,
        val workTypeCode: String,
        val workTypeRevision: Int,
        val instructionJson: String?,
        val instructionSummary: String?,
        val baseVersion: Long,
        val baselineVersion: Int,
    ) : WorkPlanningUiAction

    data class ReviseInstruction(
        val workOrderId: String,
        val instructionJson: String,
        val summary: String?,
        val changeReason: String,
        val baseVersion: Long,
    ) : WorkPlanningUiAction

    data class CreateTask(
        val workOrderId: String,
        val taskCode: String?,
        val title: String,
        val sortOrder: Int,
        val mandatory: Boolean,
        val baseVersion: Long,
    ) : WorkPlanningUiAction

    data class UpdateTask(
        val workOrderId: String,
        val taskId: String,
        val taskCode: String?,
        val title: String,
        val description: String?,
        val sortOrder: Int,
        val mandatory: Boolean,
        val estimatedDurationMinutes: Int?,
        val evidenceRequirementKey: String?,
        val state: String,
        val baseTaskVersion: Long,
        val baseWorkOrderVersion: Long,
    ) : WorkPlanningUiAction

    data class AddDependency(
        val workOrderId: String,
        val predecessorWorkOrderId: String,
        val lagMinutes: Long,
        val baseVersion: Long,
    ) : WorkPlanningUiAction

    data class RemoveDependency(
        val workOrderId: String,
        val dependencyId: String,
        val baseVersion: Long,
        val baseDependencyVersion: Long,
    ) : WorkPlanningUiAction

    data class Activate(
        val projectId: String,
        val baseVersion: Long,
        val baselineVersion: Int,
    ) : WorkPlanningUiAction
}

@Composable
fun WorkPlanningSection(
    plan: ProjectPlanDto,
    workTypes: List<WorkTypeChoiceDto>,
    orders: List<WorkOrderDto>,
    onAction: (WorkPlanningUiAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "أوامر العمل وربط السياسات",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "كل أمر مخطط يحتفظ بمراجعات نوع العمل والسياسات والتعليمات وقائمة الفحص كما كانت عند التخطيط.",
        )
        Text(
            "الجاهزية: لم تُقيّم بعد — لا توجد مطالبة بتوفر أفراد أو مواد أو أدوات في هذه المرحلة.",
        )
        if (workTypes.isEmpty()) {
            Text("لا توجد مراجعة WorkType نشطة قابلة للاختيار.")
        } else {
            Text(
                "الأنواع النشطة: " +
                    workTypes.joinToString {
                        "${it.workType.code} r${it.workType.revision}"
                    },
            )
        }
        if (orders.isEmpty()) {
            Text("لا توجد أوامر عمل لهذا المشروع بعد.")
        }
        orders.forEach {
            WorkOrderCard(
                order = it,
                types = workTypes,
                onAction = onAction,
            )
        }
        if (
            plan.project.lifecycleState in setOf("PLANNING", "READY") &&
            plan.projectSites.isNotEmpty() &&
            workTypes.isNotEmpty()
        ) {
            CreateWorkOrderForm(
                plan = plan,
                types = workTypes,
                onAction = onAction,
            )
        }
        if (plan.project.lifecycleState == "READY") {
            Button(
                enabled =
                    orders.any { it.countsTowardProjectProgress } &&
                        orders.none {
                            it.countsTowardProjectProgress &&
                                it.lifecycleState == "DRAFT"
                        },
                onClick = {
                    onAction(
                        WorkPlanningUiAction.Activate(
                            projectId = plan.project.projectId,
                            baseVersion = plan.project.version,
                            baselineVersion =
                                plan.project.baselineVersion,
                        ),
                    )
                },
            ) {
                Text("تفعيل المشروع من خط الأساس الحالي")
            }
        }
    }
}

@Composable
private fun WorkOrderCard(
    order: WorkOrderDto,
    types: List<WorkTypeChoiceDto>,
    onAction: (WorkPlanningUiAction) -> Unit,
) {
    var editing by
        remember(order.workOrderId) {
            mutableStateOf(false)
        }
    var revising by
        remember(order.workOrderId) {
            mutableStateOf(false)
        }
    var taskTitle by
        remember(order.workOrderId) {
            mutableStateOf("")
        }
    var predecessor by
        remember(order.workOrderId) {
            mutableStateOf("")
        }
    var dependencyLag by
        remember(order.workOrderId) {
            mutableStateOf("0")
        }
    val editable =
        order.lifecycleState in
            setOf("DRAFT", "PLANNED")

    Column(
        verticalArrangement =
            Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${order.workOrderCode} · ${order.title} · " +
                "${order.lifecycleState} · v${order.version}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "الأولوية ${order.priorityCode} · وزن " +
                "${order.progressWeight} · baseline " +
                "${order.baselineVersion}",
        )
        Text(
            "الجدول: ${order.plannedStart ?: "—"} → " +
                (order.plannedEnd ?: "—"),
        )
        Text(
            "الجاهزية: " +
                if (
                    order.readinessState ==
                    "NOT_EVALUATED"
                ) {
                    "لم تُقيّم بعد"
                } else {
                    order.readinessState
                },
        )
        order.binding?.let { binding ->
            Text(
                "WorkType ${binding.workType.code} " +
                    "r${binding.workType.revision} · " +
                    "Assignment r${binding.assignmentPolicy.revision} · " +
                    "Readiness r${binding.readinessPolicy.revision} · " +
                    "Evidence r${binding.evidencePolicy.revision} · " +
                    "Review r${binding.reviewPolicy.revision}",
            )
        }
        order.instruction?.let {
            Text(
                "تعليمات r${it.revision} · schema " +
                    "${it.payloadSchemaVersion} · " +
                    (it.summary ?: "دون ملخص"),
            )
        }
        if (order.checklist.isNotEmpty()) {
            Text("قائمة الفحص (تعريف فقط)")
            order.checklist.forEach {
                Text(
                    "• ${it.label} · " +
                        if (it.required) {
                            "مطلوب"
                        } else {
                            "اختياري"
                        } +
                        " · ${it.completionState}",
                )
            }
        }
        if (order.requirements.isNotEmpty()) {
            Text("المتطلبات (غير مُشبعة افتراضياً)")
            order.requirements.forEach {
                Text(
                    "• ${it.family}/${it.key} · " +
                        it.satisfactionState,
                )
            }
        }

        if (order.tasks.isNotEmpty()) {
            Text("مهام التخطيط")
        }
        order.tasks.forEach { task ->
            WorkTaskRow(
                order = order,
                task = task,
                editable = editable,
                onAction = onAction,
            )
        }

        val incomingDependencies =
            order.dependencies.filter {
                it.successorWorkOrderId ==
                    order.workOrderId
            }
        if (incomingDependencies.isNotEmpty()) {
            Text("اعتماديات FINISH_TO_START")
        }
        incomingDependencies.forEach { dependency ->
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "يعتمد على " +
                        dependency.predecessorWorkOrderId
                            .take(8) +
                        " · ${dependency.dependencyType} · " +
                        "${dependency.lagMinutes} دقيقة",
                )
                if (editable) {
                    OutlinedButton(
                        onClick = {
                            onAction(
                                WorkPlanningUiAction
                                    .RemoveDependency(
                                        workOrderId =
                                            order.workOrderId,
                                        dependencyId =
                                            dependency.dependencyId,
                                        baseVersion =
                                            order.version,
                                        baseDependencyVersion =
                                            dependency.version,
                                    ),
                            )
                        },
                    ) {
                        Text("إزالة الاعتمادية")
                    }
                }
            }
        }

        if (editable) {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        editing = !editing
                    },
                ) {
                    Text("تعديل النطاق والجدول")
                }
                if (
                    order.lifecycleState ==
                    "PLANNED"
                ) {
                    OutlinedButton(
                        onClick = {
                            revising = !revising
                        },
                    ) {
                        Text("مراجعة التعليمات")
                    }
                }
            }
            if (editing) {
                EditWorkForm(order) { values ->
                    onAction(
                        WorkPlanningUiAction.Update(
                            workOrderId =
                                order.workOrderId,
                            areaId = values.areaId,
                            workPackageId =
                                values.workPackageId,
                            title = values.title,
                            description =
                                values.description,
                            plannedStart =
                                values.plannedStart,
                            plannedEnd =
                                values.plannedEnd,
                            priorityCode =
                                values.priorityCode,
                            baseVersion =
                                order.version,
                        ),
                    )
                    editing = false
                }
            }
            if (
                order.lifecycleState == "DRAFT" &&
                types.isNotEmpty()
            ) {
                PlanForm(
                    order = order,
                    types = types,
                    onAction = onAction,
                )
            }
            if (revising) {
                ReviseForm(order) {
                        json,
                        summary,
                        reason,
                    ->
                    onAction(
                        WorkPlanningUiAction
                            .ReviseInstruction(
                                workOrderId =
                                    order.workOrderId,
                                instructionJson =
                                    json,
                                summary = summary,
                                changeReason =
                                    reason,
                                baseVersion =
                                    order.version,
                            ),
                    )
                    revising = false
                }
            }
            OutlinedTextField(
                value = taskTitle,
                onValueChange = {
                    taskTitle = it
                },
                label = {
                    Text(
                        "مهمة فرعية اختيارية",
                    )
                },
            )
            Button(
                enabled = taskTitle.isNotBlank(),
                onClick = {
                    onAction(
                        WorkPlanningUiAction.CreateTask(
                            workOrderId =
                                order.workOrderId,
                            taskCode = null,
                            title =
                                taskTitle.trim(),
                            sortOrder =
                                order.tasks.size,
                            mandatory = true,
                            baseVersion =
                                order.version,
                        ),
                    )
                    taskTitle = ""
                },
            ) {
                Text("إضافة مهمة تخطيطية")
            }
            OutlinedTextField(
                value = predecessor,
                onValueChange = {
                    predecessor = it
                },
                label = {
                    Text("WorkOrder ID السابق")
                },
            )
            OutlinedTextField(
                value = dependencyLag,
                onValueChange = {
                    dependencyLag = it
                },
                label = {
                    Text("Lag بالدقائق")
                },
            )
            Button(
                enabled =
                    predecessor.isNotBlank(),
                onClick = {
                    onAction(
                        WorkPlanningUiAction
                            .AddDependency(
                                workOrderId =
                                    order.workOrderId,
                                predecessorWorkOrderId =
                                    predecessor.trim(),
                                lagMinutes =
                                    dependencyLag
                                        .toLongOrNull()
                                        ?.coerceAtLeast(0)
                                        ?: 0,
                                baseVersion =
                                    order.version,
                            ),
                    )
                    predecessor = ""
                    dependencyLag = "0"
                },
            ) {
                Text("إضافة FINISH_TO_START")
            }
        }
    }
}

@Composable
private fun WorkTaskRow(
    order: WorkOrderDto,
    task: WorkTaskDto,
    editable: Boolean,
    onAction: (WorkPlanningUiAction) -> Unit,
) {
    Row(
        horizontalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "• ${task.taskCode ?: "—"} · " +
                "${task.title} · ${task.state}",
        )
        if (editable) {
            OutlinedButton(
                onClick = {
                    onAction(
                        WorkPlanningUiAction.UpdateTask(
                            workOrderId =
                                order.workOrderId,
                            taskId = task.taskId,
                            taskCode = task.taskCode,
                            title = task.title,
                            description =
                                task.description,
                            sortOrder =
                                task.sortOrder,
                            mandatory =
                                task.mandatory,
                            estimatedDurationMinutes =
                                task.estimatedDurationMinutes,
                            evidenceRequirementKey =
                                task.evidenceRequirementKey,
                            state =
                                if (
                                    task.state ==
                                    "PLANNED"
                                ) {
                                    "CANCELLED"
                                } else {
                                    "PLANNED"
                                },
                            baseTaskVersion =
                                task.version,
                            baseWorkOrderVersion =
                                order.version,
                        ),
                    )
                },
            ) {
                Text(
                    if (task.state == "PLANNED") {
                        "إلغاء المهمة"
                    } else {
                        "إعادة المهمة للتخطيط"
                    },
                )
            }
        }
    }
}

@Composable
private fun CreateWorkOrderForm(
    plan: ProjectPlanDto,
    types: List<WorkTypeChoiceDto>,
    onAction: (WorkPlanningUiAction) -> Unit,
) {
    var selectedTypeIndex by
        remember(plan.project.projectId, types) {
            mutableStateOf(0)
        }
    var selectedSiteIndex by
        remember(
            plan.project.projectId,
            plan.projectSites,
        ) {
            mutableStateOf(0)
        }
    val type =
        types[
            selectedTypeIndex.coerceIn(
                0,
                types.lastIndex,
            )
        ]
    val site =
        plan.projectSites[
            selectedSiteIndex.coerceIn(
                0,
                plan.projectSites.lastIndex,
            )
        ]
    var title by
        remember(plan.project.projectId) {
            mutableStateOf("")
        }
    var description by
        remember(plan.project.projectId) {
            mutableStateOf("")
        }
    var area by
        remember(plan.project.projectId) {
            mutableStateOf("")
        }
    var workPackage by
        remember(plan.project.projectId) {
            mutableStateOf("")
        }
    var plannedStart by
        remember(plan.project.projectId) {
            mutableStateOf("")
        }
    var plannedEnd by
        remember(plan.project.projectId) {
            mutableStateOf("")
        }

    Text("إنشاء WorkOrder DRAFT")
    ProjectSiteSelector(
        sites = plan.projectSites,
        selectedIndex = selectedSiteIndex,
        onSelect = {
            selectedSiteIndex = it
        },
    )
    WorkTypeSelector(
        types = types,
        selectedIndex = selectedTypeIndex,
        onSelect = {
            selectedTypeIndex = it
        },
    )
    OutlinedTextField(
        title,
        { title = it },
        label = {
            Text("عنوان أمر العمل")
        },
    )
    OutlinedTextField(
        description,
        { description = it },
        label = {
            Text("نطاق العمل — اختياري")
        },
    )
    OutlinedTextField(
        area,
        { area = it },
        label = {
            Text("Area ID — اختياري")
        },
    )
    OutlinedTextField(
        workPackage,
        { workPackage = it },
        label = {
            Text("WorkPackage ID — اختياري")
        },
    )
    OutlinedTextField(
        plannedStart,
        { plannedStart = it },
        label = {
            Text(
                "البداية المخططة ISO-8601 — اختياري",
            )
        },
    )
    OutlinedTextField(
        plannedEnd,
        { plannedEnd = it },
        label = {
            Text(
                "النهاية المخططة ISO-8601 — اختياري",
            )
        },
    )
    Text(
        "سيُنشأ بـ ${type.workType.code} " +
            "r${type.workType.revision} في " +
            "${site.siteCode}; الكود يخصصه الخادم " +
            "من CodePolicy.",
    )
    Button(
        enabled = title.isNotBlank(),
        onClick = {
            onAction(
                WorkPlanningUiAction.Create(
                    projectId =
                        plan.project.projectId,
                    siteId = site.siteId,
                    projectSiteId =
                        site.projectSiteId,
                    areaId = area.blankNull(),
                    workPackageId =
                        workPackage.blankNull(),
                    workTypeCode =
                        type.workType.code,
                    workTypeRevision =
                        type.workType.revision,
                    title = title.trim(),
                    description =
                        description.blankNull(),
                    plannedStart =
                        plannedStart.blankNull(),
                    plannedEnd =
                        plannedEnd.blankNull(),
                    priorityCode =
                        type.defaultPriorityCode,
                    baseProjectVersion =
                        plan.project.version,
                    baselineVersion =
                        plan.project.baselineVersion,
                ),
            )
            title = ""
            description = ""
            plannedStart = ""
            plannedEnd = ""
        },
    ) {
        Text("إنشاء DRAFT")
    }
}

@Composable
private fun EditWorkForm(
    order: WorkOrderDto,
    submit: (WorkOrderEditValues) -> Unit,
) {
    var area by
        remember(order.version) {
            mutableStateOf(
                order.areaId.orEmpty(),
            )
        }
    var workPackage by
        remember(order.version) {
            mutableStateOf(
                order.workPackageId.orEmpty(),
            )
        }
    var title by
        remember(order.version) {
            mutableStateOf(order.title)
        }
    var description by
        remember(order.version) {
            mutableStateOf(
                order.description.orEmpty(),
            )
        }
    var plannedStart by
        remember(order.version) {
            mutableStateOf(
                order.plannedStart.orEmpty(),
            )
        }
    var plannedEnd by
        remember(order.version) {
            mutableStateOf(
                order.plannedEnd.orEmpty(),
            )
        }
    var priority by
        remember(order.version) {
            mutableStateOf(
                order.priorityCode,
            )
        }
    OutlinedTextField(
        area,
        { area = it },
        label = {
            Text("Area ID — اختياري")
        },
    )
    OutlinedTextField(
        workPackage,
        { workPackage = it },
        label = {
            Text("WorkPackage ID — اختياري")
        },
    )
    OutlinedTextField(
        title,
        { title = it },
        label = {
            Text("العنوان")
        },
    )
    OutlinedTextField(
        description,
        { description = it },
        label = {
            Text("الوصف / النطاق")
        },
    )
    OutlinedTextField(
        plannedStart,
        { plannedStart = it },
        label = {
            Text("البداية المخططة ISO-8601")
        },
    )
    OutlinedTextField(
        plannedEnd,
        { plannedEnd = it },
        label = {
            Text("النهاية المخططة ISO-8601")
        },
    )
    OutlinedTextField(
        priority,
        { priority = it },
        label = {
            Text("الأولوية")
        },
    )
    Button(
        enabled =
            title.isNotBlank() &&
                priority.isNotBlank(),
        onClick = {
            submit(
                WorkOrderEditValues(
                    areaId = area.blankNull(),
                    workPackageId =
                        workPackage.blankNull(),
                    title = title.trim(),
                    description =
                        description.blankNull(),
                    plannedStart =
                        plannedStart.blankNull(),
                    plannedEnd =
                        plannedEnd.blankNull(),
                    priorityCode =
                        priority.trim(),
                ),
            )
        },
    ) {
        Text("حفظ")
    }
}

@Composable
private fun PlanForm(
    order: WorkOrderDto,
    types: List<WorkTypeChoiceDto>,
    onAction: (WorkPlanningUiAction) -> Unit,
) {
    var selectedTypeIndex by
        remember(order.workOrderId, types) {
            mutableStateOf(0)
        }
    val type =
        types[
            selectedTypeIndex.coerceIn(
                0,
                types.lastIndex,
            )
        ]
    var summary by
        remember(order.workOrderId) {
            mutableStateOf("")
        }
    var json by
        remember(order.workOrderId) {
            mutableStateOf("")
        }
    WorkTypeSelector(
        types = types,
        selectedIndex = selectedTypeIndex,
        onSelect = {
            selectedTypeIndex = it
        },
    )
    OutlinedTextField(
        summary,
        { summary = it },
        label = {
            Text("ملخص التعليمات")
        },
    )
    OutlinedTextField(
        json,
        { json = it },
        label = {
            Text(
                "JSON تعليمات اختياري؛ القالب عند الفراغ",
            )
        },
    )
    Button(
        onClick = {
            onAction(
                WorkPlanningUiAction.Plan(
                    workOrderId =
                        order.workOrderId,
                    workTypeCode =
                        type.workType.code,
                    workTypeRevision =
                        type.workType.revision,
                    instructionJson =
                        json.blankNull(),
                    instructionSummary =
                        summary.blankNull(),
                    baseVersion =
                        order.version,
                    baselineVersion =
                        order.baselineVersion,
                ),
            )
        },
    ) {
        Text("تخطيط وربط المراجعات")
    }
}

@Composable
private fun WorkTypeSelector(
    types: List<WorkTypeChoiceDto>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by
        remember(types) {
            mutableStateOf(false)
        }
    val selected =
        types[
            selectedIndex.coerceIn(
                0,
                types.lastIndex,
            )
        ]
    Column {
        OutlinedButton(
            onClick = {
                expanded = true
            },
        ) {
            Text(
                "WorkType: ${selected.workType.code} " +
                    "r${selected.workType.revision}",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            },
        ) {
            types.forEachIndexed {
                    index,
                    choice,
                ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${choice.workType.code} " +
                                "r${choice.workType.revision} · " +
                                choice.workType.name,
                        )
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ProjectSiteSelector(
    sites: List<ProjectPlanSiteDto>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by
        remember(sites) {
            mutableStateOf(false)
        }
    val selected =
        sites[
            selectedIndex.coerceIn(
                0,
                sites.lastIndex,
            )
        ]
    Column {
        OutlinedButton(
            onClick = {
                expanded = true
            },
        ) {
            Text(
                "الموقع: ${selected.siteCode} · " +
                    selected.name,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            },
        ) {
            sites.forEachIndexed {
                    index,
                    site,
                ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${site.siteCode} · " +
                                site.name,
                        )
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ReviseForm(
    order: WorkOrderDto,
    submit:
        (
            String,
            String?,
            String,
        ) -> Unit,
) {
    var json by
        remember(order.version) {
            mutableStateOf(
                order.instruction
                    ?.structuredPayloadJson
                    ?: "{}",
            )
        }
    var summary by
        remember(order.version) {
            mutableStateOf(
                order.instruction
                    ?.summary
                    .orEmpty(),
            )
        }
    var reason by
        remember(order.version) {
            mutableStateOf("")
        }
    OutlinedTextField(
        json,
        { json = it },
        label = {
            Text("JSON التعليمات")
        },
    )
    OutlinedTextField(
        summary,
        { summary = it },
        label = {
            Text("الملخص")
        },
    )
    OutlinedTextField(
        reason,
        { reason = it },
        label = {
            Text("سبب التغيير")
        },
    )
    Button(
        enabled =
            reason.isNotBlank() &&
                json.isNotBlank(),
        onClick = {
            submit(
                json,
                summary.blankNull(),
                reason.trim(),
            )
        },
    ) {
        Text("إنشاء مراجعة جديدة")
    }
}

private data class WorkOrderEditValues(
    val areaId: String?,
    val workPackageId: String?,
    val title: String,
    val description: String?,
    val plannedStart: String?,
    val plannedEnd: String?,
    val priorityCode: String,
)

private fun String.blankNull() =
    trim().takeIf {
        it.isNotEmpty()
    }
