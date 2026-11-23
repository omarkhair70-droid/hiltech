package com.hiltech.shared.core.executive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutiveNativeWorkspaceGateTest {
    @Test
    fun onlyOriginalNativeProjectsDestinationsAreConnected() {
        val id = "11111111-1111-4111-8111-111111111111"
        assertTrue(executiveNativeProjectDestination(
            ExecutiveDestination(ExecutiveDestinationKind.PROJECT_DETAIL, id),
        ))
        assertTrue(executiveNativeProjectDestination(
            ExecutiveDestination(ExecutiveDestinationKind.PROJECT_HEALTH, id),
        ))
        assertFalse(executiveNativeProjectDestination(
            ExecutiveDestination(ExecutiveDestinationKind.APPROVAL_DETAIL, id),
        ))
        assertFalse(executiveNativeProjectDestination(
            ExecutiveDestination(ExecutiveDestinationKind.ASSET_AVAILABILITY, id),
        ))
    }
}
