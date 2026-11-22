package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class LinkPurchaseOrderReplacementRequest(
    val operationId: UUID,
    val replacementPurchaseOrderId: UUID,
    val expectedReplacedVersion: Long,
    val expectedReplacementVersion: Long,
    val reason: String,
)
data class PurchaseOrderReplacementReceipt(
    val supersessionId: UUID,
    val replacedPurchaseOrderId: UUID,
    val replacementPurchaseOrderId: UUID,
    val state: String = "REPLACED_BY_SEPARATELY_APPROVED_PO",
    val replayed: Boolean,
    val payableCreated: Boolean = false,
    val supplierNotified: Boolean = false,
)

/**
 * Supplier/price/quantity/tax replacement is an independent approved PO,
 * not a mutation of the previously approved supplier and commercial terms.
 * The original must be cancelled before dispatch and before physical receipt.
 */
@Service
class PurchaseOrderCommercialReplacementService @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val orders: PurchaseOrderIssuePersistence,
    private val issuer: PurchaseOrderIssueService,
    private val currentCandidate: PurchaseOrderCandidateApprovalSubject,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po.commercial-replacement.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun link(
        ctx: ProcurementDraftContext, oldId: UUID,
        req: LinkPurchaseOrderReplacementRequest,
    ): PurchaseOrderReplacementReceipt {
        if (!enabled) unavailable()
        val reason = req.reason.trim()
        if (req.operationId != ctx.operationId || oldId == req.replacementPurchaseOrderId ||
            req.expectedReplacedVersion < 2L || req.expectedReplacementVersion != 1L ||
            reason.length !in 12..1000 || reason.any { it.code < 32 }
        ) invalid()
        val old = orders.byId(oldId) ?: unavailable()
        val next = orders.byId(req.replacementPurchaseOrderId) ?: unavailable()
        if (next.organizationId != old.organizationId) unavailable()
        issuer.requireIssuerForLifecycle(ctx.actor, old.organizationId)
        val id = UUID.nameUUIDFromBytes(
            "hiltech:po-commercial-replacement:${ctx.operationId}".toByteArray(Charsets.UTF_8),
        )
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(oldId, req.replacementPurchaseOrderId,
                req.expectedReplacedVersion, req.expectedReplacementVersion, reason)
                .joinToString("\u001f").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        val outcome = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_PO_SUPERSESSION_RECORDED",
            "PROCUREMENT_PO_SUPERSESSION_LINK", id, fingerprint, ctx.correlationId,
        )) {
            // PostgreSQL trigger takes both PO row locks in stable ID order;
            // no intermediate client-declared "approval" can bypass source.
            issuer.requireIssuerForLifecycle(ctx.actor, old.organizationId)
            val oldState = orders.effectiveState(oldId) ?: unavailable()
            val nextState = orders.effectiveState(req.replacementPurchaseOrderId) ?: unavailable()
            if (oldState.order != old || nextState.order != next ||
                oldState.version != req.expectedReplacedVersion ||
                oldState.effectiveState != "CANCELLED" ||
                nextState.version != req.expectedReplacementVersion ||
                nextState.effectiveState != "ISSUED" ||
                orders.hasSupplierCommunication(oldId) ||
                orders.hasSupplierCommunication(next.id) ||
                orders.hasPhysicalPoReceiving(oldId) ||
                orders.hasPhysicalPoReceiving(next.id) ||
                !currentCandidate.isCurrent(ApprovalSubjectRef(
                    next.organizationId, "PROCUREMENT_PO_CANDIDATE",
                    next.candidateId, 1L,
                ))
            ) stale()
            val now = clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_po_supersession_link (
                  id, operation_id, organization_id, replaced_purchase_order_id,
                  replacement_purchase_order_id, replaced_effective_version,
                  replacement_effective_version, reason, linked_by_user_id,
                  linked_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, ctx.operationId, old.organizationId, oldId, next.id,
                oldState.version, nextState.version, reason, ctx.actor,
                now.atOffset(ZoneOffset.UTC), ctx.correlationId,
            ) == 1)
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor,
                action = "PROCUREMENT_PO_SUPERSESSION_RECORDED",
                targetType = "PROCUREMENT_PURCHASE_ORDER", targetId = oldId,
                occurredAt = now, correlationId = ctx.correlationId,
                reason = "CANCELLED_ORIGINAL_AND_SEPARATELY_APPROVED_REPLACEMENT",
            ))
            IdempotentCommandOutcome(
                "PROCUREMENT_PO_SUPERSESSION_RECORDED",
                """{"supersessionId":"$id"}""",
            )
        }
        return PurchaseOrderReplacementReceipt(id, oldId, next.id, replayed = outcome.replayed)
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_REPLACEMENT_UNAVAILABLE",
        "PO replacement requires a new independently approved, authorized purchase.", HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_REPLACEMENT_INVALID",
        "Original/replacement IDs, exact versions and reason are required.", HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_REPLACEMENT_SOURCE_CHANGED",
        "Original or replacement PO is no longer eligible.", HttpStatus.CONFLICT,
    )
}

@RestController
class PurchaseOrderCommercialReplacementController(
    private val service: PurchaseOrderCommercialReplacementService,
) {
    @PostMapping("/v1/procurement/purchase-orders/{oldId}/commercial-replacement")
    fun replace(
        servlet: HttpServletRequest,
        @PathVariable oldId: UUID,
        @RequestBody request: LinkPurchaseOrderReplacementRequest,
    ): PurchaseOrderReplacementReceipt {
        val ctx = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, request.operationId)
        val actor = ctx.identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
        return service.link(
            ProcurementDraftContext(request.operationId, actor, ctx.correlationId),
            oldId, request,
        )
    }
}
