package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.documents.SupplierInvoiceDocumentEvidenceTarget
import com.hiltech.server.documents.SupplierInvoiceDocumentEvidenceTargetPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Finance-bound access for invoice uploads. Company configuration, current
 * membership and the ACTIVE ApprovalEngine authority are re-checked each time.
 */
@Component
class SupplierInvoiceEvidenceTargetAdapter @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val clock: Clock,
    @Value("\${hiltech.procurement.supplier-invoices.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.invoice.finance-authority-key:}")
    private val financeAuthorityKey: String = "",
) : SupplierInvoiceDocumentEvidenceTargetPort {
    override fun loadTarget(slotId: UUID): SupplierInvoiceDocumentEvidenceTarget? =
        jdbc.query(
            """
            SELECT s.id, s.purchase_order_id, s.organization_id,
                   s.supplier_organization_id
            FROM procurement_supplier_invoice_document_slot s
            JOIN procurement_purchase_order p ON p.id=s.purchase_order_id
              AND p.organization_id=s.organization_id
              AND p.supplier_organization_id=s.supplier_organization_id
            WHERE s.id=?
            """.trimIndent(),
            { rs, _ -> SupplierInvoiceDocumentEvidenceTarget(
                rs.getObject("id", UUID::class.java),
                rs.getObject("purchase_order_id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getObject("supplier_organization_id", UUID::class.java),
            ) }, slotId,
        ).singleOrNull()

    override fun canReserve(actor: UUID, target: SupplierInvoiceDocumentEvidenceTarget): Boolean =
        financeAuthorized(actor, target.purchasingOrganizationId) &&
            loadTarget(target.slotId) == target

    override fun canAccess(actor: UUID, target: SupplierInvoiceDocumentEvidenceTarget): Boolean =
        canReserve(actor, target)

    private fun financeAuthorized(actor: UUID, org: UUID): Boolean {
        val key = financeAuthorityKey.trim()
        if (!enabled || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) return false
        val now = clock.instant()
        if (!approvals.isActiveOrganizationMember(org, actor, now)) return false
        val auth = approvals.resolveCurrentAuthority(org, key, now) ?: return false
        return auth.organizationId == org && auth.authorityKey == key &&
            approvals.actorMatchesAuthority(actor, auth, now)
    }
}
