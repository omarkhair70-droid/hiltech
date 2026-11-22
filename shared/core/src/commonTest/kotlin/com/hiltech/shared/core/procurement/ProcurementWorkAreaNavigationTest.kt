package com.hiltech.shared.core.procurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcurementWorkAreaNavigationTest {
    @Test fun allFlagsOffHasNoProcurementAreas() {
        val available = visibleProcurementWorkAreas(
            requirementsEnabled = false, sourcingEnabled = false, issuerEnabled = false,
        )
        assertTrue(available.isEmpty())
        assertNull(allowedProcurementWorkArea(null, available))
    }

    @Test fun onlyRequirementsFeatureCannotExposeBuyerOrIssuer() {
        val available = visibleProcurementWorkAreas(true, false, false)
        assertEquals(listOf(ProcurementWorkArea.REQUIREMENTS), available)
        assertFalse(ProcurementWorkArea.SOURCING in available)
        assertFalse(ProcurementWorkArea.ISSUANCE in available)
    }

    @Test fun onlyIssuerFeatureOpensPOIssuanceWithoutExposingQuotes() {
        val available = visibleProcurementWorkAreas(false, false, true)
        assertEquals(listOf(ProcurementWorkArea.ISSUANCE), available)
        assertEquals(
            ProcurementWorkArea.ISSUANCE,
            allowedProcurementWorkArea(ProcurementWorkArea.SOURCING, available),
        )
    }

    @Test fun sourcingAndIssuerStaySeparateAndOrdered() {
        val available = visibleProcurementWorkAreas(false, true, true)
        assertEquals(
            listOf(ProcurementWorkArea.SOURCING, ProcurementWorkArea.ISSUANCE),
            available,
        )
        assertEquals(
            ProcurementWorkArea.ISSUANCE,
            allowedProcurementWorkArea(ProcurementWorkArea.ISSUANCE, available),
        )
        assertEquals(
            ProcurementWorkArea.SOURCING,
            allowedProcurementWorkArea(ProcurementWorkArea.REQUIREMENTS, available),
        )
    }

    @Test fun allThreeAreDistinctBusinessWorkAreas() {
        val available = visibleProcurementWorkAreas(true, true, true)
        assertEquals(
            listOf(
                ProcurementWorkArea.REQUIREMENTS,
                ProcurementWorkArea.SOURCING,
                ProcurementWorkArea.ISSUANCE,
            ),
            available,
        )
        assertEquals(3, available.map { it.title }.toSet().size)
    }

    @Test fun removedFeatureDoesNotKeepPreviousRoute() {
        val authorizedNow = visibleProcurementWorkAreas(true, false, false)
        assertEquals(
            ProcurementWorkArea.REQUIREMENTS,
            allowedProcurementWorkArea(ProcurementWorkArea.ISSUANCE, authorizedNow),
        )
    }
}
