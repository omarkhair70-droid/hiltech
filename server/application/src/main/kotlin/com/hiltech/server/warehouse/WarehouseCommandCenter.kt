package com.hiltech.server.warehouse

import jakarta.servlet.http.HttpServletRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class WarehouseInventoryOverview(
    val balanceLineCount: Int,
    val stockItemCount: Int,
    val zeroOnHandLineCount: Int,
    val zeroAvailableLineCount: Int,
    val lowStockPolicyConfigured: Boolean,
    val lowStockPolicyReason: String?,
)

data class WarehouseCommandCenterItem(
    val sourceType: String,
    val sourceId: UUID,
    val label: String,
    val state: String,
    val reasonCodes: List<String>,
    val occurredAt: Instant?,
    val dueAt: Instant?,
    val projectId: UUID?,
    val siteId: UUID?,
    val workOrderId: UUID?,
    val drillType: String? = null,
    val drillId: UUID? = null,
)

data class WarehouseCommandCenterQueue(
    val items: List<WarehouseCommandCenterItem>,
    val limited: Boolean,
)

data class WarehouseCommandCenterSnapshot(
    val warehouseId: UUID,
    val organizationId: UUID,
    val warehouseCode: String,
    val warehouseName: String,
    val asOf: Instant,
    val inventory: WarehouseInventoryOverview,
    val dueReturns: WarehouseCommandCenterQueue,
    val activeReservations: WarehouseCommandCenterQueue,
    val assetExceptions: WarehouseCommandCenterQueue,
    val receivingQueue: WarehouseCommandCenterQueue,
    val stocktakeQueue: WarehouseCommandCenterQueue,
    val importExceptions: WarehouseCommandCenterQueue,
    val recentMovements: WarehouseCommandCenterQueue,
    val projectResourceReadiness: WarehouseProjectResourceReadinessQueue =
        WarehouseProjectResourceReadinessQueue(false, emptyList(), false),
)

interface WarehouseCommandCenterAccessPort {
    fun requireWarehouse(actorUserId: UUID, warehouseId: UUID): WarehouseSnapshot
    fun canReadImportExceptions(actorUserId: UUID, organizationId: UUID): Boolean
}

@Component
class DefaultWarehouseCommandCenterAccess(
    private val warehouseRead: WarehouseReadService,
    private val authorization: WarehouseAuthorizationPort,
) : WarehouseCommandCenterAccessPort {
    override fun requireWarehouse(actorUserId: UUID, warehouseId: UUID): WarehouseSnapshot =
        warehouseRead.warehouse(actorUserId, warehouseId)

    override fun canReadImportExceptions(actorUserId: UUID, organizationId: UUID): Boolean =
        authorization.canManageImport(actorUserId, organizationId)
}

interface WarehouseCommandCenterReadPort {
    fun snapshot(warehouse: WarehouseSnapshot, at: Instant, includeImportExceptions: Boolean): WarehouseCommandCenterSnapshot
}

@Component
class JdbcWarehouseCommandCenterReadPort(private val jdbc: JdbcTemplate) : WarehouseCommandCenterReadPort {
    override fun snapshot(
        warehouse: WarehouseSnapshot,
        at: Instant,
        includeImportExceptions: Boolean,
    ): WarehouseCommandCenterSnapshot {
        val atOffset = at.atOffset(ZoneOffset.UTC)
        val inventory = jdbc.query(
            """SELECT count(*) AS balance_lines, count(DISTINCT sb.stock_item_id) AS stock_items,
                      count(*) FILTER (WHERE sb.on_hand_qty = 0) AS zero_on_hand,
                      count(*) FILTER (
                          WHERE sb.on_hand_qty - sb.reserved_qty - sb.damaged_qty - sb.quarantine_qty = 0
                      ) AS zero_available
               FROM stock_balance sb
               JOIN storage_location l ON l.id = sb.storage_location_id
               WHERE l.warehouse_id = ?""".trimIndent(),
            { rs, _ ->
                WarehouseInventoryOverview(
                    rs.getInt("balance_lines"),
                    rs.getInt("stock_items"),
                    rs.getInt("zero_on_hand"),
                    rs.getInt("zero_available"),
                    false,
                    "LOW_STOCK_THRESHOLD_NOT_CONFIGURED",
                )
            },
            warehouse.warehouseId,
        ).single()

        val dueReturns = page(jdbc.query(
            """SELECT 'ASSET_MOVEMENT' AS source_type, c.source_movement_id AS source_id,
                      'ASSET' AS drill_type, a.id AS drill_id,
                      a.asset_code AS label,
                      CASE WHEN c.expected_return_at < ? THEN 'OVERDUE' ELSE 'EXPECTED_RETURN' END AS state,
                      CASE WHEN c.expected_return_at < ? THEN 'RETURN_OVERDUE' ELSE 'RETURN_EXPECTED' END AS reason_codes,
                      NULL::timestamptz AS occurred_at, c.expected_return_at AS due_at,
                      c.current_project_id AS project_id, c.current_site_id AS site_id,
                      c.current_work_order_id AS work_order_id
               FROM asset_custody_projection c
               JOIN asset a ON a.id = c.asset_id
               WHERE a.warehouse_id = ? AND c.expected_return_at IS NOT NULL
               ORDER BY c.expected_return_at, a.asset_code, a.id LIMIT 51""".trimIndent(),
            ::item, atOffset, atOffset, warehouse.warehouseId,
        ))

        val reservations = page(jdbc.query(
            """SELECT 'ASSET_RESERVATION' AS source_type, r.id AS source_id,
                      'ASSET' AS drill_type, r.asset_id AS drill_id, a.asset_code AS label,
                      r.state, 'ACTIVE_RESERVATION' AS reason_codes,
                      r.recorded_at AS occurred_at, r.end_at AS due_at,
                      r.project_id, r.site_id, r.work_order_id
               FROM asset_reservation r
               JOIN asset a ON a.id = r.asset_id
               WHERE a.warehouse_id = ? AND r.state = 'ACTIVE'
                 AND (r.start_at IS NULL OR r.start_at <= ?)
                 AND (r.end_at IS NULL OR r.end_at > ?)
               UNION ALL
               SELECT 'STOCK_RESERVATION', r.id, 'STOCK_ITEM', r.stock_item_id, i.item_code, r.state, 'ACTIVE_RESERVATION',
                      r.recorded_at, r.end_at, r.project_id, r.site_id, r.work_order_id
               FROM stock_reservation r
               JOIN stock_item i ON i.id = r.stock_item_id
               JOIN storage_location l ON l.id = r.storage_location_id
               WHERE l.warehouse_id = ? AND r.state = 'ACTIVE'
                 AND (r.start_at IS NULL OR r.start_at <= ?)
                 AND (r.end_at IS NULL OR r.end_at > ?)
               ORDER BY due_at NULLS LAST, label, source_id LIMIT 51""".trimIndent(),
            ::item,
            warehouse.warehouseId, atOffset, atOffset,
            warehouse.warehouseId, atOffset, atOffset,
        ))

        val assetExceptions = page(jdbc.query(
            """SELECT reasons.source_type, reasons.source_id, 'ASSET' AS drill_type, a.id AS drill_id,
                      a.asset_code AS label,
                      reasons.state, reasons.code AS reason_codes,
                      NULL::timestamptz AS occurred_at, reasons.due_at,
                      NULL::uuid AS project_id, NULL::uuid AS site_id, NULL::uuid AS work_order_id
               FROM asset a
               CROSS JOIN LATERAL asset_availability_reasons(a.id, ?) reasons
               WHERE a.warehouse_id = ?
                 AND reasons.state IN (
                     'BLOCKED_CONDITION','BLOCKED_INCIDENT','BLOCKED_INSPECTION',
                     'BLOCKED_CALIBRATION','BLOCKED_MAINTENANCE'
                 )
               ORDER BY reasons.rank, a.asset_code, reasons.source_id LIMIT 51""".trimIndent(),
            ::item, atOffset, warehouse.warehouseId,
        ))

        val receiving = page(jdbc.query(
            """SELECT 'RECEIVING' AS source_type, r.id AS source_id,
                      'RECEIVING' AS drill_type, r.id AS drill_id,
                      r.source_type || coalesce(' · ' || r.source_ref, '') AS label,
                      r.state, 'RECEIVING_' || r.state AS reason_codes,
                      r.updated_at AS occurred_at, NULL::timestamptz AS due_at,
                      NULL::uuid AS project_id, NULL::uuid AS site_id, NULL::uuid AS work_order_id
               FROM receiving_record r
               JOIN storage_location l ON l.id = r.destination_storage_location_id
               WHERE l.warehouse_id = ?
                 AND r.state IN ('DRAFT','IN_PROGRESS','RECEIVED_WITH_DISCREPANCY')
               ORDER BY r.updated_at DESC, r.id LIMIT 51""".trimIndent(),
            ::item, warehouse.warehouseId,
        ))

        val stocktakes = page(jdbc.query(
            """SELECT 'STOCKTAKE_SESSION' AS source_type, s.id AS source_id,
                      'STOCKTAKE' AS drill_type, s.id AS drill_id,
                      'Stocktake ' || left(s.id::text, 8) AS label, s.state,
                      'STOCKTAKE_' || s.state AS reason_codes,
                      s.updated_at AS occurred_at, NULL::timestamptz AS due_at,
                      NULL::uuid AS project_id, NULL::uuid AS site_id, NULL::uuid AS work_order_id
               FROM stocktake_session s
               WHERE s.warehouse_id = ? AND s.state NOT IN ('CLOSED','CANCELLED')
               UNION ALL
               SELECT 'INVENTORY_ADJUSTMENT_REQUEST', a.id, 'STOCKTAKE', s.id,
                      i.item_code || ' · adjustment', coalesce(a.approval_status, 'PENDING'),
                      CASE WHEN a.approval_request_id IS NULL THEN 'APPROVAL_REFERENCE_MISSING'
                           ELSE 'ADJUSTMENT_' || coalesce(a.approval_status, 'PENDING') END,
                      a.requested_at, NULL::timestamptz, NULL::uuid, NULL::uuid, NULL::uuid
               FROM inventory_adjustment_request a
               JOIN stocktake_session s ON s.id = a.stocktake_session_id
               JOIN stock_item i ON i.id = a.stock_item_id
               WHERE s.warehouse_id = ? AND a.applied_movement_id IS NULL
               ORDER BY occurred_at DESC NULLS LAST, source_id LIMIT 51""".trimIndent(),
            ::item, warehouse.warehouseId, warehouse.warehouseId,
        ))

        val imports = if (includeImportExceptions) page(jdbc.query(
            """SELECT 'INVENTORY_IMPORT_ROW' AS source_type, r.id AS source_id,
                      NULL::text AS drill_type, NULL::uuid AS drill_id,
                      coalesce(r.normalized_part_number, r.normalized_description, 'Import row ' || r.source_row_number) AS label,
                      r.review_state AS state, array_to_string(r.issue_codes, ',') AS reason_codes,
                      r.updated_at AS occurred_at, NULL::timestamptz AS due_at,
                      NULL::uuid AS project_id, NULL::uuid AS site_id, NULL::uuid AS work_order_id
               FROM inventory_import_row r
               WHERE r.organization_id = ?
                 AND r.review_state IN ('PENDING','NEEDS_REVIEW')
                 AND cardinality(r.issue_codes) > 0
               ORDER BY r.updated_at DESC, r.id LIMIT 51""".trimIndent(),
            ::item, warehouse.organizationId,
        )) else WarehouseCommandCenterQueue(emptyList(), false)

        val movements = page(jdbc.query(
            """SELECT * FROM (
                   SELECT 'STOCK_MOVEMENT' AS source_type, m.id AS source_id,
                          'STOCK_ITEM' AS drill_type, m.stock_item_id AS drill_id, i.item_code AS label,
                          m.movement_type AS state, m.movement_type AS reason_codes,
                          m.occurred_at, NULL::timestamptz AS due_at,
                          m.project_id, m.site_id, m.work_order_id
                   FROM stock_movement m
                   JOIN stock_item i ON i.id = m.stock_item_id
                   LEFT JOIN storage_location f ON f.id = m.from_storage_location_id
                   LEFT JOIN storage_location t ON t.id = m.to_storage_location_id
                   WHERE f.warehouse_id = ? OR t.warehouse_id = ?
                   UNION ALL
                   SELECT 'ASSET_MOVEMENT', m.id, 'ASSET', m.asset_id, a.asset_code, m.movement_type, m.movement_type,
                          m.occurred_at, m.expected_return_at, m.project_id, m.site_id, m.work_order_id
                   FROM asset_movement m
                   JOIN asset a ON a.id = m.asset_id
                   WHERE a.warehouse_id = ?
               ) movement_union
               ORDER BY occurred_at DESC, source_id LIMIT 51""".trimIndent(),
            ::item, warehouse.warehouseId, warehouse.warehouseId, warehouse.warehouseId,
        ))

        return WarehouseCommandCenterSnapshot(
            warehouse.warehouseId, warehouse.organizationId, warehouse.code, warehouse.name, at,
            inventory, dueReturns, reservations, assetExceptions, receiving, stocktakes, imports, movements,
        )
    }

    private fun page(items: List<WarehouseCommandCenterItem>) =
        WarehouseCommandCenterQueue(items.take(50), items.size > 50)

    private fun item(rs: ResultSet, ignored: Int) = WarehouseCommandCenterItem(
        sourceType = rs.getString("source_type"),
        sourceId = rs.getObject("source_id", UUID::class.java),
        label = rs.getString("label"),
        state = rs.getString("state"),
        reasonCodes = rs.getString("reason_codes").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
        occurredAt = rs.instant("occurred_at"),
        dueAt = rs.instant("due_at"),
        projectId = rs.getObject("project_id", UUID::class.java),
        siteId = rs.getObject("site_id", UUID::class.java),
        workOrderId = rs.getObject("work_order_id", UUID::class.java),
        drillType = rs.getString("drill_type"),
        drillId = rs.getObject("drill_id", UUID::class.java),
    )

    private fun ResultSet.instant(name: String): Instant? =
        getObject(name, OffsetDateTime::class.java)?.toInstant()
}

@Component
class WarehouseCommandCenterService(
    private val access: WarehouseCommandCenterAccessPort,
    private val read: WarehouseCommandCenterReadPort,
    private val projectResources: WarehouseProjectResourceReadinessSource,
    private val clock: Clock,
) {
    fun snapshot(actorUserId: UUID, warehouseId: UUID): WarehouseCommandCenterSnapshot {
        val warehouse = access.requireWarehouse(actorUserId, warehouseId)
        val at = clock.instant()
        return read.snapshot(
            warehouse,
            at,
            access.canReadImportExceptions(actorUserId, warehouse.organizationId),
        ).copy(
            projectResourceReadiness = projectResources.queue(actorUserId, warehouse, at),
        )
    }
}

@RestController
@RequestMapping("/v1/warehouses")
class WarehouseCommandCenterController(private val service: WarehouseCommandCenterService) {
    @GetMapping("/{warehouseId}/command-center")
    fun commandCenter(request: HttpServletRequest, @PathVariable warehouseId: UUID): WarehouseCommandCenterSnapshot =
        service.snapshot(request.stockActor(), warehouseId)
}
