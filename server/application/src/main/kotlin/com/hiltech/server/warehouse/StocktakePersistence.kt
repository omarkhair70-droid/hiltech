package com.hiltech.server.warehouse

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class StocktakeState { DRAFT, ACTIVE, SUBMITTED, RECONCILING, CLOSED, CANCELLED }

data class StocktakeSessionView(
    val id: UUID, val organizationId: UUID, val warehouseId: UUID, val rootStorageLocationId: UUID?,
    val state: StocktakeState, val categoryIds: List<UUID>, val stockItemIds: List<UUID>,
    val snapshotAt: String?, val createdBy: UUID, val createdAt: String, val correlationId: String, val version: Long,
)
data class StocktakeSnapshotLineView(
    val id: UUID, val sessionId: UUID, val organizationId: UUID, val stockItemId: UUID, val itemCode: String,
    val storageLocationId: UUID, val locationCode: String, val expectedOnHand: String, val expectedReserved: String,
    val expectedDamaged: String, val expectedQuarantine: String, val unitCode: String,
    val balanceVersion: Long, val itemVersion: Long, val snapshotAt: String,
)
data class StockCountRecordView(
    val id: UUID, val sessionId: UUID, val snapshotLineId: UUID, val organizationId: UUID,
    val stockItemId: UUID, val storageLocationId: UUID, val countedQuantity: String, val unitCode: String,
    val countedBy: UUID, val countedAt: String, val scanSource: String?, val notes: String?,
    val supersedesCountRecordId: UUID?, val operationId: UUID, val correlationId: String,
)
data class StocktakeSnapshotCandidate(
    val stockItemId: UUID, val itemCode: String, val storageLocationId: UUID, val locationCode: String,
    val onHand: BigDecimal, val reserved: BigDecimal, val damaged: BigDecimal, val quarantine: BigDecimal,
    val unitCode: String, val balanceVersion: Long, val itemVersion: Long,
)
data class InventoryDiscrepancyView(
    val id: UUID, val sessionId: UUID, val snapshotLineId: UUID, val latestCountRecordId: UUID,
    val organizationId: UUID, val stockItemId: UUID, val storageLocationId: UUID,
    val expectedQuantity: String, val countedQuantity: String, val varianceQuantity: String,
    val snapshotBalanceVersion: Long, val currentBalanceVersion: Long, val sourceChangedSinceSnapshot: Boolean,
    val state: String, val createdBy: UUID, val createdAt: String, val updatedAt: String, val version: Long,
)

@Repository
class StocktakePersistence(private val jdbc: JdbcTemplate) {
    private val json = Json { ignoreUnknownKeys = true }

    fun session(id: UUID, lock: Boolean = false): StocktakeSessionView? = jdbc.query(
        "SELECT * FROM stocktake_session WHERE id = ?" + if (lock) " FOR UPDATE" else "",
        { r, _ -> r.session() }, id,
    ).singleOrNull()

    fun insertSession(id: UUID, org: UUID, warehouse: UUID, root: UUID, categories: List<UUID>,
        items: List<UUID>, actor: UUID, at: Instant, correlationId: String) {
        jdbc.update(
            """INSERT INTO stocktake_session (id, organization_id, warehouse_id, root_storage_location_id,
                state, scope_category_definition_ids, scope_stock_item_ids, created_by, created_at, updated_at,
                correlation_id)
               VALUES (?, ?, ?, ?, 'DRAFT', ?::jsonb, ?::jsonb, ?, ?, ?, ?)""".trimIndent(),
            id, org, warehouse, root, jsonIds(categories), jsonIds(items), actor,
            at.utc(), at.utc(), correlationId,
        )
    }

    fun snapshotCandidates(session: StocktakeSessionView, at: Instant): List<StocktakeSnapshotCandidate> {
        val categories = jsonIds(session.categoryIds)
        val items = jsonIds(session.stockItemIds)
        return jdbc.query(
            """WITH RECURSIVE location_scope AS (
                   SELECT id FROM storage_location
                   WHERE id = ? AND organization_id = ? AND warehouse_id = ?
                   UNION ALL
                   SELECT child.id FROM storage_location child
                   JOIN location_scope parent ON child.parent_storage_location_id = parent.id
                   WHERE child.organization_id = ? AND child.warehouse_id = ?
               )
               SELECT i.id AS stock_item_id, i.item_code, i.unit_of_measure_code,
                      i.version AS item_version, b.storage_location_id, l.code AS location_code,
                      b.on_hand_qty, b.reserved_qty, b.damaged_qty, b.quarantine_qty,
                      b.version AS balance_version
               FROM stock_balance b
               JOIN stock_item i ON i.id = b.stock_item_id AND i.organization_id = b.organization_id
               JOIN storage_location l ON l.id = b.storage_location_id AND l.organization_id = b.organization_id
               WHERE b.organization_id = ?
                 AND b.storage_location_id IN (SELECT id FROM location_scope)
                 AND i.active
                 AND l.lifecycle_state = 'ACTIVE'
                 AND (l.active_from IS NULL OR l.active_from <= ?)
                 AND (l.active_until IS NULL OR l.active_until > ?)
                 AND (?::jsonb = '[]'::jsonb OR i.category_definition_id::text IN
                        (SELECT jsonb_array_elements_text(?::jsonb)))
                 AND (?::jsonb = '[]'::jsonb OR i.id::text IN
                        (SELECT jsonb_array_elements_text(?::jsonb)))
               ORDER BY l.code, i.item_code, i.id""".trimIndent(),
            { r, _ -> StocktakeSnapshotCandidate(
                r.uuid("stock_item_id"), r.getString("item_code"), r.uuid("storage_location_id"),
                r.getString("location_code"), r.getBigDecimal("on_hand_qty"), r.getBigDecimal("reserved_qty"),
                r.getBigDecimal("damaged_qty"), r.getBigDecimal("quarantine_qty"), r.getString("unit_of_measure_code"),
                r.getLong("balance_version"), r.getLong("item_version"),
            ) },
            session.rootStorageLocationId, session.organizationId, session.warehouseId,
            session.organizationId, session.warehouseId, session.organizationId,
            at.utc(), at.utc(), categories, categories, items, items,
        )
    }

    fun insertSnapshot(id: UUID, session: StocktakeSessionView, row: StocktakeSnapshotCandidate, at: Instant) {
        jdbc.update(
            """INSERT INTO stocktake_snapshot_line (id, stocktake_session_id, organization_id, stock_item_id,
                storage_location_id, expected_on_hand_qty, expected_reserved_qty, expected_damaged_qty,
                expected_quarantine_qty, unit_code, stock_balance_version, stock_item_version, snapshot_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            id, session.id, session.organizationId, row.stockItemId, row.storageLocationId,
            row.onHand, row.reserved, row.damaged, row.quarantine, row.unitCode,
            row.balanceVersion, row.itemVersion, at.utc(),
        )
    }

    fun activate(id: UUID, version: Long, at: Instant): Boolean = jdbc.update(
        """UPDATE stocktake_session SET state = 'ACTIVE', snapshot_at = ?, updated_at = ?, version = version + 1
           WHERE id = ? AND version = ? AND state = 'DRAFT'""".trimIndent(),
        at.utc(), at.utc(), id, version,
    ) == 1

    fun snapshotLines(sessionId: UUID): List<StocktakeSnapshotLineView> = jdbc.query(
        """SELECT s.*, i.item_code, l.code AS location_code
           FROM stocktake_snapshot_line s
           JOIN stock_item i ON i.id = s.stock_item_id
           JOIN storage_location l ON l.id = s.storage_location_id
           WHERE s.stocktake_session_id = ?
           ORDER BY l.code, i.item_code, s.id""".trimIndent(),
        { r, _ -> r.snapshot() }, sessionId,
    )

    fun snapshotLine(id: UUID): StocktakeSnapshotLineView? = jdbc.query(
        """SELECT s.*, i.item_code, l.code AS location_code
           FROM stocktake_snapshot_line s
           JOIN stock_item i ON i.id = s.stock_item_id
           JOIN storage_location l ON l.id = s.storage_location_id
           WHERE s.id = ?""".trimIndent(),
        { r, _ -> r.snapshot() }, id,
    ).singleOrNull()

    fun latestCount(snapshotLineId: UUID): StockCountRecordView? = jdbc.query(
        """SELECT c.* FROM stock_count_record c
           WHERE c.snapshot_line_id = ?
             AND NOT EXISTS (
                 SELECT 1 FROM stock_count_record newer
                 WHERE newer.snapshot_line_id = c.snapshot_line_id
                   AND newer.supersedes_count_record_id = c.id
             )
           LIMIT 1""".trimIndent(),
        { r, _ -> r.count() }, snapshotLineId,
    ).singleOrNull()

    fun count(id: UUID): StockCountRecordView? = jdbc.query(
        "SELECT * FROM stock_count_record WHERE id = ?", { r, _ -> r.count() }, id,
    ).singleOrNull()

    fun counts(snapshotLineId: UUID): List<StockCountRecordView> = jdbc.query(
        """SELECT * FROM stock_count_record WHERE snapshot_line_id = ?
           ORDER BY counted_at, id""".trimIndent(),
        { r, _ -> r.count() }, snapshotLineId,
    )

    fun insertCount(id: UUID, operationId: UUID, session: StocktakeSessionView, line: StocktakeSnapshotLineView,
        quantity: BigDecimal, actor: UUID, at: Instant, scanSource: String?, notes: String?,
        supersedes: UUID?, correlationId: String) {
        jdbc.update(
            """INSERT INTO stock_count_record (id, stocktake_session_id, snapshot_line_id, organization_id,
                stock_item_id, storage_location_id, counted_quantity, unit_code, counted_by, counted_at,
                scan_source, notes, supersedes_count_record_id, operation_id, correlation_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            id, session.id, line.id, session.organizationId, line.stockItemId, line.storageLocationId,
            quantity, line.unitCode, actor, at.utc(), scanSource, notes, supersedes, operationId, correlationId,
        )
    }

    fun insertDiscrepancy(
        id: UUID, session: StocktakeSessionView, line: StocktakeSnapshotLineView, latestCount: StockCountRecordView,
        counted: BigDecimal, variance: BigDecimal, currentBalanceVersion: Long, sourceChanged: Boolean,
        actor: UUID, at: Instant,
    ) {
        jdbc.update(
            """INSERT INTO inventory_discrepancy (id, stocktake_session_id, snapshot_line_id,
                latest_count_record_id, organization_id, stock_item_id, storage_location_id,
                expected_quantity, counted_quantity, variance_quantity, snapshot_balance_version,
                current_balance_version, source_changed_since_snapshot, state, created_by, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)""".trimIndent(),
            id, session.id, line.id, latestCount.id, session.organizationId, line.stockItemId,
            line.storageLocationId, BigDecimal(line.expectedOnHand), counted, variance, line.balanceVersion,
            currentBalanceVersion, sourceChanged, actor, at.utc(), at.utc(),
        )
    }

    fun discrepancies(sessionId: UUID): List<InventoryDiscrepancyView> = jdbc.query(
        """SELECT * FROM inventory_discrepancy WHERE stocktake_session_id = ?
           ORDER BY snapshot_line_id, id""".trimIndent(),
        { r, _ -> r.discrepancy() }, sessionId,
    )

    fun submit(id: UUID, version: Long, actor: UUID, at: Instant): Boolean = jdbc.update(
        """UPDATE stocktake_session
           SET state = 'SUBMITTED', submitted_by = ?, submitted_at = ?, updated_at = ?, version = version + 1
           WHERE id = ? AND version = ? AND state = 'ACTIVE'""".trimIndent(),
        actor, at.utc(), at.utc(), id, version,
    ) == 1

    private fun ResultSet.session() = StocktakeSessionView(
        uuid("id"), uuid("organization_id"), uuid("warehouse_id"), optionalUuid("root_storage_location_id"),
        StocktakeState.valueOf(getString("state")),
        jsonIds(getString("scope_category_definition_ids")), jsonIds(getString("scope_stock_item_ids")),
        instant("snapshot_at")?.toString(), uuid("created_by"), requireNotNull(instant("created_at")).toString(),
        getString("correlation_id"), getLong("version"),
    )

    private fun ResultSet.snapshot() = StocktakeSnapshotLineView(
        uuid("id"), uuid("stocktake_session_id"), uuid("organization_id"), uuid("stock_item_id"),
        getString("item_code"), uuid("storage_location_id"), getString("location_code"),
        getBigDecimal("expected_on_hand_qty").toPlainString(), getBigDecimal("expected_reserved_qty").toPlainString(),
        getBigDecimal("expected_damaged_qty").toPlainString(), getBigDecimal("expected_quarantine_qty").toPlainString(),
        getString("unit_code"), getLong("stock_balance_version"), getLong("stock_item_version"),
        requireNotNull(instant("snapshot_at")).toString(),
    )

    private fun ResultSet.count() = StockCountRecordView(
        uuid("id"), uuid("stocktake_session_id"), uuid("snapshot_line_id"), uuid("organization_id"),
        uuid("stock_item_id"), uuid("storage_location_id"), getBigDecimal("counted_quantity").toPlainString(),
        getString("unit_code"), uuid("counted_by"), requireNotNull(instant("counted_at")).toString(),
        getString("scan_source"), getString("notes"), optionalUuid("supersedes_count_record_id"),
        uuid("operation_id"), getString("correlation_id"),
    )

    private fun ResultSet.discrepancy() = InventoryDiscrepancyView(
        uuid("id"), uuid("stocktake_session_id"), uuid("snapshot_line_id"), uuid("latest_count_record_id"),
        uuid("organization_id"), uuid("stock_item_id"), uuid("storage_location_id"),
        getBigDecimal("expected_quantity").toPlainString(), getBigDecimal("counted_quantity").toPlainString(),
        getBigDecimal("variance_quantity").toPlainString(), getLong("snapshot_balance_version"),
        getLong("current_balance_version"), getBoolean("source_changed_since_snapshot"), getString("state"),
        uuid("created_by"), requireNotNull(instant("created_at")).toString(),
        requireNotNull(instant("updated_at")).toString(), getLong("version"),
    )

    private fun jsonIds(values: List<UUID>): String = json.encodeToString(values.map(UUID::toString))
    private fun jsonIds(raw: String): List<UUID> = json.decodeFromString<List<String>>(raw).map(UUID::fromString)
    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
    private fun ResultSet.uuid(name: String): UUID = getObject(name, UUID::class.java)
    private fun ResultSet.optionalUuid(name: String): UUID? = getObject(name, UUID::class.java)
    private fun ResultSet.instant(name: String): Instant? = getObject(name, OffsetDateTime::class.java)?.toInstant()
}
