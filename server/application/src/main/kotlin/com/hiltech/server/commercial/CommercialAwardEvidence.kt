package com.hiltech.server.commercial

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Reuses canonical Evidence metadata instead of creating a second file-store.
 *
 * The current Evidence upload API supports WORK_ORDER / EMPLOYEE_DOCUMENT only,
 * so it CANNOT currently create either of these target types. This adapter is
 * fail-closed until the Evidence owner explicitly implements a commercial target,
 * with its own capture policy, access checks, scan, and company signoff.
 *
 * READY means verified bytes; the separate authorized SALES_AWARD decision is
 * an attestation that those bytes document a real signed contract/client PO.
 */
@Component
class JdbcCommercialAwardEvidenceGate(
    private val jdbc: JdbcTemplate,
) : CommercialAwardEvidenceGate {

    override fun verifiedEvidence(
        evidenceId: UUID,
        targetType: String,
        targetId: UUID,
        organizationId: UUID,
    ): Boolean {
        val evidenceType = when (targetType) {
            "COMMERCIAL_CONTRACT" -> "COMMERCIAL_SIGNED_CONTRACT"
            "COMMERCIAL_CLIENT_PO" -> "COMMERCIAL_CLIENT_PO"
            else -> return false
        }
        return jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM evidence e
              JOIN evidence_upload_session u ON u.evidence_id=e.id
              WHERE e.id=? AND e.organization_id=?
                AND e.target_type=? AND e.target_id=?
                AND e.evidence_type_code=?
                AND e.storage_state='READY' AND u.state='READY'
                AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
                AND e.object_key_ref IS NOT NULL
                AND e.classification_code='HIGHLY_RESTRICTED'
                AND e.client_visibility_mode='INTERNAL_ONLY'
                AND e.security_scan_class='ARBITRARY_FILE'
                AND e.content_type IN ('application/pdf','image/png','image/jpeg')
                AND e.sha256=u.expected_sha256
                AND e.size_bytes=u.expected_size_bytes
            )
            """.trimIndent(),
            Boolean::class.java,
            evidenceId, organizationId, targetType, targetId, evidenceType,
        ) == true
    }

    override fun verifiedAward(
        contractId: UUID,
        clientPoId: UUID,
        organizationId: UUID,
    ): Boolean {
        val record = jdbc.queryForList(
            """
            SELECT c.signed_evidence_id, p.evidence_id AS po_evidence_id
            FROM commercial_contract c
            JOIN commercial_client_po p ON p.contract_id=c.id
                AND p.organization_id=c.organization_id
                AND p.opportunity_id=c.opportunity_id
            WHERE c.id=? AND p.id=? AND c.organization_id=?
              AND c.state='SIGNED' AND p.verified=true
            """.trimIndent(),
            contractId, clientPoId, organizationId,
        ).singleOrNull() ?: return false

        val signedEvidenceId = record["signed_evidence_id"] as? UUID ?: return false
        val poEvidenceId = record["po_evidence_id"] as? UUID ?: return false

        // Revalidate immediately before conversion; evidence could have been
        // quarantined or rejected after an earlier approval.
        return verifiedEvidence(
            signedEvidenceId, "COMMERCIAL_CONTRACT", contractId, organizationId,
        ) && verifiedEvidence(
            poEvidenceId, "COMMERCIAL_CLIENT_PO", clientPoId, organizationId,
        )
    }
}
