package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidenceFinalizeRecord
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.storage.EvidenceObjectStoragePort
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.math.BigDecimal
import org.springframework.http.HttpStatus

class SupplierQuoteProtectedDocumentTest {
    private val quoteId = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val rfqId = UUID.randomUUID()
    private val supplierId = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val evidenceId = UUID.randomUUID()
    private val slotId = UUID.randomUUID()
    private val key = "org/$org/evidence/$evidenceId/objects/${UUID.randomUUID()}"
    private val bytes = "%PDF-1.7\nPrivate price quotation\n%%EOF\n".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private val sourceRef = "evidence:$evidenceId"
    private val source = SupplierQuoteDocumentSource(
        quoteId, rfqId, org, supplierId, sourceRef, digest,
    )
    private val sourcing = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val evidence = Mockito.mock(EvidencePersistencePort::class.java)
    private val verification = Mockito.mock(SupplierQuoteDocumentVerificationPort::class.java)
    private val buyer = Mockito.mock(SupplierSourcingAuthorizationPort::class.java)
    private val storage = Mockito.mock(EvidenceObjectStoragePort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val at = Instant.parse("2026-11-22T10:00:00Z")

    private fun record() = EvidenceFinalizeRecord(
        evidenceId = evidenceId,
        uploadSessionId = UUID.randomUUID(),
        organizationId = org,
        targetType = "SUPPLIER_QUOTE_DOCUMENT",
        targetId = slotId,
        workOrderId = null,
        workOrderLifecycleState = null,
        capturedByUserId = actor,
        evidenceRequirementKey = "SUPPLIER_QUOTE_BINARY",
        evidencePolicyId = null,
        evidencePolicyRevision = null,
        evidenceTypeCode = "SUPPLIER_QUOTE",
        contentType = "application/pdf",
        expectedSizeBytes = bytes.size.toLong(),
        expectedSha256 = digest,
        storageState = "READY",
        uploadState = "READY",
        objectKey = key,
        uploadExpiresAt = at.plusSeconds(600),
        classificationCode = "HIGHLY_RESTRICTED",
        clientVisibilityMode = "INTERNAL_ONLY",
        securityScanClass = "SIGNED_DOCUMENT",
        evidenceVersion = 2,
        uploadSessionVersion = 2,
    )

    private fun service(enabled: Boolean = true) = SupplierQuoteProtectedDocumentService(
        sourcing, evidence, verification, buyer,
        EvidenceStorageAccessPort { storage }, audit,
        Clock.fixed(at, ZoneOffset.UTC), enabled,
    )

    private fun allow() {
        Mockito.`when`(sourcing.quoteDocumentSource(quoteId)).thenReturn(source)
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(true)
        Mockito.`when`(verification.isVerified(actor, org, rfqId, supplierId, sourceRef, digest))
            .thenReturn(true)
        Mockito.`when`(evidence.loadEvidenceRecord(evidenceId)).thenReturn(record())
        Mockito.`when`(storage.readRestrictedObject(key, bytes.size.toLong())).thenReturn(bytes)
    }

    @Test fun authorizedBuyerReadsOnlyPrivateVerifiedBytesWithoutExposingStorageUrl() {
        allow()
        val document = service().read(actor, quoteId, "buyer-read")
        assertEquals(quoteId, document.quoteId)
        assertEquals("application/pdf", document.contentType)
        assertArrayEquals(bytes, document.bytes)
        Mockito.verify(storage).readRestrictedObject(key, bytes.size.toLong())
        val events = Mockito.mockingDetails(audit).invocations
            .filter { it.method.name == "append" }
            .map { it.arguments.single() as AuditEventRecord }
        assertEquals(1, events.size)
        assertEquals("PROCUREMENT_SUPPLIER_QUOTE_DOCUMENT_READ", events.single().action)
        assertEquals(quoteId, events.single().targetId)
    }

    @Test fun ordinaryNonbuyerCannotProbePrivateQuoteOrReadStorage() {
        allow()
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(false)
        val failure = assertThrows<ProductApiException> {
            service().read(actor, quoteId, "buyer-denied")
        }
        assertEquals("SUPPLIER_QUOTE_DOCUMENT_NOT_VISIBLE", failure.code)
        Mockito.verifyNoInteractions(storage)
    }

    @Test fun unverifiedSourceDoesNotReachObjectStorage() {
        allow()
        Mockito.`when`(verification.isVerified(actor, org, rfqId, supplierId, sourceRef, digest))
            .thenReturn(false)
        assertEquals("SUPPLIER_QUOTE_DOCUMENT_NOT_VISIBLE", assertThrows<ProductApiException> {
            service().read(actor, quoteId, "wrong-quote")
        }.code)
        Mockito.verifyNoInteractions(storage)
    }

    @Test fun quarantinedAndCrossOrganizationEvidenceNeverLeavesTheServer() {
        allow()
        Mockito.`when`(evidence.loadEvidenceRecord(evidenceId))
            .thenReturn(record().copy(storageState = "QUARANTINED"))
        assertEquals("SUPPLIER_QUOTE_DOCUMENT_NOT_VISIBLE", assertThrows<ProductApiException> {
            service().read(actor, quoteId, "quarantine")
        }.code)
        Mockito.`when`(evidence.loadEvidenceRecord(evidenceId))
            .thenReturn(record().copy(organizationId = UUID.randomUUID()))
        assertEquals("SUPPLIER_QUOTE_DOCUMENT_NOT_VISIBLE", assertThrows<ProductApiException> {
            service().read(actor, quoteId, "foreign")
        }.code)
        Mockito.verifyNoInteractions(storage)
    }

    @Test fun changedObjectBytesAreRejectedAfterProtectedRead() {
        allow()
        Mockito.`when`(storage.readRestrictedObject(key, bytes.size.toLong()))
            .thenReturn(ByteArray(bytes.size) { 42 })
        assertEquals("SUPPLIER_QUOTE_DOCUMENT_NOT_VISIBLE", assertThrows<ProductApiException> {
            service().read(actor, quoteId, "tampered")
        }.code)
        Mockito.verifyNoInteractions(audit)
    }

    @Test fun featureOffCannotFetchAnything() {
        allow()
        assertEquals("SUPPLIER_QUOTE_DOCUMENT_NOT_VISIBLE", assertThrows<ProductApiException> {
            service(false).read(actor, quoteId, "off")
        }.code)
        Mockito.verifyNoInteractions(storage)
    }

    @Test fun exactAssignedReviewReadsOriginalWithoutGrantingGeneralBuyerAccess() {
        allow()
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(false)
        Mockito.`when`(sourcing.quoteDocumentSlot(slotId)).thenReturn(SupplierQuoteDocumentSlot(slotId, UUID.randomUUID(), org, rfqId, supplierId, actor, at, "synthetic"))
        var checks = 0
        val result = service().readForAssignedCandidate(actor, candidate(), "synthetic-assigned-source") { checks++ }
        assertArrayEquals(bytes, result.bytes)
        assertEquals(2, checks)
        Mockito.verifyNoInteractions(buyer, verification)
    }

    @Test fun revokedAssignmentAfterObjectFetchDoesNotReturnBytesOrRecordDocumentRead() {
        allow()
        Mockito.`when`(sourcing.quoteDocumentSlot(slotId)).thenReturn(SupplierQuoteDocumentSlot(slotId, UUID.randomUUID(), org, rfqId, supplierId, actor, at, "synthetic"))
        var checks = 0
        assertThrows<ProductApiException> {
            service().readForAssignedCandidate(actor, candidate(), "synthetic-revoked-during-read") {
                checks++
                if (checks == 2) throw ProductApiException("SYNTHETIC_ASSIGNMENT_REVOKED", "Synthetic assignment revoked", HttpStatus.FORBIDDEN)
            }
        }
        Mockito.verify(storage).readRestrictedObject(key, bytes.size.toLong())
        Mockito.verifyNoInteractions(audit)
    }

    @Test fun differentCandidateQuoteHashDoesNotFetchStorage() {
        allow()
        assertThrows<ProductApiException> {
            service().readForAssignedCandidate(actor, candidate().copy(sourceSha256 = "b".repeat(64)), "synthetic-mismatched-source") {}
        }
        Mockito.verifyNoInteractions(storage, audit)
    }

    private fun candidate() = PurchaseOrderCandidateRecord(UUID.randomUUID(), UUID.randomUUID(), org, rfqId, UUID.randomUUID(), 2,
        quoteId, 1, supplierId, UUID.randomUUID(), BigDecimal.ONE, "EA", "EGP", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN,
        digest, "Synthetic supplier quotation selection", UUID.randomUUID(), at, "synthetic")
}
