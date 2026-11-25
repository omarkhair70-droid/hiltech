package com.hiltech.server.documents

import java.util.UUID

data class SupplierTechnicalDocumentEvidenceTarget(
    val slotId: UUID, val organizationId: UUID, val quoteId: UUID, val rfqId: UUID, val createdBy: UUID,
)

/** Technical reviewers open published packet originals through their exact-source reader. */
interface SupplierTechnicalDocumentEvidenceTargetPort {
    fun loadTarget(slotId: UUID): SupplierTechnicalDocumentEvidenceTarget?
    fun canReserve(actor: UUID, target: SupplierTechnicalDocumentEvidenceTarget): Boolean
    fun canAccess(actor: UUID, target: SupplierTechnicalDocumentEvidenceTarget): Boolean
}
