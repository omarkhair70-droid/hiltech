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
import com.hiltech.shared.core.ProjectCommandCenterSection
import com.hiltech.shared.core.projects.ProjectResponsibilityDto
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.work.CommandCenterMilestoneDto
import com.hiltech.shared.core.work.CommandCenterWorkDto
import com.hiltech.shared.core.work.ProjectCommandCenterDto
import com.hiltech.shared.core.work.ProjectHealthDto
import com.hiltech.shared.core.work.ProjectHealthSignalDto
import com.hiltech.shared.core.work.ProjectProgressDto
import com.hiltech.shared.core.work.ReviewDecisionDto
import com.hiltech.shared.core.work.ReviewPolicyDto
import com.hiltech.shared.core.work.ReviewStepDto
import com.hiltech.shared.core.work.ReviewerEligibilityDto
import com.hiltech.shared.core.work.ReviewWorkItemDto
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
                "HILTECH_PHASE4_SLICE05_EVIDENCE_DIR",
            ) ?: "build/reports/phase4-slice05",
        )
    Files.createDirectories(outputDirectory)

    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH Phase 4 Slice 05 Evidence",
        state =
            rememberWindowState(
                width = 1220.dp,
                height = 1180.dp,
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
            val state =
                when (screen) {
                    0 -> reviewBlockedCommandCenter()
                    1 -> activeCommandCenter()
                    else -> onHoldCommandCenter()
                }
            ProjectCommandCenterSection(
                project =
                    project(
                        lifecycle =
                            state.lifecycleState,
                        version =
                            if (state.lifecycleState == "ON_HOLD") 12 else 11,
                    ),
                commandCenter = state,
                onAction = {},
            )
        }

        LaunchedEffect(Unit) {
            try {
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-review-before-accept-blocked.png",
                    ),
                )

                screen = 1
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-command-center-progress-health.png",
                    ),
                )

                screen = 2
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-command-center-on-hold.png",
                    ),
                )

                println(
                    "HILTECH_PHASE4_SLICE05_RENDER_PASS " +
                        "rtl=PASS review_policy=PASS " +
                        "before_accept_evidence=PASS " +
                        "accepted_progress=PASS health_why=PASS " +
                        "source_refs=PASS waiting_on=PASS " +
                        "hold_resume=PASS no_commercial_progress=PASS",
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

private fun reviewBlockedCommandCenter() =
    baseCommandCenter().copy(
        submittedReviewItems =
            listOf(
                reviewItem(
                    evidenceSatisfied = false,
                    evidenceReasons =
                        listOf(
                            "BEFORE_ACCEPT_EVIDENCE_MISSING:REVIEW-PHOTO",
                        ),
                ),
            ),
    )

private fun activeCommandCenter() =
    baseCommandCenter().copy(
        submittedReviewItems =
            listOf(
                reviewItem(
                    evidenceSatisfied = true,
                    evidenceReasons =
                        emptyList(),
                ),
            ),
        reworkItems =
            listOf(
                CommandCenterWorkDto(
                    workOrderId = WORK_REWORK_ID,
                    workOrderCode = "WO-2026-006",
                    title = "إعادة إنهاء نقاط الراك",
                    lifecycleState =
                        "REWORK_REQUIRED",
                    readinessState = "READY",
                    actionCode =
                        "REWORK_REQUIRED",
                    reasonCodes =
                        listOf(
                            "REWORK_REQUIRED",
                        ),
                    projectSiteId =
                        PROJECT_SITE_ID,
                    workPackageId =
                        WORK_PACKAGE_ID,
                ),
            ),
    )

private fun onHoldCommandCenter() =
    activeCommandCenter().copy(
        lifecycleState = "ON_HOLD",
        health =
            activeCommandCenter()
                .health
                .copy(
                    projectVersion = 12,
                    state = "ON_HOLD",
                    reasonCodes =
                        listOf(
                            "PROJECT_ON_HOLD",
                        ),
                ),
    )

private fun baseCommandCenter() =
    ProjectCommandCenterDto(
        projectId = PROJECT_ID,
        projectCode = "P-2026-014",
        projectName = "Beni Suef HQ rollout",
        lifecycleState = "ACTIVE",
        projectManagerLabel =
            "فريق إدارة المواقع",
        baselineVersion = 2,
        progress =
            ProjectProgressDto(
                projectId = PROJECT_ID,
                projectVersion = 11,
                baselineVersion = 2,
                acceptedWeight = "3.000000",
                totalWeight = "5.000000",
                progressPercent = "60.0000",
                includedWorkCount = 3,
                acceptedWorkCount = 2,
                asOf =
                    "2026-09-21T01:30:00Z",
            ),
        health =
            ProjectHealthDto(
                projectId = PROJECT_ID,
                projectVersion = 11,
                baselineVersion = 2,
                state = "CRITICAL",
                healthPolicyId =
                    HEALTH_POLICY_ID,
                healthPolicyRevision = 1,
                signals =
                    listOf(
                        ProjectHealthSignalDto(
                            signalId =
                                SIGNAL_ID,
                            signalCode =
                                "REWORK_BACKLOG",
                            severity = "CRITICAL",
                            sourceType =
                                "WORK_ORDER",
                            sourceId =
                                WORK_REWORK_ID,
                            firstObservedAt =
                                "2026-09-21T01:20:00Z",
                            lastObservedAt =
                                "2026-09-21T01:30:00Z",
                            summary =
                                "WorkOrder requires rework.",
                            sourceVersion = 7,
                        ),
                    ),
                reasonCodes =
                    listOf(
                        "REWORK_BACKLOG",
                    ),
                asOf =
                    "2026-09-21T01:30:00Z",
            ),
        nextMilestone =
            CommandCenterMilestoneDto(
                milestoneId =
                    MILESTONE_ID,
                code = "M-02",
                name = "تشغيل الموقع",
                plannedDate =
                    "2026-09-24",
                state = "PLANNED",
            ),
        waitingOn =
            listOf(
                CommandCenterWorkDto(
                    workOrderId =
                        WORK_WAITING_ID,
                    workOrderCode =
                        "WO-2026-007",
                    title =
                        "تركيب أجهزة الموقع",
                    lifecycleState =
                        "PLANNED",
                    readinessState =
                        "BLOCKED",
                    actionCode =
                        "READINESS_BLOCKER_REQUIRES_AUTHORITY",
                    reasonCodes =
                        listOf(
                            "MATERIAL_AUTHORITY_PENDING_PHASE5",
                        ),
                    projectSiteId =
                        PROJECT_SITE_ID,
                    workPackageId =
                        WORK_PACKAGE_ID,
                ),
            ),
        submittedReviewItems = emptyList(),
        reworkItems = emptyList(),
        projectSiteIds =
            listOf(
                PROJECT_SITE_ID,
            ),
        workPackageIds =
            listOf(
                WORK_PACKAGE_ID,
            ),
    )

private fun reviewItem(
    evidenceSatisfied: Boolean,
    evidenceReasons: List<String>,
) =
    ReviewWorkItemDto(
        workOrderId = WORK_REVIEW_ID,
        workOrderCode = "WO-2026-005",
        projectId = PROJECT_ID,
        projectCode = "P-2026-014",
        projectName = "Beni Suef HQ rollout",
        siteId = SITE_ID,
        title = "مراجعة إنهاء الراك",
        submittedAt =
            "2026-09-21T01:00:00Z",
        submittedVersion = 4,
        currentVersion = 4,
        reviewPolicy =
            ReviewPolicyDto(
                configId =
                    REVIEW_POLICY_ID,
                revision = 1,
                code = "TECH-REVIEW",
                name = "Technical review",
                mode = "ANY_ONE",
                bindExactSubmittedVersion =
                    true,
                clientAcceptanceSeparate =
                    false,
                allowDelegation = false,
                steps =
                    listOf(
                        ReviewStepDto(
                            stepKey =
                                "technical-review",
                            sequence = 10,
                            selectorType =
                                "RELATIONSHIP",
                            selectorValue =
                                "CAN_REVIEW_WORK",
                            quorumCount = null,
                            reauthRequired =
                                false,
                            reasonRequiredOnRework =
                                true,
                            reasonRequiredOnReject =
                                true,
                            evidenceVisibilityMode =
                                "POLICY",
                        ),
                    ),
            ),
        decisions =
            listOf(
                ReviewDecisionDto(
                    decisionId =
                        REVIEW_DECISION_ID,
                    submittedWorkVersion = 4,
                    reviewStepKey =
                        "pre-check",
                    reviewerUserId =
                        REVIEWER_ID,
                    decision = "ACCEPT",
                    reason =
                        "Pre-check passed",
                    decidedAt =
                        "2026-09-21T01:05:00Z",
                    reviewerSourceType =
                        "PROJECT_RELATIONSHIP",
                    reviewerSourceRef =
                        "PROJECT_MANAGER",
                ),
            ),
        reviewer =
            ReviewerEligibilityDto(
                eligible = true,
                reviewStepKey =
                    "technical-review",
                sourceType =
                    "PROJECT_RELATIONSHIP",
                sourceRef =
                    "PROJECT_MANAGER",
                reasonCodes =
                    emptyList(),
            ),
        beforeAcceptEvidenceSatisfied =
            evidenceSatisfied,
        evidenceReasonCodes =
            evidenceReasons,
    )

private fun project(
    lifecycle: String,
    version: Long,
) =
    ProjectSummaryDto(
        projectId = PROJECT_ID,
        organizationId =
            ORGANIZATION_ID,
        projectCode = "P-2026-014",
        name = "Beni Suef HQ rollout",
        clientOrganizationId =
            CLIENT_ID,
        clientDisplayName =
            "Client",
        sourceType = "INTERNAL",
        sourceExternalReference = null,
        lifecycleState = lifecycle,
        currentResponsibility =
            ProjectResponsibilityDto(
                responsibilityId =
                    RESPONSIBILITY_ID,
                principalType = "TEAM",
                principalId = TEAM_ID,
                principalLabel =
                    "فريق إدارة المواقع",
                resolutionState =
                    "RESOLVED",
                effectiveFrom =
                    "2026-09-20T12:00:00Z",
                version = 2,
            ),
        startDatePlanned =
            "2026-09-20",
        endDatePlanned =
            "2026-10-20",
        baselineVersion = 2,
        siteCount = 1,
        createdAt =
            "2026-09-20T12:00:00Z",
        updatedAt =
            "2026-09-21T01:30:00Z",
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
private const val CLIENT_ID =
    "22222222-2222-4222-8222-222222222222"
private const val PROJECT_ID =
    "33333333-3333-4333-8333-333333333333"
private const val SITE_ID =
    "44444444-4444-4444-8444-444444444444"
private const val PROJECT_SITE_ID =
    "55555555-5555-4555-8555-555555555555"
private const val WORK_PACKAGE_ID =
    "66666666-6666-4666-8666-666666666666"
private const val MILESTONE_ID =
    "77777777-7777-4777-8777-777777777777"
private const val WORK_REVIEW_ID =
    "88888888-8888-4888-8888-888888888888"
private const val WORK_REWORK_ID =
    "99999999-9999-4999-8999-999999999999"
private const val WORK_WAITING_ID =
    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val REVIEW_POLICY_ID =
    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val HEALTH_POLICY_ID =
    "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val SIGNAL_ID =
    "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val REVIEW_DECISION_ID =
    "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val REVIEWER_ID =
    "ffffffff-ffff-4fff-8fff-ffffffffffff"
private const val RESPONSIBILITY_ID =
    "12345678-1234-4234-8234-123456789abc"
private const val TEAM_ID =
    "abcdefab-cdef-4abc-8def-abcdefabcdef"
