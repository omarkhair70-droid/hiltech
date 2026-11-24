package com.hiltech.shared.core

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.design.LocalHiltechLanguage
import com.hiltech.shared.core.design.hiltechText
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechSpace
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
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
    // Nonblank only for an authenticated identity + organization host scope.
    // Offline unverified identity keeps the original read-only presentation.
    focusScopeKey: String = "",
    onCompactFocusChanged: (Boolean) -> Unit = {},
    // The Android host increments this when System Back is pressed in focus.
    compactBackRequest: Int = 0,
    externalFocusId: String? = null,
    onExternalFocusConsumed: () -> Unit = {},
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides
            LocalHiltechLanguage.current.direction,
    ) {
        // A selected bundle is a source snapshot, never a grant to keep
        // showing an old WorkOrder after selection or assignment changes.
        val visibleBundle = state.selectedBundle?.takeIf { bundle ->
            fieldSourceAllowsSelectedBundle(
                selectedWorkOrderId = state.selectedWorkOrderId,
                bundleWorkOrderId = bundle.workOrderId,
                todayWorkOrderIds = state.today.map { it.workOrderId },
            )
        }
        val todayIds = state.today.map { it.workOrderId }
        var focusedWorkOrderId by remember(focusScopeKey) { mutableStateOf<String?>(null) }
        var returnAnchorId by remember(focusScopeKey) { mutableStateOf<String?>(null) }
        // The shared host owns scrolling. No nested scroll state is created.
        DisposableEffect(focusScopeKey) {
            onDispose { onCompactFocusChanged(false) }
        }
        LaunchedEffect(focusScopeKey, compactBackRequest) {
            if (compactBackRequest > 0 && focusScopeKey.isNotBlank() &&
                fieldFocusStillAssigned(focusedWorkOrderId, todayIds)
            ) {
                returnAnchorId = focusedWorkOrderId
                focusedWorkOrderId = null
            }
        }
        LaunchedEffect(focusScopeKey, focusedWorkOrderId, todayIds) {
            if (focusedWorkOrderId != null &&
                !fieldFocusStillAssigned(focusedWorkOrderId, todayIds)
            ) {
                focusedWorkOrderId = null
                returnAnchorId = null
            }
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val wide =
                maxWidth >= 760.dp
            LaunchedEffect(focusScopeKey, wide, externalFocusId, todayIds) {
                val requested = externalFocusId ?: return@LaunchedEffect
                if (!wide && focusScopeKey.isNotBlank() && fieldFocusStillAssigned(requested, todayIds)) {
                    focusedWorkOrderId = requested
                    returnAnchorId = null
                }
                onExternalFocusConsumed()
            }
            // Clear compact focus when the adaptive layout becomes wide;
            // rotating back must not revive a previous focused route.
            LaunchedEffect(focusScopeKey, wide) {
                if (wide && focusedWorkOrderId != null) {
                    focusedWorkOrderId = null
                    returnAnchorId = null
                }
            }
            LaunchedEffect(focusScopeKey, wide, focusedWorkOrderId, todayIds) {
                onCompactFocusChanged(
                    !wide && focusScopeKey.isNotBlank() &&
                        fieldFocusStillAssigned(focusedWorkOrderId, todayIds)
                )
            }
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
                    visibleBundle
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
            } else {
                val compactFocus = focusScopeKey.isNotBlank()
                val focusedId = focusedWorkOrderId?.takeIf {
                    fieldFocusStillAssigned(it, todayIds)
                }
                val focusHeaderRequester = remember(focusScopeKey) { BringIntoViewRequester() }
                val returnCardRequester = remember(focusScopeKey) { BringIntoViewRequester() }
                LaunchedEffect(focusScopeKey, focusedId) {
                    if (compactFocus && focusedId != null) {
                        withFrameNanos { }
                        focusHeaderRequester.bringIntoView()
                    }
                }
                LaunchedEffect(focusScopeKey, returnAnchorId, todayIds) {
                    val anchor = returnAnchorId ?: return@LaunchedEffect
                    if (anchor in todayIds) {
                        // Allow the Today cards to replace the detail before
                        // returning to the same WorkOrder even after reorder.
                        withFrameNanos { }
                        returnCardRequester.bringIntoView()
                    }
                    returnAnchorId = null
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!compactFocus || focusedId == null) {
                        TodayColumn(
                            state = state,
                            onRefresh = onRefresh,
                            onSelectWork = { id ->
                                if (compactFocus && fieldFocusStillAssigned(id, todayIds)) {
                                    focusedWorkOrderId = id
                                    returnAnchorId = null
                                    onCompactFocusChanged(true)
                                }
                                onSelectWork(id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            returnAnchorId = returnAnchorId,
                            returnCardRequester = returnCardRequester,
                        )
                    } else {
                        OutlinedButton(
                            modifier = Modifier.bringIntoViewRequester(focusHeaderRequester),
                            onClick = {
                                returnAnchorId = focusedId
                                focusedWorkOrderId = null
                                onCompactFocusChanged(false)
                            },
                        ) { Text(hiltechText("الرجوع لشغلي اليوم", "Back to my work")) }
                        if (visibleBundle?.workOrderId != focusedId) {
                            if (state.loading) {
                                Text(hiltechText("بنجيب تفاصيل التكليف الحالي…", "Loading assignment details…"))
                            } else {
                                HiltechStateTag(
                                    state.errorMessage
                                        ?: "التفاصيل الحالية غير متاحة. ارجع للتكليفات وحدّثها.",
                                    HiltechStateTone.ATTENTION,
                                )
                            }
                        }
                    }
                    val detailBundle = if (compactFocus) {
                        visibleBundle?.takeIf { it.workOrderId == focusedId }
                    } else {
                        visibleBundle
                    }
                    detailBundle
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
    returnAnchorId: String? = null,
    returnCardRequester: BringIntoViewRequester? = null,
) {
    HiltechOperationalTheme {
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(FieldAssignmentFilter.All) }
        LaunchedEffect(returnAnchorId) { if (returnAnchorId != null) { query = ""; filter = FieldAssignmentFilter.All } }
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(HiltechSpace.small),
        ) {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechSectionHeading(
                    index = "FIELD / TODAY",
                    title = hiltechText("شغلي اليوم", "My work today"),
                    subtitle = hiltechText("تكليفاتك الحالية ومواقعها وجاهزيتها.", "Your current assignments, sites and readiness."),
                )
                OutlinedButton(onClick = onRefresh) {
                    Text(hiltechText("تحديث التكليفات", "Refresh assignments"))
                }
                if (state.loading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HiltechSpace.xs)) {
                        CircularProgressIndicator()
                        Text(hiltechText("بنجيب آخر تكليفاتك…", "Loading current assignments…"))
                    }
                }
                if (state.cachedTodayAt != null) {
                    HiltechStateTag(
                        "نسخة محفوظة بدون اتصال — قد يكون التكليف أو الجاهزية اتغيروا.",
                        HiltechStateTone.ATTENTION,
                    )
                    Text("آخر وقت من السيرفر: " +
                        (state.today.firstOrNull()?.bundleAsOf ?: "غير متاح"))
                }
                state.errorMessage?.let { issue ->
                    HiltechStateTag(
                        "تعذر تحديث الشغل: " + issue,
                        HiltechStateTone.BLOCKED,
                    )
                }
                if (!state.loading && state.today.isEmpty()) {
                    Text(
                        "مفيش WorkOrder حالي متعيّن ليك. لو التكليف اتشال أو اتبدل، هيختفي هنا فورًا.",
                    )
                }
                if (state.selectedBundle != null &&
                    !fieldSourceAllowsSelectedBundle(
                        selectedWorkOrderId = state.selectedWorkOrderId,
                        bundleWorkOrderId = state.selectedBundle.workOrderId,
                        todayWorkOrderIds = state.today.map { it.workOrderId },
                    )
                ) {
                    HiltechStateTag(
                        "تفاصيل أمر العمل السابق اتخفت لأن التكليف الحالي أو الاختيار اتغير. حدّث التكليفات وافتح الشغل من جديد.",
                        HiltechStateTone.BLOCKED,
                    )
                }
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(hiltechText("ابحث في تكليفاتك", "Search assignments")) })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldAssignmentFilter.entries.forEach { item ->
                    FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(hiltechText(item.ar, item.en)) })
                }
            }
            val visible = state.today.filter { item ->
                val match = query.isBlank() || listOf(item.title, item.workOrderCode, item.project.projectCode, item.site.name).any { it.contains(query.trim(), true) }
                match && when (filter) {
                    FieldAssignmentFilter.All -> true
                    FieldAssignmentFilter.Ready -> item.currentlyActionable
                    FieldAssignmentFilter.Waiting -> !item.currentlyActionable
                }
            }
            if (visible.isEmpty() && state.today.isNotEmpty()) Text(hiltechText("لا توجد تكليفات مطابقة.", "No matching assignments."))
            visible.forEach { item ->
                TodayCard(
                    item = item,
                    modifier = if (returnAnchorId == item.workOrderId &&
                        returnCardRequester != null
                    ) Modifier.bringIntoViewRequester(returnCardRequester) else Modifier,
                    selected = state.selectedWorkOrderId == item.workOrderId,
                    onClick = { onSelectWork(item.workOrderId) },
                )
            }
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
    val colors = LocalHiltechOperationalColors.current
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(),
        color = if (selected) colors.selected else colors.surface, contentColor = colors.primaryText,
        shape = HiltechShape.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(HiltechSpace.xs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.workOrderCode, Modifier.weight(1f), color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                HiltechStateTag(if (item.currentlyActionable) hiltechText("جاهز للمراجعة", "Ready for review") else hiltechText("بانتظار متطلبات", "Waiting"),
                    if (item.currentlyActionable) HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION)
            }
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Text(item.site.name, style = MaterialTheme.typography.titleSmall)
            Text(item.project.projectCode + " · " + item.site.siteCode, color = colors.secondaryText)
            item.area?.let { Text(hiltechText("المنطقة: ", "Area: ") + it.label) }
            Text(item.lifecycleState + " · " + item.readinessState, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
            Text(item.assignment.targetLabel, style = MaterialTheme.typography.bodySmall)
            item.importantBlocker?.let { HiltechStateTag(hiltechText("في انتظار: ", "Waiting for: ") + it, HiltechStateTone.ATTENTION) }
            Text(if (selected) hiltechText("العمل المفتوح", "Open assignment") else hiltechText("عرض العمل", "View assignment"),
                color = colors.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private enum class FieldAssignmentFilter(val ar: String, val en: String) {
    All("الكل", "All"), Ready("الجاهز", "Ready"), Waiting("بانتظار", "Waiting"),
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
            hiltechText("تفاصيل الشغل", "Work details"),
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
                hiltechText("تسليم العمل للمراجعة", "Submit work for review"),
                style = MaterialTheme.typography.titleMedium,
            )
            val gate = bundle.completionGate
            if (!gate.satisfied) {
                Text(hiltechText("التسليم متوقف لحين استيفاء المتطلبات:", "Submission is waiting for requirements:"))
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
                        Text(hiltechText("إلغاء", "Cancel"))
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
                        Text(hiltechText("تأكيد التسليم للمراجعة", "Confirm submission for review"))
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
                    Text(hiltechText("تقديم العمل للمراجعة", "Submit for review"))
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

        FieldDocumentRegister(bundle, cached)

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


/** Read-only register: document IDs and evidence status are the exact source values.
 * A reference is not a downloadable document or a verified file. */
@Composable
private fun FieldDocumentRegister(bundle: TechnicianJobBundleDto, cached: Boolean) {
    val colors = LocalHiltechOperationalColors.current
    var query by remember(bundle.workOrderId) { mutableStateOf("") }
    var expanded by remember(bundle.workOrderId) { mutableStateOf(false) }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hiltechText("المستندات والأدلة", "Documents & evidence"), style = MaterialTheme.typography.titleLarge)
                Text(hiltechText("المراجع: ", "References: ") + bundle.safeDocumentRefs.size +
                    " · " + hiltechText("الأدلة: ", "Evidence: ") + bundle.existingEvidence.size,
                    color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) hiltechText("طي", "Collapse") else hiltechText("عرض", "View"))
            }
        }
        if (cached) Text(hiltechText("بيانات محفوظة؛ الإصدار والصلاحية الحالية غير مؤكدين.", "Cached data; current revision and access are unconfirmed."),
            color = colors.cautionText)
        if (expanded) {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(hiltechText("ابحث بالكود أو العنوان أو الإصدار", "Search code, title or revision")) })
            val term = query.trim()
            val documents = bundle.safeDocumentRefs.filter {
                term.isEmpty() || listOf(it.code, it.title, it.revision.orEmpty(), it.sourceType).any { value -> value.contains(term, true) }
            }
            if (documents.isEmpty()) Text(if (term.isEmpty())
                hiltechText("لا توجد مراجع مستندات متاحة من المصدر.", "No document references available from source.")
                else hiltechText("لا توجد مراجع مطابقة.", "No matching references."))
            documents.forEach { doc ->
                Surface(color = colors.surfaceElevated, shape = HiltechShape.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(doc.title, style = MaterialTheme.typography.titleMedium)
                        Text(doc.code, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                        Text(hiltechText("الإصدار: ", "Revision: ") + (doc.revision ?: hiltechText("غير متاح", "Unavailable")))
                        Text(hiltechText("المصدر: ", "Source: ") + doc.sourceType, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(hiltechText("أدلة التنفيذ المسجلة", "Recorded execution evidence"), style = MaterialTheme.typography.titleMedium)
            if (bundle.existingEvidence.isEmpty()) Text(hiltechText("لا توجد أدلة في البيانات المعروضة.", "No evidence in the visible data."))
            bundle.existingEvidence.forEach { evidence ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(evidence.evidenceTypeCode, style = MaterialTheme.typography.titleSmall)
                    Text(evidence.contentType + " · v" + evidence.version, color = colors.secondaryText)
                    Text(hiltechText("حالة التخزين: ", "Storage state: ") + evidence.storageState)
                    Text(evidence.capturedAt, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                }
            }
            Text(hiltechText("هذه بيانات تعريفية من المصدر وليست معاينة للملف نفسه.", "These are source metadata, not a preview of the file itself."),
                style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
        }
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
