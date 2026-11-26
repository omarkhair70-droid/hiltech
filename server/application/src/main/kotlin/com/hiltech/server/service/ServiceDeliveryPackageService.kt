package com.hiltech.server.service

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Service
class ServiceDeliveryPackageService(
    private val repository: ServiceDeliveryRepository,
    private val sources: ServiceDeliverySources,
    private val evidence: ServiceDeliveryEvidenceReader,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional
    fun download(actor: UUID, project: UUID, releaseId: UUID, selectedEvidence: UUID?, correlation: String): ServiceDeliveryPackageDto {
        val owner = sources.manage(project, actor)
        val release = repository.activeRelease(owner.organizationId, project)?.takeIf { it.id == releaseId }
            ?: deliveryFail("SERVICE_DELIVERY_RELEASE_CHANGED")
        val p = release.proposal
        val documents = p.packageDocuments.mapIndexed { index, document ->
            val source = repository.evidence(owner.organizationId, project, deliveryUuid(document.evidenceId), historical = true)
                ?.takeIf { it.sha256 == document.sha256 && it.classification != "HIGHLY_RESTRICTED" }
                ?: deliveryFail("SERVICE_DELIVERY_PROOF_NOT_READY")
            Triple(document, source, documentName(index, document, source.contentType))
        }
        if (documents.isEmpty()) deliveryFail("SERVICE_DELIVERY_PACKAGE_NOT_READY")
        val selected = selectedEvidence?.let { id -> documents.singleOrNull { it.second.id == id } ?: deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND) }
        val filename: String
        val contentType: String
        val bytes: ByteArray
        if (selected != null) {
            filename = selected.third
            contentType = selected.second.contentType.takeIf { it in setOf("application/pdf", "image/png", "image/jpeg", "text/csv") } ?: "application/octet-stream"
            bytes = evidence.originalBytes(selected.second)
        } else {
            // Large projects can export the same approved originals individually without inventing a second release.
            if (documents.sumOf { it.second.sizeBytes } > 48L * 1024L * 1024L) deliveryFail("SERVICE_DELIVERY_PACKAGE_USE_INDIVIDUAL_DOCUMENTS")
            filename = "Handover-" + safeName(p.projectCode) + "-" + release.number + ".zip"
            contentType = "application/zip"
            val cover = ServiceDeliveryManifestPdf.render(release)
            bytes = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    fun entry(name: String, data: ByteArray) {
                        zip.putNextEntry(ZipEntry(name).also { it.time = release.recordedAt.toEpochMilli() })
                        zip.write(data); zip.closeEntry()
                    }
                    entry("Handover-index.pdf", cover)
                    documents.forEach { (_, original, name) -> entry("Documents/" + name, evidence.originalBytes(original)) }
                }
                output.toByteArray()
            }
            if (bytes.size > 64 * 1024 * 1024) deliveryFail("SERVICE_DELIVERY_PACKAGE_USE_INDIVIDUAL_DOCUMENTS")
        }
        // Export authority, active release and original hashes are checked after the storage reads as well.
        if (!sources.canManage(project, owner.organizationId, actor) || repository.activeRelease(owner.organizationId, project)?.id != releaseId ||
            documents.any { (_, source, _) -> repository.evidence(owner.organizationId, project, source.id, historical = true) != source })
            deliveryFail("SERVICE_DELIVERY_RELEASE_CHANGED")
        audit.append(AuditEventRecord(actorUserId = actor, action = "SERVICE_HANDOVER_PACKAGE_EXPORTED", targetType = "ServiceDeliveryEvent", targetId = releaseId,
            occurredAt = clock.instant(), correlationId = correlation,
            configRevisionRefsJson = """{"projectId":"$project","documentCount":${if (selected == null) documents.size else 1},"externalSend":false}"""))
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ServiceDeliveryPackageDto(project.toString(), releaseId.toString(), filename, contentType, Base64.getEncoder().encodeToString(bytes), sha)
    }

    private fun documentName(index: Int, document: HandoverDocumentDto, contentType: String): String {
        val extension = when (contentType) { "application/pdf" -> ".pdf"; "image/png" -> ".png"; "image/jpeg" -> ".jpg"; "text/csv" -> ".csv"; else -> ".bin" }
        return (index + 1).toString().padStart(3, '0') + "-" + safeName(document.documentCode.ifBlank { document.documentTitle }) + "-" + safeName(document.revisionLabel) + extension
    }
    private fun safeName(value: String): String = value.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(100).ifBlank { "Document" }
}
