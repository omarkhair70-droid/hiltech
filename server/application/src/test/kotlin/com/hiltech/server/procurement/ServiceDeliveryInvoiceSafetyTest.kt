package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ServiceDeliveryInvoiceSafetyTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val approvals=Mockito.mock(ApprovalPersistencePort::class.java)
    private val documents=Mockito.mock(ServicePurchaseDocumentTargetAdapter::class.java)
    private val commands=Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit=Mockito.mock(AuditEventWriter::class.java)
    private val clock=Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC)
    private val actor=UUID.randomUUID()
    private val op=UUID.randomUUID()
    private val orderId=UUID.randomUUID()
    private val ctx=ProcurementDraftContext(op,actor,"service-real-operations")

    private fun service(order:Boolean=false,review:Boolean=false,finance:Boolean=false,
                        reviewKey:String="",financeKey:String="")=
        ServiceDeliveryInvoiceService(jdbc,approvals,documents,commands,audit,clock,
            order,review,finance,reviewKey,financeKey)

    @Test fun serviceDeliveryCannotRecordFakeSuccessfulCheckboxWithoutCompanyAuthority() {
        val req=RecordServiceDeliveryReviewRequest(
            op,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),
            "ACCEPTED","The real service outcome has been inspected.")
        val error=assertThrows<ProductApiException> {
            service().reviewDelivery(ctx,orderId,req)
        }
        assertEquals("SERVICE_PURCHASE_UNAVAILABLE",error.code)
        Mockito.verifyNoInteractions(jdbc,approvals,documents,commands,audit)
    }

    @Test fun serviceDeliveryRequiresCanonicalEvidenceChecksum() {
        val req=RecordServiceDeliveryReviewRequest(
            op,UUID.randomUUID(),UUID.randomUUID(),"not-a-checksum",
            "ACCEPTED","The real service outcome has been inspected.")
        val error=assertThrows<ProductApiException> {
            service(true,true).reviewDelivery(ctx,orderId,req)
        }
        assertEquals("SERVICE_PURCHASE_INVALID",error.code)
        Mockito.verifyNoInteractions(jdbc,approvals,documents,commands,audit)
    }

    @Test fun disabledFinanceHasNoAccessToSupplierInvoiceAndMatches() {
        val error=assertThrows<ProductApiException> {
            service().preview(actor,UUID.randomUUID())
        }
        assertEquals("SERVICE_PURCHASE_UNAVAILABLE",error.code)
        Mockito.verifyNoInteractions(jdbc,approvals,documents,commands,audit)
    }

    @Test fun financeCannotCreateAnInvoiceUsingAClientSpecifiedNegativeAmount() {
        val req=CaptureServiceInvoiceRequest(
            op,UUID.randomUUID(),UUID.randomUUID(),"b".repeat(64),
            "ABC-2026",LocalDate.of(2026,11,23),"EGP","-3","0")
        val error=assertThrows<ProductApiException> {
            service(true,true,true,"SERVICE_ACCEPTANCE","SERVICE_FINANCE")
                .captureInvoice(ctx,orderId,req)
        }
        assertEquals("SERVICE_PURCHASE_INVALID",error.code)
        Mockito.verifyNoInteractions(jdbc,approvals,documents,commands,audit)
    }

    @Test fun userCannotInventMatchWithoutRealRecordedSourceFingerprint() {
        val error=assertThrows<ProductApiException> {
            service(true,true,true,"SERVICE_ACCEPTANCE","SERVICE_FINANCE")
                .recordMatch(ctx,UUID.randomUUID(),
                    RecordServiceInvoiceMatchRequest(op,"wrong"))
        }
        assertEquals("SERVICE_PURCHASE_INVALID",error.code)
        Mockito.verifyNoInteractions(jdbc,approvals,documents,commands,audit)
    }

    @Test fun canNeverPretendToSendPaymentWhileFinanceFeatureDisabled() {
        val error=assertThrows<ProductApiException> {
            service().handoff(ctx,UUID.randomUUID(),
                HandOffServiceInvoiceRequest(op,UUID.randomUUID()))
        }
        assertEquals("SERVICE_PURCHASE_UNAVAILABLE",error.code)
        Mockito.verifyNoInteractions(jdbc,approvals,documents,commands,audit)
    }
}
