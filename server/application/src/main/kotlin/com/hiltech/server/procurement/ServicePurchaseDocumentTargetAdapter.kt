package com.hiltech.server.procurement

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.documents.ServicePurchaseDocumentEvidenceTarget
import com.hiltech.server.documents.ServicePurchaseDocumentEvidenceTargetPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Document access follows the current operating source used by its workflow. */
@Component
class ServicePurchaseDocumentTargetAdapter @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val clock:Clock,
    @Value("\${hiltech.procurement.service-orders.enabled:false}")
    private val enabled:Boolean=false,
    @Value("\${hiltech.procurement.service-acceptance.enabled:false}")
    private val acceptanceEnabled:Boolean=false,
    @Value("\${hiltech.procurement.service-invoices.enabled:false}")
    private val invoiceEnabled:Boolean=false,
    @Value("\${hiltech.procurement.service-acceptance.authority-key:PROCUREMENT_SERVICE_ACCEPT}")
    private val acceptingAuthorityKey:String="",
    @Value("\${hiltech.procurement.service-invoices.finance-authority-key:FINANCE_SUPPLIER_REVIEW}")
    private val financeAuthorityKey:String="",
    private val operationalAuthority: OperationalAuthorityPort? = null,
) : ServicePurchaseDocumentEvidenceTargetPort {
    override fun loadTarget(id:UUID):ServicePurchaseDocumentEvidenceTarget? =
        jdbc.query(
            """
            SELECT slot.id,slot.service_order_id,slot.organization_id,slot.document_kind
            FROM procurement_service_document_slot slot
            JOIN procurement_service_order o ON o.id=slot.service_order_id
              AND o.organization_id=slot.organization_id
            JOIN organization org ON org.id=slot.organization_id
            WHERE slot.id=? AND org.organization_type='HILTECH'
              AND org.status='ACTIVE'
            """.trimIndent(),
            {rs,_->ServicePurchaseDocumentEvidenceTarget(
                rs.getObject("id",UUID::class.java),
                rs.getObject("service_order_id",UUID::class.java),
                rs.getObject("organization_id",UUID::class.java),
                rs.getString("document_kind"))},id,
        ).singleOrNull()

    override fun canReserve(actor:UUID,target:ServicePurchaseDocumentEvidenceTarget):Boolean =
        authorized(actor,target) && loadTarget(target.slotId)==target

    override fun canAccess(actor:UUID,target:ServicePurchaseDocumentEvidenceTarget):Boolean =
        canReserve(actor,target)

    private fun authorized(actor:UUID,t:ServicePurchaseDocumentEvidenceTarget):Boolean {
        if(!enabled)return false
        val (active,property,key)=when(t.documentKind) {
            "DELIVERY" -> Triple(acceptanceEnabled,
                "hiltech.procurement.service-acceptance.authority-key", acceptingAuthorityKey)
            "INVOICE" -> Triple(invoiceEnabled,
                "hiltech.procurement.service-invoices.finance-authority-key", financeAuthorityKey)
            else -> return false
        }
        if(!active)return false
        return currentOperationalAuthority(operationalAuthority, approvals, actor,
            t.organizationId, property, key, clock.instant()) != null
    }
}
