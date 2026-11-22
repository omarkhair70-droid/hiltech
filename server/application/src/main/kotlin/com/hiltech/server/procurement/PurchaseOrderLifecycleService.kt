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
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class CancelPurchaseOrderRequest(
    val operationId: UUID,
    val expectedVersion: Long,
    val reason: String,
)

data class CancelPurchaseOrderReceipt(
    val purchaseOrderId: UUID,
    val code: String,
    val state: String,
    val eventId: UUID,
    val eventVersion: Long,
    val replayed: Boolean,
    val supplierNotified: Boolean = false,
    val inventoryChanged: Boolean = false,
    val paymentChanged: Boolean = false,
)

/**
 * Cancellation preserves the issued V1 purchase order and appends a terminal
 * V2 event. Issuer authority is checked on every access, including replay.
 * There is NO supplier-facing dispatch or receiving in this S03 boundary.
 */
@Service
class PurchaseOrderLifecycleService @Autowired constructor(
    private val purchases: PurchaseOrderIssuePersistence,
    private val issuer: PurchaseOrderIssueService,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po-cancellation.enabled:false}")
    private val cancellationEnabled: Boolean = false,
    @Value("\${hiltech.procurement.po.delivery-amendments.enabled:false}")
    private val amendmentEnabled: Boolean = false,
    @Value("\${hiltech.procurement.po.supplier-communications.enabled:false}")
    private val communicationEnabled: Boolean = false,
) {
    fun issuedOrders(actor: UUID, organizationId: UUID): List<PurchaseOrderLifecycleSummary> {
        if (!cancellationEnabled && !amendmentEnabled && !communicationEnabled) unavailable()
        issuer.requireIssuerForLifecycle(actor, organizationId)
        return purchases.issuedOrderList(organizationId)
    }

    fun cancel(
        context: ProcurementDraftContext,
        purchaseOrderId: UUID,
        request: CancelPurchaseOrderRequest,
    ): CancelPurchaseOrderReceipt {
        if (!cancellationEnabled) unavailable()
        val reason = request.reason.trim()
        if (request.operationId != context.operationId ||
            request.expectedVersion < 1L ||
            reason.length !in 12..1000 ||
            reason.any { it.code < 32 }
        ) invalid()
        val order = purchases.byId(purchaseOrderId) ?: unavailable()
        issuer.requireIssuerForLifecycle(context.actor, order.organizationId)
        val eventId = UUID.nameUUIDFromBytes(
            "hiltech:procurement:po-cancel:${context.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(purchaseOrderId, request.expectedVersion, reason)
                .joinToString("\u001f").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }

        val execution = commands.execute(IdempotentCommandSpec(
            context.operationId, context.actor,
            "PROCUREMENT_PO_CANCELLED", "PROCUREMENT_PO_LIFECYCLE_EVENT",
            eventId, fingerprint, context.correlationId,
        )) {
            if (!purchases.lockOrder(purchaseOrderId, order.organizationId)) unavailable()
            val current = purchases.byId(purchaseOrderId) ?: unavailable()
            if (current != order || current.supplierDispatchState != "NOT_SENT" ||
                (purchases.hasPhysicalPoReceiving(purchaseOrderId) ||
                    purchases.hasSupplierCommunication(purchaseOrderId))) stale()
            issuer.requireIssuerForLifecycle(context.actor, current.organizationId)
            val history = purchases.lifecycleEvents(purchaseOrderId)
            if (history.any { it.organizationId != current.organizationId ||
                    it.orderId != purchaseOrderId || it.type == "CANCELLED" } ||
                history.map { it.version } != (2L..(history.size + 1L)).toList() ||
                request.expectedVersion != history.size + 1L
            ) stale()
            val at = clock.instant()
            purchases.appendLifecycleEvent(PurchaseOrderLifecycleEvent(
                id = eventId,
                operationId = context.operationId,
                organizationId = current.organizationId,
                orderId = purchaseOrderId,
                version = request.expectedVersion + 1L,
                type = "CANCELLED",
                reason = reason,
                actor = context.actor,
                at = at,
                correlationId = context.correlationId,
            ))
            audit.append(AuditEventRecord(
                actorUserId = context.actor,
                action = "PROCUREMENT_PO_CANCELLED",
                targetType = "PROCUREMENT_PURCHASE_ORDER",
                targetId = purchaseOrderId,
                occurredAt = at,
                correlationId = context.correlationId,
                reason = "UNSENT_ISSUED_PO_TERMINATED",
            ))
            IdempotentCommandOutcome(
                "PROCUREMENT_PO_CANCELLED",
                """{"cancelEventId":"$eventId","purchaseOrderId":"$purchaseOrderId"}""",
            )
        }
        val event = purchases.lifecycleEvents(purchaseOrderId)
            .singleOrNull { it.id == eventId && it.operationId == context.operationId }
            ?: unavailable()
        if (event.organizationId != order.organizationId ||
            event.type != "CANCELLED" ||
            event.version != request.expectedVersion + 1L
        ) unavailable()
        return CancelPurchaseOrderReceipt(
            purchaseOrderId, order.code, "CANCELLED",
            event.id, event.version, execution.replayed,
        )
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_CANCELLATION_UNAVAILABLE",
        "This purchase order lifecycle operation is not available.",
        HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_CANCELLATION_INVALID",
        "PO cancellation requires an explicit reason and exact active version.",
        HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_LIFECYCLE_CHANGED",
        "This PO has already changed or is no longer eligible for cancellation.",
        HttpStatus.CONFLICT,
    )
}
