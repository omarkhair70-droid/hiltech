package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServicePurchaseProtectedDocumentTest {
    @Test fun unknownOrDisabledDocumentNeverLeaksInvoiceOrDeliveryBytes() {
        val evidence=Mockito.mock(EvidencePersistencePort::class.java)
        val auth=Mockito.mock(ServicePurchaseDocumentTargetAdapter::class.java)
        val storage=Mockito.mock(EvidenceStorageAccessPort::class.java)
        val audit=Mockito.mock(AuditEventWriter::class.java)
        val clock=Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC)
        val handler=ServicePurchaseProtectedDocumentService(
            evidence,auth,storage,audit,clock,false)
        val ex=assertThrows<ProductApiException> {
            handler.read(UUID.randomUUID(),UUID.randomUUID(),"private-access-test")
        }
        assertEquals("SERVICE_DOCUMENT_NOT_VISIBLE",ex.code)
        Mockito.verifyNoInteractions(evidence,auth,storage,audit)
    }
}
