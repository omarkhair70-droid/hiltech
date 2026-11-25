package com.hiltech.server.procurement

import com.hiltech.server.operationalaccess.OperationalAuthorityOrigin
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.OperationalAuthorityProof
import com.hiltech.server.companysettings.PurchaseOrderNumberingResponse
import com.hiltech.server.companysettings.PurchaseOrderNumberingSnapshot
import com.hiltech.server.companysettings.PurchaseOrderNumberingSource
import com.hiltech.server.companysettings.PurchaseOrderNumberingSourcePort
import com.hiltech.server.companysettings.response
import com.hiltech.server.companysettings.validExistingPurchaseOrderPrefix
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalPolicyMode
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

data class IssuePurchaseOrderRequest(
    val operationId: UUID,
    val expectedCandidateVersion: Long,
    val deliveryStorageLocationId: UUID,
)

data class PurchaseOrderIssueReceipt(
    val purchaseOrderId: UUID,
    val code: String,
    val serial: Long,
    val state: String,
    val supplierDispatchState: String,
    val candidateId: UUID,
    val approvalRequestId: UUID,
    val total: String,
    val currency: String,
    val replayed: Boolean,
    val stockReceived: Boolean = false,
    val supplierNotified: Boolean = false,
    val paymentAuthorized: Boolean = false,
)

/** Issue is distinct from buyer selection, approver action and dispatch. */
@Service
class PurchaseOrderIssueService @Autowired constructor(
    private val candidates: PurchaseOrderCandidatePersistence,
    private val purchases: PurchaseOrderIssuePersistence,
    private val sourcing: SupplierSourcingPersistence,
    private val requirements: PurchaseRequirementDraftPersistence,
    private val work: WorkPersistencePort,
    private val approvalPersistence: ApprovalPersistencePort,
    private val sourceGuard: PurchaseOrderCandidateApprovalSubject,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po-issue.enabled:false}")
    private val issueEnabled: Boolean = false,
    @Value("\${hiltech.procurement.approval-policy-key:PROCUREMENT_PO_REVIEW}")
    private val approvalPolicyKey: String = "",
    @Value("\${hiltech.procurement.po.issue-authority-key:PROCUREMENT_ORDER_ISSUE}")
    private val issuerAuthorityKey: String = "",
    @Value("\${hiltech.procurement.po.number-prefix:}")
    private val poNumberPrefix: String = "",
    private val sourceVerifier: ProcurementDemandSourceVerifier? = null,
    private val operationalAuthority: OperationalAuthorityPort? = null,
    private val numberingSource: PurchaseOrderNumberingSourcePort? = null,
) {
    /** Separate workbench for currently assigned issuer, never broad procurement reads. */
    fun issuerQueue(actor: UUID, organizationId: UUID): List<PurchaseOrderIssuerQueueItem> {
        val policyVersion = requireIssuerRead(actor, organizationId)
        return purchases.issuerQueue(
            organizationId, procurementReviewPolicyKey(approvalPolicyKey), policyVersion,
        ).filter { row ->
            sourceGuard.isCurrent(ApprovalSubjectRef(
                organizationId, "PROCUREMENT_PO_CANDIDATE", row.candidateId, 1L,
            ))
        }
    }

    fun deliveryDestinations(
        actor: UUID,
        candidateId: UUID,
    ): List<PurchaseOrderDeliveryDestination> {
        val candidate = candidates.byId(candidateId) ?: unavailable()
        requireIssuerRead(actor, candidate.organizationId)
        if (!sourceGuard.isCurrent(ApprovalSubjectRef(
                candidate.organizationId, "PROCUREMENT_PO_CANDIDATE", candidate.id, 1L,
            ))
        ) stale()
        val requirement = requirements.byId(candidate.requirementId) ?: unavailable()
        if (requirement.organizationId != candidate.organizationId) unavailable()
        return when (requirement.sourceType) {
            "WORK_ORDER" -> {
                val order = requirement.workOrderId?.let { work.workOrder(it) } ?: unavailable()
                if (order.organizationId != candidate.organizationId) unavailable()
                purchases.deliveryDestinations(
                    candidate.organizationId, order.projectId, order.siteId, clock.instant(),
                )
            }
            "WAREHOUSE_REPLENISHMENT" -> {
                val location = sourceVerifier?.warehouseDestination(
                    requirement, requirements.latestTransition(requirement.id),
                ) ?: unavailable()
                listOfNotNull(purchases.warehouseDeliveryDestination(
                    candidate.organizationId, location, clock.instant(),
                ))
            }
            "ASSET_MAINTENANCE_OR_REPLACEMENT" -> {
                val location = sourceVerifier?.assetDestination(
                    requirement, requirements.latestTransition(requirement.id),
                ) ?: unavailable()
                listOfNotNull(purchases.warehouseDeliveryDestination(
                    candidate.organizationId, location, clock.instant(),
                ))
            }
            "OFFICE_TEAM_STOCK" -> {
                val location = sourceVerifier?.officeDestination(
                    requirement, requirements.latestTransition(requirement.id),
                ) ?: unavailable()
                listOfNotNull(purchases.warehouseDeliveryDestination(
                    candidate.organizationId, location, clock.instant(),
                ))
            }
            else -> unavailable()
        }
    }

    /** Reuse the existing real company issuer authority for lifecycle actions. */
    fun requireIssuerForLifecycle(actor: UUID, organizationId: UUID): Int =
        requireIssuerRead(actor, organizationId)

    private fun requireIssuerRead(actor: UUID, organizationId: UUID): Int {
        val policyKey = procurementReviewPolicyKey(approvalPolicyKey)
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(policyKey)) unavailable()
        requireIssuerAuthority(actor, organizationId)
        val now = clock.instant()
        val policy = approvalPersistence.loadActivePolicy(
            organizationId, policyKey, now,
        ) ?: unavailable()
        if (!policy.approvalRequired || policy.mode != ApprovalPolicyMode.SINGLE)
            unavailable()
        return policy.versionNumber
    }

    fun numbering(actor: UUID, organizationId: UUID): PurchaseOrderNumberingResponse {
        requireIssuerAuthority(actor, organizationId)
        return currentNumbering(organizationId).response()
    }

    private fun requireIssuerAuthority(actor: UUID, organizationId: UUID): OperationalAuthorityProof {
        if (!issueEnabled) unavailable()
        return currentOperationalAuthority(operationalAuthority, approvalPersistence, actor, organizationId,
            "hiltech.procurement.po.issue-authority-key", issuerAuthorityKey, clock.instant()) ?: unavailable()
    }

    private fun currentNumbering(organizationId: UUID): PurchaseOrderNumberingSnapshot {
        numberingSource?.let { return it.current(organizationId) }
        val prefix = poNumberPrefix.trim().takeIf(::validExistingPurchaseOrderPrefix)
        return PurchaseOrderNumberingSnapshot(organizationId,
            if (prefix == null) PurchaseOrderNumberingSource.NOT_CONFIGURED else PurchaseOrderNumberingSource.DEPLOYMENT, prefix, 0)
    }

    fun issue(
        ctx: ProcurementDraftContext,
        candidateId: UUID,
        request: IssuePurchaseOrderRequest,
    ): PurchaseOrderIssueReceipt {
        val policyKey = procurementReviewPolicyKey(approvalPolicyKey)
        if (!issueEnabled || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(policyKey)) unavailable()
        if (request.operationId != ctx.operationId || request.expectedCandidateVersion != 1L)
            invalid()
        val candidate = candidates.byId(candidateId) ?: unavailable()
        // Replays also require CURRENT authority; idempotency must not become a read bypass.
        requireIssuerRead(ctx.actor, candidate.organizationId)
        val initialIssuer = requireIssuerAuthority(ctx.actor, candidate.organizationId)
        val existingReceipt = purchases.byCandidate(candidateId)?.takeIf { it.operationId == ctx.operationId && it.issuer == ctx.actor }
        val numbering = currentNumbering(candidate.organizationId)
        if (existingReceipt == null && !numbering.ready) throw ProductApiException(
            "PROCUREMENT_PO_NUMBERING_REQUIRED", "Configure purchase order numbering in company operating settings before issue.", HttpStatus.CONFLICT)
        // Replays retain their original numbering intent even after an explicit company configuration change.
        val prefix = existingReceipt?.let { it.code.removeSuffix("-${it.serial.toString().padStart(6, '0')}") } ?: requireNotNull(numbering.prefix)
        val authorityKey = existingReceipt?.issuerAuthorityKey ?: initialIssuer.authorityKey
        val fingerprintPolicy = existingReceipt?.approvalPolicyKey ?: policyKey
        val orderId = UUID.nameUUIDFromBytes(
            "hiltech:procurement:purchase-order:${ctx.operationId}".toByteArray(Charsets.UTF_8),
        )
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(candidateId, request.expectedCandidateVersion,
                request.deliveryStorageLocationId, fingerprintPolicy, authorityKey, prefix)
                .joinToString("\u001f").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_PURCHASE_ORDER_ISSUED",
            "PROCUREMENT_PURCHASE_ORDER", orderId, fingerprint, ctx.correlationId,
        )) {
            if (!candidates.lockCandidate(candidateId)) unavailable()
            val current = candidates.byId(candidateId) ?: unavailable()
            if (current != candidate) stale()
            // All sourcing writes and technical reviews lock the RFQ row.
            if (!sourcing.lockRfq(current.rfqId)) unavailable()
            // Requirement transitions lock the immutable requirement root.
            if (!requirements.lock(current.requirementId)) unavailable()
            val frozenRequirement = requirements.byId(current.requirementId) ?: unavailable()
            if (frozenRequirement.organizationId != current.organizationId ||
                frozenRequirement.stockItemId != current.stockItemId
            ) unavailable()
            val sourceLocked = when (frozenRequirement.sourceType) {
                "WORK_ORDER" -> frozenRequirement.workOrderId?.let {
                    purchases.lockWorkAndStockSource(current.organizationId, it, current.stockItemId)
                } == true
                "WAREHOUSE_REPLENISHMENT" -> frozenRequirement.warehouseStorageLocationId?.let {
                    purchases.lockWarehouseAndStockSource(current.organizationId, it, current.stockItemId)
                } == true && sourceVerifier?.isCurrent(
                    frozenRequirement, requirements.latestTransition(frozenRequirement.id),
                ) == true
                "ASSET_MAINTENANCE_OR_REPLACEMENT" -> frozenRequirement.assetId?.let { assetId ->
                    purchases.lockAssetAndStockSource(
                        current.organizationId, assetId,
                        frozenRequirement.assetIncidentId,
                        frozenRequirement.assetMaintenanceId,
                        frozenRequirement.assetDeliveryLocationId ?: unavailable(),
                        current.stockItemId,
                    )
                } == true && sourceVerifier?.isCurrent(
                    frozenRequirement, requirements.latestTransition(frozenRequirement.id),
                ) == true
                "OFFICE_TEAM_STOCK" -> frozenRequirement.officeTeamId?.let { teamId ->
                    purchases.lockOfficeAndStockSource(
                        current.organizationId, teamId,
                        frozenRequirement.officeDeliveryLocationId ?: unavailable(),
                        current.stockItemId,
                    )
                } == true && sourceVerifier?.isCurrent(
                    frozenRequirement, requirements.latestTransition(frozenRequirement.id),
                ) == true
                else -> false
            }
            if (!sourceLocked) unavailable()
            if (!purchases.lockCurrentPurchasingOrganization(current.organizationId))
                unavailable()
            val now = clock.instant()
            val subject = ApprovalSubjectRef(
                current.organizationId, "PROCUREMENT_PO_CANDIDATE", current.id, 1L,
            )
            // Decision and evidence are rechecked IN the same write transaction.
            if (!sourceGuard.isCurrent(subject)) stale()
            val issuer = requireIssuerAuthority(ctx.actor, current.organizationId)
            if (issuer.authorityKey != initialIssuer.authorityKey) unavailable()
            val lockedNumbering = currentNumbering(current.organizationId)
            if (!lockedNumbering.ready || lockedNumbering != numbering) throw ProductApiException(
                "PROCUREMENT_PO_NUMBERING_CHANGED", "Purchase order numbering changed. Refresh the current configuration before issue.", HttpStatus.CONFLICT)
            val policy = approvalPersistence.loadActivePolicy(
                current.organizationId, policyKey, now,
            ) ?: unavailable()
            // S03 issuance is strictly approval-backed. No-approval policies
            // require a separate explicit audited finance procedure, not this shortcut.
            if (!policy.approvalRequired || policy.mode != ApprovalPolicyMode.SINGLE)
                unavailable()
            val decision = purchases.lockApprovedCandidate(
                current.organizationId, candidateId, policyKey,
            ) ?: unavailable()
            if (decision.state != ApprovalRequestState.APPROVED ||
                decision.approvedDecisionCount != 1 ||
                decision.policyVersion != policy.versionNumber
            ) unavailable()

            val need = requirements.byId(current.requirementId) ?: unavailable()
            if (need.organizationId != current.organizationId) unavailable()
            val validDestination = when (need.sourceType) {
                "WORK_ORDER" -> {
                    val order = need.workOrderId?.let { work.workOrder(it) } ?: unavailable()
                    order.organizationId == current.organizationId &&
                        purchases.validDeliveryLocation(
                            current.organizationId, request.deliveryStorageLocationId,
                            order.projectId, order.siteId, now,
                        )
                }
                "WAREHOUSE_REPLENISHMENT" -> {
                    val location = sourceVerifier?.warehouseDestination(
                        need, requirements.latestTransition(need.id),
                    ) ?: unavailable()
                    location == request.deliveryStorageLocationId &&
                        purchases.warehouseDeliveryDestination(
                            current.organizationId, location, now,
                        ) != null
                }
                "ASSET_MAINTENANCE_OR_REPLACEMENT" -> {
                    val location = sourceVerifier?.assetDestination(
                        need, requirements.latestTransition(need.id),
                    ) ?: unavailable()
                    location == request.deliveryStorageLocationId &&
                        purchases.warehouseDeliveryDestination(
                            current.organizationId, location, now,
                        ) != null
                }
                "OFFICE_TEAM_STOCK" -> {
                    val location = sourceVerifier?.officeDestination(
                        need, requirements.latestTransition(need.id),
                    ) ?: unavailable()
                    location == request.deliveryStorageLocationId &&
                        purchases.warehouseDeliveryDestination(
                            current.organizationId, location, now,
                        ) != null
                }
                else -> false
            }
            if (!validDestination) unavailable()
            if (purchases.byCandidate(candidateId) != null) {
                throw ProductApiException(
                    "PROCUREMENT_PO_ALREADY_ISSUED",
                    "An issued purchase order already exists for this candidate.",
                    HttpStatus.CONFLICT,
                )
            }
            val serial = purchases.allocateSerial(current.organizationId)
            // Numbering format is set by the company, never supplied by the buyer.
            val code = lockedNumbering.code(serial)
            val po = PurchaseOrderIssuedRecord(
                id = orderId, operationId = ctx.operationId,
                organizationId = current.organizationId,
                candidateId = current.id,
                approvalRequestId = decision.approvalRequestId,
                approvalPolicyKey = policyKey,
                approvalPolicyVersion = policy.versionNumber,
                issuerAuthorityKey = issuer.authorityKey,
                issuerAuthorityBindingId = issuer.bindingId.takeIf { issuer.origin == OperationalAuthorityOrigin.LEGACY_APPROVAL_BINDING },
                issuerOperationalBindingId = issuer.bindingId.takeIf { issuer.origin == OperationalAuthorityOrigin.OPERATIONAL },
                code = code, serial = serial,
                supplierOrganizationId = current.supplierOrganizationId,
                deliveryStorageLocationId = request.deliveryStorageLocationId,
                stockItemId = current.stockItemId,
                quoteId = current.quoteId,
                quoteSha256 = current.sourceSha256,
                issuer = ctx.actor, issuedAt = now, correlationId = ctx.correlationId,
                numberingRevisionId = lockedNumbering.revisionId,
            )
            purchases.insert(po, current)
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor,
                action = "PROCUREMENT_PURCHASE_ORDER_ISSUED",
                targetType = "PROCUREMENT_PURCHASE_ORDER",
                targetId = orderId,
                occurredAt = now, correlationId = ctx.correlationId,
                reason = "APPROVAL_BOUND_INTERNAL_ISSUE_NOT_DISPATCH",
            ))
            IdempotentCommandOutcome(
                "PROCUREMENT_PURCHASE_ORDER_ISSUED",
                """{"purchaseOrderId":"$orderId"}""",
            )
        }
        val result = purchases.byCandidate(candidateId) ?: unavailable()
        if (result.id != orderId || result.organizationId != candidate.organizationId ||
            result.operationId != ctx.operationId
        ) unavailable()
        return PurchaseOrderIssueReceipt(
            result.id, result.code, result.serial, "ISSUED", "NOT_SENT",
            result.candidateId, result.approvalRequestId,
            candidate.totalAmount.toPlainString(), candidate.currency, execution.replayed,
        )
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_ISSUE_UNAVAILABLE",
        "Purchase Order issue is not authorized or configured.",
        HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_ISSUE_INVALID",
        "PO issue command must specify the current candidate and destination.",
        HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_SOURCE_CHANGED",
        "The approved supplier or technical requirement is no longer current.",
        HttpStatus.CONFLICT,
    )
}
