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
import com.hiltech.shared.core.field.FieldExecutionIntent
import com.hiltech.shared.core.field.FieldQueuedIntent
import com.hiltech.shared.core.local.LocalEvidenceEntity
import com.hiltech.shared.core.work.FieldPersonalIssuedStockSnapshotDto
import com.hiltech.shared.core.work.FieldTodayItemDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto

data class HiltechFieldState(
    val loading: Boolean = false,
    val today: List<FieldTodayItemDto> = emptyList(),
    val selectedWorkOrderId: String? = null,
    val selectedBundle: TechnicianJobBundleDto? = null,
    val errorMessage: String? = null,
    val cachedTodayAt: Long? = null,
    val cachedBundleAt: Long? = null,
    val pendingFieldIntent: FieldQueuedIntent? = null,
    val fieldIntentMessage: String? = null,
    val localEvidence: List<LocalEvidenceEntity> = emptyList(),
    val evidenceMessage: String? = null,
    val personalIssuedStock: FieldPersonalIssuedStockSnapshotDto? = null,
    val personalIssuedStockLoading: Boolean = false,
    val personalIssuedStockError: String? = null,
)

@Composable
fun AssignedWorkSection(
    state: HiltechFieldState,
    onRefresh: () -> Unit,
    onSelectWork: (String) -> Unit,
    captureFieldIntent: Boolean = false,
    onQueueFieldIntent: (FieldExecutionIntent, String?) -> Unit = { _, _ -> },
    captureEvidence: Boolean = false,
    onSelectEvidenceFile: (String) -> Unit = {},
    onTakeEvidencePhoto: (String) -> Unit = {},
    onRefreshPersonalIssuedStock: (String) -> Unit = {},
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
                                cached = state.cachedBundleAt != null,
                                captureFieldIntent = captureFieldIntent,
                                pending = state.pendingFieldIntent,
                                feedback = state.fieldIntentMessage,
                                onQueue = onQueueFieldIntent,
                                captureEvidence = captureEvidence,
                                localEvidence = state.localEvidence,
                                evidenceFeedback = state.evidenceMessage,
                                onSelectEvidenceFile = onSelectEvidenceFile,
                                onTakeEvidencePhoto = onTakeEvidencePhoto,
                                personalIssuedStock = state.personalIssuedStock,
                                personalIssuedStockLoading = state.personalIssuedStockLoading,
                                personalIssuedStockError = state.personalIssuedStockError,
                                onRefreshPersonalIssuedStock = onRefreshPersonalIssuedStock,
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
                                cached = state.cachedBundleAt != null,
                                captureFieldIntent = captureFieldIntent,
                                pending = state.pendingFieldIntent,
                                feedback = state.fieldIntentMessage,
                                onQueue = onQueueFieldIntent,
                                captureEvidence = captureEvidence,
                                localEvidence = state.localEvidence,
                                evidenceFeedback = state.evidenceMessage,
                                onSelectEvidenceFile = onSelectEvidenceFile,
                                onTakeEvidencePhoto = onTakeEvidencePhoto,
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

        if (state.cachedTodayAt != null) {
            Text("نسخة محفوظة بدون اتصال — قد يكون التكليف أو الجاهزية اتغيروا.")
            Text("آخر وقت من السيرفر: " + (state.today.firstOrNull()?.bundleAsOf ?: "غير متاح"))
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
    cached: Boolean,
    captureFieldIntent: Boolean,
    pending: FieldQueuedIntent?,
    feedback: String?,
    onQueue: (FieldExecutionIntent, String?) -> Unit,
    captureEvidence: Boolean,
    localEvidence: List<LocalEvidenceEntity>,
    evidenceFeedback: String?,
    onSelectEvidenceFile: (String) -> Unit,
    onTakeEvidencePhoto: (String) -> Unit,
    personalIssuedStock: FieldPersonalIssuedStockSnapshotDto?,
    personalIssuedStockLoading: Boolean,
    personalIssuedStockError: String?,
    onRefreshPersonalIssuedStock: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        if (cached) {
            Text("نسخة محفوظة للقراءة فقط — يلزم اتصال لتأكيد التكليف وحالته الحالية.")
            Text("وقت بيانات السيرفر: " + bundle.asOf)
        }
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

        FieldExecutionControls(
            bundle = bundle,
            enabled = captureFieldIntent && !cached,
            pending = pending,
            feedback = feedback,
            onQueue = onQueue,
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
            if (captureEvidence) {
                FieldEvidenceCaptureControls(
                    bundle = bundle,
                    enabled = !cached,
                    evidence = localEvidence,
                    feedback = evidenceFeedback,
                    onFile = onSelectEvidenceFile,
                    onPhoto = onTakeEvidencePhoto,
                )
            } else {
                // Keep Phase4 read-only synthetic evidence and its original gate.
                Text("التقاط/رفع Evidence من الموبايل مش متاح في Slice 06.")
            }
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

        // S04A: Own issued balance comes from an independent, live authorized
        // server GET. Never render personal stock from offline cached JobBundle.
        if (cached) {
            Text("خاماتي المصروفة: يلزم الاتصال للتحقق من الرصيد والصلاحية الحاليين.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("خاماتي المصروفة — قراءة فقط",
                    style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { onRefreshPersonalIssuedStock(bundle.workOrderId) }) {
                    Text("تحديث")
                }
            }
            if (personalIssuedStockLoading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("بنراجع الخامات المصروفة لك على السيرفر…")
                }
            }
            personalIssuedStockError?.let { Text("تعذر عرض الخامات المصروفة: " + it) }
            val live = personalIssuedStock?.takeIf {
                it.workOrderId == bundle.workOrderId &&
                    it.workOrderVersion == bundle.workOrderVersion
            }
            live?.let { issued ->
                Text("تاريخ بيانات السيرفر: " + issued.asOf)
                when (issued.status) {
                    "EMPLOYEE_CONTEXT_UNAVAILABLE" ->
                        Text("لا توجد عهدة خامات شخصية متاحة لحسابك الحالي.")
                    "NO_ISSUED_CONTEXT" ->
                        Text("لا توجد سجلات خامات مصروفة لك في التكليف ده.")
                    "SOURCE_CURRENT" -> issued.items.forEach { item ->
                        Text("• " + item.stockItemName + " (" + item.stockItemCode +
                            ") · المتبقي في عهدتك " + item.outstandingQuantity +
                            " " + item.unitCode + " · إصدار المصدر " + item.sourceVersion)
                    }
                    else -> Text("حالة المصدر غير معروفة، يلزم إعادة التحقق.")
                }
                Text("الأرقام دي رصيد متبقٍ من الخامات المصروفة لك؛ مش رصيد المخزن أو كمية تم استهلاكها.")
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
            "التنفيذ من الموبايل",
            style =
                MaterialTheme.typography
                    .titleMedium,
        )
        Text(
            if (captureFieldIntent && !cached) {
                "الأوامر تحفظ محليًا ولا تصبح حالة مؤكدة إلا بعد قبول السيرفر."
            } else {
                "القراءة فقط. أوامر التنفيذ والتقاط الأدلة والمزامنة الفعلية غير مفعّلة في Slice 01."
            },
        )
    }
}
