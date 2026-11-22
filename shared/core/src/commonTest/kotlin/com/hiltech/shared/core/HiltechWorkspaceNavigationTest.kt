package com.hiltech.shared.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiltechWorkspaceNavigationTest {
    @Test fun signedOutNeverGetsProcurementWorkspaceFromClientFlag() {
        val available = visibleHiltechWorkspaces(
            signedIn = false,
            procurementFeatureEnabled = true,
            activeOrganizationAvailable = true,
        )
        assertEquals(listOf(HiltechWorkspace.OVERVIEW), available)
        assertFalse(HiltechWorkspace.PROCUREMENT in available)
    }

    @Test fun defaultOffFeatureFlagKeepsLegacyOverview() {
        val available = visibleHiltechWorkspaces(
            signedIn = true,
            procurementFeatureEnabled = false,
            activeOrganizationAvailable = true,
        )
        assertEquals(listOf(HiltechWorkspace.OVERVIEW), available)
    }

    @Test fun noOrganizationNoProcurementNavigation() {
        val available = visibleHiltechWorkspaces(
            signedIn = true,
            procurementFeatureEnabled = true,
            activeOrganizationAvailable = false,
        )
        assertEquals(listOf(HiltechWorkspace.OVERVIEW), available)
    }

    @Test fun authorizedHostFeatureOffersBothRoutesWithoutGrantingAnAction() {
        val available = visibleHiltechWorkspaces(
            signedIn = true,
            procurementFeatureEnabled = true,
            activeOrganizationAvailable = true,
        )
        assertEquals(
            listOf(HiltechWorkspace.OVERVIEW, HiltechWorkspace.PROCUREMENT),
            available,
        )
        assertTrue(HiltechWorkspace.PROCUREMENT in available)
    }

    @Test fun disabledFeatureAlwaysResolvesPreviousProcurementRouteBackToOverview() {
        val disabled = listOf(HiltechWorkspace.OVERVIEW)
        assertEquals(
            HiltechWorkspace.OVERVIEW,
            allowedHiltechWorkspace(HiltechWorkspace.PROCUREMENT, disabled),
        )
        assertEquals(
            HiltechWorkspace.PROCUREMENT,
            allowedHiltechWorkspace(
                HiltechWorkspace.PROCUREMENT,
                listOf(HiltechWorkspace.OVERVIEW, HiltechWorkspace.PROCUREMENT),
            ),
        )
    }
}
