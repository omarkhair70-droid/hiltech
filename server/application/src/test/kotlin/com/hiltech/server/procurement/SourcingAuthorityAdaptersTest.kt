package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SourcingAuthorityAdaptersTest {
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val sourcing = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val at = Instant.parse("2026-11-22T20:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @Test fun defaultBusinessAuthorityStillRequiresCurrentMembershipAndARealSource() {
        assertFalse(CurrentBuyerSourcingAuthority(approvals, clock, true, "")
            .canManageSourcing(actor, org))
        assertFalse(CurrentBuyerSourcingAuthority(approvals, clock, false, "BUYER_AUTH")
            .canManageSourcing(actor, org))
        Mockito.verify(approvals).isActiveOrganizationMember(org, actor, at)
        assertEquals(0, Mockito.mockingDetails(approvals).invocations.count { it.method.name == "resolveCurrentAuthority" })
    }

    @Test fun buyerMustBeCurrentMemberAndCurrentBindingNotDisplayRole() {
        val key = "BUYER_AUTH"
        val buyer = CurrentBuyerSourcingAuthority(approvals, clock, true, key)
        assertFalse(buyer.canManageSourcing(actor, org))
        Mockito.verify(approvals).isActiveOrganizationMember(org, actor, at)
        Mockito.verify(approvals, Mockito.never()).resolveCurrentAuthority(org, key, at)

        Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, at)).thenReturn(true)
        val binding = ApprovalAuthorityBindingSnapshot(
            UUID.randomUUID(), org, key, ApprovalPrincipalType.USER,
            actor, at.minusSeconds(100), null, 1,
        )
        Mockito.`when`(approvals.resolveCurrentAuthority(org, key, at)).thenReturn(binding)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, at)).thenReturn(true)
        assertTrue(buyer.canManageSourcing(actor, org))
        Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, at)).thenReturn(false)
        assertFalse(buyer.canManageSourcing(actor, org))
    }

    @Test fun technicalReviewRequiresRealScopedRfqAndSeparateAuthority() {
        val review = CurrentQuoteTechnicalReviewAuthority(
            approvals, sourcing, clock, true, "QUOTE_REVIEWER",
        )
        assertFalse(review.canReviewQuote(actor, org, UUID.randomUUID()))
        Mockito.verifyNoInteractions(approvals)
    }

    @Test fun defaultTechnicalReviewStillRequiresAnExistingScopedRfq() {
        val review = CurrentQuoteTechnicalReviewAuthority(
            approvals, sourcing, clock, true, "",
        )
        assertFalse(review.canReviewQuote(actor, org, UUID.randomUUID()))
        Mockito.verifyNoInteractions(approvals)
        assertEquals(1, Mockito.mockingDetails(sourcing).invocations.count { it.method.name == "rfq" })
    }
}
