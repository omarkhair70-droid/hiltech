package com.hiltech.shared.core.executive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutiveVisiblePulseTest {
    private val projectRef = ExecutiveSourceReferenceDto(
        module = "PROJECTS",
        sourceType = "PROJECT",
        sourceId = "11111111-2222-4333-8444-555555555555",
        sourceVersion = 5,
        readPath = "/v1/projects/11111111-2222-4333-8444-555555555555",
    )

    private fun project(health: String) = ExecutiveProjectDto(
        projectId = projectRef.sourceId,
        projectCode = "PR-0001",
        name = "Visible project",
        lifecycleState = "ACTIVE",
        projectVersion = 5,
        projectUpdatedAt = "2026-11-23T00:00:00Z",
        healthState = health,
        reference = projectRef,
    )

    private fun snapshot(
        needs: ExecutiveSectionDto<ExecutiveDecisionDto> = ExecutiveSectionDto(
            "CURRENT", emptyList(), "INBOX_WORK_QUEUE",
        ),
        projects: ExecutiveSectionDto<ExecutiveProjectDto> = ExecutiveSectionDto(
            "PARTIAL", emptyList(), "PROJECTS_AND_PROJECT_HEALTH", partial = true,
        ),
        assets: ExecutiveSectionDto<ExecutiveAssetExceptionDto> = ExecutiveSectionDto(
            "CURRENT", emptyList(), "ASSET_SERVICE_QUEUE",
        ),
    ) = ExecutiveHomeDto(
        viewerIdentityId = "11111111-2222-4333-8444-555555555555",
        organizationId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        asOf = "2026-11-23T00:00:00Z",
        correlationId = "corr",
        needsYouScope = "CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS",
        needsYou = needs,
        projects = projects,
        assetExceptions = assets,
        pendingDomains = emptyList(),
    )

    @Test
    fun boundedEmptyProjectWindowDoesNotClaimCompanyHealth() {
        val pulse = executiveVisiblePulse(snapshot())
        assertEquals(ExecutivePulseState.UNVERIFIED, pulse[1].state)
        assertTrue(pulse[1].incomplete)
        assertEquals(ExecutivePulseState.WINDOW_EMPTY, pulse[0].state)
    }

    @Test
    fun aVisibleCriticalProjectStaysVisibleEvenWhenWindowPartial() {
        val pulse = executiveVisiblePulse(snapshot(
            projects = ExecutiveSectionDto(
                "PARTIAL", listOf(project("HEALTHY"), project("CRITICAL")),
                "PROJECTS_AND_PROJECT_HEALTH", partial = true,
            ),
        ))
        assertEquals(ExecutivePulseState.CRITICAL_VISIBLE, pulse[1].state)
        assertTrue(pulse[1].incomplete)
    }

    @Test
    fun unknownHealthAndFailedSourceMustNeverLookHealthy() {
        val unknown = executiveVisiblePulse(snapshot(
            projects = ExecutiveSectionDto(
                "CURRENT", listOf(project("UNKNOWN")), "PROJECTS_AND_PROJECT_HEALTH",
            ),
        ))
        assertEquals(ExecutivePulseState.UNVERIFIED, unknown[1].state)
        assertTrue(unknown[1].incomplete)

        val failed = executiveVisiblePulse(snapshot(
            projects = ExecutiveSectionDto(
                "UNAVAILABLE", emptyList(), "PROJECTS_AND_PROJECT_HEALTH",
            ),
        ))
        assertEquals(ExecutivePulseState.SOURCE_UNAVAILABLE, failed[1].state)
    }

    @Test
    fun inaccessibleAssetQueueDoesNotBecomeAnEmptyHealthyCompany() {
        val pulse = executiveVisiblePulse(snapshot(
            assets = ExecutiveSectionDto(
                "PARTIAL", emptyList(), "ASSET_SERVICE_QUEUE",
                partial = true,
                unavailableReason = "SOME_WAREHOUSE_QUEUES_UNAVAILABLE",
            ),
        ))
        assertEquals(ExecutivePulseState.UNVERIFIED, pulse[2].state)
        assertTrue(pulse[2].incomplete)
    }

    @Test
    fun actorWideDecisionWithNextCursorIsAttentionNotCompanyTotal() {
        val decision = ExecutiveDecisionDto(
            safeTitleCode = "APPROVAL_REQUIRED",
            attentionClass = "ACTION_REQUIRED",
            updatedAt = "2026-11-23T00:00:00Z",
            reference = ExecutiveSourceReferenceDto(
                module = "INBOX",
                sourceType = "APPROVAL_REQUEST",
                sourceId = projectRef.sourceId,
                readPath = "/v1/approvals/${projectRef.sourceId}",
            ),
        )
        val pulse = executiveVisiblePulse(snapshot(
            needs = ExecutiveSectionDto(
                "PARTIAL", listOf(decision), "INBOX_WORK_QUEUE",
                partial = true, nextCursor = "signed-cursor",
            ),
        ))
        assertEquals(ExecutivePulseState.ATTENTION_VISIBLE, pulse[0].state)
        assertTrue(pulse[0].incomplete)
    }
}
