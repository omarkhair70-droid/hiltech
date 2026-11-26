package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import com.hiltech.server.work.WorkPersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Canonical ApprovalEngine subject for an exact delivery-only PO amendment.
 * An approved proposal cannot apply to another order or a newer version.
 * Financial terms and supplier stay identical to already-issued V1.
 */
@Component
class PurchaseOrderDeliveryAmendmentSubject @Autowired constructor(
    private val proposals: PurchaseOrderDeliveryAmendmentPersistence,
    private val purchases: PurchaseOrderIssuePersistence,
    private val candidates: PurchaseOrderCandidatePersistence,
    private val requirements: PurchaseRequirementDraftPersistence,
    private val work: WorkPersistencePort,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po.delivery-amendments.enabled:false}")
    private val enabled: Boolean = false,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == "PROCUREMENT_PO_DELIVERY_AMENDMENT"

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean {
        if (!enabled || !supports(subject.subjectType) || subject.subjectVersion != 1L)
            return false
        val proposal = proposals.byId(subject.subjectId) ?: return false
        if (proposal.organizationId != subject.organizationId) return false
        val state = purchases.effectiveState(proposal.purchaseOrderId) ?: return false
        if (state.order.organizationId != subject.organizationId ||
            state.order.supplierDispatchState != "NOT_SENT" ||
            state.effectiveState != "ISSUED" ||
            state.version != proposal.baseVersion ||
            state.destinationId != proposal.previousDestinationId ||
            proposal.newDestinationId == proposal.previousDestinationId
        ) return false

        val source = candidates.byId(state.order.candidateId) ?: return false
        if (source.organizationId != subject.organizationId ||
            source.stockItemId != state.order.stockItemId
        ) return false
        val demand = requirements.byId(source.requirementId) ?: return false
        if (demand.organizationId != subject.organizationId ||
            demand.stockItemId != state.order.stockItemId
        ) return false
        val order = demand.workOrderId?.let { work.workOrder(it) } ?: return false
        if (order.organizationId != subject.organizationId) return false

        return purchases.validDeliveryLocation(
            subject.organizationId,
            proposal.newDestinationId,
            order.projectId,
            order.siteId,
            clock.instant(),
        )
    }

}
