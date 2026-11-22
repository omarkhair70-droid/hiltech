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
                )
            }, requirementId,
        ).singleOrNull()

    fun appendTransition(record: PurchaseRequirementTransitionRecord) {
        check(jdbc.update(
            """INSERT INTO procurement_requirement_transition (
                id, operation_id, requirement_id, organization_id, source_version,
                result_version, transition_type, from_state, to_state, reason,
                actor_user_id, occurred_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            record.id, record.operationId, record.requirementId, record.organizationId,
            record.sourceVersion, record.resultVersion, record.type,
            record.fromState, record.toState, record.reason, record.actor,
            record.at.atOffset(ZoneOffset.UTC), record.correlationId,
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
        WHERE p.organization_id = ?
          AND p.work_order_id = ?
          AND p.stock_item_id = ?
          AND p.id <> ?
          AND p.lifecycle_state = 'DRAFT'
          AND NOT EXISTS (
              SELECT 1 FROM procurement_requirement_transition t
              WHERE t.requirement_id = p.id AND t.transition_type = 'WITHDRAW'
          )
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
