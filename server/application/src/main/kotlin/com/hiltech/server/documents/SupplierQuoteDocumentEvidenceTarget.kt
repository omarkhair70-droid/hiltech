package com.hiltech.server.documents

import java.util.UUID

/** Restricts generic Evidence lifecycle calls to an immutable supplier/RFQ slot. */
data class SupplierQuoteDocumentEvidenceTarget(
    val slotId: UUID,
    val rfqId: UUID,
    val purchasingOrganizationId: UUID,
    val supplierOrganizationId: UUID,
)

interface SupplierQuoteDocumentEvidenceTargetPort {
    fun loadTarget(slotId: UUID): SupplierQuoteDocumentEvidenceTarget?
    fun canReserve(identityId: UUID, target: SupplierQuoteDocumentEvidenceTarget): Boolean
    fun canAccess(identityId: UUID, target: SupplierQuoteDocumentEvidenceTarget): Boolean
}
