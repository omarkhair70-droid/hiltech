package com.hiltech.shared.core.executive

import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutivePresentationOrderTest {
    private fun record(code: String, state: String) = ExecutiveProjectDto(
        projectId = "11111111-2222-4333-8444-555555555555",
        projectCode = code,
        name = code,
        lifecycleState = "ACTIVE",
        projectVersion = 1,
        projectUpdatedAt = "2026-11-23T00:00:00Z",
        healthState = state,
        reference = ExecutiveSourceReferenceDto(
            module = "PROJECTS",
            sourceType = "PROJECT",
            sourceId = "11111111-2222-4333-8444-555555555555",
            sourceVersion = 1,
            readPath = "/v1/projects/11111111-2222-4333-8444-555555555555",
        ),
    )

    @Test
    fun attentionStatesLeadWithoutInventingScoresOrHidingUnknown() {
        val input = listOf(
            record("P6", "HEALTHY"),
            record("P4", "UNKNOWN"),
            record("P2", "ON_HOLD"),
            record("P7", "NEW_FUTURE_STATE"),
            record("P3", "ATTENTION"),
            record("P1", "CRITICAL"),
            record("P5", "UNAVAILABLE"),
        )
        assertEquals(
            listOf("P1", "P2", "P3", "P4", "P5", "P7", "P6"),
            orderExecutiveProjectsByHealth(input).map { it.projectCode },
        )
        assertEquals(input.size, orderExecutiveProjectsByHealth(input).size)
    }
}
