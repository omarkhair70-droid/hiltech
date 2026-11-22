package com.hiltech.server.procurement

import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.ReceivingLineView
import com.hiltech.server.warehouse.ReceivingRecordView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Exact canonical PO scope copied once into a Warehouse receiving link. */
data class ProcurementPoReceivingSource(
    val purchaseOrderId: UUID,
    val organizationId: UUID,
    val orderVersion: Long,
    val stockItemId: UUID,
    val destinationId: UUID,
    val orderedQuantity: BigDecimal,
    val unitCode: String,
)

interface ProcurementPoReceivingSourcePort {
    fun preflightCreate(
        organizationId: UUID,
        destinationId: UUID,
        sourceRef: String?,
        deliveryDocumentRef: String?,
    ): ProcurementPoReceivingSource

    fun bindCreated(
        receivingRecordId: UUID,
        operationId: UUID,
        actor: UUID,
        correlationId: String,
        source: ProcurementPoReceivingSource,
    )

    fun validateLine(
        receiving: ReceivingRecordView,
        itemId: UUID,
        unit: String,
        expected: BigDecimal?,
    )

    fun validateReceive(
        receiving: ReceivingRecordView,
        line: ReceivingLineView,
        quantity: BigDecimal,
    )

    fun validateComplete(receiving: ReceivingRecordView)

    /** Historical link check remains active even when the feature flag is disabled. */
    fun hasAnyLinkedReceipt(purchaseOrderId: UUID): Boolean
}

/** Test/default safety: PO-labelled receiving never bypasses canonical source checks. */
object DenyPoReceivingSource : ProcurementPoReceivingSourcePort {
    private fun denied(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_RECEIVING_DISABLED", "PO receiving source is not authorized.",
        HttpStatus.NOT_FOUND,
    )
    override fun preflightCreate(
        organizationId: UUID,
        destinationId: UUID,
        sourceRef: String?,
        deliveryDocumentRef: String?,
    ): ProcurementPoReceivingSource = denied()

    override fun bindCreated(
        receivingRecordId: UUID, operationId: UUID, actor: UUID,
        correlationId: String, source: ProcurementPoReceivingSource,
    ): Unit = denied()

    override fun validateLine(
        receiving: ReceivingRecordView, itemId: UUID, unit: String,
        expected: BigDecimal?,
    ): Unit = denied()

    override fun validateReceive(
        receiving: ReceivingRecordView, line: ReceivingLineView, quantity: BigDecimal,
    ): Unit = denied()
    override fun validateComplete(receiving: ReceivingRecordView): Unit = denied()
    override fun hasAnyLinkedReceipt(purchaseOrderId: UUID): Boolean = true
}

/**
 * Every PO stock movement happens ONLY through ReceivingService and its
 * canonical StockLedgerPersistence. This port is merely a transactional
 * source/remaining-quantity guard; it never writes stock itself.
 */
@Component
class JdbcProcurementPoReceivingSource @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val purchases: PurchaseOrderIssuePersistence,
    private val candidates: PurchaseOrderCandidatePersistence,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po.receiving.enabled:false}")
    private val enabled: Boolean = false,
) : ProcurementPoReceivingSourcePort {
    override fun preflightCreate(
        organizationId: UUID, destinationId: UUID,
        sourceRef: String?, deliveryDocumentRef: String?,
    ): ProcurementPoReceivingSource {
        requireEnabled()
        val poId = parsePoRef(sourceRef)
        val reference = deliveryDocumentRef?.trim()
        if (reference == null || reference.length !in 8..255 ||
            reference.any { it.code < 32 }
        ) denied()
        // Called INSIDE the canonical Warehouse create transaction. Locking
        // the PO prevents a concurrent cancellation/amendment from racing.
        if (!purchases.lockOrder(poId, organizationId)) denied()
        val state = purchases.effectiveState(poId) ?: denied()
        if (state.order.organizationId != organizationId ||
            state.effectiveState != "ISSUED" ||
            state.destinationId != destinationId ||
            state.order.supplierDispatchState != "NOT_SENT"
        ) denied()
        val source = candidates.byId(state.order.candidateId) ?: denied()
        if (source.organizationId != organizationId ||
            source.stockItemId != state.order.stockItemId
        ) denied()
        return ProcurementPoReceivingSource(
            poId, organizationId, state.version, source.stockItemId,
            destinationId, source.quantity, source.unitCode,
        )
    }

    override fun bindCreated(
        receivingRecordId: UUID, operationId: UUID,
        actor: UUID, correlationId: String,
        source: ProcurementPoReceivingSource,
    ) {
        requireEnabled()
        val id = UUID.nameUUIDFromBytes(
            "hiltech:po-receiving-link:$operationId".toByteArray(Charsets.UTF_8),
        )
        check(jdbc.update(
            """
            INSERT INTO procurement_po_receiving_link (
                id, operation_id, organization_id, purchase_order_id, receiving_record_id,
                source_order_version, stock_item_id, delivery_storage_location_id,
                ordered_quantity, ordered_unit_code, linked_by_user_id,
                linked_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, operationId, source.organizationId, source.purchaseOrderId,
            receivingRecordId, source.orderVersion, source.stockItemId,
            source.destinationId, source.orderedQuantity, source.unitCode,
            actor, clock.instant().atOffset(ZoneOffset.UTC), correlationId,
        ) == 1)
    }

    override fun validateLine(
        receiving: ReceivingRecordView,
        itemId: UUID, unit: String, expected: BigDecimal?,
    ) {
        val source = requireCurrentSource(receiving)
        if (source.stockItemId != itemId ||
            source.unitCode != unit ||
            expected == null || expected.signum() <= 0 ||
            expected.compareTo(source.orderedQuantity) > 0
        ) denied()
    }

    override fun validateReceive(
        receiving: ReceivingRecordView,
        line: ReceivingLineView,
        quantity: BigDecimal,
    ) {
        val source = requireCurrentSource(receiving)
        val expected = line.expectedQuantity?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: denied()
        if (line.expectedStockItemId != source.stockItemId ||
            line.unitCode != source.unitCode ||
            quantity.signum() <= 0 ||
            quantity.compareTo(expected) > 0
        ) denied()
        val alreadyReceived = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(rl.received_quantity), 0)
            FROM receiving_line rl
            JOIN procurement_po_receiving_link lk ON lk.receiving_record_id = rl.receiving_record_id
            WHERE lk.purchase_order_id = ? AND rl.received_at IS NOT NULL
            """.trimIndent(),
            BigDecimal::class.java, source.purchaseOrderId,
        ) ?: BigDecimal.ZERO
        if (alreadyReceived.add(quantity).compareTo(source.orderedQuantity) > 0) denied()
    }

    override fun validateComplete(receiving: ReceivingRecordView) {
        requireCurrentSource(receiving)
    }

    override fun hasAnyLinkedReceipt(purchaseOrderId: UUID): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM procurement_po_receiving_link
                WHERE purchase_order_id = ?
            )
            """.trimIndent(),
            Boolean::class.java, purchaseOrderId,
        ) == true

    private fun requireCurrentSource(receiving: ReceivingRecordView): ProcurementPoReceivingSource {
        requireEnabled()
        val linked = jdbc.query(
            """
            SELECT organization_id, purchase_order_id, source_order_version, stock_item_id,
                   delivery_storage_location_id, ordered_quantity, ordered_unit_code
            FROM procurement_po_receiving_link
            WHERE receiving_record_id = ?
            """.trimIndent(),
            { rs, _ -> ProcurementPoReceivingSource(
                rs.getObject("purchase_order_id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getLong("source_order_version"),
                rs.getObject("stock_item_id", UUID::class.java),
                rs.getObject("delivery_storage_location_id", UUID::class.java),
                rs.getBigDecimal("ordered_quantity"), rs.getString("ordered_unit_code"),
            ) }, receiving.id,
        ).singleOrNull() ?: denied()
        // Lock PO to serialize receipt writes across multiple deliveries and
        // prevent simultaneous cancellation/amendment while stock is posted.
        if (!purchases.lockOrder(linked.purchaseOrderId, receiving.organizationId))
            denied()
        val state = purchases.effectiveState(linked.purchaseOrderId) ?: denied()
        if (state.order.organizationId != receiving.organizationId ||
            linked.organizationId != receiving.organizationId ||
            state.effectiveState != "ISSUED" ||
            state.destinationId != receiving.destinationStorageLocationId ||
            state.destinationId != linked.destinationId ||
            state.version != linked.orderVersion ||
            receiving.sourceRef != "po:${linked.purchaseOrderId}"
        ) denied()
        return linked
    }

    private fun parsePoRef(raw: String?): UUID {
        val value = raw?.trim() ?: denied()
        if (!value.startsWith("po:")) denied()
        val uuidText = value.removePrefix("po:")
        return runCatching { UUID.fromString(uuidText) }.getOrNull()
            ?.takeIf { value == "po:$it" } ?: denied()
    }

    private fun requireEnabled() {
        if (!enabled) denied()
    }
    private fun denied(): Nothing = throw ProductApiException(
        "PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
        "Purchase Order is not current, not in scope or not eligible for this receipt.",
        HttpStatus.CONFLICT,
    )
}
