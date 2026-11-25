package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalDecisionConstraint
import com.hiltech.server.approval.ApprovalDecisionContext
import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalReadService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.documents.ProtectedDocumentRasterizer
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID
import java.util.Base64

data class DeliveredPurchaseReviewQuote(val deliveryId: UUID, val candidateId: UUID, val requestId: UUID,
    val sha256: String, val fileName: String, val contentType: String, val bytes: ByteArray)

@Component
class PurchaseOrderReviewService @Autowired constructor(
    private val candidates: PurchaseOrderCandidatePersistence,
    private val sourcing: SupplierSourcingPersistence,
    private val requirements: PurchaseRequirementDraftPersistence,
    private val records: PurchaseOrderReviewPersistence,
    private val eligibility: ProcurementCandidateReviewEligibilityPort,
    private val reads: ApprovalReadService,
    private val policies: ApprovalPersistencePort,
    private val configuration: ProcurementReviewPolicyConfiguration,
    private val guard: PurchaseOrderCandidateApprovalSubject,
    private val documents: SupplierQuoteProtectedDocumentService,
    private val evidence: EvidencePersistencePort,
    private val approvals: ApprovalEngineService,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.approval-request.enabled:false}") private val enabled: Boolean = false,
) {
    fun completed(actor: UUID, requestId: UUID): PurchaseReviewCompletedDto {
        val (organization, result) = records.completedForActor(actor, requestId) ?: unavailable()
        if (!eligibility.isEligible(actor, organization)) unavailable()
        return result
    }

    fun read(actor: UUID, candidateId: UUID, requestId: UUID): PurchaseOrderReviewDto {
        val (candidate, request) = assigned(actor, candidateId, requestId)
        val quote = quote(candidate)
        val names = records.names(candidateId) ?: unavailable()
        val sourceCurrent = guard.isCurrent(ApprovalSubjectRef(candidate.organizationId, "PROCUREMENT_PO_CANDIDATE", candidate.id, 1))
        val currentPolicy = policies.loadActivePolicy(candidate.organizationId, configuration.key, clock.instant())
        val policyMatches = currentPolicy != null && currentPolicy.approvalRequired && currentPolicy.mode == ApprovalPolicyMode.SINGLE &&
            currentPolicy.versionNumber == request.policyRevision && request.policyKey == configuration.key
        val independent = actor != candidate.proposedBy && actor != request.requester
        val opened = records.opened(actor, candidate, requestId, request.version, request.policyRevision)
        val proofId = evidenceId(quote)
        val document = proofId?.let(evidence::loadEvidenceRecord)
        val available = document != null && document.organizationId == candidate.organizationId &&
            document.targetType == "SUPPLIER_QUOTE_DOCUMENT" && document.expectedSha256 == candidate.sourceSha256 &&
            document.storageState == "READY" && document.uploadState == "READY" &&
            document.expectedSizeBytes in 1..16L * 1024 * 1024 &&
            document.contentType in setOf("application/pdf", "image/jpeg", "image/png", "image/webp")
        val proof = PurchaseReviewProofDto(proofId?.toString().orEmpty(), candidate.sourceSha256,
            document?.contentType.orEmpty(), quoteFileName(quote.revisionNumber, document?.contentType.orEmpty()),
            document?.expectedSizeBytes ?: 0, available)
        val mayDecide = independent && sourceCurrent && policyMatches
        return PurchaseOrderReviewDto(candidate.id.toString(), candidate.organizationId.toString(), candidate.revisionNumber,
            requestId.toString(), request.version, request.state, request.policyRevision, names.supplierName, names.supplierCode,
            names.itemName, names.itemCode, candidate.quantity.toPlainString(), candidate.unitCode, candidate.unitPrice.toPlainString(),
            candidate.taxAmount.toPlainString(), candidate.totalAmount.toPlainString(), candidate.currency, names.sourceKind,
            names.sourceLabel, candidate.requirementVersion, quote.supplierQuoteReference, quote.revisionNumber,
            quote.validUntil?.toString(), quote.deliveryDays, quote.deliveryTerms, quote.paymentTerms, quote.warrantyTerms,
            candidate.rationale, names.preparedByName, candidate.createdAt.toString(), sourceCurrent, independent, opened,
            mayDecide && opened && available, mayDecide, !sourceCurrent, proof)
    }

    fun quotationPage(actor: UUID, candidateId: UUID, requestId: UUID, body: PurchaseQuotePageRequestDto, correlationId: String): PurchaseQuotePageDto {
        if (body.pageIndex !in 0..499) throw ProductApiException("PROCUREMENT_REVIEW_PAGE_INVALID", "The document page is invalid.", HttpStatus.BAD_REQUEST)
        val file = quotation(actor, candidateId, requestId, body.source, correlationId)
        val page = try { ProtectedDocumentRasterizer.render(file.bytes, file.contentType, body.pageIndex) }
        catch (_: Exception) { throw ProductApiException("PROCUREMENT_REVIEW_DOCUMENT_UNAVAILABLE", "The protected quotation page could not be displayed.", HttpStatus.UNPROCESSABLE_ENTITY) }
        val (candidate, approval) = assigned(actor, candidateId, requestId)
        matchSource(candidate, approval, body.source)
        requireCurrentProofSource(actor, candidate, approval)
        return PurchaseQuotePageDto(file.deliveryId.toString(), candidateId.toString(), requestId.toString(), body.source,
            page.pageIndex, page.pageCount, Base64.getEncoder().encodeToString(page.png))
    }

    private fun quotation(actor: UUID, candidateId: UUID, requestId: UUID, expected: PurchaseReviewSourceDto, correlationId: String): DeliveredPurchaseReviewQuote {
        val (candidate, request) = assigned(actor, candidateId, requestId)
        matchSource(candidate, request, expected)
        requireCurrentProofSource(actor, candidate, request)
        val quote = quote(candidate)
        val proofId = evidenceId(quote) ?: unavailable()
        val file = documents.readForAssignedCandidate(actor, candidate, correlationId) {
            val (current, approval) = assigned(actor, candidateId, requestId)
            matchSource(current, approval, expected)
            requireCurrentProofSource(actor, current, approval)
        }
        // Recheck once more at receipt creation; no bytes are exposed if assignment changed during retrieval.
        val (current, approval) = assigned(actor, candidateId, requestId)
        matchSource(current, approval, expected)
        requireCurrentProofSource(actor, current, approval)
        val deliveryId = UUID.randomUUID()
        records.insertDelivery(PurchaseQuoteDeliveryRecord(deliveryId, candidate.organizationId, candidate.id, requestId,
            request.version, request.policyRevision, quote.id, proofId, candidate.sourceSha256, actor, clock.instant()), correlationId)
        return DeliveredPurchaseReviewQuote(deliveryId, candidateId, requestId, candidate.sourceSha256,
            quoteFileName(quote.revisionNumber, file.contentType), file.contentType, file.bytes)
    }

    fun recordOpened(actor: UUID, candidateId: UUID, requestId: UUID, body: OpenPurchaseReviewQuoteDto, correlationId: String): PurchaseQuoteOpeningReceiptDto {
        val operation = uuid(body.operationId)
        val deliveryId = uuid(body.deliveryId)
        val (candidate, _) = eligibleSource(actor, candidateId, requestId)
        val execution = commands.execute(IdempotentCommandSpec(operation, actor, "OPEN_PURCHASE_REVIEW_QUOTATION", "PROCUREMENT_PO_CANDIDATE",
            candidateId, fingerprint(body.source, body.deliveryId), correlationId)) {
            lockCandidateSource(candidate)
            val (current, approval) = assigned(actor, candidateId, requestId)
            matchSource(current, approval, body.source)
            requireCurrentProofSource(actor, current, approval)
            val delivery = records.delivery(deliveryId) ?: unavailable()
            if (delivery.organizationId != current.organizationId || delivery.candidateId != candidateId || delivery.approvalRequestId != requestId ||
                delivery.approvalRequestVersion != approval.version || delivery.policyRevision != approval.policyRevision ||
                delivery.quoteId != current.quoteId || delivery.evidenceId.toString() != body.source.evidenceId ||
                delivery.sha256 != current.sourceSha256 || delivery.reviewer != actor) unavailable()
            records.insertOpening(UUID.nameUUIDFromBytes("purchase-quote-opening:$operation".toByteArray()), operation, deliveryId, clock.instant(), correlationId)
            audit.append(AuditEventRecord(actorUserId = actor, action = "PROCUREMENT_REVIEW_QUOTATION_OPENED", targetType = "PROCUREMENT_PO_CANDIDATE",
                targetId = candidateId, occurredAt = clock.instant(), correlationId = correlationId, reason = "DEVICE_VIEWER_OPENED_VERIFIED_QUOTATION"))
            IdempotentCommandOutcome("PROCUREMENT_REVIEW_QUOTATION_OPENED")
        }
        return PurchaseQuoteOpeningReceiptDto(body.operationId, candidateId.toString(), requestId.toString(), true, execution.replayed)
    }

    fun decide(actor: UUID, candidateId: UUID, requestId: UUID, body: DecidePurchaseReviewDto, correlationId: String): PurchaseReviewDecisionReceiptDto {
        val operation = uuid(body.operationId)
        if (body.decision !in setOf("APPROVE", "REJECT", "REQUEST_CHANGE", "CLOSE_STALE") || body.comment.orEmpty().length > 1000 ||
            (body.decision != "APPROVE" && body.comment.isNullOrBlank())) throw ProductApiException(
            "PROCUREMENT_REVIEW_DECISION_INVALID", "A valid decision and required reason are needed.", HttpStatus.BAD_REQUEST)
        val (candidate, _) = eligibleSource(actor, candidateId, requestId)
        val execution = commands.execute(IdempotentCommandSpec(operation, actor, "DECIDE_PURCHASE_PROPOSAL", "PROCUREMENT_PO_CANDIDATE", candidateId,
            fingerprint(body.source, listOf(requestId, body.decision, body.comment?.trim().orEmpty()).joinToString("|")), correlationId)) {
            lockCandidateSource(candidate)
            val (current, approval) = assigned(actor, candidateId, requestId)
            matchSource(current, approval, body.source)
            val sourceCurrent = guard.isCurrent(ApprovalSubjectRef(current.organizationId, "PROCUREMENT_PO_CANDIDATE", current.id, 1))
            if (body.decision == "CLOSE_STALE" && sourceCurrent) throw ProductApiException(
                "PROCUREMENT_REVIEW_SOURCE_STILL_CURRENT", "This source is current; review it before choosing a business decision.", HttpStatus.CONFLICT)
            val decision = if (body.decision == "CLOSE_STALE") ApprovalDecisionType.REQUEST_CHANGE else ApprovalDecisionType.valueOf(body.decision)
            val result = approvals.decideForSourceWorkflow(actor, UUID.nameUUIDFromBytes("purchase-review-decision:$operation".toByteArray()), requestId,
                decision, body.comment?.trim()?.takeIf(String::isNotBlank), correlationId)
            IdempotentCommandOutcome("PROCUREMENT_PROPOSAL_REVIEW_RECORDED", buildJsonObject { put("state", result.state.name) }.toString())
        }
        val state = Json.parseToJsonElement(requireNotNull(execution.outcome.resultPayloadJson)).jsonObject.getValue("state").jsonPrimitive.content
        return PurchaseReviewDecisionReceiptDto(body.operationId, candidateId.toString(), requestId.toString(), state, execution.replayed)
    }

    private fun lockCandidateSource(candidate: PurchaseOrderCandidateRecord) {
        if (!candidates.lockCandidate(candidate.id) || !sourcing.lockRfq(candidate.rfqId) || !requirements.lock(candidate.requirementId)) unavailable()
    }

    private fun eligibleSource(actor: UUID, candidateId: UUID, requestId: UUID): Pair<PurchaseOrderCandidateRecord, PurchaseReviewRequestRecord> {
        if (!enabled) unavailable()
        val candidate = candidates.byId(candidateId) ?: unavailable()
        if (!eligibility.isEligible(actor, candidate.organizationId)) unavailable()
        val request = records.request(candidateId, requestId) ?: unavailable()
        if (request.organizationId != candidate.organizationId || request.candidateVersion != 1L) unavailable()
        return candidate to request
    }

    private fun assigned(actor: UUID, candidateId: UUID, requestId: UUID): Pair<PurchaseOrderCandidateRecord, PurchaseReviewRequestRecord> {
        val pair = eligibleSource(actor, candidateId, requestId)
        val request = pair.second
        if (request.state != "PENDING") unavailable()
        val assigned = reads.one(actor, requestId)
        if (assigned.subjectType != "PROCUREMENT_PO_CANDIDATE" || assigned.subjectId != candidateId.toString() || assigned.subjectVersion != 1L ||
            assigned.policyKey != request.policyKey || assigned.policyVersion != request.policyRevision || assigned.approvalRequestId != requestId.toString()) unavailable()
        return pair
    }

    private fun quote(candidate: PurchaseOrderCandidateRecord): SupplierQuoteEvidence = sourcing.quotes(candidate.rfqId).singleOrNull {
        it.id == candidate.quoteId && it.rfqId == candidate.rfqId && it.supplierOrganizationId == candidate.supplierOrganizationId &&
            it.revisionNumber == candidate.quoteRevision && it.sourceSha256 == candidate.sourceSha256
    } ?: unavailable()

    private fun matchSource(candidate: PurchaseOrderCandidateRecord, request: PurchaseReviewRequestRecord, expected: PurchaseReviewSourceDto) {
        if (request.version != expected.approvalRequestVersion || candidate.revisionNumber != expected.candidateRevision ||
            candidate.quoteRevision != expected.quoteRevision || candidate.sourceSha256 != expected.expectedSha256 ||
            evidenceId(quote(candidate))?.toString().orEmpty() != expected.evidenceId) throw ProductApiException(
            "PROCUREMENT_REVIEW_CONTEXT_CHANGED", "The displayed review context changed; refresh its exact source.", HttpStatus.CONFLICT)
    }
    private fun requireCurrentProofSource(actor: UUID, candidate: PurchaseOrderCandidateRecord, request: PurchaseReviewRequestRecord) {
        val policy = policies.loadActivePolicy(candidate.organizationId, configuration.key, clock.instant())
        if (actor == candidate.proposedBy || actor == request.requester || policy == null || !policy.approvalRequired ||
            policy.mode != ApprovalPolicyMode.SINGLE || policy.versionNumber != request.policyRevision || request.policyKey != configuration.key ||
            !guard.isCurrent(ApprovalSubjectRef(candidate.organizationId, "PROCUREMENT_PO_CANDIDATE", candidate.id, 1))) {
            throw ProductApiException("PROCUREMENT_REVIEW_CONTEXT_CHANGED", "The current independent review source is no longer available.", HttpStatus.CONFLICT)
        }
    }
    private fun evidenceId(quote: SupplierQuoteEvidence): UUID? = quote.sourceEvidenceRef.takeIf { it.startsWith("evidence:") }
        ?.removePrefix("evidence:")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    private fun fingerprint(source: PurchaseReviewSourceDto, action: String): String = IdempotencyKeyContract.fingerprint(buildJsonObject {
        put("requestVersion", source.approvalRequestVersion); put("candidateRevision", source.candidateRevision)
        put("quoteRevision", source.quoteRevision); put("evidenceId", source.evidenceId); put("sha256", source.expectedSha256); put("action", action)
    }.toString())
    private fun quoteFileName(revision: Int, contentType: String) = "supplier-quotation-r$revision." + when (contentType) {
        "application/pdf" -> "pdf"; "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "bin"
    }
    private fun uuid(raw: String): UUID = runCatching { UUID.fromString(raw) }.getOrElse {
        throw ProductApiException("PROCUREMENT_REVIEW_INVALID_IDENTIFIER", "A review identifier is invalid.", HttpStatus.BAD_REQUEST)
    }
    private fun unavailable(): Nothing = throw ProductApiException("PROCUREMENT_REVIEW_NOT_AVAILABLE", "This assigned purchase review is unavailable.", HttpStatus.NOT_FOUND)
}

@Component
class PurchaseOrderReviewDecisionConstraint(
    private val candidates: PurchaseOrderCandidatePersistence,
    private val eligibility: ProcurementCandidateReviewEligibilityPort,
    private val policies: ApprovalPersistencePort,
    private val configuration: ProcurementReviewPolicyConfiguration,
    private val proofs: PurchaseOrderReviewPersistence,
    private val clock: Clock,
) : ApprovalDecisionConstraint {
    override fun supports(subjectType: String): Boolean = subjectType == "PROCUREMENT_PO_CANDIDATE"
    override fun canDecide(actor: UUID, context: ApprovalDecisionContext, decision: ApprovalDecisionType): Boolean {
        val candidate = candidates.byId(context.subjectId) ?: return false
        if (!supports(context.subjectType) || context.subjectVersion != 1L || candidate.organizationId != context.organizationId ||
            actor == candidate.proposedBy || actor == context.requesterUserId || !eligibility.isEligible(actor, context.organizationId)) return false
        val policy = policies.loadActivePolicy(context.organizationId, configuration.key, clock.instant()) ?: return false
        if (context.policyKey != configuration.key || policy.versionNumber != context.policyVersion || policy.authorityKey != context.authorityKey ||
            !policy.approvalRequired || policy.mode != ApprovalPolicyMode.SINGLE) return false
        return decision != ApprovalDecisionType.APPROVE || proofs.opened(actor, candidate, context.requestId, context.requestVersion, context.policyVersion)
    }
}
