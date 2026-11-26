package com.hiltech.server.documents

import java.util.UUID

/** Private supplier quote upload target for a non-stock service RFQ.
 * This must not reuse the StockItem-bound SUPPLIER_QUOTE_DOCUMENT target. */
data class ServiceQuoteDocumentEvidenceTarget(
    val slotId: UUID,
    val rfqId: UUID,
    val purchasingOrganizationId: UUID,
    val supplierOrganizationId: UUID,
)

interface ServiceQuoteDocumentEvidenceTargetPort {
    fun loadTarget(slotId: UUID): ServiceQuoteDocumentEvidenceTarget?
    fun canReserve(identityId: UUID, target: ServiceQuoteDocumentEvidenceTarget): Boolean
    fun canAccess(identityId: UUID, target: ServiceQuoteDocumentEvidenceTarget): Boolean
}
