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
import com.hiltech.shared.core.work.WorkOrderDto
import com.hiltech.shared.core.work.WorkQueueContextDto
import com.hiltech.shared.core.work.WorkReadinessDto

sealed interface WorkReadinessUiAction {
    data class Evaluate(
        val workOrderId: String,
        val baseVersion: Long,
    ) : WorkReadinessUiAction

    data class Assign(
        val workOrderId: String,
        val targetType: String?,
        val targetId: String?,
        val baseVersion: Long,
    ) : WorkReadinessUiAction

    data class Reassign(
        val workOrderId: String,
        val targetType: String,
        val targetId: String,
        val baseVersion: Long,
        val currentAssignmentId: String,
        val baseAssignmentVersion: Long,
        val reason: String?,
    ) : WorkReadinessUiAction

    data class Waive(
        val workOrderId: String,
        val requirementId: String,
        val baseWorkOrderVersion: Long,
        val baseRequirementVersion: Long,
        val reason: String,
    ) : WorkReadinessUiAction
}

@Composable
fun WorkReadinessSection(
    orders: List<WorkOrderDto>,
    readinessByWorkOrder: Map<String, WorkReadinessDto>,
    workQueueContext: List<WorkQueueContextDto>,
    onAction: (WorkReadinessUiAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "الجاهزية والتعيين",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "الجاهزية منفصلة عن حالة أمر العمل. لا يتم افتراض توافر شخص أو مادة أو أداة بدون مصدر حقيقي.",
        )

        if (workQueueContext.isNotEmpty()) {
            Text("Waiting On", style = MaterialTheme.typography.titleSmall)
            workQueueContext.forEach { item ->
                Text(
                    "• " + item.workOrderCode + " · " + item.actionCode +
                        if (item.reasonCodes.isEmpty()) "" else " · " + item.reasonCodes.joinToString(),
                )
            }
        }

        orders
            .filter { it.lifecycleState in setOf("PLANNED", "ASSIGNED") }
            .forEach { order ->
                val readiness = readinessByWorkOrder[order.workOrderId]
                if (readiness == null) {
                    Text(
                        order.workOrderCode + " · جاهزية غير محملة بعد.",
                    )
                } else {
                    WorkReadinessCard(
                        order = order,
                        readiness = readiness,
                        onAction = onAction,
                    )
                }
            }
    }
}

@Composable
private fun WorkReadinessCard(
    order: WorkOrderDto,
    readiness: WorkReadinessDto,
    onAction: (WorkReadinessUiAction) -> Unit,
) {
    var reassignmentReason by
        remember(order.workOrderId, readiness.workOrderVersion) {
            mutableStateOf("")
        }
    var waiverReason by
        remember(order.workOrderId, readiness.workOrderVersion) {
            mutableStateOf("")
        }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            order.workOrderCode + " · " + order.title,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "الحالة " + readiness.lifecycleState +
                " · الجاهزية " + readiness.readinessState +
                " · نمط التعيين " + readiness.assignmentMode +
                " · v" + readiness.workOrderVersion,
        )

        if (readiness.waitingOnReasonCodes.isNotEmpty()) {
            Text(
                "Waiting On: " +
                    readiness.waitingOnReasonCodes.joinToString(),
            )
        }

        if (readiness.requirements.isNotEmpty()) {
            Text("متطلبات الجاهزية")
            readiness.requirements.forEach { requirement ->
                Text(
                    "• " + (requirement.label ?: requirement.key) +
                        " · " + requirement.typeCode +
                        " · " + requirement.satisfactionState +
                        (requirement.evaluationReasonCode?.let { " · " + it } ?: ""),
                )
                if (
                    requirement.waiverAllowed &&
                    requirement.satisfactionState == "BLOCKED"
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = waiverReason.isNotBlank(),
                            onClick = {
                                onAction(
                                    WorkReadinessUiAction.Waive(
                                        workOrderId = order.workOrderId,
                                        requirementId = requirement.requirementId,
                                        baseWorkOrderVersion = readiness.workOrderVersion,
                                        baseRequirementVersion = requirement.version,
                                        reason = waiverReason.trim(),
                                    ),
                                )
                            },
                        ) {
                            Text("اعتماد استثناء موثق")
                        }
                    }
                }
            }
        }

        if (
            readiness.waitingOnReasonCodes.any {
                it.contains("PHASE5") ||
                    it.contains("MATERIAL") ||
                    it.contains("ASSET")
            }
        ) {
            Text(
                "المواد والأدوات: التوافر الرسمي مؤجل لمصدر Phase 5؛ لا توجد علامة خضراء افتراضية.",
            )
        }

        if (readiness.blockers.isNotEmpty()) {
            Text("العوائق الحالية")
            readiness.blockers.forEach {
                Text(
                    "• " + (it.explanationCode ?: it.blockerTypeCode) +
                        " · " + it.description,
                )
            }
        }

        Button(
            onClick = {
                onAction(
                    WorkReadinessUiAction.Evaluate(
                        workOrderId = order.workOrderId,
                        baseVersion = readiness.workOrderVersion,
                    ),
                )
            },
        ) {
            Text("إعادة تقييم الجاهزية")
        }

        val eligible = readiness.eligibleTargets.filter { it.eligible }
        val ineligible = readiness.eligibleTargets.filterNot { it.eligible }
        Text("الأهداف المؤهلة: " + eligible.size)
        eligible.forEach { target ->
            Text(
                "• " + target.targetType + " · " + target.displayLabel +
                    " · source " + target.sourceFreshness,
            )
        }
        ineligible.take(8).forEach { target ->
            Text(
                "• غير مؤهل: " + target.targetType + " · " +
                    target.displayLabel + " · " + target.reasonCodes.joinToString(),
            )
        }

        if (
            readiness.lifecycleState == "PLANNED" &&
            readiness.readinessState == "READY" &&
            readiness.currentAssignment == null
        ) {
            if (
                readiness.assignmentMode == "AUTO" &&
                eligible.size == 1
            ) {
                Button(
                    onClick = {
                        onAction(
                            WorkReadinessUiAction.Assign(
                                workOrderId = order.workOrderId,
                                targetType = null,
                                targetId = null,
                                baseVersion = readiness.workOrderVersion,
                            ),
                        )
                    },
                ) {
                    Text("تعيين تلقائي حسب السياسة")
                }
            }
            eligible.forEach { target ->
                OutlinedButton(
                    onClick = {
                        onAction(
                            WorkReadinessUiAction.Assign(
                                workOrderId = order.workOrderId,
                                targetType = target.targetType,
                                targetId = target.targetId,
                                baseVersion = readiness.workOrderVersion,
                            ),
                        )
                    },
                ) {
                    Text("تعيين: " + target.displayLabel)
                }
            }
        }

        readiness.currentAssignment?.let { current ->
            Text(
                "التعيين الحالي: " + current.targetType +
                    " · " + current.targetLabel +
                    " · منذ " + current.validFrom,
            )
            OutlinedTextField(
                value = reassignmentReason,
                onValueChange = { reassignmentReason = it },
                label = { Text("سبب إعادة التعيين — عند الحاجة") },
            )
            eligible
                .filterNot {
                    it.targetType == current.targetType &&
                        it.targetId == current.targetId
                }
                .forEach { target ->
                    OutlinedButton(
                        onClick = {
                            onAction(
                                WorkReadinessUiAction.Reassign(
                                    workOrderId = order.workOrderId,
                                    targetType = target.targetType,
                                    targetId = target.targetId,
                                    baseVersion = readiness.workOrderVersion,
                                    currentAssignmentId = current.assignmentId,
                                    baseAssignmentVersion = current.version,
                                    reason = reassignmentReason.trim().takeIf { it.isNotEmpty() },
                                ),
                            )
                        },
                    ) {
                        Text("إعادة التعيين إلى " + target.displayLabel)
                    }
                }
        }

        if (readiness.assignmentHistory.isNotEmpty()) {
            Text("سجل التعيين")
            readiness.assignmentHistory.forEach {
                Text(
                    "• " + it.targetType + " · " + it.targetLabel +
                        " · " + it.state + " · " + it.assignedAt,
                )
            }
        }

        if (
            readiness.requirements.any {
                it.waiverAllowed &&
                    it.satisfactionState == "BLOCKED"
            }
        ) {
            OutlinedTextField(
                value = waiverReason,
                onValueChange = { waiverReason = it },
                label = { Text("سبب الاستثناء") },
            )
        }
    }
}
