package com.hiltech.server.documents.persistence

import com.hiltech.server.documents.SupplierQuoteDocumentEvidenceReservationInsert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/** Proves service quotations are not rejected by the stock-only Evidence gate. */
class ServiceRestrictedEvidenceReservationTest {
    private fun reservation(): SupplierQuoteDocumentEvidenceReservationInsert {
        val evidence=UUID.randomUUID()
        val org=UUID.randomUUID()
        return SupplierQuoteDocumentEvidenceReservationInsert(
            evidenceId=evidence,
            uploadSessionId=UUID.randomUUID(),
            operationId=UUID.randomUUID(),
            objectKey="org/"+org+"/evidence/"+evidence+"/objects/"+UUID.randomUUID(),
            organizationId=org,documentSlotId=UUID.randomUUID(),
            contentType="application/pdf",originalFileName="original-quote.pdf",
            sizeBytes=128L,sha256="a".repeat(64),
            capturedAt=Instant.parse("2026-11-23T00:00:00Z"),
            clientOccurredAt=null,capturedByUserId=UUID.randomUUID(),
            securityScanClass="SIGNED_DOCUMENT",
            uploadExpiresAt=Instant.parse("2026-11-23T01:00:00Z"),
            createdAt=Instant.parse("2026-11-23T00:00:00Z"),
        )
    }

    @Test fun serviceQuoteCreatesRestrictedReservationAndUploadSession() {
        val db=Mockito.mock(JdbcTemplate::class.java)
        JdbcEvidencePersistence(db).insertServiceQuoteDocumentReservation(reservation())
        val calls=Mockito.mockingDetails(db).invocations.filter{
            it.method.name=="update"
        }
        assertEquals(2,calls.size)
        val args=calls.flatMap{it.arguments.toList()}
        assertTrue(args.contains("SERVICE_QUOTE_DOCUMENT"))
        assertTrue(args.contains("SERVICE_QUOTE_BINARY"))
        assertTrue(args.contains("SERVICE_QUOTE"))
        // Fixed restricted classification is SQL-owned, not a client claim.
        assertTrue(calls.any{it.arguments[0].toString().contains("'HIGHLY_RESTRICTED'")})
        assertTrue(calls.any{it.arguments[0].toString().contains("'INTERNAL_ONLY'")})
    }
    @Test fun serviceDeliveryAndInvoiceEachUseCanonicalRestrictedDocumentSlot() {
        for ((kind,code) in listOf("DELIVERY" to "SERVICE_DELIVERY",
                                  "INVOICE" to "SERVICE_INVOICE")) {
            val db=Mockito.mock(JdbcTemplate::class.java)
            JdbcEvidencePersistence(db).insertServicePurchaseDocumentReservation(
                reservation(),kind,
            )
            val calls=Mockito.mockingDetails(db).invocations.filter{
                it.method.name=="update"
            }
            assertEquals(2,calls.size)
            val args=calls.flatMap{it.arguments.toList()}
            assertTrue(args.contains("SERVICE_PURCHASE_DOCUMENT"))
            assertTrue(args.contains(code))
            assertTrue(args.contains(code+"_BINARY"))
            assertTrue(calls.any{it.arguments[0].toString().contains("'HIGHLY_RESTRICTED'")})
            assertTrue(calls.any{it.arguments[0].toString().contains("'INTERNAL_ONLY'")})
        }
    }

    @Test fun unsupportedServiceEvidenceKindDoesNotCreateRows() {
        val db=Mockito.mock(JdbcTemplate::class.java)
        val ex=org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            JdbcEvidencePersistence(db).insertServicePurchaseDocumentReservation(
                reservation(),"UNVERIFIED",
            )
        }
        assertEquals("Unsupported service purchase document kind",ex.message)
        Mockito.verifyNoInteractions(db)
    }

}
