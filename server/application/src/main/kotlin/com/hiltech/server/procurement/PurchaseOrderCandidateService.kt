package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class PurchaseOrderCandidatePreview(
    val rfqId: UUID,
    val quoteId: UUID,
    val supplierOrganizationId: UUID?,
    val sourceCurrent: Boolean,
    val quoteLatest: Boolean,
    val quotedTotal: String?,
    val currency: String?,
    val blockers: List<String>,
    val canPropose: Boolean,
    val purchaseAuthorized: Boolean = false,
    val purchaseOrderIssued: Boolean = false,
)

data class ProposePurchaseOrderCandidateRequest(
    val operationId: UUID,
    val quoteEvidenceId: UUID,
    val expectedQuoteRevision: Int,
    val rationale: String,
)

data class PurchaseOrderCandidateReceipt(
    val candidateId: UUID,
    val rfqId: UUID,
    val quoteId: UUID,
    val requirementVersion: Long,
    val candidateVersion: Long,
    val state: String,
    val replayed: Boolean,
    val purchaseAuthorized: Boolean = false,
    val purchaseOrderIssued: Boolean = false,
)

/**
 * S03 initial boundary. A buyer's explicit supplier choice is captured as
 * immutable internal evidence, but not interpreted as company approval.
 * Uses the existing RFQ source, technical review, document verification,
 * supplier relationship and idempotent audit runtime. Never issues a PO.
 */
@Service
class PurchaseOrderCandidateService @Autowired constructor(
    private val sourcing: SupplierSourcingPersistence,
    private val sourcingService: SupplierSourcingService,
    private val buyerAuthorization: SupplierSourcingAuthorizationPort,
    private val quoteVerification: SupplierQuoteDocumentVerificationPort,
    private val candidates: PurchaseOrderCandidatePersistence,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val sourcingEnabled: Boolean = false,
    @Value("\${hiltech.procurement.po-candidates.enabled:false}")
    private val candidatesEnabled: Boolean = false,
) {
    fun preview(actor: UUID, rfqId: UUID, quoteId: UUID): PurchaseOrderCandidatePreview {
        val rfq = authorizedRfq(actor, rfqId)
        val compared = sourcingService.comparison(actor, rfqId)
        val row = compared.comparison.latestBySupplier.firstOrNull { it.quoteId == quoteId }
        val problems = linkedSetOf<String>()
        if (compared.rfq.id != rfq.id ||
            compared.rfq.requirementId != rfq.requirementId ||
            compared.rfq.capturedRequirementVersion != rfq.requirementVersion ||
            !compared.rfq.sourceCurrent
        ) problems += "SOURCE_NOT_CURRENT"
        if (row == null) problems += "QUOTE_NOT_LATEST"
        if (row != null) {
            if (!sourcing.isEligibleSupplier(rfq.organizationId, row.supplierOrganizationId))
                problems += "SUPPLIER_NOT_LINKED"
            if (row.revisionNumber != sourcing.latestRevision(rfqId, row.supplierOrganizationId))
                problems += "QUOTE_REVISION_CHANGED"
            if (!quoteVerification.isVerified(
                actor, rfq.organizationId, rfqId, row.supplierOrganizationId,
                row.evidenceRef, row.evidenceSha256,
            )) problems += "QUOTE_DOCUMENT_UNVERIFIED"
            problems.addAll(row.issues)
        }
        if (candidates.byRfq(rfqId) != null) problems += "CANDIDATE_ALREADY_RECORDED"

        return PurchaseOrderCandidatePreview(
            rfqId = rfqId,
            quoteId = quoteId,
            supplierOrganizationId = row?.supplierOrganizationId,
            sourceCurrent = compared.rfq.sourceCurrent,
            quoteLatest = row != null,
            quotedTotal = row?.total,
            currency = row?.currency,
            blockers = problems.toList(),
            canPropose = problems.isEmpty(),
        )
    }

    fun propose(
        context: ProcurementDraftContext,
        rfqId: UUID,
        request: ProposePurchaseOrderCandidateRequest,
    ): PurchaseOrderCandidateReceipt {
        val rationale = request.rationale.trim()
        if (context.operationId != request.operationId ||
            request.expectedQuoteRevision < 1 ||
            rationale.length !in 12..1000 ||
            rationale.any { it.code < 32 }
        ) invalid()

        val rfq = authorizedRfq(context.actor, rfqId)
        val id = UUID.nameUUIDFromBytes(
            "hiltech:procurement:po-candidate:${context.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(
                rfqId, request.quoteEvidenceId, request.expectedQuoteRevision, rationale,
            ).joinToString("\u001f").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }

        val execution = commands.execute(
            IdempotentCommandSpec(
                context.operationId, context.actor,
                "PROCUREMENT_PO_CANDIDATE_PROPOSED",
                "PROCUREMENT_PO_CANDIDATE", id, fingerprint, context.correlationId,
            ),
        ) {
            // Quote-recording locks this RFQ too, serializing version selection.
            if (!sourcing.lockRfq(rfqId)) unavailable()
            val lockedRfq = authorizedRfq(context.actor, rfqId)
            if (lockedRfq != rfq) stale()
            val preview = preview(context.actor, rfqId, request.quoteEvidenceId)
            if (!preview.canPropose) stale()
            val chosen = sourcingService.comparison(context.actor, rfqId)
                .comparison.latestBySupplier.singleOrNull { it.quoteId == request.quoteEvidenceId }
                ?: stale()
            if (chosen.revisionNumber != request.expectedQuoteRevision ||
                chosen.quantity != lockedRfq.sourceQuantity.toPlainString() ||
                chosen.unitCode != lockedRfq.sourceUnitCode
            ) stale()
            val quote = sourcing.quotes(rfqId).singleOrNull { it.id == request.quoteEvidenceId }
                ?: stale()
            if (quote.revisionNumber != chosen.revisionNumber ||
                quote.supplierOrganizationId != chosen.supplierOrganizationId ||
                quote.sourceSha256 != chosen.evidenceSha256 ||
                quote.sourceEvidenceRef != chosen.evidenceRef
            ) stale()
            val moneyTotal = quote.unitPrice.multiply(quote.quantity)
                .add(quote.taxAmount).setScale(6, RoundingMode.HALF_UP)
            if (quote.quantity != lockedRfq.sourceQuantity ||
                quote.unitCode != lockedRfq.sourceUnitCode ||
                moneyTotal.signum() < 0
            ) stale()

            val now = clock.instant()
            candidates.insert(PurchaseOrderCandidateRecord(
                id = id,
                operationId = context.operationId,
                organizationId = lockedRfq.organizationId,
                rfqId = rfqId,
                requirementId = lockedRfq.requirementId,
                requirementVersion = lockedRfq.requirementVersion,
                quoteId = quote.id,
                quoteRevision = quote.revisionNumber,
                supplierOrganizationId = quote.supplierOrganizationId,
                stockItemId = lockedRfq.stockItemId,
                quantity = quote.quantity,
                unitCode = quote.unitCode,
                currency = quote.currency,
                unitPrice = quote.unitPrice,
                taxAmount = quote.taxAmount,
                totalAmount = moneyTotal,
                sourceSha256 = quote.sourceSha256,
                rationale = rationale,
                proposedBy = context.actor,
                createdAt = now,
                correlationId = context.correlationId,
            ))
            audit.append(AuditEventRecord(
                actorUserId = context.actor,
                action = "PROCUREMENT_PO_CANDIDATE_PROPOSED",
                targetType = "PROCUREMENT_PO_CANDIDATE",
                targetId = id,
                occurredAt = now,
                correlationId = context.correlationId,
                reason = "BUYER_SELECTION_FOR_APPROVAL_NOT_PO",
            ))
            IdempotentCommandOutcome(
                "PROCUREMENT_PO_CANDIDATE_PROPOSED", """{"candidateId":"$id"}""",
            )
        }
        val current = candidates.byRfq(rfqId) ?: unavailable()
        if (current.id != id || current.organizationId != rfq.organizationId ||
            current.quoteId != request.quoteEvidenceId ||
            current.quoteRevision != request.expectedQuoteRevision
        ) stale()
        return PurchaseOrderCandidateReceipt(
            candidateId = current.id, rfqId = current.rfqId,
            quoteId = current.quoteId, requirementVersion = current.requirementVersion,
            candidateVersion = 1, state = "DRAFT_FOR_APPROVAL", replayed = execution.replayed,
        )
    }

    private fun authorizedRfq(actor: UUID, id: UUID): SupplierRfqRecord {
        if (!sourcingEnabled || !candidatesEnabled) unavailable()
        val rfq = sourcing.rfq(id) ?: unavailable()
        if (!buyerAuthorization.canManageSourcing(actor, rfq.organizationId)) unavailable()
        return rfq
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_CANDIDATE_UNAVAILABLE",
        "Purchase candidate is not available.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_CANDIDATE_SOURCE_CHANGED",
        "Quote, supplier or technical source changed; refresh before proposing.",
        HttpStatus.CONFLICT,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_CANDIDATE_INVALID",
        "Invalid buyer selection or rationale.",
        HttpStatus.BAD_REQUEST,
    )
}
