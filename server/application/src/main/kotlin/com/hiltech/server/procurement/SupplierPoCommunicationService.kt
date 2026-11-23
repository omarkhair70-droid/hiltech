package com.hiltech.server.procurement

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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class AttestSupplierPoCommunicationRequest(
    val operationId: UUID,
    val expectedOrderVersion: Long,
    val eventType: String,
    val channel: String,
    val externalReference: String,
    val occurredAt: Instant,
)
data class SupplierPoCommunicationView(
    val eventId: UUID,
    val purchaseOrderId: UUID,
    val eventType: String,
    val eventVersion: Long,
    val channel: String,
    val externalReference: String,
    val occurredAt: Instant,
    val recordedBy: UUID,
    val replayed: Boolean = false,
    val sentByThisApi: Boolean = false,
)

/**
 * Records separately evidenced operator attestations only. This API does NOT
 * send messages, contact vendors or mark an external API delivery successful.
 * Authority, idempotency and lifecycle are checked on every operation/replay.
 */
@Service
class SupplierPoCommunicationService @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val orders: PurchaseOrderIssuePersistence,
    private val issuer: PurchaseOrderIssueService,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po.supplier-communications.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun history(actor: UUID, poId: UUID): List<SupplierPoCommunicationView> {
        available()
        val po = orders.byId(poId) ?: missing()
        issuer.requireIssuerForLifecycle(actor, po.organizationId)
        return query(poId)
    }

    fun attest(ctx: ProcurementDraftContext, poId: UUID, input: AttestSupplierPoCommunicationRequest): SupplierPoCommunicationView {
        available()
        if (input.operationId != ctx.operationId || input.expectedOrderVersion < 1L ||
            input.eventType !in setOf("EXTERNAL_DISPATCH_ATTESTED", "SUPPLIER_ACK_ATTESTED") ||
            input.channel !in setOf("EMAIL", "SUPPLIER_PORTAL", "HAND_DELIVERY", "OTHER_VERIFIED_CHANNEL") ||
            input.externalReference.trim().length !in 8..255 ||
            input.externalReference.any { it.code < 32 } ||
            input.occurredAt.isAfter(clock.instant()) ||
            input.occurredAt.isBefore(clock.instant().minusSeconds(366L * 86400L))
        ) invalid()
        val po = orders.byId(poId) ?: missing()
        issuer.requireIssuerForLifecycle(ctx.actor, po.organizationId)
        val eventId = UUID.nameUUIDFromBytes(
            "hiltech:po-supplier-communication:${ctx.operationId}".toByteArray(Charsets.UTF_8),
        )
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(poId, input.expectedOrderVersion, input.eventType,
                input.channel, input.externalReference.trim(), input.occurredAt)
                .joinToString("\u001f").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }

        val outcome = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_PO_SUPPLIER_COMMUNICATION_ATTESTED",
            "PROCUREMENT_PO_SUPPLIER_COMMUNICATION_EVENT",
            eventId, fingerprint, ctx.correlationId,
        )) {
            if (!orders.lockOrder(poId, po.organizationId)) missing()
            issuer.requireIssuerForLifecycle(ctx.actor, po.organizationId)
            val state = orders.effectiveState(poId) ?: missing()
            if (state.effectiveState != "ISSUED" ||
                state.order.organizationId != po.organizationId ||
                state.version != input.expectedOrderVersion
            ) stale()
            val existing = query(poId)
            val version = when (input.eventType) {
                "EXTERNAL_DISPATCH_ATTESTED" -> {
                    if (existing.isNotEmpty() || orders.hasPhysicalPoReceiving(poId)) stale()
                    1L
                }
                "SUPPLIER_ACK_ATTESTED" -> {
                    if (existing.size != 1 || existing.single().eventType != "EXTERNAL_DISPATCH_ATTESTED") stale()
                    2L
                }
                else -> invalid()
            }
            val now = clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_po_supplier_communication_event(
                  id, operation_id, organization_id, purchase_order_id,
                  event_version, event_type, channel, external_reference,
                  recorded_by_user_id, occurred_at, recorded_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                eventId, ctx.operationId, po.organizationId, poId,
                version, input.eventType, input.channel,
                input.externalReference.trim(), ctx.actor,
                input.occurredAt.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC), ctx.correlationId,
            ) == 1)
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor,
                action = "PROCUREMENT_PO_SUPPLIER_COMMUNICATION_ATTESTED",
                targetType = "PROCUREMENT_PURCHASE_ORDER",
                targetId = poId, occurredAt = now,
                correlationId = ctx.correlationId,
                reason = input.eventType,
            ))
            IdempotentCommandOutcome(
                "PROCUREMENT_PO_SUPPLIER_COMMUNICATION_ATTESTED",
                """{"eventId":"$eventId","purchaseOrderId":"$poId"}""",
            )
        }
        issuer.requireIssuerForLifecycle(ctx.actor, po.organizationId)
        val event = query(poId).singleOrNull { it.eventId == eventId } ?: missing()
        return event.copy(replayed = outcome.replayed)
    }

    private fun query(poId: UUID) = jdbc.query(
        """
        SELECT id, purchase_order_id, event_version, event_type, channel,
               external_reference, occurred_at, recorded_by_user_id
        FROM procurement_po_supplier_communication_event
        WHERE purchase_order_id = ? ORDER BY event_version
        """.trimIndent(),
        { rs, _ -> SupplierPoCommunicationView(
            rs.getObject("id", UUID::class.java),
            rs.getObject("purchase_order_id", UUID::class.java),
            rs.getString("event_type"), rs.getLong("event_version"),
            rs.getString("channel"), rs.getString("external_reference"),
            rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("recorded_by_user_id", UUID::class.java),
        ) }, poId,
    )
    private fun available() { if (!enabled) missing() }
    private fun missing(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_COMMUNICATION_UNAVAILABLE",
        "PO communication attestation is unavailable or unauthorized.", HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_COMMUNICATION_INVALID",
        "A valid evidence reference, channel, event type and timestamp are required.", HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_COMMUNICATION_STALE",
        "Order or communication history changed; action cannot be applied.", HttpStatus.CONFLICT,
    )
}

@RestController
class SupplierPoCommunicationController(private val service: SupplierPoCommunicationService) {
    @GetMapping("/v1/procurement/purchase-orders/{id}/supplier-communications")
    fun list(servlet: HttpServletRequest, @PathVariable id: UUID): List<SupplierPoCommunicationView> =
        service.history(actor(servlet), id)

    @PostMapping("/v1/procurement/purchase-orders/{id}/supplier-communications")
    fun attest(
        servlet: HttpServletRequest, @PathVariable id: UUID,
        @RequestBody body: AttestSupplierPoCommunicationRequest,
    ): SupplierPoCommunicationView {
        val c = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey, body.operationId)
        return service.attest(
            ProcurementDraftContext(body.operationId, actor(servlet), c.correlationId),
            id, body,
        )
    }
    private fun actor(servlet: HttpServletRequest) =
        HiltechRequestContext.current(servlet).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED", "Authentication required", HttpStatus.UNAUTHORIZED)
}
