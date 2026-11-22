package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServiceQuoteProtectedDocumentTest {
    @Test fun disabledServiceSourcingCannotRevealSupplierDocumentOrTouchStorage() {
        val jdbc=Mockito.mock(JdbcTemplate::class.java)
        val evidence=Mockito.mock(EvidencePersistencePort::class.java)
        val access=Mockito.mock(ServiceQuoteDocumentTargetAdapter::class.java)
        val storage=Mockito.mock(EvidenceStorageAccessPort::class.java)
        val audit=Mockito.mock(AuditEventWriter::class.java)
        val clock=Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC)
        val service=ServiceQuoteProtectedDocumentService(
            jdbc,evidence,access,storage,audit,clock,false,
        )
        val error=assertThrows<ProductApiException> {
            service.read(UUID.randomUUID(),UUID.randomUUID(),"service-private-test")
        }
        assertEquals("SERVICE_QUOTE_DOCUMENT_NOT_VISIBLE",error.code)
        Mockito.verifyNoInteractions(jdbc,evidence,access,storage,audit)
    }
}
