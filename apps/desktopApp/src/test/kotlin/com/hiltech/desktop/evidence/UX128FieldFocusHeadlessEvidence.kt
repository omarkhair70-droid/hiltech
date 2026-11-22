package com.hiltech.desktop.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.AssignedWorkSection
import com.hiltech.shared.core.FieldWorkPresentation
import com.hiltech.shared.core.HiltechFieldState
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldBlockerDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldEvidenceRequirementDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldReadinessRequirementDto
import com.hiltech.shared.core.work.FieldResourceRequirementDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldTodayItemDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.FieldInstructionDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * UX128: actual source AssignedWorkSection with the original Android debug
 * evidence fixtures copied below verbatim for the isolated Desktop test runner.
 * No production APIs, credentials, mutations, Android device, AWT Robot,
 * or invented task/approval authority.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking<Unit> {
    val dir = Path.of(
        System.getenv("HILTECH_UX128_FIELD_EVIDENCE_DIR") ?: "build/reports/ux128-field-focus",
    )
    Files.createDirectories(dir)
    var fieldState by androidx.compose.runtime.mutableStateOf(fixtureState("today"))
    val selected = mutableListOf<String>()
    val selectedScene = ImageComposeScene(width = 430, height = 960, coroutineContext = coroutineContext) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
                AssignedWorkSection(
                    state = fieldState,
                    onRefresh = {},
                    onSelectWork = { id ->
                        selected += id
                        fieldState = fixtureState("detail")
                    },
                    presentation = FieldWorkPresentation.FOCUSED_COMPACT,
                    focusScopeKey = "synthetic-actor:synthetic-org:warehouse-job-study",
                )
            }
        }
    }
    try {
        save(selectedScene, dir.resolve("ux128-field-00-today-phone.png"), 430, 960)
        // Native Compose pointer press/release, not a preselected static render.
        selectedScene.sendPointerEvent(PointerEventType.Press, Offset(187f, 148f))
        selectedScene.sendPointerEvent(PointerEventType.Release, Offset(187f, 148f))
        save(selectedScene, dir.resolve("ux128-field-01-focused-phone.png"), 430, 960)
        check(selected == listOf(WORK_ORDER_ID)) {
            "A real Today card pointer click must call the original onSelectWork with the exact source ID: $selected"
        }
        // Read only when cached: the exact JobBundle is preserved and authority
        // to execute is still disabled by the existing source component.
        fieldState = fixtureState("cached")
        save(selectedScene, dir.resolve("ux128-field-02-cached-phone.png"), 430, 960)
        selectedScene.sendPointerEvent(PointerEventType.Press, Offset(101f, 40f))
        selectedScene.sendPointerEvent(PointerEventType.Release, Offset(101f, 40f))
        save(selectedScene, dir.resolve("ux128-field-03-return-phone.png"), 430, 960)
        // If back did not restore the list, the same pointer cannot reselect.
        selectedScene.sendPointerEvent(PointerEventType.Press, Offset(187f, 148f))
        selectedScene.sendPointerEvent(PointerEventType.Release, Offset(187f, 148f))
        check(selected.size == 2) { "Back must restore Today and permit same WorkOrder selection." }
        fieldState = fixtureState("revoked")
        save(selectedScene, dir.resolve("ux128-field-04-revoked-phone.png"), 430, 960)
    } finally {
        selectedScene.close()
    }

    val wide = ImageComposeScene(width = 1150, height = 980, coroutineContext = coroutineContext) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
                AssignedWorkSection(
                    state = fixtureState("detail"),
                    onRefresh = {},
                    onSelectWork = {},
                    presentation = FieldWorkPresentation.FOCUSED_COMPACT,
                    focusScopeKey = "synthetic-actor:synthetic-org:warehouse-job-study",
                )
            }
        }
    }
    try {
        save(wide, dir.resolve("ux128-field-05-wide-preserved.png"), 1150, 980)
    } finally {
        wide.close()
    }
    println(
        "UX128_FIELD_FOCUS_PASS selected_source=PASS back_reselect=PASS cached_read_only_source=PASS " +
        "revoked_hides_detail=INSPECT_IMAGE wide_layout=INSPECT_IMAGE no_robot=true synthetic_fixture=true",
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun save(scene: ImageComposeScene, path: Path, width: Int, height: Int) {
    scene.render().use { rendered ->
        check(rendered.width == width && rendered.height == height)
        requireNotNull(rendered.encodeToData(EncodedImageFormat.PNG)).use {
            check(it.bytes.size > 4000) { "Suspicious blank Compose screenshot: $path" }
            Files.write(path, it.bytes)
        }
    }
}

// Original Phase4/Phase6 Android debug read fixtures, not production employee data.
// Source: apps/androidApp/src/debug/kotlin/com/hiltech/android/AssignedWorkEvidenceActivity.kt
private fun fixtureState(
    mode: String,
): HiltechFieldState =
    when (mode) {
        "revoked" ->
            HiltechFieldState(
                loading = false,
                today = emptyList(),
                selectedWorkOrderId = null,
                selectedBundle = null,
            )

        "cached" ->
            HiltechFieldState(
                loading = false,
                today = listOf(todayItem(actionable = false)),
                selectedWorkOrderId = WORK_ORDER_ID,
                selectedBundle = jobBundle(),
                cachedTodayAt = 1_700_000_000_000L,
                cachedBundleAt = 1_700_000_000_100L,
            )

        "detail" ->
            HiltechFieldState(
                loading = false,
                today =
                    listOf(
                        todayItem(
                            actionable = false,
                        ),
                    ),
                selectedWorkOrderId =
                    WORK_ORDER_ID,
                selectedBundle =
                    jobBundle(),
            )

        else ->
            HiltechFieldState(
                loading = false,
                today =
                    listOf(
                        todayItem(
                            actionable = true,
                        ),
                    ),
                selectedWorkOrderId = null,
                selectedBundle = null,
            )
    }

private fun todayItem(
    actionable: Boolean,
) =
    FieldTodayItemDto(
        workOrderId = WORK_ORDER_ID,
        workOrderCode = "WO-2026-010",
        title = "تركيب راك الموقع",
        lifecycleState = "ASSIGNED",
        readinessState =
            if (actionable) {
                "READY"
            } else {
                "BLOCKED"
            },
        plannedStart =
            "2026-09-21T07:00:00Z",
        plannedEnd =
            "2026-09-21T10:00:00Z",
        priorityCode = "HIGH",
        project = project(),
        site = site(),
        area = null,
        assignment = assignment(),
        waitingReasonCodes =
            if (actionable) {
                emptyList()
            } else {
                listOf(
                    "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                )
            },
        importantBlocker =
            if (actionable) {
                null
            } else {
                "في انتظار مصدر مواد موثوق من Phase 5"
            },
        instructionRevision = 2,
        workOrderVersion = 8,
        bundleAsOf =
            "2026-09-21T06:30:00Z",
        currentlyActionable =
            actionable,
    )

private fun jobBundle() =
    TechnicianJobBundleDto(
        workOrderId = WORK_ORDER_ID,
        workOrderCode = "WO-2026-010",
        title = "تركيب راك الموقع",
        description =
            "نفّذ حسب تعليمات الموقع الحالية. الشاشة دي للقراءة فقط.",
        lifecycleState = "ASSIGNED",
        readinessState = "BLOCKED",
        workOrderVersion = 8,
        project = project(),
        site = site(),
        area = null,
        assignment = assignment(),
        binding =
            FieldWorkBindingDto(
                bindingRevision = 3,
                workType =
                    config(
                        "RACK-INSTALL",
                        "Rack installation",
                        4,
                    ),
                assignmentPolicy =
                    config(
                        "FIELD-ASSIGN",
                        "Field assignment",
                        2,
                    ),
                readinessPolicy =
                    config(
                        "FIELD-READY",
                        "Field readiness",
                        3,
                    ),
                evidencePolicy =
                    config(
                        "FIELD-EVIDENCE",
                        "Field evidence",
                        2,
                    ),
                reviewPolicy =
                    config(
                        "TECH-REVIEW",
                        "Technical review",
                        1,
                    ),
                trackingPolicy = null,
                checklistTemplate = null,
                instructionTemplate =
                    config(
                        "RACK-INSTRUCTION",
                        "Rack instruction",
                        2,
                    ),
            ),
        instruction =
            FieldInstructionDto(
                revision = 2,
                payloadSchemaVersion = 1,
                structuredPayloadJson =
                    """{"steps":["Verify rack position","Confirm grounding"]}""",
                summary =
                    "راجع مكان الراك والتأريض قبل أي تنفيذ.",
                sourceTemplate =
                    config(
                        "RACK-INSTRUCTION",
                        "Rack instruction",
                        2,
                    ),
            ),
        tasks = emptyList(),
        checklist = emptyList(),
        readinessRequirements =
            listOf(
                FieldReadinessRequirementDto(
                    requirementId =
                        REQUIREMENT_ID,
                    family = "MATERIAL",
                    key = "FIBER-PATCH-CORD",
                    typeCode = "MATERIAL",
                    label =
                        "Fiber patch cord",
                    required = true,
                    satisfactionState =
                        "BLOCKED",
                    evaluationReasonCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    waived = false,
                    version = 2,
                ),
            ),
        evidenceRequirements =
            listOf(
                FieldEvidenceRequirementDto(
                    requirementKey =
                        "INSTALLATION-PHOTO",
                    evidenceTypeCode = "PHOTO",
                    stage = "BEFORE_SUBMIT",
                    minCount = 1,
                    maxCount = 4,
                    offlineCaptureAllowedByPolicy =
                        true,
                    allowedContentTypes =
                        listOf(
                            "image/jpeg",
                        ),
                ),
            ),
        existingEvidence = emptyList(),
        resourceRequirements =
            listOf(
                FieldResourceRequirementDto(
                    requirementId =
                        REQUIREMENT_ID,
                    family = "MATERIAL",
                    key = "FIBER-PATCH-CORD",
                    typeCode = "MATERIAL",
                    required = true,
                    state = "BLOCKED",
                    integrationState =
                        "SOURCE_PENDING_PHASE5",
                    reasonCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    version = 2,
                ),
            ),
        safeDocumentRefs = emptyList(),
        blockers =
            listOf(
                FieldBlockerDto(
                    blockerId =
                        BLOCKER_ID,
                    requirementId =
                        REQUIREMENT_ID,
                    blockerTypeCode =
                        "READINESS_REQUIREMENT",
                    explanationCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    description =
                        "في انتظار مصدر مواد موثوق من Phase 5",
                    state = "OPEN",
                    version = 1,
                ),
            ),
        waitingReasonCodes =
            listOf(
                "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
            ),
        executionCapabilities =
            FieldExecutionCapabilitiesDto(
                startOffline = false,
                blockOffline = false,
                resumeOffline = false,
                evidenceCaptureOffline = false,
                submitOffline = false,
                authoritativeOfflineQueue =
                    false,
            ),
        asOf = "2026-09-21T06:30:00Z",
    )

private fun project() =
    FieldProjectDto(
        projectId = PROJECT_ID,
        projectCode = "P-010",
        name = "Beni Suef rollout",
        lifecycleState = "ACTIVE",
        baselineVersion = 2,
    )

private fun site() =
    FieldSiteDto(
        siteId = SITE_ID,
        siteCode = "BS-01",
        name = "موقع بني سويف 01",
        projectSiteId =
            PROJECT_SITE_ID,
        projectSiteCode = "PS-01",
        lifecycleState = "ACTIVE",
        accessInstructions =
            "اتصل بمسؤول الموقع قبل الدخول.",
        timezone = "Africa/Cairo",
    )

private fun assignment() =
    FieldAssignmentDto(
        assignmentId =
            ASSIGNMENT_ID,
        targetType = "USER",
        targetId = USER_ID,
        targetLabel = "الفني الحالي",
        validFrom =
            "2026-09-21T06:00:00Z",
        validUntil = null,
        version = 1,
    )

private fun config(
    code: String,
    name: String,
    revision: Int,
) =
    FieldConfigRefDto(
        id =
            java.util.UUID
                .nameUUIDFromBytes(
                    code.toByteArray(),
                )
                .toString(),
        code = code,
        name = name,
        revision = revision,
    )

private const val WORK_ORDER_ID =
    "11111111-1111-4111-8111-111111111111"
private const val PROJECT_ID =
    "22222222-2222-4222-8222-222222222222"
private const val SITE_ID =
    "33333333-3333-4333-8333-333333333333"
private const val PROJECT_SITE_ID =
    "44444444-4444-4444-8444-444444444444"
private const val ASSIGNMENT_ID =
    "55555555-5555-4555-8555-555555555555"
private const val USER_ID =
    "66666666-6666-4666-8666-666666666666"
private const val REQUIREMENT_ID =
    "77777777-7777-4777-8777-777777777777"
private const val BLOCKER_ID =
    "88888888-8888-4888-8888-888888888888"

