package com.hiltech.server.warehouse

import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** A pending claim, not approved stock consumption. No HR/private value fields. */
data class FieldUsagePendingForWarehouse(
    val operationId: UUID,
    val organizationId: UUID,
    val workOrderId: UUID,
    val requirementId: UUID,
    val stockItemId: UUID,
    val employeeId: UUID,
    val quantity: String,
    val unitCode: String,
    val issuedVersion: Long,
    val recordedAt: Instant,
)
data class FieldUsageWarehouseReviewPage(
    val warehouseId: UUID,
    val state: String = "PENDING_REVIEW_ONLY",
    val rows: List<FieldUsagePendingForWarehouse>,
    val limited: Boolean,
)

interface FieldUsageWarehouseReviewReadPort {
    fun pending(organizationId: UUID, warehouseId: UUID): List<FieldUsagePendingForWarehouse>
}

@Repository
class JdbcFieldUsageWarehouseReviewRead(private val jdbc: JdbcTemplate) : FieldUsageWarehouseReviewReadPort {
    override fun pending(organizationId: UUID, warehouseId: UUID) = jdbc.query(
        """
        SELECT r.operation_id, r.organization_id, r.work_order_id, r.requirement_id,
               r.stock_item_id, r.employee_id, r.quantity, r.unit_code,
               r.issued_version, r.created_at
        FROM field_material_usage_report r
        WHERE r.organization_id = ? AND r.state = 'PENDING_REVIEW'
          AND EXISTS (
              SELECT 1 FROM stock_movement m JOIN storage_location l
                ON l.id = m.from_storage_location_id
              WHERE m.organization_id = r.organization_id AND m.work_order_id = r.work_order_id
                AND m.stock_item_id = r.stock_item_id
                AND m.work_requirement_instance_id = r.requirement_id
                AND m.recipient_target_type = 'EMPLOYEE'
                AND m.recipient_target_id = r.employee_id
                AND m.movement_type = 'ISSUE' AND l.warehouse_id = ?
          )
          -- A multi-warehouse issue is an explicit review hold, not a unilateral queue.
          AND NOT EXISTS (
              SELECT 1 FROM stock_movement m LEFT JOIN storage_location l
                ON l.id = m.from_storage_location_id
              WHERE m.organization_id = r.organization_id AND m.work_order_id = r.work_order_id
                AND m.stock_item_id = r.stock_item_id
                AND m.work_requirement_instance_id = r.requirement_id
                AND m.recipient_target_type = 'EMPLOYEE'
                AND m.recipient_target_id = r.employee_id
                AND m.movement_type = 'ISSUE'
                AND l.warehouse_id IS DISTINCT FROM ?
          )
        ORDER BY r.created_at, r.operation_id LIMIT 101
        """.trimIndent(),
        { row, _ ->
            FieldUsagePendingForWarehouse(
                row.getObject("operation_id", UUID::class.java),
                row.getObject("organization_id", UUID::class.java),
                row.getObject("work_order_id", UUID::class.java),
                row.getObject("requirement_id", UUID::class.java),
                row.getObject("stock_item_id", UUID::class.java),
                row.getObject("employee_id", UUID::class.java),
                row.getBigDecimal("quantity").toPlainString(),
                row.getString("unit_code"), row.getLong("issued_version"),
                row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            )
        },
        organizationId, warehouseId, warehouseId,
    )
}

/**
 * Existing warehouse reservation + per-origin movement permission are both needed.
 * This does NOT grant review approval, Consume or any cross-warehouse visibility.
 */
@Service
class FieldUsageWarehouseReviewService(
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val stock: StockFulfillmentPersistence,
    private val read: FieldUsageWarehouseReviewReadPort,
    @Value("\${hiltech.field.material-usage-review-read.enabled:false}")
    private val enabled: Boolean = false,
) {
    @Transactional(readOnly = true)
    fun pending(actor: UUID, warehouseId: UUID): FieldUsageWarehouseReviewPage {
        if (!enabled) hidden()
        val facility = warehouse.warehouse(warehouseId)?.takeIf {
            it.active && authorization.canReserveResources(actor, it)
        } ?: hidden()
        val candidates = read.pending(facility.organizationId, warehouseId)
        val visible = candidates.filter { r ->
            if (r.organizationId != facility.organizationId) return@filter false
            val origins = stock.origins(r.stockItemId, r.requirementId, r.employeeId)
            origins.isNotEmpty() && origins.all { id ->
                val origin = warehouse.storageLocation(id)
                origin != null && origin.organizationId == facility.organizationId &&
                    origin.warehouseId == warehouseId && authorization.canTransferStock(actor, origin)
            }
        }.take(100)
        return FieldUsageWarehouseReviewPage(
            warehouseId, rows = visible,
            limited = visible.size == 100 && candidates.size > 100,
        )
    }

    private fun hidden(): Nothing = throw ProductApiException(
        "FIELD_MATERIAL_REVIEW_UNAVAILABLE",
        "The warehouse review context is not available.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FieldUsageWarehouseReviewController(private val service: FieldUsageWarehouseReviewService) {
    @GetMapping("/v1/warehouses/{warehouseId}/material-usage-reports/pending")
    fun pending(request: HttpServletRequest, @PathVariable warehouseId: UUID) =
        service.pending(request.stockActor(), warehouseId)
}
