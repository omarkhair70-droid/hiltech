package com.hiltech.server.warehouse

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Warehouse-owned contract for purchase-order-source receiving checks.
 *
 * The canonical ReceivingService must remain inside the Warehouse module:
 * Procurement supplies its governed-PO implementation without making Warehouse
 * depend on the Procurement module. This does not bypass any PO approval,
 * dispatch attestation, Evidence, quantity or ledger guard.
 */
interface PoReceivingPolicyPort {
    fun linkNewRecord(
        recordId: UUID,
        organizationId: UUID,
        destinationId: UUID,
        sourceType: ReceivingSourceType,
        sourceRef: String?,
        actor: UUID,
        at: Instant,
        correlationId: String,
    )

    fun isGovernedPo(record: ReceivingRecordView): Boolean

    fun requireStockLine(
        record: ReceivingRecordView,
        stockItemId: UUID,
        unitCode: String,
        expected: BigDecimal?,
    )

    fun requireStockReceipt(
        record: ReceivingRecordView,
        stockItemId: UUID,
        unitCode: String,
        quantity: BigDecimal,
    )

    fun requireReadyDeliveryNote(record: ReceivingRecordView)

    fun requireStockOnly(record: ReceivingRecordView)

    fun requireBoundCurrent(record: ReceivingRecordView)
}
