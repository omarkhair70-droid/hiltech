package com.hiltech.server.procurement

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Canonical Evidence adapter. Buyer authorization is checked again here,
 * independently of the quote-write command. No caller-supplied URI, checksum,
 * or arbitrary Evidence from HR/work can bypass its supplier/RFQ document slot.
 */
@Component
@Primary
class JdbcSupplierQuoteDocumentVerification(
    private val jdbc: JdbcTemplate,
    private val authority: SupplierSourcingAuthorizationPort,
    private val suppliers: SupplierSourcingPersistence,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val enabled: Boolean = false,
) : SupplierQuoteDocumentVerificationPort {
    override fun isVerified(
        actor: UUID,
        purchasingOrganizationId: UUID,
        rfqId: UUID,
        supplierOrganizationId: UUID,
        sourceEvidenceRef: String,
        expectedSha256: String,
    ): Boolean {
        if (!enabled || !authority.canManageSourcing(actor, purchasingOrganizationId)) return false
        if (!suppliers.isEligibleSupplier(purchasingOrganizationId, supplierOrganizationId)) return false
        if (!Regex("^[0-9a-f]{64}$").matches(expectedSha256)) return false
        if (!sourceEvidenceRef.startsWith("evidence:")) return false
        val id = runCatching {
            UUID.fromString(sourceEvidenceRef.removePrefix("evidence:"))
        }.getOrNull() ?: return false
        return jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM procurement_supplier_quote_document_slot slot
                JOIN procurement_supplier_rfq rfq ON rfq.id = slot.rfq_id
                JOIN evidence e
                  ON e.target_type = 'SUPPLIER_QUOTE_DOCUMENT'
                 AND e.target_id = slot.id
                 AND e.organization_id = slot.organization_id
                JOIN evidence_upload_session us ON us.evidence_id = e.id
                WHERE e.id = ?
                  AND slot.organization_id = ?
                  AND slot.rfq_id = ?
                  AND slot.supplier_organization_id = ?
                  AND rfq.organization_id = slot.organization_id
                  AND e.sha256 = ?
                  AND us.expected_sha256 = ?
                  AND e.storage_state = 'READY'
                  AND us.state = 'READY'
                  AND e.finalized_at IS NOT NULL
                  AND us.finalized_at IS NOT NULL
                  AND e.object_key_ref IS NOT NULL
                  AND e.classification_code = 'HIGHLY_RESTRICTED'
                  AND e.client_visibility_mode = 'INTERNAL_ONLY'
                  AND e.evidence_requirement_key = 'SUPPLIER_QUOTE_BINARY'
                  AND e.evidence_type_code = 'SUPPLIER_QUOTE'
                  AND (
                      (e.content_type = 'application/pdf' AND e.security_scan_class = 'SIGNED_DOCUMENT')
                      OR (e.content_type IN ('image/jpeg', 'image/png', 'image/webp')
                          AND e.security_scan_class = 'NATIVE_MEDIA')
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            id, purchasingOrganizationId, rfqId, supplierOrganizationId,
            expectedSha256, expectedSha256,
        ) == true
    }
}
