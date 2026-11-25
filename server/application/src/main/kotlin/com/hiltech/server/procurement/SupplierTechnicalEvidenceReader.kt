package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.ProtectedDocumentRasterizer
import com.hiltech.server.documents.storage.EvidenceUploadSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Only separately curated technical originals are eligible. Priced quotation Evidence is never resolved here. */
@Service
class SupplierTechnicalEvidenceReader(
    private val repository: SupplierTechnicalReviewRepository,
    private val source: SupplierTechnicalReviewSource,
    private val storage: EvidenceStorageAccessPort,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional
    fun packetPage(actor: UUID, quoteId: UUID, packetId: UUID, evidenceId: UUID, page: Int, correlation: String): SupplierTechnicalEvidencePageDto {
        val quote = source.accessibleQuote(actor, quoteId)
        val packet = repository.packet(quote.organizationId, quoteId, packetId) ?: unavailable()
        val pin = repository.proofReferences(packet.id).singleOrNull { it.first == evidenceId } ?: unavailable()
        val original = repository.evidence(quote.organizationId, quoteId, evidenceId)?.takeIf { it.sha == pin.second } ?: unavailable()
        val rendered = render(original, page)
        // Revocation or quarantine during the private object read yields neither a page nor a receipt.
        source.accessibleQuote(actor, quoteId)
        if (repository.evidence(quote.organizationId, quoteId, evidenceId) != original) unavailable()
        val receipt = UUID.randomUUID()
        repository.recordView(receipt, actor, packet, original, clock.instant())
        recordAudit(actor, original.id, packet.id, receipt, correlation)
        return dto(original, packet.id, rendered, receipt)
    }

    @Transactional
    fun buyerPage(actor: UUID, quoteId: UUID, evidenceId: UUID, page: Int, correlation: String): SupplierTechnicalEvidencePageDto {
        val quote = source.accessibleQuote(actor, quoteId, prepare = true)
        val original = repository.evidence(quote.organizationId, quoteId, evidenceId) ?: unavailable()
        val rendered = render(original, page)
        source.accessibleQuote(actor, quoteId, prepare = true)
        if (repository.evidence(quote.organizationId, quoteId, evidenceId) != original) unavailable()
        recordAudit(actor, original.id, null, null, correlation)
        return dto(original, null, rendered, null)
    }

    @Transactional
    fun confirmDisplayed(actor: UUID, quoteId: UUID, packetId: UUID, receipt: UUID, correlation: String): SupplierTechnicalDisplayDto {
        val quote = source.accessibleQuote(actor, quoteId)
        val packet = repository.packet(quote.organizationId, quoteId, packetId) ?: unavailable()
        val pin = repository.deliveredProof(actor, packet, receipt) ?: unavailable()
        val original = repository.evidence(quote.organizationId, quoteId, pin.first)?.takeIf { it.sha == pin.second } ?: unavailable()
        if (repository.confirmDisplayed(actor, receipt, clock.instant())) audit.append(AuditEventRecord(
            actorUserId = actor, action = "SUPPLIER_TECHNICAL_ORIGINAL_DISPLAYED", targetType = "Evidence", targetId = original.id,
            occurredAt = clock.instant(), correlationId = correlation,
            configRevisionRefsJson = """{"technicalPacketId":"$packetId","viewReceiptId":"$receipt","humanReadingVerified":false}"""))
        return SupplierTechnicalDisplayDto(quote.organizationId.toString(), quoteId.toString(), packetId.toString(), original.id.toString(), receipt.toString(), true)
    }

    private fun render(original: SupplierTechnicalEvidenceSource, page: Int): com.hiltech.server.documents.ProtectedDocumentRasterPage {
        if (page !in 0..499 || original.size !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES ||
            !original.key.startsWith("org/${original.organizationId}/evidence/${original.id}/objects/")) unavailable()
        val bytes = runCatching { storage.readRestrictedObject(original.key, original.size) }.getOrNull()
            ?: throw ProductApiException("SUPPLIER_TECHNICAL_STORAGE_UNAVAILABLE", "The technical original could not be opened. Try again.", HttpStatus.SERVICE_UNAVAILABLE, retryable = true)
        if (bytes.size.toLong() != original.size || technicalHash(bytes) != original.sha) technicalFail("SUPPLIER_TECHNICAL_ORIGINAL_CHANGED")
        return runCatching { ProtectedDocumentRasterizer.render(bytes, original.contentType, page) }
            .getOrElse { technicalFail("SUPPLIER_TECHNICAL_PREVIEW_UNAVAILABLE") }
    }

    private fun dto(original: SupplierTechnicalEvidenceSource, packet: UUID?, page: com.hiltech.server.documents.ProtectedDocumentRasterPage, receipt: UUID?) =
        SupplierTechnicalEvidencePageDto(original.organizationId.toString(), original.quoteId.toString(), packet?.toString(), original.id.toString(),
            original.title, original.contentType, original.sha, page.pageIndex, page.pageCount, Base64.getEncoder().encodeToString(page.png), receipt?.toString())

    private fun recordAudit(actor: UUID, evidence: UUID, packet: UUID?, receipt: UUID?, correlation: String) = audit.append(AuditEventRecord(
        actorUserId = actor, action = "SUPPLIER_TECHNICAL_ORIGINAL_OPENED", targetType = "Evidence", targetId = evidence, occurredAt = clock.instant(), correlationId = correlation,
        configRevisionRefsJson = """{"technicalPacketId":${packet?.let { "\"$it\"" } ?: "null"},"viewReceiptId":${receipt?.let { "\"$it\"" } ?: "null"}}"""))

    private fun unavailable(): Nothing = technicalFail("SUPPLIER_TECHNICAL_ORIGINAL_UNAVAILABLE", HttpStatus.NOT_FOUND)
}
