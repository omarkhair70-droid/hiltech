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
        Text(
            "مركز قيادة المشروع",
            style =
                MaterialTheme.typography
                    .titleMedium,
        )
        Text(
            commandCenter.projectCode +
                " · " +
                commandCenter.projectName +
                " · baseline " +
                commandCenter.baselineVersion,
        )

        val progress =
            commandCenter.progress
        Text(
            "التقدم التشغيلي المقبول: " +
                (
                    progress.progressPercent
                        ?.let { it + "%" }
                        ?: "غير متاح — لا يوجد وزن داخل المقام"
                ),
        )
        Text(
            "Accepted weight " +
                progress.acceptedWeight +
                " / " +
                progress.totalWeight +
                " · الأعمال المقبولة " +
                progress.acceptedWorkCount +
                "/" +
                progress.includedWorkCount,
        )
        Text(
            "Project version " +
                progress.projectVersion +
                " · baseline " +
                progress.baselineVersion,
        )

        val health =
            commandCenter.health
        Text(
            "صحة المشروع: " +
                health.state,
            style =
                MaterialTheme.typography
                    .titleSmall,
        )
        if (health.signals.isEmpty()) {
            Text(
                if (health.state == "UNKNOWN") {
                    "لا توجد بعدُ إشارة تقييم كافية أو سياسة صحة فعالة."
                } else {
                    "لا توجد إشارات مشكلة حالية."
                },
            )
        } else {
            Text("لماذا؟")
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
                "Waiting On",
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
                "مراجعات منتظرة",
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
                "إعادة العمل",
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

        when (project.lifecycleState) {
            "ACTIVE" -> {
                OutlinedTextField(
                    value = holdReason,
                    onValueChange = {
                        holdReason = it
                    },
                    label = {
                        Text(
                            "سبب إيقاف المشروع",
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
                        "وضع المشروع On Hold",
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
                            "قرار/حل سبب الإيقاف",
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
                    Text("استئناف المشروع")
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

    Column(
        verticalArrangement =
            Arrangement.spacedBy(4.dp),
    ) {
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
            Text("سجل قرارات المراجعة")
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
                    "سبب/ملاحظة القرار",
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
                Text("قبول")
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
                Text("طلب إعادة عمل")
            }
        }
    }
}
