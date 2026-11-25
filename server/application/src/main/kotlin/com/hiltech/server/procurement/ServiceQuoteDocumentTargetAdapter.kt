package com.hiltech.server.procurement

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.documents.ServiceQuoteDocumentEvidenceTarget
import com.hiltech.server.documents.ServiceQuoteDocumentEvidenceTargetPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Service quotes must have their own supplier-and-RFQ scoped private document
 * slot. Stock-material RFQs cannot be forged to satisfy a service order.
 */
@Component
class ServiceQuoteDocumentTargetAdapter @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val suppliers: SupplierSourcingPersistence,
    private val clock: Clock,
    @Value("\${hiltech.procurement.service-sourcing.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.service-sourcing.buyer-authority-key:PROCUREMENT_BUYER}")
    private val buyerKey: String = "",
    private val operationalAuthority: OperationalAuthorityPort? = null,
) : ServiceQuoteDocumentEvidenceTargetPort {
    override fun loadTarget(slotId: UUID): ServiceQuoteDocumentEvidenceTarget? =
        jdbc.query(
            """
            SELECT slot.id,slot.rfq_id,slot.organization_id,slot.supplier_organization_id
            FROM procurement_service_quote_document_slot slot
            JOIN procurement_service_rfq rfq ON rfq.id=slot.rfq_id
              AND rfq.organization_id=slot.organization_id
            JOIN procurement_service_need root ON root.id=rfq.service_need_id
              AND root.organization_id=rfq.organization_id
            JOIN team t ON t.id=root.team_id AND t.organization_id=root.organization_id
            JOIN organization o ON o.id=root.organization_id
            JOIN LATERAL (
                SELECT result_version,to_state FROM procurement_service_need_event e
                WHERE e.need_id=root.id ORDER BY result_version DESC LIMIT 1
            ) last ON true
            WHERE slot.id=? AND last.result_version=rfq.service_need_version
              AND last.to_state='READY_FOR_SOURCING'
              AND t.version=root.team_version AND t.active=true
              AND o.status='ACTIVE' AND o.organization_type='HILTECH'
            """.trimIndent(),
            { rs, _ -> ServiceQuoteDocumentEvidenceTarget(
                rs.getObject("id",UUID::class.java),
                rs.getObject("rfq_id",UUID::class.java),
                rs.getObject("organization_id",UUID::class.java),
                rs.getObject("supplier_organization_id",UUID::class.java),
            ) },slotId,
        ).singleOrNull()

    override fun canReserve(identityId: UUID, target: ServiceQuoteDocumentEvidenceTarget):
        Boolean = authorized(identityId,target.purchasingOrganizationId) &&
        suppliers.isEligibleSupplier(target.purchasingOrganizationId,target.supplierOrganizationId) &&
        loadTarget(target.slotId)==target

    override fun canAccess(identityId: UUID, target: ServiceQuoteDocumentEvidenceTarget):
        Boolean = canReserve(identityId,target)

    private fun authorized(actor: UUID, org: UUID): Boolean {
        if (!enabled) return false
        return currentOperationalAuthority(operationalAuthority, approvals, actor, org,
            "hiltech.procurement.service-sourcing.buyer-authority-key", buyerKey, clock.instant()) != null
    }
}
