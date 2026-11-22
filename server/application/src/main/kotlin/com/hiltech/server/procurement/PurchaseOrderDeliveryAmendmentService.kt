package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.WorkPersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class ProposePurchaseOrderDeliveryAmendmentRequest(
    val operationId: UUID,
    val expectedOrderVersion: Long,
    val proposedStorageLocationId: UUID,
    val reason: String,
)

data class PurchaseOrderDeliveryAmendmentProposalReceipt(
    val proposalId: UUID,
    val purchaseOrderId: UUID,
    val expectedOrderVersion: Long,
    val proposedStorageLocationId: UUID,
    val state: String,
    val replayed: Boolean,
)

data class PurchaseOrderDeliveryAmendmentStatus(
    val proposalId: UUID,
    val purchaseOrderId: UUID,
    val baseVersion: Long,
    val proposedStorageLocationId: UUID,
    val reason: String,
    val approvalState: String,
    val approvalRequestId: UUID?,
)

data class RequestPurchaseOrderDeliveryAmendmentApproval(
    val operationId: UUID,
)

data class PurchaseOrderDeliveryAmendmentApprovalReceipt(
    val proposalId: UUID,
    val approvalRequestId: UUID?,
    val policyKey: String,
    val policyVersion: Int,
    val approvalState: String?,
    val approvalRequired: Boolean,
    val replayed: Boolean,
    val orderChanged: Boolean = false,
)

data class ApplyPurchaseOrderDeliveryAmendmentRequest(
    val operationId: UUID,
    val expectedOrderVersion: Long,
)

data class PurchaseOrderDeliveryAmendmentAppliedReceipt(
    val purchaseOrderId: UUID,
    val code: String,
    val eventId: UUID,
    val orderVersion: Long,
    val effectiveDestinationId: UUID,
    val approvalRequestId: UUID,
    val state: String,
    val supplierNotified: Boolean = false,
    val financeChanged: Boolean = false,
    val inventoryChanged: Boolean = false,
    val replayed: Boolean,
)

/**
 * Delivery-only amendment (same supplier/quantity/price/tax). Three distinct
 * transitions: immutable proposal -> canonical human ApprovalEngine ->
 * separately authorized, source-locked V(N+1) event. No original PO rewrite.
 */
@Service
class PurchaseOrderDeliveryAmendmentService @Autowired constructor(
    private val proposals: PurchaseOrderDeliveryAmendmentPersistence,
    private val purchases: PurchaseOrderIssuePersistence,
    private val candidates: PurchaseOrderCandidatePersistence,
    private val requirements: PurchaseRequirementDraftPersistence,
    private val work: WorkPersistencePort,
    private val issuer: PurchaseOrderIssueService,
    private val subjectGuard: PurchaseOrderDeliveryAmendmentSubject,
    private val approvals: ApprovalEngineService,
    private val approvalRecords: ApprovalPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po.delivery-amendments.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.po.delivery-amendment-policy-key:}")
    private val policyKey: String = "",
) {
    fun destinations(
        actor: UUID,
        orderId: UUID,
    ): List<PurchaseOrderDeliveryDestination> {
        if (!enabled) unavailable()
        val state = purchases.effectiveState(orderId) ?: unavailable()
        issuer.requireIssuerForLifecycle(actor, state.order.organizationId)
        if (state.effectiveState != "ISSUED" ||
            state.order.supplierDispatchState != "NOT_SENT" ||
            purchases.hasSupplierCommunication(orderId)
        ) unavailable()
        val source = candidates.byId(state.order.candidateId) ?: unavailable()
        val demand = requirements.byId(source.requirementId) ?: unavailable()
        if (source.organizationId != state.order.organizationId ||
            demand.organizationId != state.order.organizationId
        ) unavailable()
        val workOrder = demand.workOrderId?.let { work.workOrder(it) } ?: unavailable()
        if (workOrder.organizationId != state.order.organizationId) unavailable()
        return purchases.deliveryDestinations(
            state.order.organizationId, workOrder.projectId, workOrder.siteId,
            clock.instant(),
        )
    }

    fun pending(actor: UUID, orderId: UUID): PurchaseOrderDeliveryAmendmentStatus? {
        if (!enabled) unavailable()
        val state = purchases.effectiveState(orderId) ?: unavailable()
        issuer.requireIssuerForLifecycle(actor, state.order.organizationId)
        if (state.effectiveState != "ISSUED" ||
            state.order.supplierDispatchState != "NOT_SENT" ||
            purchases.hasSupplierCommunication(orderId)
        ) unavailable()
        val proposal = proposals.byOrderAndBase(orderId, state.version) ?: return null
        val subject = ApprovalSubjectRef(
            state.order.organizationId, "PROCUREMENT_PO_DELIVERY_AMENDMENT",
            proposal.id, 1L,
        )
        val existing = approvalRecords.loadSubjectApproval(subject, requirePolicyKey())
        return PurchaseOrderDeliveryAmendmentStatus(
            proposal.id, orderId, proposal.baseVersion,
            proposal.newDestinationId, proposal.reason,
            existing?.state?.name ?: "NOT_REQUESTED",
            existing?.requestId,
        )
    }

    fun propose(
        ctx: ProcurementDraftContext,
        orderId: UUID,
        request: ProposePurchaseOrderDeliveryAmendmentRequest,
    ): PurchaseOrderDeliveryAmendmentProposalReceipt {
        if (!enabled) unavailable()
        val reason = request.reason.trim()
        if (ctx.operationId != request.operationId || request.expectedOrderVersion < 1L ||
            reason.length !in 12..1000 || reason.any { it.code < 32 }
        ) invalid()
        val order = purchases.byId(orderId) ?: unavailable()
        issuer.requireIssuerForLifecycle(ctx.actor, order.organizationId)
        val proposalId = deterministic("po-delivery-proposal", ctx.operationId)
        val fingerprint = fingerprint(
            orderId, request.expectedOrderVersion, request.proposedStorageLocationId, reason,
        )
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor,
            "PROCUREMENT_PO_DELIVERY_PROPOSED", "PROCUREMENT_PO_DELIVERY_AMENDMENT",
            proposalId, fingerprint, ctx.correlationId,
        )) {
            if (!purchases.lockOrder(orderId, order.organizationId)) unavailable()
            issuer.requireIssuerForLifecycle(ctx.actor, order.organizationId)
            val state = purchases.effectiveState(orderId) ?: unavailable()
            if (state.order != order || state.effectiveState != "ISSUED" ||
                state.order.supplierDispatchState != "NOT_SENT" ||
                purchases.hasSupplierCommunication(orderId) ||
                state.version != request.expectedOrderVersion ||
                state.destinationId == request.proposedStorageLocationId
            ) stale()
            if (proposals.byOrderAndBase(orderId, state.version) != null) stale()
            if (!purchases.lockDeliveryLocation(
                    order.organizationId, request.proposedStorageLocationId,
                )
            ) unavailable()
            val source = candidates.byId(order.candidateId) ?: unavailable()
            val demand = requirements.byId(source.requirementId) ?: unavailable()
            if (source.organizationId != order.organizationId ||
                demand.organizationId != order.organizationId ||
                source.stockItemId != order.stockItemId
            ) unavailable()
            val workOrder = demand.workOrderId?.let { work.workOrder(it) } ?: unavailable()
            if (workOrder.organizationId != order.organizationId ||
                !purchases.validDeliveryLocation(
                    order.organizationId, request.proposedStorageLocationId,
                    workOrder.projectId, workOrder.siteId, clock.instant(),
                )
            ) unavailable()
            val at = clock.instant()
            proposals.insert(PurchaseOrderDeliveryAmendmentRecord(
                id = proposalId,
                operationId = ctx.operationId,
                organizationId = order.organizationId,
                purchaseOrderId = orderId,
                baseVersion = state.version,
                previousDestinationId = state.destinationId,
                newDestinationId = request.proposedStorageLocationId,
                reason = reason,
                proposedBy = ctx.actor,
                at = at,
                correlationId = ctx.correlationId,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor,
                action = "PROCUREMENT_PO_DELIVERY_PROPOSED",
                targetType = "PROCUREMENT_PO_DELIVERY_AMENDMENT",
                targetId = proposalId, occurredAt = at,
                correlationId = ctx.correlationId,
                reason = "DESTINATION_CHANGE_REQUIRES_NEW_APPROVAL",
            ))
            IdempotentCommandOutcome(
                "PROCUREMENT_PO_DELIVERY_PROPOSED", """{"proposalId":"$proposalId"}""",
            )
        }
        val saved = proposals.byId(proposalId) ?: unavailable()
        if (saved.purchaseOrderId != orderId || saved.organizationId != order.organizationId ||
            saved.newDestinationId != request.proposedStorageLocationId ||
            saved.baseVersion != request.expectedOrderVersion
        ) unavailable()
        return PurchaseOrderDeliveryAmendmentProposalReceipt(
            saved.id, orderId, saved.baseVersion, saved.newDestinationId,
            "DRAFT_FOR_APPROVAL", execution.replayed,
        )
    }

    fun requestApproval(
        ctx: ProcurementDraftContext,
        proposalId: UUID,
        request: RequestPurchaseOrderDeliveryAmendmentApproval,
    ): PurchaseOrderDeliveryAmendmentApprovalReceipt {
        if (!enabled) unavailable()
        if (request.operationId != ctx.operationId) invalid()
        val proposal = proposals.byId(proposalId) ?: unavailable()
        issuer.requireIssuerForLifecycle(ctx.actor, proposal.organizationId)
        val key = requirePolicyKey()
        val subject = ApprovalSubjectRef(
            proposal.organizationId, "PROCUREMENT_PO_DELIVERY_AMENDMENT", proposalId, 1L,
        )
        if (!subjectGuard.isCurrent(subject)) stale()
        val result = approvals.requestException(
            actorUserId = ctx.actor,
            operationId = ctx.operationId,
            subject = subject,
            policyKey = key,
            reasonCode = "PROCUREMENT_PO_DELIVERY_CHANGE",
            safeReasonSummary = null,
            correlationId = ctx.correlationId,
            allowAuthorityBypass = false,
        )
        return PurchaseOrderDeliveryAmendmentApprovalReceipt(
            proposalId, result.approvalRequestId, result.policyKey,
            result.policyVersion, result.state?.name, result.approvalRequired,
            result.replayed,
        )
    }

    fun apply(
        ctx: ProcurementDraftContext,
        proposalId: UUID,
        request: ApplyPurchaseOrderDeliveryAmendmentRequest,
    ): PurchaseOrderDeliveryAmendmentAppliedReceipt {
        if (!enabled) unavailable()
        if (ctx.operationId != request.operationId || request.expectedOrderVersion < 1L)
            invalid()
        val proposal = proposals.byId(proposalId) ?: unavailable()
        issuer.requireIssuerForLifecycle(ctx.actor, proposal.organizationId)
        val key = requirePolicyKey()
        val eventId = deterministic("po-delivery-amend-applied", ctx.operationId)
        val fp = fingerprint(proposalId, request.expectedOrderVersion, key)
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_PO_DELIVERY_AMENDED",
            "PROCUREMENT_PO_LIFECYCLE_EVENT", eventId, fp, ctx.correlationId,
        )) {
            if (!purchases.lockOrder(proposal.purchaseOrderId, proposal.organizationId))
                unavailable()
            issuer.requireIssuerForLifecycle(ctx.actor, proposal.organizationId)
            if (!purchases.lockDeliveryLocation(
                    proposal.organizationId, proposal.newDestinationId,
                )
            ) unavailable()
            val current = purchases.effectiveState(proposal.purchaseOrderId) ?: unavailable()
            if (current.order.organizationId != proposal.organizationId ||
                current.order.supplierDispatchState != "NOT_SENT" ||
                purchases.hasSupplierCommunication(proposal.purchaseOrderId) ||
                current.effectiveState != "ISSUED" ||
                current.version != proposal.baseVersion ||
                request.expectedOrderVersion != proposal.baseVersion
            ) stale()
            val subject = ApprovalSubjectRef(
                proposal.organizationId, "PROCUREMENT_PO_DELIVERY_AMENDMENT",
                proposal.id, 1L,
            )
            if (!subjectGuard.isCurrent(subject)) stale()
            val now = clock.instant()
            val policy = approvalRecords.loadActivePolicy(
                proposal.organizationId, key, now,
            ) ?: unavailable()
            if (!policy.approvalRequired || policy.mode != ApprovalPolicyMode.SINGLE)
                unavailable()
            val approved = purchases.lockApprovedDeliveryAmendment(
                proposal.organizationId, proposal.id, key,
            ) ?: unavailable()
            if (approved.state != ApprovalRequestState.APPROVED ||
                approved.approvedDecisionCount != 1 ||
                approved.policyVersion != policy.versionNumber
            ) unavailable()
            val next = proposal.baseVersion + 1L
            purchases.appendLifecycleEvent(PurchaseOrderLifecycleEvent(
                id = eventId, operationId = ctx.operationId,
                organizationId = proposal.organizationId,
                orderId = proposal.purchaseOrderId,
                version = next,
                type = "DELIVERY_AMENDED", reason = proposal.reason,
                actor = ctx.actor, at = now, correlationId = ctx.correlationId,
                amendmentProposalId = proposal.id,
                approvalRequestId = approved.approvalRequestId,
                deliveryStorageLocationId = proposal.newDestinationId,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor,
                action = "PROCUREMENT_PO_DELIVERY_AMENDED",
                targetType = "PROCUREMENT_PURCHASE_ORDER",
                targetId = proposal.purchaseOrderId,
                occurredAt = now, correlationId = ctx.correlationId,
                reason = "APPROVED_DESTINATION_CHANGE_NO_COMMERCIAL_REWRITE",
            ))
            IdempotentCommandOutcome(
                "PROCUREMENT_PO_DELIVERY_AMENDED", """{"eventId":"$eventId"}""",
            )
        }
        val event = purchases.lifecycleEvents(proposal.purchaseOrderId)
            .singleOrNull { it.id == eventId && it.operationId == ctx.operationId }
            ?: unavailable()
        val order = purchases.byId(proposal.purchaseOrderId) ?: unavailable()
        if (event.type != "DELIVERY_AMENDED" ||
            event.organizationId != proposal.organizationId ||
            event.amendmentProposalId != proposal.id ||
            event.version != proposal.baseVersion + 1L ||
            event.deliveryStorageLocationId != proposal.newDestinationId
        ) unavailable()
        return PurchaseOrderDeliveryAmendmentAppliedReceipt(
            order.id, order.code, event.id, event.version,
            proposal.newDestinationId,
            event.approvalRequestId ?: unavailable(),
            "ISSUED", replayed = execution.replayed,
        )
    }

    private fun requirePolicyKey(): String {
        val value = policyKey.trim()
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(value)) unavailable()
        return value
    }

    private fun deterministic(prefix: String, operationId: UUID): UUID =
        UUID.nameUUIDFromBytes("hiltech:$prefix:$operationId".toByteArray(Charsets.UTF_8))

    private fun fingerprint(vararg parts: Any): String =
        MessageDigest.getInstance("SHA-256").digest(
            parts.joinToString("\u001f").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }

    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_DELIVERY_AMENDMENT_UNAVAILABLE",
        "Delivery amendment is unavailable without current company policy and authority.",
        HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_DELIVERY_AMENDMENT_INVALID",
        "Invalid order version, destination or reason for delivery amendment.",
        HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_DELIVERY_AMENDMENT_STALE",
        "Order or delivery source changed; propose another amendment from current version.",
        HttpStatus.CONFLICT,
    )
}
