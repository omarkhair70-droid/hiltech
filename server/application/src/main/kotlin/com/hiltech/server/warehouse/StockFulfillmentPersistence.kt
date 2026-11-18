package com.hiltech.server.warehouse

import com.hiltech.server.work.WorkOrderSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class StockFulfillmentKind { ISSUE, RETURN, CONSUME }

data class IssuedStockContext(
    val stockItemId: UUID, val employeeId: UUID, val projectId: UUID, val siteId: UUID,
    val workOrderId: UUID, val requirementId: UUID, val outstandingQuantity: String, val version: Long,
)
data class StockFulfillmentReceipt(
    val movementId: UUID, val operationId: UUID, val type: String, val itemId: UUID,
    val employeeId: UUID, val workOrderId: UUID, val requirementId: UUID, val quantity: String,
    val unitCode: String, val replayed: Boolean,
)
data class FulfilledStockFact(val movementId: UUID, val quantity: BigDecimal, val version: Long)

@Repository
class StockFulfillmentPersistence(private val jdbc: JdbcTemplate) {
    fun issued(item: UUID, work: UUID): List<IssuedStockContext> = jdbc.query("""
        SELECT * FROM stock_issued_balance WHERE stock_item_id = ? AND work_order_id = ?
        ORDER BY requirement_id, employee_id
    """.trimIndent(), { r, _ ->
        IssuedStockContext(r.getObject("stock_item_id", UUID::class.java), r.getObject("employee_id", UUID::class.java),
            r.getObject("project_id", UUID::class.java), r.getObject("site_id", UUID::class.java),
            r.getObject("work_order_id", UUID::class.java), r.getObject("requirement_id", UUID::class.java),
            r.getBigDecimal("outstanding_qty").toPlainString(), r.getLong("version"))
    }, item, work)

    // There is not yet an issued custody reassignment policy. Restrict a context
    // to actors authorized for ALL contributing source locations; fail closed.
    fun origins(item: UUID, requirement: UUID, employee: UUID): List<UUID> = jdbc.query("""
        SELECT DISTINCT from_storage_location_id FROM stock_movement
        WHERE stock_item_id = ? AND work_requirement_instance_id = ? AND recipient_target_id = ?
          AND movement_type = 'ISSUE' AND from_storage_location_id IS NOT NULL
    """.trimIndent(), { r, _ -> r.getObject(1, UUID::class.java) }, item, requirement, employee)

    fun fact(requirement: UUID, at: Instant): FulfilledStockFact? = jdbc.query("""
        SELECT id, allocated, source_version FROM (
            SELECT id, recorded_at,
                sum(CASE movement_type WHEN 'ISSUE' THEN quantity WHEN 'RETURN' THEN -quantity ELSE 0 END)
                    OVER () AS allocated,
                count(*) OVER () AS source_version
            FROM stock_movement WHERE work_requirement_instance_id = ?
              AND movement_type IN ('ISSUE','RETURN','CONSUME') AND recorded_at <= ?
        ) facts ORDER BY recorded_at DESC, id DESC LIMIT 1
    """.trimIndent(), { r, _ -> FulfilledStockFact(r.getObject("id", UUID::class.java),
        r.getBigDecimal("allocated"), r.getLong("source_version")) }, requirement, at.atOffset(ZoneOffset.UTC)).singleOrNull()

    fun post(item: StockItemView, order: WorkOrderSnapshot, requirement: UUID, employee: UUID,
        context: StockCommandContext, kind: StockFulfillmentKind, quantity: BigDecimal, location: UUID?,
        reservation: UUID?, now: Instant): UUID {
        val id = UUID.nameUUIDFromBytes("hiltech:stock-fulfilment:${context.operationId}".toByteArray(Charsets.UTF_8))
        val at = now.atOffset(ZoneOffset.UTC)
        jdbc.update("""
            INSERT INTO stock_movement (id, stock_item_id, organization_id, quantity, unit_code, movement_type,
                from_storage_location_id, to_storage_location_id, project_id, site_id, work_order_id,
                recipient_target_type, recipient_target_id, work_requirement_instance_id, fulfilled_reservation_id,
                operation_id, reason_code, occurred_at, recorded_at, recorded_by, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EMPLOYEE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), id, item.id, item.organizationId, quantity, item.baseUnitCode, kind.name,
            location.takeIf { kind == StockFulfillmentKind.ISSUE }, location.takeIf { kind == StockFulfillmentKind.RETURN },
            order.projectId, order.siteId, order.workOrderId, employee, requirement, reservation,
            context.operationId, kind.name, at, at, context.actor, context.correlationId)
        return id
    }

    fun receipt(operation: UUID, replayed: Boolean): StockFulfillmentReceipt? = jdbc.query("""
        SELECT * FROM stock_movement WHERE operation_id = ? AND work_requirement_instance_id IS NOT NULL
          AND movement_type IN ('ISSUE','RETURN','CONSUME')
    """.trimIndent(), { r, _ -> StockFulfillmentReceipt(
        r.getObject("id", UUID::class.java), operation, r.getString("movement_type"),
        r.getObject("stock_item_id", UUID::class.java), r.getObject("recipient_target_id", UUID::class.java),
        r.getObject("work_order_id", UUID::class.java), r.getObject("work_requirement_instance_id", UUID::class.java),
        r.getBigDecimal("quantity").toPlainString(), r.getString("unit_code"), replayed)
    }, operation).singleOrNull()
}
