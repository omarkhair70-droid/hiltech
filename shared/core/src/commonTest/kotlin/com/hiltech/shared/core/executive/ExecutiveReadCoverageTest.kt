package com.hiltech.shared.core.executive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutiveReadCoverageTest {
    private val id = "11111111-2222-4333-8444-555555555555"
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    private fun project(state: String, observed: String? = null) = ExecutiveProjectDto(
        projectId = id,
        projectCode = "P-1",
        name = "Source project",
        lifecycleState = "ACTIVE",
        projectVersion = 7,
        projectUpdatedAt = "2026-11-23T01:00:00Z",
        healthState = state,
        healthAsOf = observed,
        reference = ExecutiveSourceReferenceDto(
            module = "PROJECTS", sourceType = "PROJECT", sourceId = id,
            sourceVersion = 7, readPath = "/v1/projects/$id",
        ),
    )

    private fun decision() = ExecutiveDecisionDto(
        safeTitleCode = "APPROVAL_REQUIRED",
        attentionClass = "ACTION_REQUIRED",
        updatedAt = "2026-11-23T01:00:00Z",
        reference = ExecutiveSourceReferenceDto(
            module = "INBOX", sourceType = "APPROVAL_REQUEST", sourceId = id,
            sourceVersion = 4, readPath = "/v1/approvals/$id",
        ),
    )

    private fun home(
        inbox: ExecutiveSectionDto<ExecutiveDecisionDto> = ExecutiveSectionDto(
            status = "CURRENT", items = emptyList(), source = "INBOX_WORK_QUEUE", partial = false,
        ),
        projects: ExecutiveSectionDto<ExecutiveProjectDto> = ExecutiveSectionDto(
            status = "PARTIAL", items = emptyList(), source = "PROJECTS_AND_PROJECT_HEALTH", partial = true,
        ),
        assets: ExecutiveSectionDto<ExecutiveAssetExceptionDto> = ExecutiveSectionDto(
            status = "CURRENT", items = emptyList(), source = "ASSET_SERVICE_QUEUE", partial = false,
        ),
        scope: String = "CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS",
    ) = ExecutiveHomeDto(
        viewerIdentityId = id,
        organizationId = org,
        asOf = "2026-11-23T02:00:00Z",
        correlationId = "test-correlation",
        needsYouScope = scope,
        needsYou = inbox,
        projects = projects,
        assetExceptions = assets,
        pendingDomains = listOf(
            ExecutivePendingDomainDto("PAYROLL", "AWAITING_AUTHORIZED_SOURCE_CONTRACT"),
        ),
    )

    @Test
    fun allClearIsNotManufacturedFromBoundedOrActorWideRead() {
        val lines = executiveReadCoverage(home())
        assertEquals(4, lines.size)
        assertEquals(ExecutiveCoverageState.INCOMPLETE, lines[0].state)
        assertEquals(ExecutiveCoverageState.INCOMPLETE, lines[1].state)
        assertEquals(0, lines[1].visibleCount)
        assertTrue(lines[0].caveats.any { it.contains("عضوياته") })
        assertTrue(lines[1].caveats.any { it.contains("الحافظة") })
        assertEquals(ExecutiveCoverageState.AWAITING_SOURCE_CONTRACT, lines[3].state)
        assertNull(lines[3].visibleCount)
    }

    @Test
    fun unknownProducerCannotClaimVisibleRecordsEvenWithItems() {
        val lines = executiveReadCoverage(home(
            inbox = ExecutiveSectionDto(
                status = "FUTURE_STATE", items = listOf(decision()),
                source = "INBOX_WORK_QUEUE", partial = false,
            ),
            assets = ExecutiveSectionDto(
                status = "CURRENT", items = emptyList(), source = "FINANCE_LEDGER",
                partial = false, sourceAsOf = "2026-11-23T01:00:00Z",
            ),
        ))
        assertEquals(ExecutiveCoverageState.UNAVAILABLE, lines[0].state)
        assertNull(lines[0].visibleCount)
        assertEquals(ExecutiveCoverageState.UNAVAILABLE, lines[2].state)
        assertNull(lines[2].sourceAsOf)
    }

    @Test
    fun cursorIsIncompleteEvenWhenProducerForgotPartialFlag() {
        val lines = executiveReadCoverage(home(
            inbox = ExecutiveSectionDto(
                status = "CURRENT", items = listOf(decision()), source = "INBOX_WORK_QUEUE",
                partial = false, nextCursor = "opaque",
            ),
        ))
        assertEquals(1, lines[0].visibleCount)
        assertEquals(ExecutiveCoverageState.INCOMPLETE, lines[0].state)
        assertTrue(lines[0].caveats.any { it.contains("النافذة") })
    }

    @Test
    fun projectUnknownAndMissingObservedTimeAreNotTreatedAsFresh() {
        val lines = executiveReadCoverage(home(
            projects = ExecutiveSectionDto(
                status = "PARTIAL",
                items = listOf(project("UNKNOWN"), project("CRITICAL", "2026-11-23T01:30:00Z")),
                source = "PROJECTS_AND_PROJECT_HEALTH", partial = true,
            ),
        ))
        assertEquals(ExecutiveCoverageState.INCOMPLETE, lines[1].state)
        assertTrue(lines[1].caveats.any { it.contains("صحة مؤكد") })
        assertTrue(lines[1].caveats.any { it.contains("وقت مصدر") })
    }

    @Test
    fun unavailableSourceIsNotZeroAndPendingIsNotAnOutage() {
        val lines = executiveReadCoverage(home(
            assets = ExecutiveSectionDto(
                status = "UNAVAILABLE", items = emptyList(), source = "ASSET_SERVICE_QUEUE",
                partial = false, unavailableReason = "SOURCE_READ_FAILED_OR_NOT_AUTHORIZED",
            ),
        ))
        assertEquals(ExecutiveCoverageState.UNAVAILABLE, lines[2].state)
        assertNull(lines[2].visibleCount)
        assertTrue(lines[2].caveats.any { it.contains("SOURCE_READ_FAILED_OR_NOT_AUTHORIZED") })
        assertEquals(ExecutiveCoverageState.AWAITING_SOURCE_CONTRACT, lines[3].state)
    }

    @Test
    fun unexpectedActorWideScopeMustBeExplicitlyFlagged() {
        val lines = executiveReadCoverage(home(scope = "WRONG_ORG_SCOPE"))
        assertTrue(lines[0].caveats.any { it.contains("غير معروف") })
        assertEquals(ExecutiveCoverageState.INCOMPLETE, lines[0].state)
    }
}
