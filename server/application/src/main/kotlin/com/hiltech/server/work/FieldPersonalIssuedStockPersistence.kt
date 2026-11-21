package com.hiltech.server.work

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A stock-issued row is NOT a warehouse StockBalance. Only this actor's own
 * issued-to-Employee context can be read by Field; no warehouse authority
 * or stock write command is re-used here.
 */
data class FieldPersonalIssuedStockRow(
    val organizationId: UUID,
    val projectId: UUID,
    val siteId: UUID,
    val workOrderId: UUID,
    val employeeId: UUID,
    val requirementId: UUID,
    val stockItemId: UUID,
    val stockItemCode: String,
    val stockItemName: String,
    val unitCode: String,
    val outstandingQuantity: String,
    val sourceVersion: Long,
    val updatedAt: Instant,
    val serialized: Boolean = false,
)

interface FieldPersonalIssuedStockReadPort {
    fun ownForWorkOrder(
        organizationId: UUID,
        projectId: UUID,
        siteId: UUID,
        workOrderId: UUID,
        employeeId: UUID,
    ): List<FieldPersonalIssuedStockRow>
}

@Repository
class JdbcFieldPersonalIssuedStockRead(
    private val jdbc: JdbcTemplate,
) : FieldPersonalIssuedStockReadPort {
    override fun ownForWorkOrder(
        organizationId: UUID,
        projectId: UUID,
        siteId: UUID,
        workOrderId: UUID,
        employeeId: UUID,
    ): List<FieldPersonalIssuedStockRow> =
        jdbc.query(
            """
            SELECT b.organization_id, b.project_id, b.site_id,
                   b.work_order_id, b.employee_id, b.requirement_id,
                   b.stock_item_id, s.item_code, s.name AS item_name,
                   s.unit_of_measure_code, s.serialized, b.outstanding_qty, b.version,
                   b.updated_at
            FROM stock_issued_balance b
            JOIN work_resource_request req
              ON req.requirement_id = b.requirement_id
             AND req.work_order_id = b.work_order_id
             AND req.organization_id = b.organization_id
             AND req.stock_item_id = b.stock_item_id
            JOIN work_requirement_instance wri
              ON wri.id = req.requirement_id
             AND wri.work_order_id = req.work_order_id
             AND wri.requirement_family = 'MATERIAL'
            JOIN stock_item s
              ON s.id = b.stock_item_id
             AND s.organization_id = b.organization_id
             AND s.unit_of_measure_code = req.unit_code
            WHERE b.organization_id = ?
              AND b.project_id = ?
              AND b.site_id = ?
              AND b.work_order_id = ?
              AND b.employee_id = ?
            ORDER BY s.item_code, b.requirement_id, b.stock_item_id
            LIMIT 101
            """.trimIndent(),
            { row, _ ->
                FieldPersonalIssuedStockRow(
                    organizationId = row.getObject("organization_id", UUID::class.java),
                    projectId = row.getObject("project_id", UUID::class.java),
                    siteId = row.getObject("site_id", UUID::class.java),
                    workOrderId = row.getObject("work_order_id", UUID::class.java),
                    employeeId = row.getObject("employee_id", UUID::class.java),
                    requirementId = row.getObject("requirement_id", UUID::class.java),
                    stockItemId = row.getObject("stock_item_id", UUID::class.java),
                    stockItemCode = row.getString("item_code"),
                    stockItemName = row.getString("item_name"),
                    unitCode = row.getString("unit_of_measure_code"),
                    outstandingQuantity = row.getBigDecimal("outstanding_qty").toPlainString(),
                    sourceVersion = row.getLong("version"),
                    updatedAt = row.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                    serialized = row.getBoolean("serialized"),
                )
            },
            organizationId, projectId, siteId, workOrderId, employeeId,
        )
}
