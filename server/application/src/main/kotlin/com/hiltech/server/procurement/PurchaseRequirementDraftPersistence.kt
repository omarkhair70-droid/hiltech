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
    val workOrderId: UUID,
    val sourceWorkVersion: Long,
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
                assessment_at, created_by_user_id, created_at, correlation_id, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            draft.id, draft.operationId, draft.organizationId, draft.workOrderId,
            draft.sourceWorkVersion, draft.stockItemId, draft.stockItemVersion,
            draft.requestedQuantity, draft.unitCode, draft.justification,
            draft.assessmentCode, draft.visibleFreeQuantity,
            draft.assessedAt.atOffset(ZoneOffset.UTC), draft.requestedBy,
            draft.createdAt.atOffset(ZoneOffset.UTC), draft.correlationId,
        ) == 1)
    }

    fun byId(id: UUID): PurchaseRequirementDraftRecord? =
        jdbc.query(
            "SELECT * FROM procurement_purchase_requirement WHERE id = ?",
            { row, _ -> row.draft() }, id,
        ).singleOrNull()

    fun forWork(workOrderId: UUID): List<PurchaseRequirementDraftRecord> =
        jdbc.query(
            """
            SELECT * FROM procurement_purchase_requirement
            WHERE work_order_id = ?
            ORDER BY created_at DESC, id DESC LIMIT 100
            """.trimIndent(),
            { row, _ -> row.draft() }, workOrderId,
        )

    private fun ResultSet.draft() = PurchaseRequirementDraftRecord(
        id = getObject("id", UUID::class.java),
        operationId = getObject("operation_id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        workOrderId = getObject("work_order_id", UUID::class.java),
        sourceWorkVersion = getLong("source_work_version"),
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
    )
}
