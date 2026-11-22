package com.hiltech.server.procurement

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.CreateReceivingRequest
import com.hiltech.server.warehouse.ReceivingCommandContext
import com.hiltech.server.warehouse.ReceivingRecordResult
import com.hiltech.server.warehouse.ReceivingService
import com.hiltech.server.warehouse.ReceivingSourceType
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
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
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

data class PurchaseOrderWarehouseIntakeRow(
    val purchaseOrderId: UUID,
    val poCode: String,
    val stockItemId: UUID,
    val unitCode: String,
    val destinationStorageLocationId: UUID,
    val expectedQuantity: String,
    val physicallyReceivedQuantity: String,
    val remainingQuantity: String,
    val effectiveOrderVersion: Long,
)
data class StartPoWarehouseReceivingRequest(val operationId: UUID)

/**
 * Authoritative Warehouse-facing queue. No commercial supplier price is
 * exposed; actual item intake reuses the canonical ReceivingService/StockLedger.
 */
@Service
class PurchaseOrderWarehouseIntakeService @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val purchases: PurchaseOrderIssuePersistence,
    private val warehouse: WarehousePersistencePort,
    private val authority: WarehouseAuthorizationPort,
    private val receiving: ReceivingService,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po-receiving.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun queue(actor: UUID, org: UUID): List<PurchaseOrderWarehouseIntakeRow> {
        available()
        val now = clock.instant()
        val refs = jdbc.query(
            """
            SELECT id FROM procurement_purchase_order
            WHERE organization_id=? ORDER BY issued_at DESC LIMIT 250
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) }, org,
        )
        return refs.mapNotNull { id ->
            val state = purchases.effectiveState(id) ?: return@mapNotNull null
            if (state.effectiveState != "ISSUED" ||
                state.order.organizationId != org ||
                !purchases.hasSupplierCommunication(id)
            ) return@mapNotNull null
            val location = warehouse.storageLocation(state.destinationId)
                ?.takeIf { it.organizationId == org &&
                    it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                    (it.activeFrom == null || it.activeFrom <= now) &&
                    (it.activeUntil == null || it.activeUntil > now) &&
                    authority.canReceiveAt(actor, it)
                } ?: return@mapNotNull null
            val source = jdbc.query(
                "SELECT quantity, unit_code FROM procurement_purchase_order WHERE id=? AND organization_id=?",
                { rs, _ -> rs.getBigDecimal("quantity") to rs.getString("unit_code") },
                id, org,
            ).singleOrNull() ?: return@mapNotNull null
            val received = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(l.received_quantity),0)
                FROM procurement_po_receiving_link b
                JOIN receiving_line l ON l.receiving_record_id=b.receiving_record_id
                WHERE b.purchase_order_id=? AND b.organization_id=?
                  AND l.received_at IS NOT NULL
                """.trimIndent(),
                BigDecimal::class.java, id, org,
            ) ?: BigDecimal.ZERO
            val remaining = source.first.subtract(received)
            if (remaining.signum() <= 0) return@mapNotNull null
            PurchaseOrderWarehouseIntakeRow(
                id, state.order.code, state.order.stockItemId, source.second,
                location.storageLocationId, source.first.toPlainString(),
                received.toPlainString(), remaining.toPlainString(), state.version,
            )
        }
    }

    fun start(actor: UUID, poId: UUID, operationId: UUID, correlationId: String): ReceivingRecordResult {
        available()
        val state = purchases.effectiveState(poId) ?: unavailable()
        if (state.effectiveState != "ISSUED" ||
            !purchases.hasSupplierCommunication(poId)
        ) unavailable()
        val location = warehouse.storageLocation(state.destinationId)
            ?.takeIf { it.organizationId == state.order.organizationId &&
                it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                authority.canReceiveAt(actor, it)
            } ?: unavailable()
        // The lower-level receipt guard independently rechecks PO, company,
        // destination, item and external dispatch in the idempotent transaction.
        return receiving.create(
            ReceivingCommandContext(operationId, actor, correlationId),
            CreateReceivingRequest(
                state.order.organizationId, location.storageLocationId,
                ReceivingSourceType.PO, poId.toString(),
            ),
        )
    }
    private fun available() { if (!enabled) unavailable() }
    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_RECEIVING_UNAVAILABLE",
        "No currently authorized or dispatch-backed PO is available for this warehouse.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class PurchaseOrderWarehouseIntakeController(
    private val service: PurchaseOrderWarehouseIntakeService,
) {
    @GetMapping("/v1/procurement/organizations/{orgId}/warehouse-po-intake")
    fun queue(servlet: HttpServletRequest, @PathVariable orgId: UUID) =
        service.queue(actor(servlet), orgId)

    @PostMapping("/v1/procurement/purchase-orders/{poId}/warehouse-po-intake")
    fun start(
        servlet: HttpServletRequest, @PathVariable poId: UUID,
        @RequestBody body: StartPoWarehouseReceivingRequest,
    ): ReceivingRecordResult {
        val req = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(req.idempotencyKey, body.operationId)
        return service.start(actor(servlet), poId, body.operationId, req.correlationId)
    }

    private fun actor(servlet: HttpServletRequest): UUID =
        HiltechRequestContext.current(servlet).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required", HttpStatus.UNAUTHORIZED,
            )
}
