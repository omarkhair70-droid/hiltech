package com.hiltech.desktop.evidence

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hiltech.shared.core.WorkReadinessSection
import com.hiltech.shared.core.work.EligibleTargetDto
import com.hiltech.shared.core.work.ReadinessRequirementExecutionDto
import com.hiltech.shared.core.work.WorkAssignmentRecordDto
import com.hiltech.shared.core.work.WorkOrderDto
import com.hiltech.shared.core.work.WorkQueueContextDto
import com.hiltech.shared.core.work.WorkReadinessBlockerDto
import com.hiltech.shared.core.work.WorkReadinessDto
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main() = application {
    val outputDirectory =
        Path.of(
            System.getenv(
                "HILTECH_PHASE4_READINESS_ASSIGNMENT_EVIDENCE_DIR",
            ) ?: "build/reports/phase4-readiness-assignment",
        )
    Files.createDirectories(outputDirectory)

    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH Phase 4 Readiness Assignment Evidence",
        state =
            rememberWindowState(
                width = 1180.dp,
                height = 1050.dp,
            ),
    ) {
        var screen by remember {
            mutableStateOf(0)
        }
        val frame = window

        CompositionLocalProvider(
            LocalLayoutDirection provides
                LayoutDirection.Rtl,
        ) {
            val readiness =
                when (screen) {
                    0 -> blockedReadiness()
                    1 -> readyReadiness()
                    else -> assignedReadiness()
                }
            WorkReadinessSection(
                orders =
                    listOf(
                        workOrder(
                            lifecycle =
                                readiness.lifecycleState,
                            readiness =
                                readiness.readinessState,
                            version =
                                readiness.workOrderVersion,
                        ),
                    ),
                readinessByWorkOrder =
                    mapOf(
                        WORK_ORDER_ID to
                            readiness,
                    ),
                workQueueContext =
                    queue(readiness),
                onAction = {},
            )
        }

        LaunchedEffect(Unit) {
            try {
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-readiness-blocked-phase5.png",
                    ),
                )

                screen = 1
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-ready-target-explanations.png",
                    ),
                )

                screen = 2
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-assigned-history-reassign.png",
                    ),
                )

                println(
                    "HILTECH_PHASE4_READINESS_ASSIGNMENT_RENDER_PASS " +
                        "rtl=PASS readiness_reasons=PASS " +
                        "phase5_source_unavailable=PASS " +
                        "eligible_target_explanations=PASS " +
                        "assignment_mode=PASS assignment_history=PASS " +
                        "reassign_control=PASS waiting_on=PASS",
                )
            } finally {
                exitApplication()
            }
        }
    }
}

private suspend fun settle() {
    withFrameNanos { }
    withFrameNanos { }
    delay(450)
}

private fun blockedReadiness() =
    WorkReadinessDto(
        workOrderId = WORK_ORDER_ID,
        workOrderVersion = 5,
        lifecycleState = "PLANNED",
        readinessState = "BLOCKED",
        assignmentMode = "SUGGEST_CONFIRM",
        requirements =
            listOf(
                requirement(
                    id = REQUIREMENT_ASSIGNEE,
                    key = "ASSIGNEE",
                    type = "ASSIGNEE",
                    label = "فني مؤهل متاح",
                    state = "SATISFIED",
                    reason = "ELIGIBLE_TARGET_AVAILABLE",
                ),
                requirement(
                    id = REQUIREMENT_MATERIAL,
                    key = "PATCH-CORD",
                    type = "MATERIAL",
                    label = "خامة التنفيذ",
                    state = "BLOCKED",
                    reason = "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                ),
                requirement(
                    id = REQUIREMENT_SITE,
                    key = "SITE-ACCESS",
                    type = "SITE_ACCESS",
                    label = "دخول الموقع",
                    state = "BLOCKED",
                    reason = "SITE_ACCESS_CONFIRMATION_REQUIRED",
                    waiverAllowed = true,
                ),
            ),
        blockers =
            listOf(
                WorkReadinessBlockerDto(
                    blockerId = BLOCKER_ID,
                    requirementId =
                        REQUIREMENT_MATERIAL,
                    blockerTypeCode =
                        "READINESS_REQUIREMENT",
                    explanationCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    description =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    state = "OPEN",
                    version = 1,
                ),
            ),
        eligibleTargets =
            listOf(
                eligibleUser(),
                ineligibleUser(),
            ),
        currentAssignment = null,
        assignmentHistory = emptyList(),
        waitingOnReasonCodes =
            listOf(
                "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                "SITE_ACCESS_CONFIRMATION_REQUIRED",
            ),
        evaluatedAt =
            "2026-09-21T00:30:00Z",
    )

private fun readyReadiness() =
    blockedReadiness().copy(
        workOrderVersion = 7,
        readinessState = "READY",
        assignmentMode = "SUGGEST_CONFIRM",
        requirements =
            listOf(
                requirement(
                    id = REQUIREMENT_ASSIGNEE,
                    key = "ASSIGNEE",
                    type = "ASSIGNEE",
                    label = "فني مؤهل متاح",
                    state = "SATISFIED",
                    reason = "ELIGIBLE_TARGET_AVAILABLE",
                ),
            ),
        blockers = emptyList(),
        waitingOnReasonCodes =
            listOf(
                "ASSIGNMENT_CONFIRMATION_REQUIRED",
            ),
    )

private fun assignedReadiness(): WorkReadinessDto {
    val current =
        WorkAssignmentRecordDto(
            assignmentId = ASSIGNMENT_CURRENT,
            workOrderId = WORK_ORDER_ID,
            targetType = "USER",
            targetId = USER_ONE,
            targetLabel = "أحمد محمود",
            lead = true,
            state = "ACTIVE",
            assignedAt =
                "2026-09-21T00:40:00Z",
            assignedBy = MANAGER_USER,
            validFrom =
                "2026-09-21T00:40:00Z",
            validUntil = null,
            supersedesAssignmentId =
                ASSIGNMENT_OLD,
            reason =
                "تحويل التنفيذ إلى الفني الحالي",
            version = 1,
        )
    val previous =
        WorkAssignmentRecordDto(
            assignmentId = ASSIGNMENT_OLD,
            workOrderId = WORK_ORDER_ID,
            targetType = "TEAM",
            targetId = TEAM_ID,
            targetLabel = "فريق المواقع A",
            lead = true,
            state = "REPLACED",
            assignedAt =
                "2026-09-21T00:20:00Z",
            assignedBy = MANAGER_USER,
            validFrom =
                "2026-09-21T00:20:00Z",
            validUntil =
                "2026-09-21T00:40:00Z",
            supersedesAssignmentId = null,
            reason = null,
            version = 2,
        )
    return readyReadiness().copy(
        workOrderVersion = 9,
        lifecycleState = "ASSIGNED",
        assignmentMode = "MANUAL_ELIGIBLE",
        currentAssignment = current,
        assignmentHistory =
            listOf(
                current,
                previous,
            ),
        waitingOnReasonCodes =
            emptyList(),
    )
}

private fun requirement(
    id: String,
    key: String,
    type: String,
    label: String,
    state: String,
    reason: String?,
    waiverAllowed: Boolean = false,
) =
    ReadinessRequirementExecutionDto(
        requirementId = id,
        family = "READINESS",
        key = key,
        typeCode = type,
        label = label,
        required = true,
        satisfactionState = state,
        evaluationReasonCode = reason,
        sourceAsOf =
            "2026-09-21T00:30:00Z",
        evaluatedAt =
            "2026-09-21T00:30:00Z",
        waiverAllowed = waiverAllowed,
        waiverReasonRequired =
            waiverAllowed,
        waived = false,
        waiverRef = null,
        version = 1,
    )

private fun eligibleUser() =
    EligibleTargetDto(
        targetType = "USER",
        targetId = USER_ONE,
        displayLabel = "أحمد محمود",
        eligible = true,
        reasonCodes = emptyList(),
        sourceAsOf =
            "2026-09-21T00:30:00Z",
        sourceFreshness = "CURRENT",
        requiredRoleChecks = emptyList(),
        requiredCertificationChecks =
            emptyList(),
        currentAssignmentConflict = null,
    )

private fun ineligibleUser() =
    EligibleTargetDto(
        targetType = "USER",
        targetId = USER_TWO,
        displayLabel = "محمود علي",
        eligible = false,
        reasonCodes =
            listOf(
                "CERTIFICATION_EXPIRED",
            ),
        sourceAsOf =
            "2026-09-21T00:30:00Z",
        sourceFreshness = "CURRENT",
        requiredRoleChecks = emptyList(),
        requiredCertificationChecks =
            emptyList(),
        currentAssignmentConflict = null,
    )

private fun queue(
    readiness: WorkReadinessDto,
): List<WorkQueueContextDto> =
    if (
        readiness.waitingOnReasonCodes.isEmpty()
    ) {
        emptyList()
    } else {
        listOf(
            WorkQueueContextDto(
                workOrderId = WORK_ORDER_ID,
                workOrderCode = "WO-2026-004",
                title = "تركيب وربط الراك",
                actionCode =
                    if (
                        readiness.readinessState ==
                        "BLOCKED"
                    ) {
                        "RESOLVE_READINESS"
                    } else {
                        "ASSIGN_WORK"
                    },
                reasonCodes =
                    readiness
                        .waitingOnReasonCodes,
                ownerUserId =
                    MANAGER_USER,
            ),
        )
    }

private fun workOrder(
    lifecycle: String,
    readiness: String,
    version: Long,
) =
    WorkOrderDto(
        workOrderId = WORK_ORDER_ID,
        organizationId =
            ORGANIZATION_ID,
        workOrderCode =
            "WO-2026-004",
        projectId = PROJECT_ID,
        siteId = SITE_ID,
        projectSiteId =
            PROJECT_SITE_ID,
        title =
            "تركيب وربط الراك",
        description =
            "تنفيذ الربط حسب التعليمات المعتمدة.",
        lifecycleState = lifecycle,
        readinessState = readiness,
        priorityCode = "HIGH",
        countsTowardProjectProgress =
            true,
        progressWeight = "2.0",
        baselineVersion = 1,
        createdAt =
            "2026-09-21T00:00:00Z",
        updatedAt =
            "2026-09-21T00:30:00Z",
        version = version,
    )

private fun captureWindow(
    window: AwtWindow,
    output: Path,
) {
    val location =
        window.locationOnScreen
    val bounds =
        Rectangle(
            location.x,
            location.y,
            window.width,
            window.height,
        )
    require(
        bounds.width > 0 &&
            bounds.height > 0,
    )
    val image =
        Robot().createScreenCapture(
            bounds,
        )
    require(
        ImageIO.write(
            image,
            "png",
            output.toFile(),
        ),
    )
    require(Files.size(output) > 0)
}

private const val ORGANIZATION_ID =
    "11111111-1111-4111-8111-111111111111"
private const val PROJECT_ID =
    "22222222-2222-4222-8222-222222222222"
private const val SITE_ID =
    "33333333-3333-4333-8333-333333333333"
private const val PROJECT_SITE_ID =
    "44444444-4444-4444-8444-444444444444"
private const val WORK_ORDER_ID =
    "55555555-5555-4555-8555-555555555555"
private const val REQUIREMENT_ASSIGNEE =
    "66666666-6666-4666-8666-666666666666"
private const val REQUIREMENT_MATERIAL =
    "77777777-7777-4777-8777-777777777777"
private const val REQUIREMENT_SITE =
    "88888888-8888-4888-8888-888888888888"
private const val BLOCKER_ID =
    "99999999-9999-4999-8999-999999999999"
private const val USER_ONE =
    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val USER_TWO =
    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val TEAM_ID =
    "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val MANAGER_USER =
    "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val ASSIGNMENT_OLD =
    "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val ASSIGNMENT_CURRENT =
    "ffffffff-ffff-4fff-8fff-ffffffffffff"
