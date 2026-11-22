package com.hiltech.server.procurement

import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.ReceivingRecordView
import com.hiltech.server.warehouse.ReceivingSourceType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * S04 adapter: link receipts to the *existing* Warehouse Receiving service and
 * its physical StockMovement. No second ledger, no financial side effects.
 *
 * The guarded PO path is opt-in while legacy Phase-5 source references remain
 * unchanged. All guarded writes are checked inside the canonical command
 * transaction and serialized on the immutable PO parent row.
 */
@Service
class ProcurementPoReceivingGuard @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val orders: PurchaseOrderIssuePersistence,
    @Value("\${hiltech.procurement.po-receiving.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun linkNewRecord(
        recordId: UUID,
        organizationId: UUID,
        destinationId: UUID,
        sourceType: ReceivingSourceType,
        sourceRef: String?,
        actor: UUID,
        at: Instant,
        correlationId: String,
    ) {
        if (sourceType != ReceivingSourceType.PO || !enabled) return
        val orderId = sourceRef?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: invalid()
        val order = lockCurrent(orderId, organizationId)
        if (order.destinationId != destinationId) invalid()
        check(jdbc.update(
            """
            INSERT INTO procurement_po_receiving_link (
              receiving_record_id, organization_id, purchase_order_id,
              linked_order_version, destination_storage_location_id,
              stock_item_id, linked_by_user_id, linked_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            recordId, organizationId, orderId, order.version, destinationId,
            order.stockItemId, actor, at.atOffset(ZoneOffset.UTC), correlationId,
        ) == 1)
    }

    /** Only new verified PO receipts are governed; legacy free-text PO refs remain unchanged while feature is off. */
    fun isGovernedPo(record: ReceivingRecordView): Boolean = bound(record) != null

    fun requireStockLine(
        record: ReceivingRecordView,
        stockItemId: UUID,
        unitCode: String,
        expected: BigDecimal?,
    ) {
        val po = bound(record) ?: return
        if (po.stockItemId != stockItemId || po.unitCode != unitCode ||
            (expected != null && expected.compareTo(po.quantity) > 0)
        ) invalid()
    }

    /**
     * Lock the parent PO before summing *physical* posted receipts. Concurrent
     * receiving records for the same order therefore cannot both pass capacity.
     * This is called before canonical StockLedgerPersistence.appendReceiving.
     */
    fun requireStockReceipt(
        record: ReceivingRecordView,
        stockItemId: UUID,
        unitCode: String,
        quantity: BigDecimal,
    ) {
        val po = bound(record) ?: return
        if (po.stockItemId != stockItemId || po.unitCode != unitCode ||
            quantity.signum() <= 0
        ) invalid()
        val received = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(l.received_quantity), 0)
            FROM procurement_po_receiving_link b
            JOIN receiving_line l ON l.receiving_record_id = b.receiving_record_id
            WHERE b.purchase_order_id = ? AND b.organization_id = ?
              AND l.received_at IS NOT NULL
            """.trimIndent(),
            BigDecimal::class.java, po.id, record.organizationId,
        ) ?: BigDecimal.ZERO
        if (received.add(quantity).compareTo(po.quantity) > 0) overDelivery()
    }

    fun requireStockOnly(record: ReceivingRecordView) {
        if (bound(record) != null) invalid()
    }

    fun requireBoundCurrent(record: ReceivingRecordView) {
        bound(record)
    }

    private fun bound(record: ReceivingRecordView): LockedPo? {
        if (record.sourceType != ReceivingSourceType.PO || !enabled) return null
        val orderId = jdbc.query(
            """
            SELECT purchase_order_id FROM procurement_po_receiving_link
            WHERE receiving_record_id = ? AND organization_id = ?
              AND destination_storage_location_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("purchase_order_id", UUID::class.java) },
            record.id, record.organizationId, record.destinationStorageLocationId,
        ).singleOrNull() ?: invalid()
        if (record.sourceRef != orderId.toString()) invalid()
        val po = lockCurrent(orderId, record.organizationId)
        if (po.destinationId != record.destinationStorageLocationId) invalid()
        return po
    }

    private fun lockCurrent(orderId: UUID, organizationId: UUID): LockedPo {
        val snapshot = jdbc.query(
            """
            SELECT id, stock_item_id, quantity, unit_code
            FROM procurement_purchase_order
            WHERE id = ? AND organization_id = ? AND state = 'ISSUED'
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> LockedPo(
                rs.getObject("id", UUID::class.java),
                rs.getObject("stock_item_id", UUID::class.java),
                rs.getBigDecimal("quantity"),
                rs.getString("unit_code"),
                0L, UUID(0, 0),
            ) }, orderId, organizationId,
        ).singleOrNull() ?: invalid()
        val effective = orders.effectiveState(orderId)
            ?.takeIf { it.order.organizationId == organizationId &&
                it.effectiveState == "ISSUED" } ?: invalid()
        // A source-bound PO is not eligible for physical receipt just because
        // an internal issuer clicked Issue. An external dispatch attestation
        // must exist; this command does not itself notify the supplier.
        if (!orders.hasSupplierCommunication(orderId)) invalid()
        return snapshot.copy(version = effective.version, destinationId = effective.destinationId)
    }

    private data class LockedPo(
        val id: UUID,
        val stockItemId: UUID,
        val quantity: BigDecimal,
        val unitCode: String,
        val version: Long,
        val destinationId: UUID,
    )

    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
        "Receipt must reference a current same-company PO, approved stock item and destination.",
        HttpStatus.CONFLICT,
    )

    private fun overDelivery(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_RECEIPT_OVER_DELIVERY",
        "Physical receipt exceeds the ordered quantity; record an over-delivery discrepancy.",
        HttpStatus.CONFLICT,
    )
}
