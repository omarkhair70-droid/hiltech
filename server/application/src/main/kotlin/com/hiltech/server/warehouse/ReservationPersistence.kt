package com.hiltech.server.warehouse

import com.hiltech.server.work.WorkOrderSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class ResourceKind(val table: String, val idColumn: String) {
    ASSET("asset_reservation", "asset_id"), STOCK("stock_reservation", "stock_item_id"),
}

data class WorkResourceDemand(
    val requirementId: UUID, val requirementVersion: Long, val key: String, val family: String, val type: String,
    val assetTypeId: UUID?, val stockItemId: UUID?, val quantity: String?, val unitCode: String?, val version: Long?,
)
data class ResourceCandidate(
    val kind: ResourceKind, val id: UUID, val code: String, val locationId: UUID?, val sourceVersion: Long,
    val asOf: String, val availableQuantity: String?, val unitCode: String?, val reasonCodes: List<String>,
)
data class WorkResourceCandidates(
    val workOrderId: UUID, val workVersion: Long, val canRequest: Boolean, val requirements: List<ResourceRequirementCandidates>,
)
data class ResourceRequirementCandidates(val demand: WorkResourceDemand, val reason: String, val candidates: List<ResourceCandidate>)
data class ReservationView(
    val kind: ResourceKind, val id: UUID, val resourceId: UUID, val workOrderId: UUID, val requirementId: UUID,
    val projectId: UUID, val siteId: UUID, val storageLocationId: UUID?, val quantity: String?, val unitCode: String?,
    val state: String, val version: Long, val expiresAt: String, val requestedBy: UUID, val endReason: String?,
)
data class StockCapacity(val item: UUID, val location: UUID, val available: BigDecimal, val version: Long, val reserved: BigDecimal)

@Repository
class ReservationPersistence(private val jdbc: JdbcTemplate) {
    fun demands(work: UUID): List<WorkResourceDemand> = jdbc.query(
        """
        SELECT i.id, i.version AS requirement_version, i.requirement_key, i.requirement_family, i.requirement_type_code,
            d.asset_type_id, d.stock_item_id, d.quantity, d.unit_code, d.version
        FROM work_requirement_instance i LEFT JOIN work_resource_request d ON d.requirement_id = i.id
        WHERE i.work_order_id = ? AND NOT i.waived AND (i.requirement_family IN ('ASSET','MATERIAL')
            OR i.requirement_type_code IN ('ASSET','ASSET_TOOL','TOOL','MATERIAL'))
        ORDER BY i.requirement_key, i.id
        """.trimIndent(), { rs, _ ->
            WorkResourceDemand(rs.uuid("id"), rs.getLong("requirement_version"), rs.getString("requirement_key"),
                rs.getString("requirement_family"), rs.getString("requirement_type_code"), rs.optUuid("asset_type_id"),
                rs.optUuid("stock_item_id"), rs.getBigDecimal("quantity")?.toPlainString(), rs.getString("unit_code"),
                rs.getObject("version", Long::class.javaObjectType))
        }, work,
    )

    fun assetTypeExists(org: UUID, type: UUID): Boolean = jdbc.queryForObject(
        """SELECT EXISTS (SELECT 1 FROM asset_type_definition t JOIN config_revision c ON c.id = t.config_revision_id
            WHERE t.config_revision_id = ? AND (c.scope_type = 'SYSTEM' OR c.scope_organization_id = ?))""",
        Boolean::class.java, type, org,
    ) == true

    fun request(work: WorkOrderSnapshot, demand: UUID, request: RequestWorkResource, quantity: BigDecimal, actor: UUID, at: Instant) {
        jdbc.update("""
            INSERT INTO work_resource_request (requirement_id, work_order_id, organization_id, asset_type_id, stock_item_id,
                quantity, unit_code, requested_by, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), demand, work.workOrderId, work.organizationId, request.assetTypeId, request.stockItemId,
            quantity, request.unitCode, actor, at.utc())
    }

    fun assets(org: UUID, type: UUID): List<Triple<UUID, String, UUID?>> = jdbc.query(
        """SELECT a.id, a.asset_code, c.current_storage_location_id FROM asset a
            LEFT JOIN asset_custody_projection c ON c.asset_id = a.id
            WHERE a.organization_id = ? AND a.asset_type_definition_id = ? ORDER BY a.asset_code LIMIT 100""",
        { rs, _ -> Triple(rs.uuid("id"), rs.getString("asset_code"), rs.optUuid("current_storage_location_id")) }, org, type,
    )

    fun balances(item: UUID, at: Instant, location: UUID? = null): List<StockCapacity> = jdbc.query(
        """
        SELECT b.stock_item_id, b.storage_location_id, b.version, b.on_hand_qty - b.damaged_qty - b.quarantine_qty - r.qty AS free,
            r.qty FROM stock_balance b CROSS JOIN LATERAL (
                SELECT current_reserved_stock(b.stock_item_id, b.storage_location_id, ?) AS qty
            ) r WHERE b.stock_item_id = ? AND (?::uuid IS NULL OR b.storage_location_id = ?)
        ORDER BY b.storage_location_id
        """.trimIndent(), { rs, _ ->
            StockCapacity(rs.uuid("stock_item_id"), rs.uuid("storage_location_id"), rs.getBigDecimal("free"),
                rs.getLong("version"), rs.getBigDecimal("qty"))
        }, at.utc(), item, location, location,
    )

    fun reserve(
        kind: ResourceKind, id: UUID, resource: UUID, work: WorkOrderSnapshot, requirement: UUID, actor: UUID,
        at: Instant, expires: Instant, location: UUID? = null, quantity: BigDecimal? = null, unit: String? = null,
    ) {
        val columns = if (kind == ResourceKind.STOCK) ", storage_location_id, quantity, unit_code" else ""
        val values = if (kind == ResourceKind.STOCK) ", ?, ?, ?" else ""
        val extra = if (kind == ResourceKind.STOCK) arrayOf<Any?>(location, quantity, unit) else emptyArray<Any?>()
        jdbc.update("""
            INSERT INTO ${kind.table} (id, ${kind.idColumn}, organization_id, project_id, site_id, work_order_id,
                work_requirement_instance_id, requested_by, recorded_at, start_at, end_at, state$columns)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE'$values)
        """.trimIndent(), id, resource, work.organizationId, work.projectId, work.siteId, work.workOrderId,
            requirement, actor, at.utc(), at.utc(), expires.utc(), *extra)
    }

    fun reservation(kind: ResourceKind, id: UUID, at: Instant): ReservationView? =
        read(kind, at, "r.id = ?", id).singleOrNull()

    fun workReservations(work: UUID, at: Instant): List<ReservationView> =
        ResourceKind.entries.flatMap { read(it, at, "r.work_order_id = ?", work) }

    fun demandReservations(requirement: UUID, at: Instant): List<ReservationView> =
        ResourceKind.entries.flatMap { read(it, at, "r.work_requirement_instance_id = ? AND r.state = 'ACTIVE' AND r.end_at > ?", requirement, at.utc()) }

    fun warehouseReservations(warehouse: UUID, at: Instant): List<ReservationView> =
        read(ResourceKind.ASSET, at, "EXISTS (SELECT 1 FROM asset a WHERE a.id = r.asset_id AND a.warehouse_id = ?)", warehouse,
            order = "(r.state = 'ACTIVE') DESC, r.recorded_at DESC, r.id") +
            read(ResourceKind.STOCK, at, "EXISTS (SELECT 1 FROM storage_location s WHERE s.id = r.storage_location_id AND s.warehouse_id = ?)", warehouse,
                order = "(r.state = 'ACTIVE') DESC, r.recorded_at DESC, r.id")

    fun affectedWork(kind: ResourceKind, resource: UUID, at: Instant): List<Pair<UUID, UUID>> = jdbc.query(
        """SELECT DISTINCT work_order_id, requested_by FROM ${kind.table} WHERE ${kind.idColumn} = ?
            AND organization_id IS NOT NULL AND state = 'ACTIVE' AND end_at > ?""",
        { rs, _ -> rs.uuid("work_order_id") to rs.uuid("requested_by") }, resource, at.utc(),
    )

    fun openWork(org: UUID): List<UUID> = jdbc.query(
        """SELECT DISTINCT w.id FROM work_order w JOIN work_resource_request d ON d.work_order_id = w.id
            WHERE w.organization_id = ? AND w.lifecycle_state IN ('PLANNED','ASSIGNED','IN_PROGRESS','BLOCKED')
            ORDER BY w.id LIMIT 100""", { rs, _ -> rs.uuid("id") }, org,
    )

    fun expired(at: Instant): List<ReservationView> = ResourceKind.entries.flatMap {
        read(it, at, "r.state = 'ACTIVE' AND r.end_at <= ?", at.utc(), order = "r.end_at, r.id")
    }

    fun advanceAsset(id: UUID) {
        jdbc.update("UPDATE asset SET version = version + 1 WHERE id = ?", id)
    }

    fun end(reservation: ReservationView, state: String, actor: UUID?, at: Instant, reason: String): Boolean =
        jdbc.update("""UPDATE ${reservation.kind.table} SET state = ?, ended_by = ?, ended_at = ?, end_reason = ?, version = version + 1
            WHERE id = ? AND state = 'ACTIVE' AND version = ?""",
            state, actor, at.utc(), reason, reservation.id, reservation.version) == 1

    private fun read(kind: ResourceKind, at: Instant, filter: String, vararg args: Any?, order: String = "r.recorded_at DESC, r.id"): List<ReservationView> {
        val stock = if (kind == ResourceKind.STOCK) "r.storage_location_id, r.quantity, r.unit_code"
            else "NULL::uuid AS storage_location_id, NULL::numeric AS quantity, NULL::varchar AS unit_code"
        return jdbc.query("""
            SELECT r.*, r.${kind.idColumn} AS resource_id, $stock,
                CASE WHEN r.state = 'ACTIVE' AND r.end_at <= ? THEN 'EXPIRED' ELSE r.state END AS effective_state
            FROM ${kind.table} r WHERE r.organization_id IS NOT NULL AND $filter ORDER BY $order LIMIT 100
        """.trimIndent(), { rs, _ ->
            ReservationView(kind, rs.uuid("id"), rs.uuid("resource_id"), rs.uuid("work_order_id"), rs.uuid("work_requirement_instance_id"),
                rs.uuid("project_id"), rs.uuid("site_id"), rs.optUuid("storage_location_id"), rs.getBigDecimal("quantity")?.toPlainString(),
                rs.getString("unit_code"), rs.getString("effective_state"), rs.getLong("version"),
                rs.getObject("end_at", OffsetDateTime::class.java).toInstant().toString(), rs.uuid("requested_by"), rs.getString("end_reason"))
        }, at.utc(), *args)
    }

    private fun ResultSet.uuid(column: String) = requireNotNull(optUuid(column))
    private fun ResultSet.optUuid(column: String): UUID? = getObject(column, UUID::class.java)
    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
