package com.hiltech.server.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class PurchaseRequirementDraftRecord(
    val id: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val workOrderId: UUID?,
    val sourceWorkVersion: Long?,
    val stockItemId: UUID,
    val stockItemVersion: Long,
    val requestedQuantity: BigDecimal,
    val unitCode: String,
    val justification: String,
    val assessmentCode: String,
    val visibleFreeQuantity: BigDecimal?,
    val assessedAt: Instant,
    val requestedBy: UUID,
    val createdAt: Instant,
    val correlationId: String,
    val version: Long = 1,
    val sourceType: String = "WORK_ORDER",
    val warehouseStorageLocationId: UUID? = null,
    val warehouseSourceVersion: Long? = null,
    val assetId: UUID? = null,
    val assetSourceVersion: Long? = null,
    val assetIncidentId: UUID? = null,
    val assetMaintenanceId: UUID? = null,
    val assetIssueVersion: Long? = null,
    val assetDeliveryLocationId: UUID? = null,
    val assetDeliveryLocationVersion: Long? = null,
)

data class PurchaseRequirementRevision(
    val quantity: BigDecimal,
    val justification: String,
    val workVersion: Long,
    val itemVersion: Long,
    val unitCode: String,
    val assessmentCode: String,
    val freeQuantity: BigDecimal?,
    val assessedAt: Instant,
)

data class PurchaseRequirementTransitionRecord(
    val id: UUID,
    val operationId: UUID,
    val requirementId: UUID,
    val organizationId: UUID,
    val sourceVersion: Long,
    val resultVersion: Long,
    val type: String,
    val fromState: String,
    val toState: String,
    val reason: String?,
    val actor: UUID,
    val at: Instant,
    val correlationId: String,
    val revised: PurchaseRequirementRevision? = null,
)

@Repository
class PurchaseRequirementDraftPersistence(private val jdbc: JdbcTemplate) {
    fun insert(draft: PurchaseRequirementDraftRecord) {
        check(jdbc.update(
            """
            INSERT INTO procurement_purchase_requirement (
                id, operation_id, organization_id, work_order_id, source_work_version,
                stock_item_id, stock_item_version, requested_quantity, unit_code,
                justification, lifecycle_state, assessment_code, visible_free_quantity,
                assessment_at, created_by_user_id, created_at, correlation_id, version,
                source_type, warehouse_storage_location_id, warehouse_source_version,
                asset_id, asset_source_version, asset_incident_id,
                asset_maintenance_id, asset_issue_version,
                asset_delivery_location_id, asset_delivery_location_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, 1,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            draft.id, draft.operationId, draft.organizationId, draft.workOrderId,
            draft.sourceWorkVersion, draft.stockItemId, draft.stockItemVersion,
            draft.requestedQuantity, draft.unitCode, draft.justification,
            draft.assessmentCode, draft.visibleFreeQuantity,
            draft.assessedAt.atOffset(ZoneOffset.UTC), draft.requestedBy,
            draft.createdAt.atOffset(ZoneOffset.UTC), draft.correlationId,
            draft.sourceType, draft.warehouseStorageLocationId, draft.warehouseSourceVersion,
            draft.assetId, draft.assetSourceVersion, draft.assetIncidentId,
            draft.assetMaintenanceId, draft.assetIssueVersion,
            draft.assetDeliveryLocationId, draft.assetDeliveryLocationVersion,
        ) == 1)
    }

    /** Serialize all transitions on the immutable root without rewriting that root. */
    fun lock(id: UUID): Boolean = jdbc.query(
        "SELECT id FROM procurement_purchase_requirement WHERE id = ? FOR UPDATE",
        { rs, _ -> rs.getObject("id", UUID::class.java) }, id,
    ).isNotEmpty()

    fun latestTransition(requirementId: UUID): PurchaseRequirementTransitionRecord? =
        jdbc.query(
            """SELECT * FROM procurement_requirement_transition WHERE requirement_id = ?
               ORDER BY result_version DESC LIMIT 1""",
            { rs, _ ->
                PurchaseRequirementTransitionRecord(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("operation_id", UUID::class.java),
                    rs.getObject("requirement_id", UUID::class.java),
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getLong("source_version"),
                    rs.getLong("result_version"),
                    rs.getString("transition_type"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("reason"),
                    rs.getObject("actor_user_id", UUID::class.java),
                    rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                    rs.getString("correlation_id"),
                    if (rs.getBigDecimal("spec_quantity") == null) null else PurchaseRequirementRevision(
                        rs.getBigDecimal("spec_quantity"),
                        rs.getString("spec_justification"),
                        rs.getLong("spec_work_version"),
                        rs.getLong("spec_item_version"),
                        rs.getString("spec_unit_code"),
                        rs.getString("spec_assessment_code"),
                        rs.getBigDecimal("spec_visible_free_quantity"),
                        rs.getObject("spec_assessed_at", OffsetDateTime::class.java).toInstant(),
                    ),
                )
            }, requirementId,
        ).singleOrNull()

    fun appendTransition(record: PurchaseRequirementTransitionRecord) {
        check(jdbc.update(
            """INSERT INTO procurement_requirement_transition (
                id, operation_id, requirement_id, organization_id, source_version,
                result_version, transition_type, from_state, to_state, reason,
                actor_user_id, occurred_at, correlation_id,
                spec_quantity, spec_justification, spec_work_version, spec_item_version,
                spec_unit_code, spec_assessment_code, spec_visible_free_quantity, spec_assessed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            record.id, record.operationId, record.requirementId, record.organizationId,
            record.sourceVersion, record.resultVersion, record.type,
            record.fromState, record.toState, record.reason, record.actor,
            record.at.atOffset(ZoneOffset.UTC), record.correlationId,
            record.revised?.quantity, record.revised?.justification,
            record.revised?.workVersion, record.revised?.itemVersion,
            record.revised?.unitCode, record.revised?.assessmentCode,
            record.revised?.freeQuantity, record.revised?.assessedAt?.atOffset(ZoneOffset.UTC),
        ) == 1)
    }

    /**
     * Advisory only: other open DRAFTs for the exact same WorkOrder and item.
     * The caller is responsible for current WorkOrder project authorization.
     * Intentionally return 21 rows to expose truncation at the 20-item UI cap.
     * A withdrawn draft is never an active duplicate.
     */
    fun otherOpenDraftIds(
        organizationId: UUID,
        workOrderId: UUID,
        stockItemId: UUID,
        excludeId: UUID,
    ): List<UUID> = jdbc.query(
        """
        SELECT p.id FROM procurement_purchase_requirement p
        LEFT JOIN LATERAL (
            SELECT t.to_state FROM procurement_requirement_transition t
            WHERE t.requirement_id = p.id
            ORDER BY t.result_version DESC LIMIT 1
        ) latest ON true
        WHERE p.organization_id = ?
          AND p.work_order_id = ?
          AND p.stock_item_id = ?
          AND p.id <> ?
          AND (latest.to_state IS NULL OR latest.to_state IN (
              'DRAFT', 'SUBMITTED', 'NEEDS_INFO', 'READY_FOR_SOURCING'
          ))
        ORDER BY p.created_at DESC, p.id DESC
        LIMIT 21
        """.trimIndent(),
        { row, _ -> row.getObject("id", UUID::class.java) },
        organizationId, workOrderId, stockItemId, excludeId,
    )

    fun byId(id: UUID): PurchaseRequirementDraftRecord? =
        jdbc.query(
            "SELECT * FROM procurement_purchase_requirement WHERE id = ?",
            { row, _ -> row.draft() }, id,
        ).singleOrNull()

    fun forAsset(assetId: UUID): List<PurchaseRequirementDraftRecord> = jdbc.query(
        """
        SELECT * FROM procurement_purchase_requirement
        WHERE source_type='ASSET_MAINTENANCE_OR_REPLACEMENT' AND asset_id=?
        ORDER BY created_at DESC, id DESC LIMIT 100
        """.trimIndent(), { row, _ -> row.draft() }, assetId,
    )

    fun forWarehouseLocation(
        organizationId: UUID,
        storageLocationId: UUID,
    ): List<PurchaseRequirementDraftRecord> = jdbc.query(
        """
        SELECT * FROM procurement_purchase_requirement
        WHERE organization_id=? AND source_type='WAREHOUSE_REPLENISHMENT'
          AND warehouse_storage_location_id=?
        ORDER BY created_at DESC, id DESC LIMIT 100
        """.trimIndent(),
        { row, _ -> row.draft() }, organizationId, storageLocationId,
    )

    fun otherOpenWarehouseDraftIds(
        organizationId: UUID, storageLocationId: UUID, stockItemId: UUID, excludeId: UUID,
    ): List<UUID> = jdbc.query(
        """
        SELECT p.id FROM procurement_purchase_requirement p
        LEFT JOIN LATERAL (
          SELECT t.to_state FROM procurement_requirement_transition t
          WHERE t.requirement_id=p.id ORDER BY t.result_version DESC LIMIT 1
        ) last ON true
        WHERE p.organization_id=? AND p.source_type='WAREHOUSE_REPLENISHMENT'
          AND p.warehouse_storage_location_id=? AND p.stock_item_id=? AND p.id<>?
          AND (last.to_state IS NULL OR last.to_state IN
            ('DRAFT','SUBMITTED','NEEDS_INFO','READY_FOR_SOURCING'))
        ORDER BY p.created_at DESC, p.id DESC LIMIT 21
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        organizationId, storageLocationId, stockItemId, excludeId,
    )

    fun forWork(workOrderId: UUID): List<PurchaseRequirementDraftRecord> =
        jdbc.query(
            """
            SELECT * FROM procurement_purchase_requirement
            WHERE work_order_id = ?
            ORDER BY created_at DESC, id DESC LIMIT 100
            """.trimIndent(),
            { row, _ -> row.draft() }, workOrderId,
        )

    /** Project-scoped technical intake; buyer/finance authority is NOT derived. */
    fun projectIntake(organizationId: UUID, projectId: UUID): List<PurchaseRequirementDraftRecord> =
        jdbc.query(
            """
            SELECT p.* FROM procurement_purchase_requirement p
            JOIN work_order w ON w.id = p.work_order_id AND w.organization_id = p.organization_id
            LEFT JOIN LATERAL (
                SELECT t.to_state FROM procurement_requirement_transition t
                WHERE t.requirement_id = p.id
                ORDER BY t.result_version DESC LIMIT 1
            ) latest ON true
            WHERE p.organization_id = ? AND w.project_id = ?
              AND latest.to_state IN ('SUBMITTED', 'NEEDS_INFO', 'READY_FOR_SOURCING')
            ORDER BY p.created_at DESC, p.id DESC LIMIT 100
            """.trimIndent(),
            { row, _ -> row.draft() },
            organizationId, projectId,
        )

    private fun ResultSet.draft() = PurchaseRequirementDraftRecord(
        id = getObject("id", UUID::class.java),
        operationId = getObject("operation_id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        workOrderId = getObject("work_order_id", UUID::class.java),
        sourceWorkVersion = getLong("source_work_version").takeUnless { wasNull() },
        stockItemId = getObject("stock_item_id", UUID::class.java),
        stockItemVersion = getLong("stock_item_version"),
        requestedQuantity = getBigDecimal("requested_quantity"),
        unitCode = getString("unit_code"),
        justification = getString("justification"),
        assessmentCode = getString("assessment_code"),
        visibleFreeQuantity = getBigDecimal("visible_free_quantity"),
        assessedAt = getObject("assessment_at", OffsetDateTime::class.java).toInstant(),
        requestedBy = getObject("created_by_user_id", UUID::class.java),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        correlationId = getString("correlation_id"),
        version = getLong("version"),
        sourceType = getString("source_type"),
        warehouseStorageLocationId = getObject("warehouse_storage_location_id", UUID::class.java),
        warehouseSourceVersion = getLong("warehouse_source_version").takeUnless { wasNull() },
        assetId = getObject("asset_id", UUID::class.java),
        assetSourceVersion = getLong("asset_source_version").takeUnless { wasNull() },
        assetIncidentId = getObject("asset_incident_id", UUID::class.java),
        assetMaintenanceId = getObject("asset_maintenance_id", UUID::class.java),
        assetIssueVersion = getLong("asset_issue_version").takeUnless { wasNull() },
        assetDeliveryLocationId = getObject("asset_delivery_location_id", UUID::class.java),
        assetDeliveryLocationVersion = getLong("asset_delivery_location_version").takeUnless { wasNull() },
    )
}
