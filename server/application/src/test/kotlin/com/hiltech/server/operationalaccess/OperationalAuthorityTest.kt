package com.hiltech.server.operationalaccess

import com.hiltech.server.administration.CompanyResponsibilityKey
import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class OperationalAuthorityTest {
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val source = Mockito.mock(OperationalAuthorityPort::class.java)
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val property = "hiltech.procurement.sourcing.buyer-authority-key"
    private val role = OperationalResponsibility.PROCUREMENT_BUYER
    private val proof = OperationalAuthorityProof(UUID.randomUUID(), organization, role.name, 1, OperationalAuthorityOrigin.OPERATIONAL)

    @Test fun closedCatalogCoversEveryCurrentOperatingPropertyAndNoPolicyAuthority() {
        assertEquals(24, OperationalResponsibility.entries.size)
        val properties = OperationalResponsibility.entries.flatMap { it.propertyNames }
        assertEquals(51, properties.size)
        assertEquals(properties.size, properties.toSet().size)
        assertTrue(properties.all { "policy-key" !in it })
        assertTrue(OperationalResponsibility.entries.all { CompanyResponsibilityKey.valueOf(it.name).domain == it.domain })
        assertNull(OperationalResponsibility.forCatalogKey("OWNER_FINAL"))
        assertNull(OperationalResponsibility.forCatalogKey("APPROVAL_POLICY_ADMIN"))
        assertThrows<IllegalArgumentException> { OperationalResponsibility.forProperty("hiltech.finance.unknown-authority-key") }
    }

    @Test fun blankConfigurationNamesTheExistingBusinessResponsibilityWithoutGrantingIt() {
        assertEquals(role.name, OperationalResponsibility.configuredKey(property, "  "))
        Mockito.`when`(approvals.isActiveOrganizationMember(organization, actor, at)).thenReturn(true)
        assertNull(current())
        Mockito.verify(approvals).resolveCurrentAuthority(organization, role.name, at)
    }

    @Test fun explicitOperationalGrantWorksWithoutALegacyApprovalBinding() {
        allow()
        assertEquals(proof, current())
        assertEquals(0, Mockito.mockingDetails(approvals).invocations.count { it.method.name == "resolveCurrentAuthority" })
        assertEquals(0, Mockito.mockingDetails(approvals).invocations.count { it.method.name == "loadActivePolicy" })
    }

    @Test fun companyMembershipIsRequiredBeforeEitherSourceIsRead() {
        assertNull(current())
        Mockito.verifyNoInteractions(source)
        assertEquals(0, Mockito.mockingDetails(approvals).invocations.count { it.method.name == "resolveCurrentAuthority" })
    }

    @Test fun invalidOrRemovedLegacyConfigurationDoesNotBlockAnExplicitCurrentGrant() {
        allow()
        assertEquals(proof, current("invalid key"))
        assertEquals(proof, current("OLD_CUSTOM_BUYER"))
    }

    @Test fun currentCustomLegacyAuthorityPreservesItsExactOriginAndBinding() {
        Mockito.`when`(approvals.isActiveOrganizationMember(organization, actor, at)).thenReturn(true)
        val legacy = legacy("CUSTOM_BUYER")
        Mockito.`when`(approvals.resolveCurrentAuthority(organization, legacy.authorityKey, at)).thenReturn(legacy)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, legacy, at)).thenReturn(true)
        assertEquals(OperationalAuthorityProof(legacy.id, organization, "CUSTOM_BUYER", legacy.version,
            OperationalAuthorityOrigin.LEGACY_APPROVAL_BINDING), current("  CUSTOM_BUYER  "))
    }

    @Test fun legacyTeamMembershipMustBeCurrent() {
        Mockito.`when`(approvals.isActiveOrganizationMember(organization, actor, at)).thenReturn(true)
        val binding = legacy("CUSTOM_BUYER").copy(principalType = ApprovalPrincipalType.TEAM, principalId = UUID.randomUUID())
        Mockito.`when`(approvals.resolveCurrentAuthority(organization, binding.authorityKey, at)).thenReturn(binding)
        assertNull(current("CUSTOM_BUYER"))
        Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, at)).thenReturn(true)
        assertEquals(binding.id, current("CUSTOM_BUYER")?.bindingId)
    }

    @Test fun revokedNewGrantCanStillHaveIndependentExistingSourceAccess() {
        allow()
        assertEquals(OperationalAuthorityOrigin.OPERATIONAL, current("CUSTOM_BUYER")?.origin)
        Mockito.`when`(source.current(actor, organization, role, at)).thenReturn(null)
        assertNull(current("CUSTOM_BUYER"))
        val binding = legacy("CUSTOM_BUYER")
        Mockito.`when`(approvals.resolveCurrentAuthority(organization, binding.authorityKey, at)).thenReturn(binding)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, at)).thenReturn(true)
        assertEquals(OperationalAuthorityOrigin.LEGACY_APPROVAL_BINDING, current("CUSTOM_BUYER")?.origin)
    }

    @Test fun aProofForAnotherOrganizationOrResponsibilityIsNeverAccepted() {
        allow()
        Mockito.`when`(source.current(actor, organization, role, at)).thenReturn(proof.copy(organizationId = UUID.randomUUID()))
        assertNull(current())
        Mockito.`when`(source.current(actor, organization, role, at)).thenReturn(proof.copy(authorityKey = "PROCUREMENT_ORDER_ISSUE"))
        assertNull(current())
    }

    @Test fun malformedLegacyConfigurationDoesNotBecomeAnApprovalOrAdministrationShortcut() {
        Mockito.`when`(approvals.isActiveOrganizationMember(organization, actor, at)).thenReturn(true)
        assertNull(current("invalid key"))
        assertFalse(Mockito.mockingDetails(approvals).invocations.any { it.method.name == "resolveCurrentAuthority" })
    }

    private fun allow() {
        Mockito.`when`(approvals.isActiveOrganizationMember(organization, actor, at)).thenReturn(true)
        Mockito.`when`(source.current(actor, organization, role, at)).thenReturn(proof)
    }
    private fun current(key: String? = null) = currentOperationalAuthority(source, approvals, actor, organization, property, key, at)
    private fun legacy(key: String) = ApprovalAuthorityBindingSnapshot(UUID.randomUUID(), organization, key,
        ApprovalPrincipalType.USER, actor, at.minusSeconds(60), null, 3)
}
