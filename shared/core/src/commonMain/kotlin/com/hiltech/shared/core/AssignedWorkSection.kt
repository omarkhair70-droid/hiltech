package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.work.FieldTodayItemDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto

data class HiltechFieldState(
    val loading: Boolean = false,
    val today: List<FieldTodayItemDto> = emptyList(),
    val selectedWorkOrderId: String? = null,
    val selectedBundle: TechnicianJobBundleDto? = null,
    val errorMessage: String? = null,
)

@Composable
fun AssignedWorkSection(
    state: HiltechFieldState,
    onRefresh: () -> Unit,
    onSelectWork: (String) -> Unit,
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides
            LayoutDirection.Rtl,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val wide =
                maxWidth >= 760.dp
            if (wide) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(16.dp),
                ) {
                    TodayColumn(
                        state = state,
                        onRefresh = onRefresh,
                        onSelectWork = onSelectWork,
                        modifier =
                            Modifier.widthIn(
                                min = 320.dp,
                                max = 460.dp,
                            ),
                    )
                    state.selectedBundle
                        ?.let {
                            JobBundleDetail(
                                bundle = it,
                                modifier =
                                    Modifier.weight(1f),
                            )
                        }
                }
            } else {
                Column(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp),
                ) {
                    TodayColumn(
                        state = state,
                        onRefresh = onRefresh,
                        onSelectWork = onSelectWork,
                        modifier =
                            Modifier.fillMaxWidth(),
                    )
                    state.selectedBundle
                        ?.let {
                            JobBundleDetail(
                                bundle = it,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun TodayColumn(
    state: HiltechFieldState,
    onRefresh: () -> Unit,
    onSelectWork: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "شغلي اليوم",
                style =
                    MaterialTheme.typography
                        .titleLarge,
            )
            OutlinedButton(
                onClick = onRefresh,
            ) {
                Text("تحديث")
            }
        }

        if (state.loading) {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator()
                Text("بنجيب آخر تكليفاتك…")
            }
        }

        state.errorMessage?.let {
            Text(
                "تعذر تحديث الشغل: " + it,
            )
        }

        if (
            !state.loading &&
            state.today.isEmpty()
        ) {
            Text(
                "مفيش WorkOrder حالي متعيّن ليك. لو التكليف اتشال أو اتبدل، هيختفي هنا فورًا.",
            )
        }

        state.today.forEach { item ->
            TodayCard(
                item = item,
                selected =
                    state.selectedWorkOrderId ==
                        item.workOrderId,
                onClick = {
                    onSelectWork(
                        item.workOrderId,
                    )
                },
            )
        }
    }
}

@Composable
private fun TodayCard(
    item: FieldTodayItemDto,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier =
            Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth(),
            verticalArrangement =
                Arrangement.spacedBy(4.dp),
        ) {
            Text(
                item.workOrderCode +
                    " · " +
                    item.title,
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            Text(
                item.project.projectCode +
                    " · " +
                    item.site.siteCode +
                    " — " +
                    item.site.name,
            )
            item.area?.let {
                Text(
                    "المنطقة: " +
                        it.label,
                )
            }
            Text(
                "الحالة: " +
                    item.lifecycleState +
                    " · readiness: " +
                    item.readinessState,
            )
            Text(
                "التكليف: " +
                    item.assignment.targetType +
                    " · " +
                    item.assignment.targetLabel,
            )
            item.importantBlocker
                ?.let {
                    Text(
                        "Waiting On: " + it,
                    )
                }
            Text(
                if (item.currentlyActionable) {
                    "السياق جاهز للعرض — التنفيذ يبدأ في Phase 6."
                } else {
                    "في انتظار شرط/دور آخر قبل التنفيذ."
                },
            )
            if (selected) {
                Text("مفتوح دلوقتي")
            }
        }
    }
}

@Composable
private fun JobBundleDetail(
    bundle: TechnicianJobBundleDto,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "تفاصيل الشغل",
            style =
                MaterialTheme.typography
                    .titleLarge,
        )
        Text(
            bundle.workOrderCode +
                " · " +
                bundle.title,
        )
        bundle.description?.let {
            Text(it)
        }
        Text(
            "المشروع: " +
                bundle.project.projectCode +
                " · " +
                bundle.project.name,
        )
        Text(
            "الموقع: " +
                bundle.site.siteCode +
                " · " +
                bundle.site.name,
        )
        bundle.site.accessInstructions
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                Text(
                    "تعليمات الدخول: " + it,
                )
            }
        bundle.area?.let {
            Text(
                "المنطقة: " +
                    it.label,
            )
        }

        Text(
            "التكليف الحالي: " +
                bundle.assignment.targetType +
                " · " +
                bundle.assignment.targetLabel,
        )
        Text(
            "WorkOrder v" +
                bundle.workOrderVersion +
                " · asOf " +
                bundle.asOf,
        )

        bundle.binding?.let {
            Text(
                "WorkType: " +
                    it.workType.code +
                    " r" +
                    it.workType.revision +
                    " · binding r" +
                    it.bindingRevision,
            )
        }

        bundle.instruction?.let {
            Text(
                "التعليمات r" +
                    it.revision +
                    (
                        it.summary
                            ?.let { summary ->
                                " · " + summary
                            }
                            ?: ""
                    ),
            )
        }

        if (bundle.tasks.isNotEmpty()) {
            Text(
                "المهام",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            bundle.tasks.forEach {
                Text(
                    "• " +
                        it.title +
                        (
                            if (it.mandatory) {
                                " · مطلوبة"
                            } else {
                                ""
                            }
                        ),
                )
            }
        }

        if (bundle.checklist.isNotEmpty()) {
            Text(
                "Checklist — عرض فقط",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            bundle.checklist.forEach {
                Text(
                    "• " +
                        it.label +
                        " · " +
                        it.completionState,
                )
            }
        }

        if (
            bundle.readinessRequirements
                .isNotEmpty()
        ) {
            Text(
                "متطلبات الجاهزية",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            bundle.readinessRequirements
                .forEach {
                    Text(
                        "• " +
                            (it.label ?: it.key) +
                            " · " +
                            it.satisfactionState +
                            (
                                it.evaluationReasonCode
                                    ?.let { code ->
                                        " · " + code
                                    }
                                    ?: ""
                            ),
                    )
                }
        }

        if (
            bundle.evidenceRequirements
                .isNotEmpty()
        ) {
            Text(
                "Evidence المطلوب — تعريفات فقط",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            bundle.evidenceRequirements
                .forEach {
                    Text(
                        "• " +
                            it.requirementKey +
                            " · " +
                            it.evidenceTypeCode +
                            " · " +
                            it.stage,
                    )
                }
            Text(
                "التقاط/رفع Evidence من الموبايل مش متاح في Slice 06.",
            )
        }

        if (
            bundle.resourceRequirements
                .isNotEmpty()
        ) {
            Text(
                "المواد/الأدوات",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            bundle.resourceRequirements
                .forEach {
                    Text(
                        "• " +
                            it.key +
                            " · " +
                            it.integrationState +
                            (
                                it.reasonCode
                                    ?.let { reason ->
                                        " · " + reason
                                    }
                                    ?: ""
                            ),
                    )
                }
        }

        if (bundle.blockers.isNotEmpty()) {
            Text(
                "العوائق",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            bundle.blockers.forEach {
                Text(
                    "• " +
                        it.description +
                        (
                            it.explanationCode
                                ?.let { code ->
                                    " · " + code
                                }
                                ?: ""
                        ),
                )
            }
        }

        if (
            bundle.safeDocumentRefs
                .isNotEmpty()
        ) {
            Text(
                "مستندات حالية مصرح بها",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )
            bundle.safeDocumentRefs
                .forEach {
                    Text(
                        "• " +
                            it.code +
                            " · " +
                            it.title +
                            (
                                it.revision
                                    ?.let { revision ->
                                        " · r" + revision
                                    }
                                    ?: ""
                            ),
                    )
                }
        } else {
            Text(
                "مفيش document revision source موثوق للـWork ده حاليًا.",
            )
        }

        Text(
            "قدرات التنفيذ على الجهاز",
            style =
                MaterialTheme.typography
                    .titleMedium,
        )
        Text(
            "Start offline: " +
                bundle.executionCapabilities.startOffline +
                " · Block offline: " +
                bundle.executionCapabilities.blockOffline +
                " · Resume offline: " +
                bundle.executionCapabilities.resumeOffline,
        )
        Text(
            "Evidence offline: " +
                bundle.executionCapabilities.evidenceCaptureOffline +
                " · Submit offline: " +
                bundle.executionCapabilities.submitOffline +
                " · Authoritative queue: " +
                bundle.executionCapabilities.authoritativeOfflineQueue,
        )
        Text(
            "التنفيذ وOffline Sync لسه Phase 6 — مفيش Started/Completed/Waiting to sync state محلي هنا.",
        )
    }
}
