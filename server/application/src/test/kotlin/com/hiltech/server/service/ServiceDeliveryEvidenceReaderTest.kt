package com.hiltech.server.service

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.storage.EvidenceObjectStoragePort
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServiceDeliveryEvidenceReaderTest {
    private val org = UUID.randomUUID(); private val project = UUID.randomUUID(); private val maker = UUID.randomUUID(); private val reviewer = UUID.randomUUID()
    private val at = Instant.parse("2026-11-25T14:00:00Z")
    private val repo = Mockito.mock(ServiceDeliveryRepository::class.java)
    private val sources = Mockito.mock(ServiceDeliverySources::class.java)
    private val subject = Mockito.mock(ServiceDeliveryApprovalSubject::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val bytes = ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(BufferedImage(40,30,BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val proof = ServiceDeliveryEvidenceSource(UUID.randomUUID(), org, project, "Synthetic client response", "CLIENT_RESPONSE", hash,
        "image/png", bytes.size.toLong(), "synthetic/protected-response", "SERVICE_DELIVERY_DOCUMENT", UUID.randomUUID(), "HIGHLY_RESTRICTED", "INTERNAL_ONLY")
    private val p = ServiceDeliveryProposal(UUID.randomUUID(), org, project, "HANDOVER_CLIENT_RESPONSE", "HANDOVER", 1, ServiceDeliveryDetails(),
        "a".repeat(64), 44, 1, UUID.randomUUID(), 1, reviewer, maker, at, "Synthetic project", "PROJECT-1", "Synthetic client", null)
    private var beforeRead: () -> Unit = {}
    private var stored = bytes
    private var reads = 0
    private val reader = ServiceDeliveryEvidenceReader(repo, sources, subject, object : EvidenceStorageAccessPort {
        override fun current(): EvidenceObjectStoragePort? = null
        override fun readRestrictedObject(objectKey: String, maxBytes: Long): ByteArray { reads++; beforeRead(); return stored }
    }, audit, Clock.fixed(at, ZoneOffset.UTC))
    private fun configure() {
        Mockito.`when`(repo.proposal(org, p.id)).thenReturn(p)
        Mockito.`when`(subject.canRead(reviewer, p)).thenReturn(true)
        Mockito.`when`(repo.proposalProofs(p)).thenReturn(listOf(proof.id to (hash to true)))
        Mockito.`when`(repo.evidence(org, project, proof.id, true)).thenReturn(proof)
    }
    @Test fun protectedRenderedPageAndDisplayedReceiptAreSeparateAndBoundToExactActorProposalAndHash() {
        configure()
        val page = reader.proposalPage(reviewer, org, p.id, proof.id, 0, "synthetic-preview")
        assertEquals(hash, page.originalSha256); assertEquals(1, page.pageCount); assertNotNull(page.viewReceiptId)
        assertTrue(repo.viewed(reviewer, p).isEmpty())
        val receipt = UUID.fromString(page.viewReceiptId)
        Mockito.`when`(repo.deliveredProof(reviewer, p, receipt)).thenReturn(proof.id to hash)
        assertTrue(reader.confirmDisplayed(reviewer, org, p.id, receipt, "synthetic-displayed").confirmed)
        assertThrows<ProductApiException> { reader.confirmDisplayed(maker, org, p.id, receipt, "synthetic-wrong-viewer") }
        assertThrows<ProductApiException> { reader.confirmDisplayed(reviewer, org, p.id, UUID.randomUUID(), "synthetic-guessed-receipt") }
    }
    @Test fun crossOrganizationUnrelatedEvidenceAndUnauthorizedIdentityCannotReadPrivateStorage() {
        configure()
        assertThrows<ProductApiException> { reader.proposalPage(reviewer, UUID.randomUUID(), p.id, proof.id, 0, "synthetic-scope") }
        assertThrows<ProductApiException> { reader.proposalPage(maker, org, p.id, proof.id, 0, "synthetic-scope") }
        assertThrows<ProductApiException> { reader.proposalPage(reviewer, org, p.id, UUID.randomUUID(), 0, "synthetic-scope") }
        assertEquals(0, reads)
    }
    @Test fun revocationDuringStorageReadAndChangedOriginalBytesCannotCreateOpeningProof() {
        configure()
        beforeRead = { Mockito.`when`(subject.canRead(reviewer, p)).thenReturn(false) }
        assertThrows<ProductApiException> { reader.proposalPage(reviewer, org, p.id, proof.id, 0, "synthetic-revoked") }
        assertTrue(Mockito.mockingDetails(repo).invocations.none { it.method.name.startsWith("recordView") })
        beforeRead = {}; Mockito.`when`(subject.canRead(reviewer, p)).thenReturn(true)
        stored = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertEquals("SERVICE_DELIVERY_EVIDENCE_BYTES_CHANGED", assertThrows<ProductApiException> {
            reader.proposalPage(reviewer, org, p.id, proof.id, 0, "synthetic-corrupt")
        }.code)
        assertTrue(Mockito.mockingDetails(repo).invocations.none { it.method.name.startsWith("recordView") })
    }
}
