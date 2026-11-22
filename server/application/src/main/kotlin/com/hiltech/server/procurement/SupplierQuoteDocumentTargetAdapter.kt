package com.hiltech.server.procurement

import com.hiltech.server.documents.SupplierQuoteDocumentEvidenceTarget
import com.hiltech.server.documents.SupplierQuoteDocumentEvidenceTargetPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/** Source-bound buyer authorization, not generic Evidence creator access. */
@Component
class SupplierQuoteDocumentTargetAdapter(
    private val sourcing: SupplierSourcingPersistence,
    private val authority: SupplierSourcingAuthorizationPort,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val enabled: Boolean = false,
) : SupplierQuoteDocumentEvidenceTargetPort {
    override fun loadTarget(slotId: UUID): SupplierQuoteDocumentEvidenceTarget? {
        val slot = sourcing.quoteDocumentSlot(slotId) ?: return null
        val rfq = sourcing.rfq(slot.rfqId) ?: return null
        if (slot.organizationId != rfq.organizationId) return null
        return SupplierQuoteDocumentEvidenceTarget(
            slot.id, slot.rfqId, slot.organizationId, slot.supplierOrganizationId,
        )
    }

    override fun canReserve(
        identityId: UUID,
        target: SupplierQuoteDocumentEvidenceTarget,
    ): Boolean = enabled && authority.canManageSourcing(
        identityId, target.purchasingOrganizationId,
    ) && sourcing.isEligibleSupplier(
        target.purchasingOrganizationId, target.supplierOrganizationId,
    ) && loadTarget(target.slotId) == target

    override fun canAccess(
        identityId: UUID,
        target: SupplierQuoteDocumentEvidenceTarget,
    ): Boolean = canReserve(identityId, target)
}
