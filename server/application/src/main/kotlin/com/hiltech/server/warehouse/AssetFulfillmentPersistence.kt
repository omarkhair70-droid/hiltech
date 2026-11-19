package com.hiltech.server.warehouse

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class FulfillmentEmployeeChoice(val id: UUID, val code: String, val name: String)

data class AssetDueReturnRow(
    val assetId: UUID, val assetCode: String, val assetName: String?, val employeeCode: String,
    val employeeName: String, val workOrderId: UUID?, val sourceMovementId: UUID,
    val expectedReturnAt: String, val overdue: Boolean,
)
data class AssetDueReturnQueue(
    val items: List<AssetDueReturnRow>, val limited: Boolean, val asOf: String,
    val nextExpectedReturnAt: String? = null, val nextAssetCode: String? = null, val nextAssetId: UUID? = null,
)

@Repository
class AssetFulfillmentPersistence(private val jdbc: JdbcTemplate) {
    fun recipients(organization: UUID, search: String): List<FulfillmentEmployeeChoice> = jdbc.query("""
        SELECT e.id, e.employee_code, p.display_name
        FROM employee e JOIN person p ON p.id = e.person_id
        WHERE e.organization_id = ? AND e.state = 'ACTIVE'
          AND (strpos(lower(e.employee_code), lower(?)) > 0 OR strpos(lower(p.display_name), lower(?)) > 0)
        ORDER BY e.employee_code, e.id LIMIT 100
    """.trimIndent(), { rs, _ -> FulfillmentEmployeeChoice(rs.getObject("id", UUID::class.java),
        rs.getString("employee_code"), rs.getString("display_name")) }, organization, search, search)

    /** Only current accountable employee custody with an explicitly recorded due time. */
    fun dueReturns(org: UUID, warehouse: UUID, at: Instant, afterDue: Instant? = null,
        afterCode: String? = null, afterId: UUID? = null): AssetDueReturnQueue {
        val cursor = afterDue != null && afterCode != null && afterId != null
        val cursorClause = if (cursor) """
              AND (c.expected_return_at > ? OR
                (c.expected_return_at = ? AND (a.asset_code > ? OR (a.asset_code = ? AND a.id > ?))))
        """.trimIndent() else ""
        val sql = """
            SELECT a.id, a.asset_code, a.display_name AS asset_name, e.employee_code,
                p.display_name AS employee_name, c.current_work_order_id, c.source_movement_id, c.expected_return_at
            FROM asset_custody_projection c
            JOIN asset a ON a.id = c.asset_id
            JOIN employee e ON e.id = c.current_custodian_target_id AND e.organization_id = a.organization_id
            JOIN person p ON p.id = e.person_id
            WHERE a.organization_id = ? AND a.warehouse_id = ?
              AND c.current_custodian_target_type = 'EMPLOYEE' AND c.expected_return_at IS NOT NULL
            $cursorClause
            ORDER BY c.expected_return_at, a.asset_code, a.id LIMIT 101
        """.trimIndent()
        val mapper = { r: java.sql.ResultSet, _: Int ->
            val due = r.getObject("expected_return_at", OffsetDateTime::class.java).toInstant()
            AssetDueReturnRow(r.getObject("id", UUID::class.java), r.getString("asset_code"), r.getString("asset_name"),
                r.getString("employee_code"), r.getString("employee_name"), r.getObject("current_work_order_id", UUID::class.java),
                r.getObject("source_movement_id", UUID::class.java), due.toString(), due <= at)
        }
        val rows = if (cursor) {
            val due = requireNotNull(afterDue).atOffset(ZoneOffset.UTC)
            jdbc.query(sql, mapper, org, warehouse, due, due, afterCode, afterCode, afterId)
        } else jdbc.query(sql, mapper, org, warehouse)
        val visible = rows.take(100)
        val last = visible.lastOrNull().takeIf { rows.size > 100 }
        return AssetDueReturnQueue(visible, rows.size > 100, at.toString(),
            last?.expectedReturnAt, last?.assetCode, last?.assetId)
    }

    fun id(operation: UUID): UUID = UUID.nameUUIDFromBytes("hiltech:asset-physical:$operation".toByteArray(Charsets.UTF_8))

    internal fun post(asset: AssetIdentityView, context: AssetCommandContext, prior: AssetCustodyView,
        target: AssetDestination, kind: String, condition: AssetCondition, reservation: UUID?, due: Instant?, now: Instant): UUID {
        val movement = id(context.operationId)
        val at = now.atOffset(ZoneOffset.UTC)
        jdbc.update("""
            INSERT INTO asset_movement (id, asset_id, organization_id, movement_type,
                from_storage_location_id, to_storage_location_id, from_custodian_target_type, from_custodian_target_id,
                to_custodian_target_type, to_custodian_target_id, project_id, site_id, work_order_id,
                occurred_at, recorded_at, recorded_by, source_operation_id, condition_at_transfer,
                expected_return_at, correlation_id, fulfilled_reservation_id, prior_custody_movement_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), movement, asset.id, asset.organizationId, kind, prior.storageLocationId, target.locationId,
            prior.employeeId?.let { "EMPLOYEE" }, prior.employeeId, target.employeeId?.let { "EMPLOYEE" }, target.employeeId,
            target.projectId, target.siteId, target.workOrderId, at, at, context.actor, context.operationId, condition.name,
            due?.atOffset(ZoneOffset.UTC), context.correlationId, reservation, prior.sourceMovementId)
        return movement
    }

    fun inspect(asset: UUID, context: AssetCommandContext, returned: UUID, condition: AssetCondition,
        notes: String?, damageIncident: UUID?, now: Instant): UUID {
        val id = id(context.operationId)
        jdbc.update("""
            INSERT INTO asset_return_inspection (id, asset_id, return_movement_id, inspected_by, inspected_at,
                condition_observed, notes, damage_incident_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), id, asset, returned, context.actor, now.atOffset(ZoneOffset.UTC), condition.name, notes, damageIncident)
        return id
    }
}
