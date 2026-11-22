package com.hiltech.shared.core

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.field.FieldExecutionIntent
import com.hiltech.shared.core.field.FieldMaterialUsageIntent
import com.hiltech.shared.core.field.FieldQueuedIntent
import com.hiltech.shared.core.local.LocalEvidenceEntity
import com.hiltech.shared.core.work.FieldEngineeringTestDefinitionDto
import com.hiltech.shared.core.work.FieldPersonalIssuedStockLineDto
import com.hiltech.shared.core.work.FieldPersonalIssuedStockSnapshotDto
import com.hiltech.shared.core.work.FieldTodayItemDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto

data class FieldEngineeringTestDraft(
    val requirementKey: String,
    val testSubjectRef: String,
    val values: Map<String, String>,
    val instrumentTag: String? = null,
    val deviceReportedStatus: String? = null,
)

/**
 * UX #128 experiment only. Default preserves current production field behavior.
 * FOCUSED_COMPACT is opt-in for a caller with an explicit identity/org scope key.
 */
enum class FieldWorkPresentation {
    BASELINE,
    FOCUSED_COMPACT,
}

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
    val materialUsageIntents: List<FieldMaterialUsageIntent> = emptyList(),
    val materialUsageMessage: String? = null,
    val assignedAssetLookup: AssetLookupState = AssetLookupState(),
    val engineeringTestSubmitting: Boolean = false,
    val engineeringTestMessage: String? = null,
)

@Composable
fun AssignedWorkSection(
    state: HiltechFieldState,
    onRefresh: () -> Unit,
    onSelectWork: (String) -> Unit,
    captureFieldIntent: Boolean = false,
    onQueueFieldIntent: (FieldExecutionIntent, String?) -> Unit = { _, _ -> },
    onQueueCompletion: () -> Unit = {},
    captureEvidence: Boolean = false,
    onSelectEvidenceFile: (String) -> Unit = {},
    onTakeEvidencePhoto: (String) -> Unit = {},
    onRefreshPersonalIssuedStock: (String) -> Unit = {},
    showPersonalIssuedStock: Boolean = false,
    onQueueMaterialUsage: (FieldPersonalIssuedStockLineDto, String) -> Unit = { _, _ -> },
    showMaterialUsageReporting: Boolean = false,
    onLookupAssignedAssetTag: (String, String) -> Unit = { _, _ -> },
    showAssignedAssetLookup: Boolean = false,
    onSubmitEngineeringTest: (String, FieldEngineeringTestDraft) -> Unit = { _, _ -> },
    showEngineeringTests: Boolean = false,
    presentation: FieldWorkPresentation = FieldWorkPresentation.BASELINE,
    focusScopeKey: String = "",
    // Only for the opt-in focus study; must be the SAME scroll state used
    // by the parent host, never a second nested vertical scroll.
    focusHostScroll: ScrollState? = null,
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
                                onQueueCompletion = onQueueCompletion,
                                captureEvidence = captureEvidence,
                                localEvidence = state.localEvidence,
                                evidenceFeedback = state.evidenceMessage,
                                onSelectEvidenceFile = onSelectEvidenceFile,
                                onTakeEvidencePhoto = onTakeEvidencePhoto,
                                personalIssuedStock = state.personalIssuedStock,
                                personalIssuedStockLoading = state.personalIssuedStockLoading,
                                personalIssuedStockError = state.personalIssuedStockError,
                                onRefreshPersonalIssuedStock = onRefreshPersonalIssuedStock,
                                showPersonalIssuedStock = showPersonalIssuedStock,
                                materialUsageIntents = state.materialUsageIntents,
                                materialUsageMessage = state.materialUsageMessage,
                                onQueueMaterialUsage = onQueueMaterialUsage,
                                showMaterialUsageReporting = showMaterialUsageReporting,
                                assignedAssetLookup = state.assignedAssetLookup,
                                onLookupAssignedAssetTag = onLookupAssignedAssetTag,
                                showAssignedAssetLookup = showAssignedAssetLookup,
                                onSubmitEngineeringTest = onSubmitEngineeringTest,
                                showEngineeringTests = showEngineeringTests,
                                engineeringTestSubmitting = state.engineeringTestSubmitting,
                                engineeringTestMessage = state.engineeringTestMessage,
                                modifier =
                                    Modifier.weight(1f),
                            )
                        }
                }

            } else if (
                presentation == FieldWorkPresentation.FOCUSED_COMPACT &&
                focusScopeKey.isNotBlank()
            ) {
                // UX-only local navigation state. Never an authority or offline cache.
                // MainActivity/Windows still use BASELINE and do not pass this key.
                var focusedWorkOrderId by remember(focusScopeKey) {
                    mutableStateOf<String?>(null)
                }
                var savedTodayOffset by remember(focusScopeKey) { mutableStateOf(0) }
                // ID is the semantic anchor. A pixel offset alone points to
                // another WorkOrder if tasks reorder while a bundle is open.
                var returnWorkOrderId by remember(focusScopeKey) {
                    mutableStateOf<String?>(null)
                }
                val cardViewportY = remember(focusScopeKey) { mutableMapOf<String, Float>() }
                var selectedCardViewportY by remember(focusScopeKey) {
                    mutableStateOf<Float?>(null)
                }
                var restoreOffset by remember(focusScopeKey) { mutableStateOf<Int?>(null) }
                val focusedId = focusedWorkOrderId
                    ?.takeIf { id -> state.today.any { it.workOrderId == id } }
                // Keep one scroll owner: the actual Shell's scroll, supplied
                // by the caller only when/if this research candidate is wired.
                LaunchedEffect(focusScopeKey) {
                    focusHostScroll?.scrollTo(0)
                }
                LaunchedEffect(focusScopeKey, focusedId, restoreOffset) {
                    val scroll = focusHostScroll
                    if (scroll != null) {
                        if (focusedId != null) {
                            // Wait until the focused detail replaces the long
                            // Today list. Scrolling immediately during the same
                            // composition is overwritten by the parent's
                            // max-scroll clamping from the new layout.
                            withFrameNanos { }
                            scroll.scrollTo(0)
                        } else {
                            restoreOffset?.let { target ->
                                // Wait for Today content to lay out after leaving
                                // detail; clamped restoration is deliberate.
                                withFrameNanos { }
                                scroll.scrollTo(target)
                                // Source ordering may change during detail view.
                                // Resolve the SAME WorkOrder card from its new
                                // measured viewport position, not the old index.
                                val anchorId = returnWorkOrderId
                                val previousY = selectedCardViewportY
                                if (anchorId != null && previousY != null &&
                                    state.today.any { it.workOrderId == anchorId }
                                ) {
                                    for (attempt in 0 until 4) {
                                        withFrameNanos { }
                                        val currentY = cardViewportY[anchorId] ?: continue
                                        val delta = (currentY - previousY).roundToInt()
                                        if (kotlin.math.abs(delta) <= 2) break
                                        scroll.scrollTo(
                                            (scroll.value + delta).coerceIn(0, scroll.maxValue)
                                        )
                                    }
                                }
                                restoreOffset = null
                                returnWorkOrderId = null
                            }
                        }
                    }
                }
                if (focusedId == null) {
                    TodayColumn(
                        state = state,
                        onRefresh = onRefresh,
                        onCardLocated = { id, y -> cardViewportY[id] = y },
                        onSelectWork = { id ->
                            if (state.today.any { it.workOrderId == id }) {
                                savedTodayOffset = focusHostScroll?.value ?: 0
                                selectedCardViewportY = cardViewportY[id]
                                restoreOffset = null
                                returnWorkOrderId = null
                                focusedWorkOrderId = id
                                onSelectWork(id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = {
                            cardViewportY.clear()
                            returnWorkOrderId = focusedWorkOrderId
                            focusedWorkOrderId = null
                            restoreOffset = savedTodayOffset
                        }) {
                            Text("الرجوع لشغلي اليوم")
                        }
                        // The selected bundle MUST belong to the requested and
                        // currently assigned WorkOrder. Never render prior content
                        // while a new item is loading, reassigned or revoked.
                        val matchingBundle = state.selectedBundle
                            ?.takeIf {
                                it.workOrderId == focusedId &&
                                    state.selectedWorkOrderId == focusedId
                            }
                        if (matchingBundle == null) {
                            if (state.loading) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator()
                                    Text("بنجيب تفاصيل التكليف الحالي…")
                                }
                            } else {
                                Text(
                                    state.errorMessage
                                        ?: "تفاصيل التكليف غير متاحة حاليًا. ارجع للقائمة أو حدّثها."
                                )
                            }
                        } else {
                        matchingBundle.let {
                                JobBundleDetail(
                                    bundle = it,
                                    cached = state.cachedBundleAt != null,
                                    captureFieldIntent = captureFieldIntent,
                                    pending = state.pendingFieldIntent,
                                    feedback = state.fieldIntentMessage,
                                    onQueue = onQueueFieldIntent,
                                    onQueueCompletion = onQueueCompletion,
                                    captureEvidence = captureEvidence,
                                    localEvidence = state.localEvidence,
                                    evidenceFeedback = state.evidenceMessage,
                                    onSelectEvidenceFile = onSelectEvidenceFile,
                                    onTakeEvidencePhoto = onTakeEvidencePhoto,
                                    personalIssuedStock = state.personalIssuedStock,
                                    personalIssuedStockLoading = state.personalIssuedStockLoading,
                                    personalIssuedStockError = state.personalIssuedStockError,
                                    onRefreshPersonalIssuedStock = onRefreshPersonalIssuedStock,
                                    showPersonalIssuedStock = showPersonalIssuedStock,
                                    materialUsageIntents = state.materialUsageIntents,
                                    materialUsageMessage = state.materialUsageMessage,
                                    onQueueMaterialUsage = onQueueMaterialUsage,
                                    showMaterialUsageReporting = showMaterialUsageReporting,
                                    assignedAssetLookup = state.assignedAssetLookup,
                                    onLookupAssignedAssetTag = onLookupAssignedAssetTag,
                                    showAssignedAssetLookup = showAssignedAssetLookup,
                                    onSubmitEngineeringTest = onSubmitEngineeringTest,
                                    showEngineeringTests = showEngineeringTests,
                                    engineeringTestSubmitting = state.engineeringTestSubmitting,
                                    engineeringTestMessage = state.engineeringTestMessage,
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                )
                            }
                        }
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
                                onQueueCompletion = onQueueCompletion,
                                captureEvidence = captureEvidence,
                                localEvidence = state.localEvidence,
                                evidenceFeedback = state.evidenceMessage,
                                onSelectEvidenceFile = onSelectEvidenceFile,
                                onTakeEvidencePhoto = onTakeEvidencePhoto,
                                personalIssuedStock = state.personalIssuedStock,
                                personalIssuedStockLoading = state.personalIssuedStockLoading,
                                personalIssuedStockError = state.personalIssuedStockError,
                                onRefreshPersonalIssuedStock = onRefreshPersonalIssuedStock,
                                showPersonalIssuedStock = showPersonalIssuedStock,
                                materialUsageIntents = state.materialUsageIntents,
                                materialUsageMessage = state.materialUsageMessage,
                                onQueueMaterialUsage = onQueueMaterialUsage,
                                showMaterialUsageReporting = showMaterialUsageReporting,
                                assignedAssetLookup = state.assignedAssetLookup,
                                onLookupAssignedAssetTag = onLookupAssignedAssetTag,
                                showAssignedAssetLookup = showAssignedAssetLookup,
                                onSubmitEngineeringTest = onSubmitEngineeringTest,
                                showEngineeringTests = showEngineeringTests,
                                engineeringTestSubmitting = state.engineeringTestSubmitting,
                                engineeringTestMessage = state.engineeringTestMessage,
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
    onCardLocated: ((String, Float) -> Unit)? = null,
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
                modifier =
                    if (onCardLocated != null) {
                        Modifier.onGloballyPositioned { coordinates ->
                            onCardLocated(item.workOrderId, coordinates.positionInRoot().y)
                        }
                    } else {
                        Modifier
                    },
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
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        modifier =
            modifier.fillMaxWidth(),
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
    onQueueCompletion: () -> Unit,
    captureEvidence: Boolean,
    localEvidence: List<LocalEvidenceEntity>,
    evidenceFeedback: String?,
    onSelectEvidenceFile: (String) -> Unit,
    onTakeEvidencePhoto: (String) -> Unit,
    personalIssuedStock: FieldPersonalIssuedStockSnapshotDto?,
    personalIssuedStockLoading: Boolean,
    personalIssuedStockError: String?,
    onRefreshPersonalIssuedStock: (String) -> Unit,
    showPersonalIssuedStock: Boolean,
    materialUsageIntents: List<FieldMaterialUsageIntent>,
    materialUsageMessage: String?,
    onQueueMaterialUsage: (FieldPersonalIssuedStockLineDto, String) -> Unit,
    showMaterialUsageReporting: Boolean,
    assignedAssetLookup: AssetLookupState,
    onLookupAssignedAssetTag: (String, String) -> Unit,
    showAssignedAssetLookup: Boolean,
    onSubmitEngineeringTest: (String, FieldEngineeringTestDraft) -> Unit,
    showEngineeringTests: Boolean,
    engineeringTestSubmitting: Boolean,
    engineeringTestMessage: String?,
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

        if (bundle.completionGate.available) {
            Text(
                "تسليم العمل للمراجعة",
                style = MaterialTheme.typography.titleMedium,
            )
            val gate = bundle.completionGate
            if (!gate.satisfied) {
                Text("التسليم متوقف لحين استيفاء المتطلبات:")
                gate.reasonCodes.forEach { reason ->
                    Text("• " + reason)
                }
            }
            if (pending?.commandType == "SubmitWorkCompletion") {
                Text("طلب التسليم محفوظ محليًا: " + pending.state)
            }
            var confirmCompletion by remember(
                bundle.workOrderId,
                bundle.workOrderVersion,
            ) { mutableStateOf(false) }
            if (confirmCompletion) {
                Text("سيُرسل هذا العمل إلى المراجع، ولن يُعتبر مقبولًا قبل قرار المراجعة.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmCompletion = false }) {
                        Text("إلغاء")
                    }
                    OutlinedButton(
                        enabled =
                            !cached &&
                                pending == null &&
                                bundle.executionCapabilities.submitOffline &&
                                gate.satisfied,
                        onClick = {
                            confirmCompletion = false
                            onQueueCompletion()
                        },
                    ) {
                        Text("تأكيد التسليم للمراجعة")
                    }
                }
            } else {
                OutlinedButton(
                    enabled =
                        !cached &&
                            pending == null &&
                            bundle.executionCapabilities.submitOffline &&
                            gate.satisfied,
                    onClick = { confirmCompletion = true },
                ) {
                    Text("تقديم العمل للمراجعة")
                }
            }
        }

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

        val structuredTests =
            bundle.evidenceRequirements
                .filter { it.structuredTest != null }
        if (showEngineeringTests && structuredTests.isNotEmpty()) {
            Text(
                "الاختبارات الهندسية",
                style = MaterialTheme.typography.titleMedium,
            )
            if (cached) {
                Text("الاختبارات structured للقراءة فقط بدون إنترنت؛ يلزم اتصال لإرسال نتيجة جديدة.")
            } else {
                structuredTests.forEach { requirement ->
                    val test = requireNotNull(requirement.structuredTest)
                    EngineeringTestCaptureCard(
                        workOrderId = bundle.workOrderId,
                        requirementKey = requirement.requirementKey,
                        test = test,
                        rawEvidenceReady =
                            !test.rawEvidenceRequired ||
                                bundle.existingEvidence.any {
                                    it.requirementKey == requirement.requirementKey &&
                                        it.storageState == "READY"
                                },
                        submitting = engineeringTestSubmitting,
                        feedback = engineeringTestMessage,
                        onSubmit = { draft ->
                            onSubmitEngineeringTest(bundle.workOrderId, draft)
                        },
                    )
                }
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
        if (showPersonalIssuedStock && cached) {
            Text("خاماتي المصروفة: يلزم الاتصال للتحقق من الرصيد والصلاحية الحاليين.")
        } else if (showPersonalIssuedStock) {
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
                        MaterialUsageCaptureLine(
                            item = item,
                            intents =
                                materialUsageIntents.filter {
                                    it.requirementId == item.requirementId &&
                                        it.stockItemId == item.stockItemId
                                },
                            enabled = showMaterialUsageReporting,
                            onQueue = onQueueMaterialUsage,
                        )
                    }
                    else -> Text("حالة المصدر غير معروفة، يلزم إعادة التحقق.")
                }
                Text("الأرقام دي رصيد متبقٍ من الخامات المصروفة لك؛ مش رصيد المخزن أو كمية تم استهلاكها.")
                if (showMaterialUsageReporting) {
                    Text("بلاغ الاستخدام المحلي لا يخصم الرصيد ده. الخصم الحقيقي يحصل فقط بعد مراجعة المخزن والتسوية الرسمية.")
                    materialUsageMessage?.let { Text(it) }
                }
            }
        }

        if (showAssignedAssetLookup && cached) {
            Text("QR / AssetTag: يلزم الاتصال للتحقق من الأصل وصالحية التكليف الحالية.")
        } else if (showAssignedAssetLookup) {
            Text(
                "QR / AssetTag داخل التكليف",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("النتيجة Live من السيرفر ومقيدة بالـWorkOrder المفتوح؛ المسح لا يغير عهدة أو مخزون.")
            AssetTagLookupSection(
                state = assignedAssetLookup,
                lookup = { code -> onLookupAssignedAssetTag(bundle.workOrderId, code) },
            )
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


@Composable
private fun MaterialUsageCaptureLine(
    item: FieldPersonalIssuedStockLineDto,
    intents: List<FieldMaterialUsageIntent>,
    enabled: Boolean,
    onQueue: (FieldPersonalIssuedStockLineDto, String) -> Unit,
) {
    var quantity by remember(
        item.requirementId,
        item.stockItemId,
        item.sourceVersion,
    ) {
        mutableStateOf("")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "• " + item.stockItemName + " (" + item.stockItemCode +
                ") · المتبقي في عهدتك " + item.outstandingQuantity +
                " " + item.unitCode + " · إصدار المصدر " + item.sourceVersion,
        )
        if (enabled) {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text("الكمية المستخدمة (" + item.unitCode + ")") },
                supportingText = {
                    Text("ده بلاغ استخدام فقط؛ مش خصم مخزون محلي.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    onQueue(item, quantity.trim())
                    quantity = ""
                },
                enabled = quantity.trim().isNotEmpty(),
            ) {
                Text("سجل استخدام الخامة")
            }
        }
        intents.forEach { intent ->
            val stateText =
                when (intent.state) {
                    "PENDING", "RETRYABLE", "SYNCING" ->
                        "معلّق محليًا — هيتبعت بنفس العملية لما الاتصال يرجع"
                    "APPLIED" ->
                        "وصل للسيرفر — PENDING_REVIEW، ولسه مش خصم مخزون"
                    "CONFLICT" ->
                        "تعارض مع المصدر — راجع الرصيد الحالي قبل إعادة القرار"
                    "TERMINAL_FAILED" ->
                        "مرفوض نهائيًا — راجع البيانات"
                    "CANCELLED_LOCAL" ->
                        "ملغي محليًا"
                    else -> intent.state
                }
            Text(
                "↳ " + intent.quantity + " " + intent.unitCode +
                    " · " + stateText +
                    (intent.lastResultCode?.let { " · " + it } ?: ""),
            )
        }
    }
}

@Composable
private fun EngineeringTestCaptureCard(
    workOrderId: String,
    requirementKey: String,
    test: FieldEngineeringTestDefinitionDto,
    rawEvidenceReady: Boolean,
    submitting: Boolean,
    feedback: String?,
    onSubmit: (FieldEngineeringTestDraft) -> Unit,
) {
    var subject by remember(workOrderId, requirementKey, test.templateId, test.templateRevision) {
        mutableStateOf("")
    }
    var instrumentTag by remember(workOrderId, requirementKey, test.templateId, test.templateRevision) {
        mutableStateOf("")
    }
    var values by remember(workOrderId, requirementKey, test.templateId, test.templateRevision) {
        mutableStateOf(test.metrics.associate { it.key to "" })
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(test.label, style = MaterialTheme.typography.titleSmall)
        Text(
            "Template ${test.templateCode} r${test.templateRevision} · schema v${test.schemaVersion}" +
                (test.reviewerRelationshipCode?.let { " · review: $it" } ?: ""),
        )
        OutlinedTextField(
            value = subject,
            onValueChange = { subject = it },
            label = { Text(test.subjectLabel) },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        test.metrics.forEach { metric ->
            OutlinedTextField(
                value = values[metric.key].orEmpty(),
                onValueChange = { value ->
                    values = values + (metric.key to value)
                },
                label = {
                    Text(
                        metric.label +
                            (metric.unitCode?.let { " ($it)" } ?: "") +
                            if (metric.required) " *" else "",
                    )
                },
                supportingText = {
                    val parts = buildList {
                        metric.dimension?.let { add(it) }
                        if (metric.options.isNotEmpty()) {
                            add("الاختيارات: " + metric.options.joinToString(" / "))
                        }
                        val range = listOfNotNull(
                            metric.minValue?.let { "min $it" },
                            metric.maxValue?.let { "max $it" },
                        )
                        if (range.isNotEmpty()) add(range.joinToString(" · "))
                        metric.precision?.let { add("precision $it") }
                    }
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (test.instrumentAssetRequired) {
            OutlinedTextField(
                value = instrumentTag,
                onValueChange = { instrumentTag = it },
                label = { Text("AssetTag جهاز الاختبار *") },
                supportingText = {
                    Text(
                        if (test.calibrationRequired) {
                            "هيتم التحقق من الأصل والمعايرة الحالية من السيرفر وقت الإرسال."
                        } else {
                            "هيتم حل الـAssetTag للأصل الحقيقي وقت الإرسال."
                        },
                    )
                },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (test.rawEvidenceRequired && !rawEvidenceReady) {
            Text("الاختبار ده محتاج ملف/صورة Evidence جاهزة على السيرفر قبل إرسال القيم.")
        }
        if (submitting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("بنثبت نتيجة الاختبار على المصدر الحالي…")
            }
        }
        feedback?.let { Text(it) }
        val requiredValuesReady =
            test.metrics.filter { it.required }.all {
                values[it.key]?.trim()?.isNotEmpty() == true
            }
        val canSubmit =
            !submitting &&
                subject.trim().isNotEmpty() &&
                requiredValuesReady &&
                (!test.instrumentAssetRequired || instrumentTag.trim().isNotEmpty()) &&
                rawEvidenceReady
        OutlinedButton(
            onClick = {
                onSubmit(
                    FieldEngineeringTestDraft(
                        requirementKey = requirementKey,
                        testSubjectRef = subject.trim(),
                        values = values.mapValues { it.value.trim() }
                            .filterValues { it.isNotEmpty() },
                        instrumentTag = instrumentTag.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            },
            enabled = canSubmit,
        ) {
            Text("إرسال نتيجة الاختبار")
        }
        Text("أي Retest جديد بيتسجل كسجل جديد؛ النتيجة القديمة لا تتعدل.")
    }
}
