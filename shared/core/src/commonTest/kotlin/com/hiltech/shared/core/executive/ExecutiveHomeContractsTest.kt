package com.hiltech.shared.core.executive

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutiveHomeContractsTest {
    @Test
    fun roundTripPreservesUnavailableAndUnknownInsteadOfManufacturingMetrics() {
        val original = ExecutiveHomeDto(
            organizationId = "dd09c3a1-447e-4ca7-97d4-9aab2dabfba9",
            asOf = "2026-11-22T20:00:00Z",
            correlationId = "trace-1",
            needsYouScope = "CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS",
            needsYou = ExecutiveSectionDto(
                status = "UNAVAILABLE",
                items = emptyList(),
                source = "INBOX_WORK_QUEUE",
                partial = false,
                unavailableReason = "SOURCE_READ_FAILED_OR_NOT_AUTHORIZED",
            ),
            projects = ExecutiveSectionDto(
                status = "PARTIAL",
                items = listOf(
                    ExecutiveProjectDto(
                        projectId = "bc09c3a1-447e-4ca7-97d4-9aab2dabfba9",
                        projectCode = "PR-1",
                        name = "Visible Project",
                        lifecycleState = "ACTIVE",
                        projectVersion = 7,
                        projectUpdatedAt = "2026-11-22T17:00:00Z",
                        healthState = "UNKNOWN",
                        healthAsOf = "2026-11-22T19:58:00Z",
                        reference = ExecutiveSourceReferenceDto(
                            module = "PROJECTS",
                            sourceType = "PROJECT",
                            sourceId = "bc09c3a1-447e-4ca7-97d4-9aab2dabfba9",
                            sourceVersion = 7,
                            readPath = "/v1/projects/bc09c3a1-447e-4ca7-97d4-9aab2dabfba9",
                        ),
                    ),
                ),
                source = "PROJECTS_AND_PROJECT_HEALTH",
                partial = true,
            ),
            assetExceptions = ExecutiveSectionDto(
                status = "CURRENT",
                items = emptyList(),
                source = "ASSET_SERVICE_QUEUE",
                partial = false,
            ),
            pendingDomains = listOf(
                ExecutivePendingDomainDto("PAYROLL", "AWAITING_AUTHORIZED_SOURCE_CONTRACT"),
            ),
        )

        val codec = Json { explicitNulls = false }
        val decoded = codec.decodeFromString(
            ExecutiveHomeDto.serializer(),
            codec.encodeToString(ExecutiveHomeDto.serializer(), original),
        )

        assertEquals("UNAVAILABLE", decoded.needsYou.status)
        assertEquals("SOURCE_READ_FAILED_OR_NOT_AUTHORIZED", decoded.needsYou.unavailableReason)
        assertTrue(decoded.needsYou.items.isEmpty())
        assertEquals("UNKNOWN", decoded.projects.items.single().healthState)
        assertEquals("2026-11-22T19:58:00Z", decoded.projects.items.single().healthAsOf)
        assertEquals(7L, decoded.projects.items.single().reference.sourceVersion)
        assertTrue(decoded.projects.partial)
        assertNull(decoded.projects.sourceAsOf)
        assertFalse(decoded.assetExceptions.partial)
        assertEquals("AWAITING_AUTHORIZED_SOURCE_CONTRACT", decoded.pendingDomains.single().status)
    }
}
