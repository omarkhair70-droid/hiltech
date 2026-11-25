package com.hiltech.server.procurement

import com.hiltech.server.documents.SupplierTechnicalDocumentEvidenceTarget
import com.hiltech.server.documents.SupplierTechnicalDocumentEvidenceTargetPort
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SupplierTechnicalDocumentTarget(
    private val repository: SupplierTechnicalReviewRepository,
    private val source: SupplierTechnicalReviewSource,
) : SupplierTechnicalDocumentEvidenceTargetPort {
    override fun loadTarget(slotId: UUID) = repository.slot(slotId)?.let {
        SupplierTechnicalDocumentEvidenceTarget(it.id, it.organizationId, it.quoteId, it.rfqId, it.creator)
    }

    override fun canReserve(actor: UUID, target: SupplierTechnicalDocumentEvidenceTarget): Boolean {
        if (actor != target.createdBy || !canAccess(actor, target)) return false
        val quote = repository.quote(target.quoteId) ?: return false
        val current = source.describe(quote)
        return current.sourceCurrent && current.latestQuote
    }

    override fun canAccess(actor: UUID, target: SupplierTechnicalDocumentEvidenceTarget): Boolean =
        source.canPrepare(actor, target.organizationId) && repository.quote(target.quoteId)?.let {
            it.organizationId == target.organizationId && it.rfqId == target.rfqId
        } == true
}
