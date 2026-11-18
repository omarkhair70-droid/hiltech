package com.hiltech.server.warehouse

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class AssetFulfillmentPersistence(private val jdbc: JdbcTemplate) {
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
