package com.hiltech.server.warehouse

import com.hiltech.server.work.WorkOrderSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class StockFulfillmentKind { ISSUE, RETURN, CONSUME }

data class IssuedStockContext(
    val stockItemId: UUID, val employeeId: UUID, val projectId: UUID, val siteId: UUID,
    val workOrderId: UUID, val requirementId: UUID, val outstandingQuantity: String, val version: Long,
)
data class IssuedStockDirectoryEntry(
    val context: IssuedStockContext, val employeeCode: String, val employeeName: String,
    val workCode: String, val workTitle: String, val workVersion: Long, val unitCode: String,
)
data class IssuedStockDirectory(val items: List<IssuedStockDirectoryEntry>, val limited: Boolean)

data class StockFulfillmentReceipt(
    val movementId: UUID, val operationId: UUID, val type: String, val itemId: UUID,
    val employeeId: UUID, val workOrderId: UUID, val requirementId: UUID, val quantity: String,
    val unitCode: String, val replayed: Boolean,
)
data class StockFulfillmentHistoryRow(
    val movementId: UUID, val operationId: UUID, val type: String, val quantity: String, val unitCode: String,
    val employeeCode: String, val employeeName: String, val workOrderId: UUID, val requirementId: UUID,
    val fromLocationCode: String?, val toLocationCode: String?, val occurredAt: String,
)
data class StockFulfillmentHistory(val items: List<StockFulfillmentHistoryRow>, val limited: Boolean)
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

    // Search only Stock contexts with an authoritative recorded ISSUE from this Warehouse.
    // Authorization for ALL origins is checked separately before constructing a response.
    fun directory(item: UUID, org: UUID, warehouse: UUID, search: String): List<Triple<IssuedStockContext, String, String>> = jdbc.query("""
        SELECT b.*, e.employee_code, p.display_name
        FROM stock_issued_balance b
        JOIN employee e ON e.id = b.employee_id AND e.organization_id = b.organization_id
        JOIN person p ON p.id = e.person_id
        WHERE b.stock_item_id = ? AND b.organization_id = ? AND b.outstanding_qty > 0
          AND (strpos(lower(e.employee_code), lower(?)) > 0 OR strpos(lower(p.display_name), lower(?)) > 0)
          AND EXISTS (SELECT 1 FROM stock_movement m JOIN storage_location l ON l.id = m.from_storage_location_id
            WHERE m.stock_item_id = b.stock_item_id AND m.work_requirement_instance_id = b.requirement_id
              AND m.recipient_target_id = b.employee_id AND m.movement_type = 'ISSUE' AND l.warehouse_id = ?)
        ORDER BY e.employee_code, b.work_order_id, b.requirement_id, b.employee_id LIMIT 101
    """.trimIndent(), { r, _ ->
        Triple(IssuedStockContext(r.getObject("stock_item_id", UUID::class.java), r.getObject("employee_id", UUID::class.java),
            r.getObject("project_id", UUID::class.java), r.getObject("site_id", UUID::class.java),
            r.getObject("work_order_id", UUID::class.java), r.getObject("requirement_id", UUID::class.java),
            r.getBigDecimal("outstanding_qty").toPlainString(), r.getLong("version")),
            r.getString("employee_code"), r.getString("display_name"))
    }, item, org, search, search, warehouse)

    // There is not yet an issued custody reassignment policy. Restrict a context
    // to actors authorized for ALL contributing source locations; fail closed.
    fun origins(item: UUID, requirement: UUID, employee: UUID): List<UUID> = jdbc.query("""
        SELECT DISTINCT from_storage_location_id FROM stock_movement
        WHERE stock_item_id = ? AND work_requirement_instance_id = ? AND recipient_target_id = ?
          AND movement_type = 'ISSUE' AND from_storage_location_id IS NOT NULL
    """.trimIndent(), { r, _ -> r.getObject(1, UUID::class.java) }, item, requirement, employee)

    /**
     * Read immutable physical movements, including completed/zero custody.
     * Apply origin and endpoint authorization before the limit, not after it.
     */
    fun history(item: UUID, org: UUID, warehouse: UUID, search: String, visible: List<UUID>): StockFulfillmentHistory {
        if (visible.isEmpty()) return StockFulfillmentHistory(emptyList(), false)
        val placeholders = visible.joinToString(",") { "?" }
        val rows = jdbc.query("""
            SELECT m.id, m.operation_id, m.movement_type, m.quantity, m.unit_code,
                e.employee_code, p.display_name, m.work_order_id, m.work_requirement_instance_id,
                f.code AS from_code, t.code AS to_code, m.occurred_at
            FROM stock_movement m
            JOIN employee e ON e.id = m.recipient_target_id AND e.organization_id = m.organization_id
            JOIN person p ON p.id = e.person_id
            LEFT JOIN storage_location f ON f.id = m.from_storage_location_id
            LEFT JOIN storage_location t ON t.id = m.to_storage_location_id
            WHERE m.stock_item_id = ? AND m.organization_id = ?
              AND m.recipient_target_type = 'EMPLOYEE'
              AND m.movement_type IN ('ISSUE','RETURN','CONSUME')
              AND m.work_requirement_instance_id IS NOT NULL
              AND (strpos(lower(e.employee_code), lower(?)) > 0 OR strpos(lower(p.display_name), lower(?)) > 0)
              AND EXISTS (
                  SELECT 1 FROM stock_movement origin JOIN storage_location l ON l.id = origin.from_storage_location_id
                  WHERE origin.stock_item_id = m.stock_item_id AND origin.organization_id = m.organization_id
                    AND origin.work_requirement_instance_id = m.work_requirement_instance_id
                    AND origin.recipient_target_id = m.recipient_target_id
                    AND origin.movement_type = 'ISSUE' AND l.warehouse_id = ?
              )
              AND NOT EXISTS (
                  SELECT 1 FROM stock_movement origin
                  WHERE origin.stock_item_id = m.stock_item_id AND origin.organization_id = m.organization_id
                    AND origin.work_requirement_instance_id = m.work_requirement_instance_id
                    AND origin.recipient_target_id = m.recipient_target_id AND origin.movement_type = 'ISSUE'
                    AND (origin.from_storage_location_id IS NULL OR origin.from_storage_location_id NOT IN ($placeholders))
              )
              AND (m.from_storage_location_id IS NULL OR m.from_storage_location_id IN ($placeholders))
              AND (m.to_storage_location_id IS NULL OR m.to_storage_location_id IN ($placeholders))
            ORDER BY m.recorded_at DESC, m.id DESC LIMIT 101
        """.trimIndent(), { r, _ ->
            StockFulfillmentHistoryRow(r.getObject("id", UUID::class.java), r.getObject("operation_id", UUID::class.java),
                r.getString("movement_type"), r.getBigDecimal("quantity").toPlainString(), r.getString("unit_code"),
                r.getString("employee_code"), r.getString("display_name"), r.getObject("work_order_id", UUID::class.java),
                r.getObject("work_requirement_instance_id", UUID::class.java), r.getString("from_code"), r.getString("to_code"),
                r.getObject("occurred_at", OffsetDateTime::class.java).toString())
        }, *(listOf<Any>(item, org, search, search, warehouse) + visible + visible + visible).toTypedArray())
        return StockFulfillmentHistory(rows.take(100), rows.size > 100)
    }

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
