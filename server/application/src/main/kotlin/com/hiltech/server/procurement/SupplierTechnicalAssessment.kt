package com.hiltech.server.procurement

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Technical assessment is not a purchasing approval or a supplier award. */
interface SupplierQuoteTechnicalReviewerAuthorizationPort {
    fun canReviewOrganization(actor: UUID, organizationId: UUID): Boolean = false
    fun canReviewQuote(actor: UUID, organizationId: UUID, rfqId: UUID): Boolean
}

/** Until a real organization policy is bound, no actor can review supplier quotes. */
@Component
class DenyAllSupplierQuoteTechnicalReviewerAuthorization : SupplierQuoteTechnicalReviewerAuthorizationPort {
    override fun canReviewQuote(actor: UUID, organizationId: UUID, rfqId: UUID): Boolean = false
}

data class RecordSupplierQuoteTechnicalAssessmentRequest(
    val operationId: UUID,
    val expectedReviewRevision: Int,
    val decision: String,
    val justification: String,
    val technicalPacketId: UUID? = null,
    val packetReviewed: Boolean = false,
)

data class SupplierQuoteTechnicalTarget(
    val quoteEvidenceId: UUID,
    val rfqId: UUID,
    val organizationId: UUID,
)

data class SupplierQuoteTechnicalAssessmentRecord(
    val id: UUID,
    val operationId: UUID,
    val quoteEvidenceId: UUID,
    val rfqId: UUID,
    val organizationId: UUID,
    val reviewRevision: Int,
    val decision: String,
    val justification: String,
    val reviewedBy: UUID,
    val reviewedAt: Instant,
    val correlationId: String,
    val technicalPacketId: UUID? = null,
    val packetReviewed: Boolean = false,
)

/** Called inside the canonical RFQ lock, including the original assessment API. */
interface SupplierTechnicalAssessmentSourcePort {
    fun requireCurrentAssessment(actor: UUID, quoteId: UUID, packetId: UUID?, packetReviewed: Boolean, decision: String)
}

data class SupplierQuoteTechnicalAssessmentReceipt(
    val id: UUID,
    val quoteEvidenceId: UUID,
    val reviewRevision: Int,
    val decision: String,
    val replayed: Boolean,
)
