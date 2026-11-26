package com.hiltech.shared.core.executive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ExecutiveOperationalBriefTest {
    private val actor = "11111111-2222-4333-8444-555555555555"
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val projectId = "11111111-2222-4333-8444-555555555555"
    private val signalId = "99999999-1111-4111-8111-111111111111"

    private fun project(state: String, index: Int = 1, signal: Boolean = false) = ExecutiveProjectDto(
        projectId = projectId,
        projectCode = "P-$index",
        name = "Project $index",
        lifecycleState = "ACTIVE",
        projectVersion = 12,
        projectUpdatedAt = "2026-11-23T02:00:00Z",
        healthState = state,
        healthAsOf = "2026-11-23T02:01:00Z",
        signals = if (signal) listOf(
            ExecutiveHealthSignalDto(
                code = "BLOCKED_WORK",
                severity = "CRITICAL",
                summary = "Source blocker",
                lastObservedAt = "2026-11-23T02:01:00Z",
                reference = ExecutiveSourceReferenceDto(
                    "PROJECTS", "WORK_ORDER", signalId, 4, "/v1/projects/$projectId/health",
                ),
            ),
        ) else emptyList(),
        reference = ExecutiveSourceReferenceDto(
            "PROJECTS", "PROJECT", projectId, 12, "/v1/projects/$projectId",
        ),
    )

    private fun asset(index: Int) = ExecutiveAssetExceptionDto(
        warehouseId = org,
        assetId = projectId,
        assetCode = "AS-$index",
        availabilityState = "BLOCKED",
        calibrationStatus = "DUE",
        maintenanceStatus = "IN_PROGRESS",
        evaluatedAt = "2026-11-23T02:02:00Z",
        reference = ExecutiveSourceReferenceDto(
            "WAREHOUSE", "ASSET", projectId, 7, "/v1/assets/$projectId/availability",
        ),
    )

    private fun decision(index: Int) = ExecutiveDecisionDto(
        safeTitleCode = "APPROVAL_$index",
        safeSummary = "Requires owner decision",
        attentionClass = "ACTION_REQUIRED",
        updatedAt = "2026-11-23T02:03:00Z",
        reference = ExecutiveSourceReferenceDto(
            "INBOX", "APPROVAL_REQUEST", projectId, 5, "/v1/approvals/$projectId",
        ),
    )

    private fun snapshot(
        needs: ExecutiveSectionDto<ExecutiveDecisionDto> = ExecutiveSectionDto(
            "CURRENT", emptyList(), "INBOX_WORK_QUEUE", partial = false,
        ),
        projects: ExecutiveSectionDto<ExecutiveProjectDto> = ExecutiveSectionDto(
            "PARTIAL", emptyList(), "PROJECTS_AND_PROJECT_HEALTH", partial = true,
        ),
        assets: ExecutiveSectionDto<ExecutiveAssetExceptionDto> = ExecutiveSectionDto(
            "CURRENT", emptyList(), "ASSET_SERVICE_QUEUE", partial = false,
        ),
    ) = ExecutiveHomeDto(
        viewerIdentityId = actor,
        organizationId = org,
        asOf = "2026-11-23T02:04:00Z",
        correlationId = "correlation",
        needsYouScope = "CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS",
        needsYou = needs,
        projects = projects,
        assetExceptions = assets,
        pendingDomains = emptyList(),
    )

    @Test
    fun criticalProjectIsFirstAndSignalNavigationStaysOnItsProject() {
        val brief = buildExecutiveOperationalBrief(snapshot(
            projects = ExecutiveSectionDto(
                "PARTIAL", listOf(project("ATTENTION", 1), project("HEALTHY", 2),
                    project("CRITICAL", 3, signal = true), project("UNKNOWN", 4)),
                "PROJECTS_AND_PROJECT_HEALTH", partial = true,
            ),
        ))
        val lane = brief.lanes[1]
        assertEquals(ExecutiveBriefKind.PROJECT_RISK, lane.kind)
        assertEquals(listOf("P-3", "P-1", "P-4"), lane.entries.map { it.title.substringBefore(" · ") })
        assertEquals(ExecutiveBriefPriority.SOURCE_CRITICAL, lane.entries.first().priority)
        assertTrue(lane.entries.first().detail.contains("BLOCKED_WORK"))
        assertTrue(lane.entries.first().detail.contains("Source blocker"))
        assertEquals("2026-11-23T02:01:00Z", lane.entries.first().observedAt)
        assertEquals(ExecutiveDestination(
            ExecutiveDestinationKind.PROJECT_HEALTH, projectId,
        ), lane.entries.first().destination)
        assertTrue(lane.incomplete)
        assertFalse(lane.extraVisibleItems)
        assertEquals("2026-11-23T02:04:00Z", brief.assembledAt)
    }

    @Test
    fun boundedListHasExplicitOverflowWithoutInventingCompanyTotals() {
        val brief = buildExecutiveOperationalBrief(snapshot(
            needs = ExecutiveSectionDto(
                "PARTIAL", (1..7).map(::decision), "INBOX_WORK_QUEUE",
                partial = true, nextCursor = "opaque",
            ),
            assets = ExecutiveSectionDto(
                "CURRENT", (1..7).map(::asset), "ASSET_SERVICE_QUEUE", partial = false,
            ),
        ), maxPerLane = 2)
        assertEquals(2, brief.lanes[0].entries.size)
        assertEquals(2, brief.lanes[2].entries.size)
        assertTrue(brief.lanes[0].incomplete)
        assertTrue(brief.lanes[0].extraVisibleItems)
        assertTrue(brief.lanes[2].extraVisibleItems)
        assertFalse(brief.lanes[2].incomplete)
        assertEquals("CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS", brief.needsYouScope)
    }

    @Test
    fun assetReasonsAreQuotedFromActualSourceNotInferredFromStatus() {
        val withReasons = asset(1).copy(reasonCodes = listOf("CALIBRATION_OVERDUE"))
        val brief = buildExecutiveOperationalBrief(snapshot(
            assets = ExecutiveSectionDto(
                "CURRENT", listOf(withReasons), "ASSET_SERVICE_QUEUE", partial = false,
            ),
        ))
        assertTrue(brief.lanes[2].entries.single().detail.contains("CALIBRATION_OVERDUE"))
        assertEquals(ExecutiveBriefPriority.ACTION_VISIBLE, brief.lanes[2].entries.single().priority)
    }

    @Test
    fun sourceUnavailabilityCannotExposeCardsEvenWhenProducerResponseIsInconsistent() {
        val brief = buildExecutiveOperationalBrief(snapshot(
            needs = ExecutiveSectionDto(
                "UNAVAILABLE", listOf(decision(1)), "INBOX_WORK_QUEUE",
                partial = false, unavailableReason = "SOURCE_READ_FAILED_OR_NOT_AUTHORIZED",
            ),
            assets = ExecutiveSectionDto(
                "UNAVAILABLE", listOf(asset(1)), "ASSET_SERVICE_QUEUE",
                partial = false, unavailableReason = "SOURCE_READ_FAILED_OR_NOT_AUTHORIZED",
            ),
        ))
        assertTrue(brief.lanes[0].entries.isEmpty())
        assertTrue(brief.lanes[2].entries.isEmpty())
        assertTrue(brief.lanes[0].incomplete)
        assertEquals("SOURCE_READ_FAILED_OR_NOT_AUTHORIZED", brief.lanes[2].missingReason)
    }

    @Test
    fun healthyOnlyWindowIsNotMisrepresentedAsAnIncidentList() {
        val brief = buildExecutiveOperationalBrief(snapshot(
            projects = ExecutiveSectionDto(
                "PARTIAL", listOf(project("HEALTHY")), "PROJECTS_AND_PROJECT_HEALTH", partial = true,
            ),
        ))
        assertTrue(brief.lanes[1].entries.isEmpty())
        assertTrue(brief.lanes[1].incomplete)
    }

    @Test
    fun badSourceReferenceCannotProduceNavigationPrivilege() {
        val invalid = decision(1).copy(
            reference = ExecutiveSourceReferenceDto(
                "INBOX", "PAYROLL_RUN", projectId, 3, "/v1/approvals/$projectId",
            ),
        )
        val brief = buildExecutiveOperationalBrief(snapshot(
            needs = ExecutiveSectionDto(
                "CURRENT", listOf(invalid), "INBOX_WORK_QUEUE", partial = false,
            ),
        ))
        assertNull(brief.lanes[0].entries.single().destination)
    }

    @Test
    fun unknownProducerStatusAndMissingHealthTimestampFailClosed() {
        val brief = buildExecutiveOperationalBrief(snapshot(
            needs = ExecutiveSectionDto(
                "FUTURE_UNKNOWN_STATE", listOf(decision(1)), "INBOX_WORK_QUEUE",
                partial = false,
            ),
            projects = ExecutiveSectionDto(
                "PARTIAL", listOf(project("UNAVAILABLE").copy(healthAsOf = null)),
                "PROJECTS_AND_PROJECT_HEALTH", partial = true,
            ),
            assets = ExecutiveSectionDto(
                "FUTURE_UNKNOWN_STATE", listOf(asset(1)), "ASSET_SERVICE_QUEUE",
                partial = false,
            ),
        ))
        assertTrue(brief.lanes[0].entries.isEmpty())
        assertTrue(brief.lanes[2].entries.isEmpty())
        assertTrue(brief.lanes[0].incomplete)
        assertTrue(brief.lanes[2].incomplete)
        assertEquals(ExecutiveBriefPriority.UNVERIFIED, brief.lanes[1].entries.single().priority)
        // Project modification time cannot stand in for a source health evaluation.
        assertNull(brief.lanes[1].entries.single().observedAt)
    }

    @Test
    fun mismatchedSourceOrActorScopeCannotDisplaySensitiveCards() {
        val forgedSource = buildExecutiveOperationalBrief(snapshot(
            needs = ExecutiveSectionDto(
                "CURRENT", listOf(decision(1)), "PAYROLL_RUNS", partial = false,
            ),
            assets = ExecutiveSectionDto(
                "CURRENT", listOf(asset(1)), "FINANCE_LEDGER", partial = false,
            ),
        ))
        assertTrue(forgedSource.lanes[0].entries.isEmpty())
        assertTrue(forgedSource.lanes[2].entries.isEmpty())
        assertEquals("UNAVAILABLE", forgedSource.lanes[0].sourceStatus)
        assertEquals("UNAVAILABLE", forgedSource.lanes[2].sourceStatus)

        val unexpectedScope = buildExecutiveOperationalBrief(snapshot().copy(
            needsYouScope = "OTHER_ORG_WIDE",
            needsYou = ExecutiveSectionDto(
                "CURRENT", listOf(decision(1)), "INBOX_WORK_QUEUE", partial = false,
            ),
        ))
        assertTrue(unexpectedScope.lanes[0].entries.isEmpty())
        assertEquals("UNTRUSTED_SOURCE_OR_SCOPE", unexpectedScope.lanes[0].missingReason)
    }

    @Test
    fun briefCannotRequestUnboundedItems() {
        assertFailsWith<IllegalArgumentException> {
            buildExecutiveOperationalBrief(snapshot(), maxPerLane = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            buildExecutiveOperationalBrief(snapshot(), maxPerLane = 100)
        }
    }
}
