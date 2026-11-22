package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkPersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Canonical ApprovalEngine subject guard for an immutable purchasing proposal.
 *
 * No approval policy, authority, supplier award or PO is provisioned here.
 * An approval is valid only while its exact source and commercial evidence
 * are still current. Unlike buyer APIs this is an internal trusted guard:
 * it never returns price or source documents to the caller.
 */
@Component
class PurchaseOrderCandidateApprovalSubject @Autowired constructor(
    private val candidates: PurchaseOrderCandidatePersistence,
    private val sourcing: SupplierSourcingPersistence,
    private val requirements: PurchaseRequirementDraftPersistence,
    private val work: WorkPersistencePort,
    private val stock: StockLedgerPersistence,
    private val evidence: EvidencePersistencePort,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po-candidates.enabled:false}")
    private val enabled: Boolean = false,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == "PROCUREMENT_PO_CANDIDATE"

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean {
        if (!enabled || !supports(subject.subjectType) || subject.subjectVersion != 1L)
            return false
        val candidate = candidates.byId(subject.subjectId) ?: return false
        if (candidate.id != subject.subjectId ||
            candidate.organizationId != subject.organizationId
        ) return false
        val rfq = sourcing.rfq(candidate.rfqId) ?: return false
        if (rfq.organizationId != subject.organizationId ||
            rfq.requirementId != candidate.requirementId ||
            rfq.requirementVersion != candidate.requirementVersion ||
            rfq.stockItemId != candidate.stockItemId ||
            rfq.sourceQuantity.compareTo(candidate.quantity) != 0 ||
            rfq.sourceUnitCode != candidate.unitCode
        ) return false

        val source = requirements.byId(candidate.requirementId) ?: return false
        val transition = requirements.latestTransition(candidate.requirementId)
            ?: return false
        if (source.organizationId != subject.organizationId ||
            source.stockItemId != candidate.stockItemId ||
            transition.resultVersion != candidate.requirementVersion ||
            transition.toState != "READY_FOR_SOURCING"
        ) return false
        val currentQuantity = transition.revised?.quantity ?: source.requestedQuantity
        val currentUnit = transition.revised?.unitCode ?: source.unitCode
        if (currentQuantity.compareTo(candidate.quantity) != 0 ||
            currentUnit != candidate.unitCode
        ) return false
        val job = work.workOrder(source.workOrderId) ?: return false
        val item = stock.item(source.stockItemId) ?: return false
        if (job.organizationId != source.organizationId ||
            job.version != (transition.revised?.workVersion ?: source.sourceWorkVersion) ||
            job.lifecycleState !in setOf(
                WorkOrderLifecycle.PLANNED,
                WorkOrderLifecycle.ASSIGNED,
                WorkOrderLifecycle.IN_PROGRESS,
                WorkOrderLifecycle.BLOCKED,
            ) || item.organizationId != source.organizationId ||
            item.version != (transition.revised?.itemVersion ?: source.stockItemVersion) ||
            !item.active || item.baseUnitCode != candidate.unitCode
        ) return false

        if (!sourcing.isEligibleSupplier(candidate.organizationId, candidate.supplierOrganizationId))
            return false
        if (sourcing.latestRevision(candidate.rfqId, candidate.supplierOrganizationId) !=
            candidate.quoteRevision
        ) return false
        val quote = sourcing.quotes(candidate.rfqId)
            .singleOrNull { it.id == candidate.quoteId } ?: return false
        if (quote.rfqId != candidate.rfqId ||
            quote.supplierOrganizationId != candidate.supplierOrganizationId ||
            quote.revisionNumber != candidate.quoteRevision ||
            quote.quantity.compareTo(candidate.quantity) != 0 ||
            quote.unitCode != candidate.unitCode ||
            quote.currency != candidate.currency ||
            quote.unitPrice.compareTo(candidate.unitPrice) != 0 ||
            quote.taxAmount.compareTo(candidate.taxAmount) != 0 ||
            quote.sourceSha256 != candidate.sourceSha256 ||
            quote.validUntil?.isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC))) != false ||
            quote.unitPrice.multiply(quote.quantity).add(quote.taxAmount)
                .setScale(6, RoundingMode.HALF_UP).compareTo(candidate.totalAmount) != 0 ||
            sourcing.latestTechnicalAssessments(candidate.rfqId)[candidate.quoteId] != "COMPLIANT"
        ) return false

        if (!quote.sourceEvidenceRef.startsWith("evidence:")) return false
        val evidenceId = runCatching {
            UUID.fromString(quote.sourceEvidenceRef.removePrefix("evidence:"))
        }.getOrNull() ?: return false
        val documented = evidence.loadEvidenceRecord(evidenceId) ?: return false
        val slot = sourcing.quoteDocumentSlot(documented.targetId) ?: return false
        return documented.evidenceId == evidenceId &&
            documented.organizationId == candidate.organizationId &&
            documented.targetType == "SUPPLIER_QUOTE_DOCUMENT" &&
            documented.evidenceRequirementKey == "SUPPLIER_QUOTE_BINARY" &&
            documented.evidenceTypeCode == "SUPPLIER_QUOTE" &&
            documented.expectedSha256 == candidate.sourceSha256 &&
            documented.storageState == "READY" &&
            documented.uploadState == "READY" &&
            documented.classificationCode == "HIGHLY_RESTRICTED" &&
            documented.clientVisibilityMode == "INTERNAL_ONLY" &&
            slot.organizationId == candidate.organizationId &&
            slot.rfqId == candidate.rfqId &&
            slot.supplierOrganizationId == candidate.supplierOrganizationId
    }
}
