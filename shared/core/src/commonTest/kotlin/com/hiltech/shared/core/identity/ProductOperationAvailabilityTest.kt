package com.hiltech.shared.core.identity

import com.hiltech.shared.core.nativeWorkspaceMayBeShown
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductOperationAvailabilityTest {
    private fun identity(capabilities: IdentityProductCapabilitiesDto? = null) = IdentityBootstrapDto(
        identityId = "synthetic-user", identityStatus = "ACTIVE", identityVersion = 1,
        organizations = listOf(IdentityOrganizationDto(
            "synthetic-membership", "synthetic-org", "SYNTH", "Synthetic company",
            "HILTECH", "EMPLOYEE", null, true, 1,
        )), teams = emptyList(), productCapabilities = capabilities,
    )

    @Test
    fun signedInAvailabilityNeverReplacesCurrentSourceAuthority() {
        val available = identity().operationAvailable(ProductOperation.PROCUREMENT_SOURCING)
        assertTrue(available)
        assertFalse(nativeWorkspaceMayBeShown(available, true, false))
        assertFalse((null as IdentityBootstrapDto?).operationAvailable(ProductOperation.PROCUREMENT_SOURCING))
        assertFalse(identity().copy(identityStatus = "REVOKED")
            .operationAvailable(ProductOperation.PROCUREMENT_SOURCING))
        assertFalse(identity().copy(organizations = emptyList())
            .operationAvailable(ProductOperation.PROCUREMENT_SOURCING))
    }

    @Test
    fun advertisedPauseAndUnadvertisedRouteCannotBeOverriddenByLegacyDefaults() {
        val capabilities = Json.decodeFromString<IdentityProductCapabilitiesDto>("""{
            "executiveHome": true,
            "operationAvailability": {
                "executive.home": false,
                "procurement.requirements": true,
                "service.handover": false
            }
        }""")
        val current = identity(capabilities)
        assertFalse(current.operationAvailable(ProductOperation.EXECUTIVE_HOME))
        assertFalse(current.operationAvailable(ProductOperation.SERVICE_HANDOVER))
        assertFalse(current.operationAvailable(ProductOperation.FINANCE_AP_POSTING))
        assertTrue(current.operationAvailable(ProductOperation.PROCUREMENT_REQUIREMENTS))
    }

    @Test
    fun previousServerCapabilitiesAreRespectedWhileExistingRoutesCanProbeTheirSource() {
        val current = identity(IdentityProductCapabilitiesDto(
            executiveHome = true, personalIssuedStock = true, engineeringTests = false,
        ))
        assertTrue(current.operationAvailable(ProductOperation.EXECUTIVE_HOME))
        assertTrue(current.operationAvailable(ProductOperation.FIELD_PERSONAL_ISSUED_STOCK))
        assertFalse(current.operationAvailable(ProductOperation.FIELD_ENGINEERING_TESTS))
        assertTrue(current.operationAvailable(ProductOperation.PROCUREMENT_SOURCING))
    }
}
