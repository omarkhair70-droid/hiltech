package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.hiltech.shared.core.design.*
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
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.work.ProjectCommandCenterDto
import com.hiltech.shared.core.work.ReviewWorkItemDto

sealed interface ProjectCommandCenterUiAction {
    data class Accept(
        val workOrderId: String,
        val baseVersion: Long,
        val reason: String?,
    ) : ProjectCommandCenterUiAction

    data class RequestRework(
        val workOrderId: String,
        val baseVersion: Long,
        val reason: String?,
    ) : ProjectCommandCenterUiAction

    data class PutOnHold(
        val projectId: String,
        val baseVersion: Long,
        val reason: String,
    ) : ProjectCommandCenterUiAction

    data class Resume(
        val projectId: String,
        val baseVersion: Long,
        val resolution: String,
    ) : ProjectCommandCenterUiAction
}

@Composable
fun ProjectCommandCenterSection(
    project: ProjectSummaryDto,
    commandCenter: ProjectCommandCenterDto,
    onAction: (ProjectCommandCenterUiAction) -> Unit,
) {
    var holdReason by
        remember(
            project.projectId,
            project.version,
        ) {
            mutableStateOf("")
        }

    Column(
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        val colors = LocalHiltechOperationalColors.current
        val progress = commandCenter.progress
        val health = commandCenter.health
        var sourceDetails by remember(project.projectId) { mutableStateOf(false) }
        var lifecycleAction by remember(project.projectId, project.version) { mutableStateOf(false) }
        HiltechSectionHeading("PROJECT / EXECUTION", hiltechText("مركز قيادة المشروع", "Project command center"),
            commandCenter.projectCode + " · " + commandCenter.projectName)
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(hiltechText("التقدم المقبول", "Accepted progress"), style = MaterialTheme.typography.labelLarge, color = colors.secondaryText)
                    Text(progress.progressPercent?.let { it + "%" } ?: "—", style = MaterialTheme.typography.displaySmall)
                }
                HiltechStateTag(health.state, when (health.state) {
                    "HEALTHY", "ON_TRACK" -> HiltechStateTone.CURRENT
                    "CRITICAL", "BLOCKED" -> HiltechStateTone.BLOCKED
                    else -> HiltechStateTone.ATTENTION
                })
            }
            val fraction = progress.progressPercent?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
            if (fraction != null) LinearProgressIndicator(progress = { (fraction / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth(), color = colors.accent)
            else Text(hiltechText("التقدم غير متاح من المصدر الحالي.", "Progress is unavailable from the current source."), color = colors.secondaryText)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HiltechKeyValue(hiltechText("الأعمال المقبولة", "Accepted work"), progress.acceptedWorkCount.toString() + " / " + progress.includedWorkCount, Modifier.weight(1f))
                HiltechKeyValue(hiltechText("الوزن المقبول", "Accepted weight"), progress.acceptedWeight.toString() + " / " + progress.totalWeight, Modifier.weight(1f))
            }
        }
        Text(hiltechText("صحة المشروع", "Project health"), style = MaterialTheme.typography.titleMedium)
        if (health.signals.isEmpty()) {
            Text(
                if (health.state == "UNKNOWN") {
                    "لا توجد بعدُ إشارة تقييم كافية أو سياسة صحة فعالة."
                } else {
                    "لا توجد إشارات مشكلة حالية."
                },
            )
        } else {
            Text(hiltechText("لماذا؟", "Why?"))
            health.signals.forEach {
                Text(
                    "• " +
                        it.signalCode +
                        " · " +
                        it.severity +
                        " · " +
                        it.summary +
                        " · source " +
                        it.sourceType +
                        "/" +
                        it.sourceId,
                )
            }
        }

        commandCenter.nextMilestone
            ?.let {
                Text(
                    "الميلستون التالي: " +
                        it.code +
                        " · " +
                        it.name +
                        " · " +
                        (
                            it.plannedDate
                                ?: "بدون تاريخ مخطط"
                        ),
                )
            }

        if (commandCenter.waitingOn.isNotEmpty()) {
            Text(
                hiltechText("Waiting On", "Waiting on"),
                style =
                    MaterialTheme.typography
                        .titleSmall,
            )
            commandCenter.waitingOn
                .forEach {
                    Text(
                        "• " +
                            it.workOrderCode +
                            " · " +
                            it.actionCode +
                            " · " +
                            it.reasonCodes
                                .joinToString() +
                            " · source WORK_ORDER/" +
                            it.workOrderId,
                    )
                }
        }

        if (
            commandCenter
                .submittedReviewItems
                .isNotEmpty()
        ) {
            Text(
                hiltechText("مراجعات منتظرة", "Pending reviews"),
                style =
                    MaterialTheme.typography
                        .titleSmall,
            )
            commandCenter
                .submittedReviewItems
                .forEach { item ->
                    ReviewWorkItemCard(
                        item,
                        onAction,
                    )
                }
        }

        if (
            commandCenter.reworkItems
                .isNotEmpty()
        ) {
            Text(
                hiltechText("إعادة العمل", "Rework"),
                style =
                    MaterialTheme.typography
                        .titleSmall,
            )
            commandCenter.reworkItems
                .forEach {
                    Text(
                        "• " +
                            it.workOrderCode +
                            " · " +
                            it.title +
                            " · source WORK_ORDER/" +
                            it.workOrderId,
                    )
                }
        }

        TextButton(onClick = { sourceDetails = !sourceDetails }) {
            Text(if (sourceDetails) hiltechText("إخفاء مراجع المصدر", "Hide source references") else hiltechText("مراجع المصدر", "Source references"))
        }
        if (sourceDetails) HiltechPanel(Modifier.fillMaxWidth()) {
            Text("Project v" + progress.projectVersion + " · Baseline " + progress.baselineVersion)
            Text(
                "Drill references: " +
                    commandCenter
                        .projectSiteIds.size +
                    " ProjectSite · " +
                    commandCenter
                        .workPackageIds.size +
                    " WorkPackage",
            )
            commandCenter.projectSiteIds
                .take(4)
                .forEach {
                    Text(
                        "• PROJECT_SITE/" + it,
                    )
                }
            commandCenter.workPackageIds
                .take(4)
                .forEach {
                    Text(
                        "• WORK_PACKAGE/" + it,
                    )
                }


        }

        if (project.lifecycleState in setOf("ACTIVE", "ON_HOLD")) TextButton(onClick = { lifecycleAction = !lifecycleAction }) {
            Text(if (lifecycleAction) hiltechText("إغلاق", "Close") else hiltechText("إدارة حالة المشروع", "Manage project state"))
        }
        if (lifecycleAction) when (project.lifecycleState) {
            "ACTIVE" -> {
                OutlinedTextField(
                    value = holdReason,
                    onValueChange = {
                        holdReason = it
                    },
                    label = {
                        Text(
                            hiltechText("سبب إيقاف المشروع", "Reason to put project on hold"),
                        )
                    },
                )
                Button(
                    enabled =
                        holdReason.isNotBlank(),
                    onClick = {
                        onAction(
                            ProjectCommandCenterUiAction
                                .PutOnHold(
                                    projectId =
                                        project.projectId,
                                    baseVersion =
                                        project.version,
                                    reason =
                                        holdReason.trim(),
                                ),
                        )
                    },
                ) {
                    Text(
                        hiltechText("وضع المشروع On Hold", "Put project on hold"),
                    )
                }
            }

            "ON_HOLD" -> {
                OutlinedTextField(
                    value = holdReason,
                    onValueChange = {
                        holdReason = it
                    },
                    label = {
                        Text(
                            hiltechText("قرار/حل سبب الإيقاف", "Resolution of hold reason"),
                        )
                    },
                )
                Button(
                    enabled =
                        holdReason.isNotBlank(),
                    onClick = {
                        onAction(
                            ProjectCommandCenterUiAction
                                .Resume(
                                    projectId =
                                        project.projectId,
                                    baseVersion =
                                        project.version,
                                    resolution =
                                        holdReason.trim(),
                                ),
                        )
                    },
                ) {
                    Text(hiltechText("استئناف المشروع", "Resume project"))
                }
            }
        }
    }
}

@Composable
private fun ReviewWorkItemCard(
    item: ReviewWorkItemDto,
    onAction: (ProjectCommandCenterUiAction) -> Unit,
) {
    var reason by
        remember(
            item.workOrderId,
            item.currentVersion,
        ) {
            mutableStateOf("")
        }

    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(
            item.workOrderCode +
                " · " +
                item.title,
        )
        Text(
            "Submitted v" +
                (item.submittedVersion
                    ?.toString()
                    ?: "؟") +
                " · current v" +
                item.currentVersion +
                " · policy " +
                item.reviewPolicy.code +
                " / " +
                item.reviewPolicy.mode,
        )
        Text(
            "Reviewer: " +
                if (item.reviewer.eligible) {
                    "مؤهل الآن · step " +
                        (
                            item.reviewer
                                .reviewStepKey
                                ?: "—"
                        ) +
                        " · source " +
                        (
                            item.reviewer
                                .sourceType
                                ?: "—"
                        ) +
                        "/" +
                        (
                            item.reviewer
                                .sourceRef
                                ?: "—"
                        )
                } else {
                    "غير مؤهل · " +
                        item.reviewer
                            .reasonCodes
                            .joinToString()
                },
        )
        Text(
            "BEFORE_ACCEPT evidence: " +
                if (
                    item.beforeAcceptEvidenceSatisfied
                ) {
                    "READY"
                } else {
                    "BLOCKED · " +
                        item.evidenceReasonCodes
                            .joinToString()
                },
        )

        if (item.decisions.isNotEmpty()) {
            Text(hiltechText("سجل قرارات المراجعة", "Review decision history"))
            item.decisions.forEach {
                Text(
                    "• " +
                        it.decision +
                        " · step " +
                        (
                            it.reviewStepKey
                                ?: "—"
                        ) +
                        " · reviewer " +
                        it.reviewerUserId +
                        (
                            it.reason
                                ?.let { r ->
                                    " · " + r
                                }
                                ?: ""
                        ),
                )
            }
        }

        OutlinedTextField(
            value = reason,
            onValueChange = {
                reason = it
            },
            label = {
                Text(
                    hiltechText("سبب/ملاحظة القرار", "Decision reason / note"),
                )
            },
        )

        Row(
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled =
                    item.reviewer.eligible &&
                        item.beforeAcceptEvidenceSatisfied &&
                        item.submittedVersion != null,
                onClick = {
                    onAction(
                        ProjectCommandCenterUiAction
                            .Accept(
                                workOrderId =
                                    item.workOrderId,
                                baseVersion =
                                    item.currentVersion,
                                reason =
                                    reason.trim()
                                        .takeIf {
                                            it.isNotEmpty()
                                        },
                            ),
                    )
                },
            ) {
                Text(hiltechText("قبول", "Accept"))
            }

            OutlinedButton(
                enabled =
                    item.reviewer.eligible &&
                        item.submittedVersion != null,
                onClick = {
                    onAction(
                        ProjectCommandCenterUiAction
                            .RequestRework(
                                workOrderId =
                                    item.workOrderId,
                                baseVersion =
                                    item.currentVersion,
                                reason =
                                    reason.trim()
                                        .takeIf {
                                            it.isNotEmpty()
                                        },
                            ),
                    )
                },
            ) {
                Text(hiltechText("طلب إعادة عمل", "Request rework"))
            }
        }
    }
}
