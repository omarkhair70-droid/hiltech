package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServiceSupplierSourcingServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val suppliers = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val documentTarget = Mockito.mock(ServiceQuoteDocumentTargetAdapter::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC)
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val need = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val ctx = ProcurementDraftContext(op,actor,"service-sourcing-contract")

    private fun service(needs:Boolean = false,sourcing:Boolean = false,key:String = "") =
        ServiceSupplierSourcingService(
            jdbc,approvals,suppliers,documentTarget,commands,audit,clock,
            needs,sourcing,key,
        )

    @Test fun bothFeatureFlagsAreRequiredBeforeLookingUpServiceOrSupplier() {
        for ((needsEnabled,sourcingEnabled) in listOf(false to false,false to true,true to false)) {
            val actual=assertThrows<ProductApiException> {
                service(needsEnabled,sourcingEnabled,"PROCUREMENT_SERVICE_BUYER")
                    .eligibleNeeds(actor,org)
            }
            assertEquals("SERVICE_SOURCING_UNAVAILABLE",actual.code)
        }
        Mockito.verifyNoInteractions(jdbc,approvals,suppliers,documentTarget,commands,audit)
    }

    @Test fun defaultBuyerResponsibilityWithoutAnActualAssignmentDoesNotExposeNeeds() {
        Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, clock.instant())).thenReturn(true)
        val exception=assertThrows<ProductApiException> {
            service(true,true,"").eligibleNeeds(actor,org)
        }
        assertEquals("SERVICE_SOURCING_UNAVAILABLE",exception.code)
        Mockito.verify(approvals).isActiveOrganizationMember(org, actor, clock.instant())
        Mockito.verify(approvals).resolveCurrentAuthority(org, "PROCUREMENT_BUYER", clock.instant())
        Mockito.verifyNoMoreInteractions(approvals)
        Mockito.verifyNoInteractions(jdbc,suppliers,documentTarget,commands,audit)
    }

    @Test fun unverifiedBuyerCannotEnumerateCandidateServiceNeeds() {
        val exception=assertThrows<ProductApiException> {
            service(true,true,"SERVICE_BUYER").eligibleNeeds(actor,org)
        }
        assertEquals("SERVICE_SOURCING_UNAVAILABLE",exception.code)
        Mockito.verify(approvals).isActiveOrganizationMember(org,actor,clock.instant())
        Mockito.verifyNoInteractions(jdbc,suppliers,documentTarget,commands,audit)
    }

    @Test fun wrongOperationRejectsRfqBeforeAccessingCompanyData() {
        val exception=assertThrows<ProductApiException> {
            service(true,true,"SERVICE_BUYER").open(
                ctx,need,OpenServiceRfqRequest(UUID.randomUUID(),2))
        }
        assertEquals("SERVICE_SOURCING_INVALID",exception.code)
        Mockito.verifyNoInteractions(jdbc,approvals,suppliers,documentTarget,commands,audit)
    }

    @Test fun quoteCurrencyCannotSlipIntoBuyerAccessOrEvidenceChecks() {
        val request=RecordServiceQuoteRequest(
            op,UUID.randomUUID(),1,"SVC-42",UUID.randomUUID(),"a".repeat(64),
            "INVALID","100","0","Original supplier service terms and milestones."
        )
        val exception=assertThrows<ProductApiException> {
            service(true,true,"SERVICE_BUYER").recordQuote(ctx,need,request)
        }
        assertEquals("SERVICE_SOURCING_INVALID",exception.code)
        Mockito.verifyNoInteractions(jdbc,approvals,suppliers,documentTarget,commands,audit)
    }

    @Test fun negativeAmountRejectedBeforeAnyReadOfSupplierPricing() {
        val request=RecordServiceQuoteRequest(
            op,UUID.randomUUID(),1,"SVC-42",UUID.randomUUID(),"a".repeat(64),
            "EGP","-100","0","External service quotation with milestones."
        )
        val exception=assertThrows<ProductApiException> {
            service(true,true,"SERVICE_BUYER").recordQuote(ctx,need,request)
        }
        assertEquals("SERVICE_SOURCING_INVALID",exception.code)
        Mockito.verifyNoInteractions(jdbc,approvals,suppliers,documentTarget,commands,audit)
    }

    @Test fun serviceDocumentAuthorizationIsFailClosedWithoutBuyerPolicy() {
        val adapter=ServiceQuoteDocumentTargetAdapter(
            jdbc,approvals,suppliers,clock,false,"",
        )
        val target=com.hiltech.server.documents.ServiceQuoteDocumentEvidenceTarget(
            UUID.randomUUID(),UUID.randomUUID(),org,UUID.randomUUID(),
        )
        assertFalse(adapter.canReserve(actor,target))
        assertFalse(adapter.canAccess(actor,target))
        Mockito.verifyNoInteractions(jdbc,approvals,suppliers)
    }

    @Test fun supplierSearchRequiresAvailabilityAndAnAssignedBuyer() {
        for ((needsEnabled,sourcingEnabled) in listOf(false to false,false to true,true to false)) {
            val error = assertThrows<ProductApiException> {
                service(needsEnabled, sourcingEnabled, "SERVICE_BUYER").searchSuppliers(actor, org, "Supplier")
            }
            assertEquals("SERVICE_SOURCING_UNAVAILABLE", error.code)
        }
        Mockito.verifyNoInteractions(jdbc,approvals,suppliers,documentTarget,commands,audit)
        Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, clock.instant())).thenReturn(true)
        val error = assertThrows<ProductApiException> {
            service(true, true, "").searchSuppliers(actor, org, "Supplier")
        }
        assertEquals("SERVICE_SOURCING_UNAVAILABLE", error.code)
        Mockito.verify(approvals).isActiveOrganizationMember(org, actor, clock.instant())
        Mockito.verify(approvals).resolveCurrentAuthority(org, "PROCUREMENT_BUYER", clock.instant())
        Mockito.verifyNoMoreInteractions(approvals)
        Mockito.verifyNoInteractions(jdbc,suppliers,documentTarget,commands,audit)
    }

    @Test fun currentServiceBuyerSearchesLinkedSuppliersWithoutMasterDataOrCommandAccess() {
        allowBuyer()
        val candidate = SourcingSupplierCandidate(UUID.randomUUID(), "مورد خدمات تجريبي", 3)
        Mockito.`when`(suppliers.searchEligibleSuppliers(org, "خدمات")).thenReturn(listOf(candidate))

        val result = service(true, true, "SERVICE_BUYER").searchSuppliers(actor, org, "  خدمات  ")

        assertEquals(org, result.organizationId)
        assertEquals("خدمات", result.query)
        assertEquals(listOf(candidate), result.suppliers)
        Mockito.verify(suppliers).searchEligibleSuppliers(org, "خدمات")
        Mockito.verifyNoMoreInteractions(suppliers)
        Mockito.verifyNoInteractions(jdbc,documentTarget,commands,audit)
    }

    @Test fun missingMembershipOrCurrentAuthorityCannotEnumerateSuppliers() {
        val service = service(true, true, "SERVICE_BUYER")
        assertEquals("SERVICE_SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service.searchSuppliers(actor, org, "Supplier")
        }.code)
        Mockito.verify(approvals, Mockito.never()).resolveCurrentAuthority(org, "SERVICE_BUYER", clock.instant())
        Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, clock.instant())).thenReturn(true)
        assertEquals("SERVICE_SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service.searchSuppliers(actor, org, "Supplier")
        }.code)
        Mockito.verifyNoInteractions(jdbc,suppliers,documentTarget,commands,audit)
    }

    @Test fun foreignOrganizationAndDifferentAuthorityBindingsCannotAuthorizeSearch() {
        val binding = allowBuyer()
        val service = service(true, true, "SERVICE_BUYER")
        for (wrong in listOf(binding.copy(organizationId = UUID.randomUUID()),
            binding.copy(authorityKey = "MATERIAL_BUYER"))) {
            Mockito.`when`(approvals.resolveCurrentAuthority(org, "SERVICE_BUYER", clock.instant()))
                .thenReturn(wrong)
            assertEquals("SERVICE_SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
                service.searchSuppliers(actor, org, "Supplier")
            }.code)
            Mockito.verify(approvals, Mockito.never()).actorMatchesAuthority(actor, wrong, clock.instant())
        }
        Mockito.verifyNoInteractions(jdbc,suppliers,documentTarget,commands,audit)
    }

    @Test fun teamBuyerMustStillMatchTheCurrentSourceOnEverySearch() {
        val binding = allowBuyer(ApprovalPrincipalType.TEAM)
        Mockito.`when`(suppliers.searchEligibleSuppliers(org, "Supplier")).thenReturn(emptyList())
        val service = service(true, true, "SERVICE_BUYER")
        assertEquals(emptyList<SourcingSupplierCandidate>(), service.searchSuppliers(actor, org, "Supplier").suppliers)

        Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, clock.instant())).thenReturn(false)
        assertEquals("SERVICE_SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service.searchSuppliers(actor, org, "Supplier")
        }.code)
        Mockito.verify(suppliers, Mockito.times(1)).searchEligibleSuppliers(org, "Supplier")
        Mockito.verifyNoInteractions(jdbc,documentTarget,commands,audit)
    }

    @Test fun aRevokedBuyerBindingCannotReuseAnEarlierSuccessfulDirectoryRead() {
        allowBuyer()
        Mockito.`when`(suppliers.searchEligibleSuppliers(org, "Supplier")).thenReturn(emptyList())
        val service = service(true, true, "SERVICE_BUYER")
        service.searchSuppliers(actor, org, "Supplier")

        Mockito.`when`(approvals.resolveCurrentAuthority(org, "SERVICE_BUYER", clock.instant())).thenReturn(null)
        assertEquals("SERVICE_SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service.searchSuppliers(actor, org, "Supplier")
        }.code)
        Mockito.verify(suppliers, Mockito.times(1)).searchEligibleSuppliers(org, "Supplier")
        Mockito.verifyNoInteractions(jdbc,documentTarget,commands,audit)
    }

    @Test fun supplierLinkChangesAreReadAgainRatherThanCachedForTheBuyer() {
        allowBuyer()
        val candidate = SourcingSupplierCandidate(UUID.randomUUID(), "Synthetic supplier", 1)
        Mockito.`when`(suppliers.searchEligibleSuppliers(org, "Supplier"))
            .thenReturn(listOf(candidate), emptyList())
        val service = service(true, true, "SERVICE_BUYER")
        assertEquals(listOf(candidate), service.searchSuppliers(actor, org, "Supplier").suppliers)
        assertEquals(emptyList<SourcingSupplierCandidate>(), service.searchSuppliers(actor, org, "Supplier").suppliers)
        Mockito.verify(suppliers, Mockito.times(2)).searchEligibleSuppliers(org, "Supplier")
        Mockito.verifyNoInteractions(jdbc,documentTarget,commands,audit)
    }

    @Test fun invalidSupplierQueriesDoNotReachTheSourceDirectory() {
        allowBuyer()
        val service = service(true, true, "SERVICE_BUYER")
        for (query in listOf("", "   ", "a", "a".repeat(81), "ab\ncd", "ab\u007fcd")) {
            assertEquals("SERVICE_SOURCING_INVALID", assertThrows<ProductApiException> {
                service.searchSuppliers(actor, org, query)
            }.code)
        }
        Mockito.verifyNoInteractions(jdbc,suppliers,documentTarget,commands,audit)
    }

    private fun allowBuyer(principalType: ApprovalPrincipalType = ApprovalPrincipalType.USER): ApprovalAuthorityBindingSnapshot {
        val at = clock.instant()
        val binding = ApprovalAuthorityBindingSnapshot(
            UUID.randomUUID(), org, "SERVICE_BUYER", principalType,
            if (principalType == ApprovalPrincipalType.USER) actor else UUID.randomUUID(),
            at.minusSeconds(100), null, 1,
        )
        Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, at)).thenReturn(true)
        Mockito.`when`(approvals.resolveCurrentAuthority(org, "SERVICE_BUYER", at)).thenReturn(binding)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, at)).thenReturn(true)
        return binding
    }
}
