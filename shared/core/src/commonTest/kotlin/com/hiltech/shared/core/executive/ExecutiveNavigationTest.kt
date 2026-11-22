package com.hiltech.shared.core.executive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutiveNavigationTest {
    private val id = "11111111-2222-4333-8444-555555555555"
    private val other = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    @Test
    fun knownSourceDestinationsResolveWithoutInventingExecutiveCommands() {
        val scenarios = listOf(
            ExecutiveSourceReferenceDto("INBOX", "APPROVAL_REQUEST", id, 2, "/v1/approvals/$id")
                to ExecutiveDestination(ExecutiveDestinationKind.APPROVAL_DETAIL, id),
            ExecutiveSourceReferenceDto("PROJECTS", "PROJECT", id, 8, "/v1/projects/$id")
                to ExecutiveDestination(ExecutiveDestinationKind.PROJECT_DETAIL, id),
            ExecutiveSourceReferenceDto("PROJECTS", "WORK_ORDER", id, 3, "/v1/projects/$other/health")
                to ExecutiveDestination(ExecutiveDestinationKind.PROJECT_HEALTH, other),
            ExecutiveSourceReferenceDto("PROJECTS", "MILESTONE", id, 3, "/v1/projects/$other/health")
                to ExecutiveDestination(ExecutiveDestinationKind.PROJECT_HEALTH, other),
            ExecutiveSourceReferenceDto("PROJECTS", "WORK_BLOCKER", id, 3, "/v1/projects/$other/health")
                to ExecutiveDestination(ExecutiveDestinationKind.PROJECT_HEALTH, other),
            ExecutiveSourceReferenceDto("WAREHOUSE", "ASSET", id, 4, "/v1/assets/$id/availability")
                to ExecutiveDestination(ExecutiveDestinationKind.ASSET_AVAILABILITY, id),
        )
        scenarios.forEach { (source, expected) ->
            assertEquals(expected, resolveExecutiveDestination(source))
        }
    }

    @Test
    fun rejectsMismatchedIdsUnknownDomainsAndExternalPaths() {
        listOf(
            ExecutiveSourceReferenceDto("INBOX", "APPROVAL_REQUEST", id, 2, "/v1/approvals/$other"),
            ExecutiveSourceReferenceDto("INBOX", "APPROVAL_REQUEST", id, 2, "https://evil.example/"),
            ExecutiveSourceReferenceDto("INBOX", "APPROVAL_REQUEST", id, 2, "//evil.example/v1/approvals/$id"),
            ExecutiveSourceReferenceDto("INBOX", "PAYROLL_RUN", id, 2, "/v1/approvals/$id"),
            ExecutiveSourceReferenceDto("FINANCE", "ASSET", id, 2, "/v1/assets/$id/availability"),
            ExecutiveSourceReferenceDto("WAREHOUSE", "ASSET", id, 2, "/v1/assets/$id/availability?admin=1"),
            ExecutiveSourceReferenceDto("PROJECTS", "PROJECT", "not-uuid", 3, "/v1/projects/not-uuid"),
            ExecutiveSourceReferenceDto("PROJECTS", "PAYROLL_RUN", id, 3, "/v1/projects/$other/health"),
            ExecutiveSourceReferenceDto("PROJECTS", "PAYROLL_RUN", id, 3, "/v1/projects/$other/health/../../payroll"),
            ExecutiveSourceReferenceDto("PROJECTS", "WORK_ORDER", "non-uuid", 3, "/v1/projects/$other/health"),
            ExecutiveSourceReferenceDto("PROJECTS", "WORK_ORDER", id, 3, "/v1/projects/$other/health/../../payroll"),
            ExecutiveSourceReferenceDto("PROJECTS", "WORK_ORDER", id, 3, "https://evil.example/v1/projects/$other/health"),
        ).forEach { assertNull(resolveExecutiveDestination(it)) }
    }
}
