package com.hiltech.server.commercial

import com.hiltech.server.documents.CommercialEvidenceTargetPort
import com.hiltech.server.documents.CommercialEvidenceTargetSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Phase 10 integration to the canonical Evidence lifecycle. Does not own
 * storage, upload sessions or binary finalization.
 */
@Component
class JdbcCommercialEvidenceTarget(
    private val jdbc: JdbcTemplate,
    private val access: CommercialAccess,
) : CommercialEvidenceTargetPort {
    override fun loadTarget(
        targetType: String,
        targetId: UUID,
    ): CommercialEvidenceTargetSnapshot? {
        val record = when (targetType) {
            "COMMERCIAL_CONTRACT" -> jdbc.queryForList(
                """SELECT c.organization_id, c.state AS document_state,
                          o.state AS opportunity_state, q.state AS quote_state
                   FROM commercial_contract c
                   JOIN commercial_opportunity o ON o.id=c.opportunity_id
                     AND o.organization_id=c.organization_id
                   JOIN commercial_quote_version q ON q.id=c.quote_version_id
                     AND q.opportunity_id=c.opportunity_id
                     AND q.organization_id=c.organization_id
                   WHERE c.id=?""",
                targetId,
            ).singleOrNull()
            "COMMERCIAL_CLIENT_PO" -> jdbc.queryForList(
                """SELECT p.organization_id,
                          CASE WHEN p.verified THEN 'VERIFIED' ELSE 'DRAFT' END AS document_state,
                          o.state AS opportunity_state,
                          q.state AS quote_state,
                          c.state AS contract_state
                   FROM commercial_client_po p
                   JOIN commercial_contract c ON c.id=p.contract_id
                     AND c.opportunity_id=p.opportunity_id
                     AND c.organization_id=p.organization_id
                   JOIN commercial_opportunity o ON o.id=p.opportunity_id
                     AND o.organization_id=p.organization_id
                   JOIN commercial_quote_version q ON q.id=c.quote_version_id
                     AND q.organization_id=p.organization_id
                     AND q.opportunity_id=p.opportunity_id
                   WHERE p.id=?""",
                targetId,
            ).singleOrNull()
            else -> null
        } ?: return null
        val mayReserve = record["document_state"] == "DRAFT" &&
            record["quote_state"] == "RELEASED" &&
            record["opportunity_state"] in setOf("OPEN", "QUALIFIED") &&
            (targetType == "COMMERCIAL_CONTRACT" || record["contract_state"] == "SIGNED")
        return CommercialEvidenceTargetSnapshot(
            targetType = targetType,
            targetId = targetId,
            organizationId = record["organization_id"] as UUID,
            mayReserve = mayReserve,
        )
    }

    override fun canAccess(
        actorIdentityId: UUID,
        target: CommercialEvidenceTargetSnapshot,
    ): Boolean = runCatching {
        access.require(actorIdentityId, target.organizationId, "SALES_AWARD")
        true
    }.getOrDefault(false)
}
