package com.hiltech.server.warehouse

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class StockClassification { STOCK_QUANTITY, STOCK_SERIALIZED }
enum class StockMovementType { INITIALIZE_OPENING, TRANSFER }

data class StockItemView(
    val id: UUID, val organizationId: UUID, val itemCode: String, val name: String, val description: String?,
    val brand: String?, val partNumber: String?, val categoryDefinitionId: UUID, val categoryRevision: Int,
    val baseUnitCode: String, val classification: StockClassification, val serialized: Boolean,
    val lotTracked: Boolean, val active: Boolean, val sourceImportRowId: UUID?, val version: Long,
    val openingInitialized: Boolean = false, val verifiedZeroOpening: Boolean = false,
)

data class StockUnitView(val code: String, val name: String)
data class StockCategoryView(val id: UUID, val name: String, val revision: Int)
data class StockBalanceView(val storageLocationId: UUID, val locationCode: String, val onHandQuantity: String, val version: Long)
data class StockMovementView(
    val id: UUID, val operationId: UUID, val type: String, val quantity: String, val unitCode: String,
    val fromStorageLocationId: UUID?, val toStorageLocationId: UUID?, val sourceImportRowId: UUID?,
    val occurredAt: String, val recordedBy: UUID,
)

internal data class StockSourceVerification(
    val rowVersion: Long, val sourceSha256: String, val locationId: UUID, val categoryId: UUID,
    val approverId: UUID, val verifiedBy: UUID, val verifiedAt: Instant, val resolvedIssues: Set<String>,
    val cutoverAt: Instant,
)

@Repository
class StockLedgerPersistence(private val jdbc: JdbcTemplate) {
    fun item(id: UUID, lock: Boolean = false): StockItemView? = jdbc.query(
        "$itemSelect WHERE i.id = ?" + if (lock) " FOR NO KEY UPDATE OF i" else "",
        { row, _ -> row.stockItem() }, id,
    ).singleOrNull()

    fun items(organizationId: UUID, afterCode: String?): List<StockItemView> = jdbc.query(
        """
        $itemSelect WHERE organization_id = ? AND item_code > COALESCE(?::varchar, '')
        ORDER BY item_code LIMIT 100
        """.trimIndent(),
        { row, _ -> row.stockItem() }, organizationId, afterCode,
    )

    fun units(organizationId: UUID): List<StockUnitView> = jdbc.query(
        "SELECT code, name FROM stock_unit WHERE organization_id = ? AND active ORDER BY code",
        { row, _ -> StockUnitView(row.getString("code"), row.getString("name")) }, organizationId,
    )

    fun registerUnit(organizationId: UUID, code: String, name: String): Int = jdbc.update(
        """
        INSERT INTO stock_unit (organization_id, code, name, active) VALUES (?, ?, ?, true)
        ON CONFLICT (organization_id, code) DO UPDATE SET name = EXCLUDED.name, active = true
        WHERE NOT stock_unit.active
        """.trimIndent(), organizationId, code, name,
    )

    fun categories(organizationId: UUID, at: Instant): List<StockCategoryView> = jdbc.query(
        """
        SELECT r.id, r.name, r.revision_number FROM config_revision r
        JOIN stock_item_category_definition d ON d.config_revision_id = r.id
        WHERE r.family = 'stock-categories' AND r.lifecycle_state = 'ACTIVE'
          AND (r.scope_type = 'SYSTEM' OR r.scope_organization_id = ?)
          AND (r.effective_from IS NULL OR r.effective_from <= ?)
          AND (r.effective_to IS NULL OR r.effective_to > ?)
        ORDER BY r.name, r.id
        """.trimIndent(),
        { row, _ -> StockCategoryView(row.uuid("id"), row.getString("name"), row.getInt("revision_number")) },
        organizationId, at.utc(), at.utc(),
    )

    fun insertItem(item: StockItemView) {
        jdbc.update(
            """
            INSERT INTO stock_item (id, organization_id, item_code, name, description, brand, part_number,
                category_definition_id, category_revision, unit_of_measure_code, classification, serialized,
                lot_tracked, active, source_import_row_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            item.id, item.organizationId, item.itemCode, item.name, item.description, item.brand, item.partNumber,
            item.categoryDefinitionId, item.categoryRevision, item.baseUnitCode, item.classification.name,
            item.serialized, item.lotTracked, item.active, item.sourceImportRowId,
        )
    }

    internal fun verification(rowId: UUID, lock: Boolean = false): StockSourceVerification? = jdbc.query(
        "SELECT * FROM stock_import_verification WHERE source_row_id = ?" + if (lock) " FOR SHARE" else "",
        { row, _ ->
            val issues = row.getArray("resolved_issue_codes")
            val resolved = try { (issues.array as Array<*>).filterIsInstance<String>().toSet() } finally { issues.free() }
            StockSourceVerification(
                row.getLong("source_row_version"), row.getString("source_sha256"), row.uuid("storage_location_id"),
                row.uuid("category_definition_id"), row.uuid("cutover_approver_id"), row.uuid("verified_by"),
                row.getObject("verified_at", OffsetDateTime::class.java).toInstant(), resolved,
                row.getObject("cutover_at", OffsetDateTime::class.java).toInstant(),
            )
        }, rowId,
    ).singleOrNull()

    fun approveImport(rowId: UUID, itemId: UUID, actor: UUID, at: Instant, version: Long): Int = jdbc.update(
        """
        UPDATE inventory_import_row SET review_state = 'APPROVED', approved_target_type = 'STOCK_ITEM',
            approved_target_id = ?, reviewed_by_user_id = ?, reviewed_at = ?, updated_at = ?, version = version + 1
        WHERE id = ? AND version = ? AND review_state = 'NEEDS_REVIEW'
        """.trimIndent(), itemId, actor, at.utc(), at.utc(), rowId, version,
    )

    fun balances(itemId: UUID): List<StockBalanceView> = jdbc.query(
        """
        SELECT b.storage_location_id, s.code, b.on_hand_qty, b.version FROM stock_balance b
        JOIN storage_location s ON s.id = b.storage_location_id WHERE b.stock_item_id = ? ORDER BY s.code
        """.trimIndent(),
        { row, _ -> StockBalanceView(row.uuid("storage_location_id"), row.getString("code"),
            row.getBigDecimal("on_hand_qty").toPlainString(), row.getLong("version")) }, itemId,
    )

    fun movements(itemId: UUID, visibleLocations: List<UUID>): List<StockMovementView> {
        if (visibleLocations.isEmpty()) return emptyList()
        val placeholders = visibleLocations.joinToString(",") { "?" }
        return jdbc.query(
            """
            SELECT * FROM stock_movement WHERE stock_item_id = ?
              AND (from_storage_location_id IS NULL OR from_storage_location_id IN ($placeholders))
              AND (to_storage_location_id IS NULL OR to_storage_location_id IN ($placeholders))
            ORDER BY recorded_at DESC, id DESC LIMIT 100
            """.trimIndent(), { row, _ -> row.stockMovement() },
            * (listOf(itemId) + visibleLocations + visibleLocations).toTypedArray(),
        )
    }

    fun movement(operationId: UUID): StockMovementView? = jdbc.query(
        "SELECT * FROM stock_movement WHERE operation_id = ?", { row, _ -> row.stockMovement() }, operationId,
    ).singleOrNull()

    internal fun append(
        item: StockItemView, operationId: UUID, actor: UUID, type: StockMovementType, quantity: BigDecimal,
        from: UUID?, to: UUID, sourceRow: UUID?, occurredAt: Instant, now: Instant, correlationId: String,
    ): UUID {
        val movementId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO stock_movement (id, stock_item_id, organization_id, operation_id, quantity, unit_code,
                movement_type, from_storage_location_id, to_storage_location_id, source_import_row_id,
                occurred_at, recorded_at, recorded_by, correlation_id, reason_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            movementId, item.id, item.organizationId, operationId, quantity, item.baseUnitCode, type.name,
            from, to, sourceRow, occurredAt.utc(), now.utc(), actor, correlationId, type.name,
        )
        return movementId
    }

    private fun ResultSet.stockItem() = StockItemView(
        uuid("id"), uuid("organization_id"), getString("item_code"), getString("name"), getString("description"),
        getString("brand"), getString("part_number"), uuid("category_definition_id"), getInt("category_revision"),
        getString("unit_of_measure_code"), StockClassification.valueOf(getString("classification")),
        getBoolean("serialized"), getBoolean("lot_tracked"), getBoolean("active"),
        optionalUuid("source_import_row_id"), getLong("version"), getBoolean("opening_initialized"), getBoolean("verified_zero_opening"),
    )

    private fun ResultSet.stockMovement() = StockMovementView(
        uuid("id"), uuid("operation_id"), getString("movement_type"), getBigDecimal("quantity").toPlainString(),
        getString("unit_code"), optionalUuid("from_storage_location_id"), optionalUuid("to_storage_location_id"),
        optionalUuid("source_import_row_id"), getObject("occurred_at", OffsetDateTime::class.java).toInstant().toString(),
        uuid("recorded_by"),
    )

    private fun ResultSet.uuid(column: String) = requireNotNull(optionalUuid(column))
    private fun ResultSet.optionalUuid(column: String): UUID? = getObject(column, UUID::class.java)
    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private val itemSelect = """
        SELECT i.*, EXISTS (SELECT 1 FROM stock_movement m
            WHERE m.stock_item_id = i.id AND m.movement_type = 'INITIALIZE_OPENING') AS opening_initialized,
            EXISTS (SELECT 1 FROM inventory_import_row r WHERE r.id = i.source_import_row_id
                AND r.approved_target_type = 'STOCK_ITEM' AND r.approved_target_id = i.id
                AND r.review_state = 'APPROVED' AND r.candidate_quantity = 0) AS verified_zero_opening
        FROM stock_item i
    """.trimIndent()
}
