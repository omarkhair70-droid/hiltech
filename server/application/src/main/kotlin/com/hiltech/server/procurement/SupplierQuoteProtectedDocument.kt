package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.storage.EvidenceUploadSpec
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class ProtectedSupplierQuoteDocument(
    val quoteId: UUID,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * Protected retrieval for an authorized internal buyer only.
 *
 * A supplier quotation is a HIGHLY_RESTRICTED financial document, so generic
 * Evidence presigned downloads are forbidden and technical reviewers do not
 * implicitly inherit buyer-price access. No object key or S3 link is returned.
 */
@Service
class SupplierQuoteProtectedDocumentService @Autowired constructor(
    private val sourcing: SupplierSourcingPersistence,
    private val evidence: EvidencePersistencePort,
    private val verification: SupplierQuoteDocumentVerificationPort,
    private val buyerAuthorization: SupplierSourcingAuthorizationPort,
    private val storageAccess: EvidenceStorageAccessPort,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun read(actor: UUID, quoteId: UUID, correlationId: String): ProtectedSupplierQuoteDocument {
        if (!enabled) unavailable()
        val source = sourcing.quoteDocumentSource(quoteId) ?: unavailable()
        if (!buyerAuthorization.canManageSourcing(actor, source.organizationId)) unavailable()

        if (!verification.isVerified(
                actor, source.organizationId, source.rfqId,
                source.supplierOrganizationId, source.sourceEvidenceRef, source.sourceSha256,
            )
        ) unavailable()
        if (!source.sourceEvidenceRef.startsWith("evidence:")) unavailable()
        val evidenceId = runCatching {
            UUID.fromString(source.sourceEvidenceRef.removePrefix("evidence:"))
        }.getOrNull() ?: unavailable()
        val recorded = evidence.loadEvidenceRecord(evidenceId) ?: unavailable()
        if (recorded.organizationId != source.organizationId ||
            recorded.evidenceId != evidenceId ||
            recorded.targetType != "SUPPLIER_QUOTE_DOCUMENT" ||
            recorded.evidenceRequirementKey != "SUPPLIER_QUOTE_BINARY" ||
            recorded.evidenceTypeCode != "SUPPLIER_QUOTE" ||
            recorded.storageState != "READY" || recorded.uploadState != "READY" ||
            recorded.classificationCode != "HIGHLY_RESTRICTED" ||
            recorded.clientVisibilityMode != "INTERNAL_ONLY" ||
            recorded.expectedSha256 != source.sourceSha256 ||
            recorded.expectedSizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES
        ) unavailable()
        val expectedScanClass = when (recorded.contentType) {
            "application/pdf" -> "SIGNED_DOCUMENT"
            "image/jpeg", "image/png", "image/webp" -> "NATIVE_MEDIA"
            else -> unavailable()
        }
        if (recorded.securityScanClass != expectedScanClass) unavailable()
        val prefix = "org/${source.organizationId}/evidence/$evidenceId/objects/"
        if (!recorded.objectKey.startsWith(prefix) ||
            runCatching { UUID.fromString(recorded.objectKey.removePrefix(prefix)) }.isFailure
        ) unavailable()
        val storage = storageAccess.current() ?: unavailable()
        val bytes = try {
            storage.readRestrictedObject(recorded.objectKey, recorded.expectedSizeBytes)
        } catch (_: Exception) {
            throw ProductApiException(
                "SUPPLIER_DOCUMENT_DELIVERY_UNAVAILABLE",
                "Protected quotation document could not be delivered.",
                HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
        if (bytes.size.toLong() != recorded.expectedSizeBytes ||
            !MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(bytes),
                hexBytes(recorded.expectedSha256),
            ) ||
            !matchesMagic(bytes, recorded.contentType)
        ) unavailable()

        audit.append(AuditEventRecord(
            actorUserId = actor,
            action = "PROCUREMENT_SUPPLIER_QUOTE_DOCUMENT_READ",
            targetType = "SUPPLIER_QUOTE_EVIDENCE",
            targetId = quoteId,
            occurredAt = clock.instant(),
            correlationId = correlationId,
            reason = "PROTECTED_SERVER_DELIVERY",
        ))
        return ProtectedSupplierQuoteDocument(quoteId, recorded.contentType, bytes)
    }

    private fun hexBytes(value: String): ByteArray {
        if (!Regex("^[0-9a-f]{64}$").matches(value)) unavailable()
        return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun matchesMagic(bytes: ByteArray, contentType: String): Boolean =
        when (contentType) {
            "application/pdf" -> bytes.size >= 5 &&
                bytes.copyOfRange(0, 5).decodeToString() == "%PDF-"
            "image/jpeg" -> bytes.size >= 3 &&
                bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte()
            "image/png" -> bytes.size >= 8 &&
                bytes.sliceArray(0..7).contentEquals(byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                ))
            "image/webp" -> bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
            else -> false
        }

    private fun unavailable(): Nothing = throw ProductApiException(
        "SUPPLIER_QUOTE_DOCUMENT_NOT_VISIBLE",
        "This quotation document is not available.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class SupplierQuoteProtectedDocumentController(
    private val delivery: SupplierQuoteProtectedDocumentService,
) {
    /**
     * POST avoids cacheable public retrieval semantics. The response contains
     * verified bytes only; all object storage URLs remain private.
     */
    @PostMapping("/v1/sourcing/quotes/{quoteId}/protected-document")
    fun read(
        request: HttpServletRequest,
        @PathVariable quoteId: UUID,
    ): ResponseEntity<ByteArray> {
        val context = HiltechRequestContext.current(request)
        val actor = context.identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
            )
        val document = delivery.read(actor, quoteId, context.correlationId)
        val extension = when (document.contentType) {
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw IllegalStateException("Restricted document MIME changed after verification.")
        }
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(document.contentType)
        headers.contentLength = document.bytes.size.toLong()
        headers.cacheControl = CacheControl.noStore().headerValue
        headers.set("Pragma", "no-cache")
        headers.set("X-Content-Type-Options", "nosniff")
        headers.contentDisposition = ContentDisposition.attachment()
            .filename("supplier-quote-${document.quoteId}.$extension").build()
        return ResponseEntity.status(HttpStatus.OK).headers(headers).body(document.bytes)
    }
}
