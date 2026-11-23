package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
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

    @Test fun buyerKeyMustBeExplicitNotDerivedFromTeamOrReviewerLabel() {
        val exception=assertThrows<ProductApiException> {
            service(true,true,"").eligibleNeeds(actor,org)
        }
        assertEquals("SERVICE_SOURCING_UNAVAILABLE",exception.code)
        Mockito.verifyNoInteractions(jdbc,approvals,suppliers,documentTarget,commands,audit)
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
}
