package com.hiltech.server.service

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.ProtectedDocumentRasterizer
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.storage.EvidenceUploadSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Verified original bytes are rasterized inside a bounded, session-only reader; no public file URL or blanket Evidence grant. */
@Service
class ServiceDeliveryEvidenceReader(
    private val repository: ServiceDeliveryRepository,
    private val sources: ServiceDeliverySources,
    private val subject: ServiceDeliveryApprovalSubject,
    private val storage: EvidenceStorageAccessPort,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional
    fun proposalPage(actor: UUID, org: UUID, proposal: UUID, evidence: UUID, page: Int, correlation: String): ServiceDeliveryEvidencePageDto {
        val p = repository.proposal(org, proposal) ?: unavailable()
        if (!subject.canRead(actor, p)) unavailable()
        val pin = repository.proposalProofs(p).singleOrNull { it.first == evidence } ?: unavailable()
        val original = repository.evidence(org, p.projectId, evidence, historical = true)?.takeIf { it.sha256 == pin.second.first } ?: unavailable()
        val rendered = render(original, page)
        // Re-read source and current authority after storage and rendering. A revoked reviewer receives no page or receipt.
        if (!subject.canRead(actor, p) || repository.evidence(org, p.projectId, evidence, historical = true) != original) unavailable()
        val receipt = UUID.randomUUID()
        repository.recordView(receipt, actor, p, original, clock.instant())
        recordAudit(actor, p.projectId, evidence, proposal, receipt, correlation)
        return pageDto(original, proposal, page, rendered, receipt)
    }

    @Transactional
    fun projectPage(actor: UUID, project: UUID, evidence: UUID, page: Int, correlation: String): ServiceDeliveryEvidencePageDto {
        val owner = sources.manage(project, actor)
        val original = repository.evidence(owner.organizationId, project, evidence) ?: unavailable()
        val rendered = render(original, page)
        if (!sources.canManage(project, owner.organizationId, actor) || repository.evidence(owner.organizationId, project, evidence) != original) unavailable()
        recordAudit(actor, project, evidence, null, null, correlation)
        return pageDto(original, null, page, rendered, null)
    }

    @Transactional
    fun confirmDisplayed(actor: UUID, org: UUID, proposal: UUID, receipt: UUID, correlation: String): ServiceDeliveryDisplayDto {
        val p = repository.proposal(org, proposal) ?: unavailable()
        if (!subject.canRead(actor, p)) unavailable()
        val pin = repository.deliveredProof(actor, p, receipt) ?: unavailable()
        val original = repository.evidence(org, p.projectId, pin.first, historical = true)?.takeIf { it.sha256 == pin.second } ?: unavailable()
        if (repository.confirmDisplayed(actor, receipt, clock.instant())) audit.append(AuditEventRecord(
            actorUserId = actor, action = "SERVICE_DELIVERY_EVIDENCE_DISPLAY_CONFIRMED", targetType = "Evidence", targetId = original.id,
            occurredAt = clock.instant(), correlationId = correlation,
            configRevisionRefsJson = """{"proposalId":"${p.id}","viewReceiptId":"$receipt","humanReadingVerified":false}"""))
        return ServiceDeliveryDisplayDto(org.toString(), p.projectId.toString(), proposal.toString(), original.id.toString(), receipt.toString(), true)
    }

    internal fun originalBytes(source: ServiceDeliveryEvidenceSource): ByteArray {
        if (source.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) unavailable()
        val bytes = try { storage.readRestrictedObject(source.objectKey, source.sizeBytes) }
            catch (_: Exception) { null }
            ?: throw ProductApiException("SERVICE_DELIVERY_STORAGE_UNAVAILABLE", "The original document could not be opened. Try again.", HttpStatus.SERVICE_UNAVAILABLE, retryable = true)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (bytes.size.toLong() != source.sizeBytes || hash != source.sha256) deliveryFail("SERVICE_DELIVERY_EVIDENCE_BYTES_CHANGED")
        return bytes
    }

    private fun render(source: ServiceDeliveryEvidenceSource, page: Int): Pair<Int, String> {
        if (page !in 0..499) deliveryFail("SERVICE_DELIVERY_PAGE_INVALID", HttpStatus.BAD_REQUEST)
        val bytes = originalBytes(source)
        val result = runCatching { ProtectedDocumentRasterizer.render(bytes, source.contentType, page) }
            .getOrElse { deliveryFail("SERVICE_DELIVERY_PREVIEW_UNAVAILABLE") }
        return result.pageCount to Base64.getEncoder().encodeToString(result.png)
    }

    private fun pageDto(source: ServiceDeliveryEvidenceSource, proposal: UUID?, page: Int, rendered: Pair<Int, String>, receipt: UUID?) =
        ServiceDeliveryEvidencePageDto(source.organizationId.toString(), source.projectId.toString(), proposal?.toString(), source.id.toString(),
            source.title, source.contentType, source.sha256, page, rendered.first, rendered.second, receipt?.toString())

    private fun recordAudit(actor: UUID, project: UUID, evidence: UUID, proposal: UUID?, receipt: UUID?, correlation: String) = audit.append(AuditEventRecord(
        actorUserId = actor, action = "SERVICE_DELIVERY_EVIDENCE_OPENED", targetType = "Evidence", targetId = evidence,
        occurredAt = clock.instant(), correlationId = correlation,
        configRevisionRefsJson = """{"projectId":"$project","proposalId":${proposal?.let { "\"$it\"" } ?: "null"},"viewReceiptId":${receipt?.let { "\"$it\"" } ?: "null"}}""",
    ))
    private fun unavailable(): Nothing = deliveryFail("SERVICE_DELIVERY_EVIDENCE_NOT_FOUND", HttpStatus.NOT_FOUND)
}
