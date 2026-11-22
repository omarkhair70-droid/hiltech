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

enum class ReceivingSourceType { PO, TRANSFER, RETURN, INITIALIZATION, OTHER }
enum class ReceivingState { DRAFT, IN_PROGRESS, RECEIVED, RECEIVED_WITH_DISCREPANCY, CANCELLED }
enum class ReceivingCandidateClass { STOCK_QUANTITY, STOCK_SERIALIZED, COMPANY_ASSET }
enum class ReceivingDiscrepancyType {
    SHORT_QTY, OVER_QTY, WRONG_ITEM, DAMAGED_ON_RECEIPT, SERIAL_MISMATCH, UNEXPECTED_ITEM, DOCUMENT_MISMATCH
}

data class ReceivingRecordView(
    val id: UUID, val organizationId: UUID, val destinationStorageLocationId: UUID,
    val sourceType: ReceivingSourceType, val sourceRef: String?, val deliveryDocumentRef: String?,
    val state: ReceivingState, val createdBy: UUID, val createdAt: String, val updatedAt: String,
    val receivedBy: UUID?, val receivedAt: String?, val correlationId: String, val version: Long,
)
data class ReceivingLineView(
    val id: UUID, val receivingRecordId: UUID, val organizationId: UUID, val candidateClass: ReceivingCandidateClass,
    val expectedStockItemId: UUID?, val expectedAssetTypeDefinitionId: UUID?, val expectedQuantity: String?,
    val receivedQuantity: String?, val unitCode: String?, val condition: AssetCondition?,
    val serials: List<String>, val sourceLineRef: String?, val receivedAssetId: UUID?,
    val receivedBy: UUID?, val receivedAt: String?, val version: Long,
)
data class ReceivingDiscrepancyView(
    val id: UUID, val receivingRecordId: UUID, val receivingLineId: UUID?, val organizationId: UUID,
    val type: ReceivingDiscrepancyType, val description: String, val createdBy: UUID, val createdAt: String, val version: Long,
)
data class ReceivingDetailView(
    val record: ReceivingRecordView, val lines: List<ReceivingLineView>, val discrepancies: List<ReceivingDiscrepancyView>,
)
data class ReceivingDirectoryView(
    val items: List<ReceivingRecordView>, val limited: Boolean,
    val nextUpdatedAt: String? = null, val nextId: UUID? = null,
)
private data class ReceivingCursorRow(val record: ReceivingRecordView, val updatedAt: String)

@Repository
class ReceivingPersistence(private val jdbc: JdbcTemplate) {
    fun record(id: UUID, lock: Boolean = false): ReceivingRecordView? = jdbc.query(
        "SELECT * FROM receiving_record WHERE id = ?" + if (lock) " FOR UPDATE" else "",
        { r, _ -> r.record() }, id,
    ).singleOrNull()

    fun line(id: UUID, lock: Boolean = false): ReceivingLineView? = jdbc.query(
        "SELECT * FROM receiving_line WHERE id = ?" + if (lock) " FOR UPDATE" else "",
        { r, _ -> r.line() }, id,
    ).singleOrNull()

    fun lines(recordId: UUID): List<ReceivingLineView> = jdbc.query(
        "SELECT * FROM receiving_line WHERE receiving_record_id = ? ORDER BY created_at, id",
        { r, _ -> r.line() }, recordId,
    )

    fun discrepancies(recordId: UUID): List<ReceivingDiscrepancyView> = jdbc.query(
        "SELECT * FROM receiving_discrepancy WHERE receiving_record_id = ? ORDER BY created_at, id",
        { r, _ -> r.discrepancy() }, recordId,
    )

    fun directory(org: UUID, locations: List<UUID>, afterUpdatedAt: Instant? = null,
        afterId: UUID? = null): ReceivingDirectoryView {
        if (locations.isEmpty()) return ReceivingDirectoryView(emptyList(), false)
        val placeholders = locations.joinToString(",") { "?" }
        val cursor = afterUpdatedAt != null && afterId != null
        val cursorClause = if (cursor)
            "AND (updated_at < ? OR (updated_at = ? AND id < ?))" else ""
        val sql = """SELECT * FROM receiving_record
            WHERE organization_id = ? AND destination_storage_location_id IN ($placeholders)
            $cursorClause
            ORDER BY updated_at DESC, id DESC LIMIT 101""".trimIndent()
        val args = mutableListOf<Any>(org).apply {
            addAll(locations)
            if (cursor) {
                val at = requireNotNull(afterUpdatedAt).atOffset(ZoneOffset.UTC)
                add(at); add(at); add(requireNotNull(afterId))
            }
        }
        val rows = jdbc.query(sql, { r, _ ->
            val record = r.record()
            ReceivingCursorRow(record, requireNotNull(r.instant("updated_at")).toString())
        }, *args.toTypedArray())
        val page = rows.take(100)
        val last = page.lastOrNull().takeIf { rows.size > 100 }
        return ReceivingDirectoryView(page.map { it.record }, rows.size > 100, last?.updatedAt, last?.record?.id)
    }

    fun insertRecord(id: UUID, org: UUID, destination: UUID, sourceType: ReceivingSourceType,
        sourceRef: String?, documentRef: String?, actor: UUID, at: Instant, correlationId: String) {
        jdbc.update(
            """INSERT INTO receiving_record (id, organization_id, destination_storage_location_id, source_type,
                source_ref, delivery_document_ref, state, created_by, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)""".trimIndent(),
            id, org, destination, sourceType.name, sourceRef, documentRef, actor,
            at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), correlationId,
        )
    }

    fun insertLine(id: UUID, record: ReceivingRecordView, candidate: ReceivingCandidateClass, item: UUID,
        expected: BigDecimal?, unit: String, sourceLineRef: String?, actor: UUID, at: Instant) {
        jdbc.update(
            """INSERT INTO receiving_line (id, receiving_record_id, organization_id, candidate_class,
                expected_stock_item_id, expected_quantity, unit_code, source_line_ref, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            id, record.id, record.organizationId, candidate.name, item, expected, unit, sourceLineRef,
            actor, at.atOffset(ZoneOffset.UTC),
        )
    }

    fun insertAssetLine(id: UUID, record: ReceivingRecordView, assetTypeId: UUID,
        sourceLineRef: String?, actor: UUID, at: Instant) {
        jdbc.update(
            """INSERT INTO receiving_line (id, receiving_record_id, organization_id, candidate_class,
                expected_asset_type_definition_id, expected_quantity, source_line_ref, created_by, created_at)
                VALUES (?, ?, ?, 'COMPANY_ASSET', ?, 1, ?, ?, ?)""".trimIndent(),
            id, record.id, record.organizationId, assetTypeId, sourceLineRef, actor, at.atOffset(ZoneOffset.UTC),
        )
    }

    fun markAssetLineReceived(id: UUID, version: Long, assetId: UUID, condition: AssetCondition,
        serial: String?, actor: UUID, at: Instant): Boolean = jdbc.update(
        """UPDATE receiving_line SET received_quantity = 1, condition_received = ?,
               serials = ?::jsonb, received_asset_id = ?, received_by = ?, received_at = ?, version = version + 1
           WHERE id = ? AND version = ? AND received_at IS NULL""".trimIndent(),
        condition.name, Json.encodeToString(listOfNotNull(serial)), assetId, actor, at.atOffset(ZoneOffset.UTC), id, version,
    ) == 1

    fun touchInProgress(id: UUID, version: Long, at: Instant): Boolean = jdbc.update(
        """UPDATE receiving_record SET state = 'IN_PROGRESS', updated_at = ?, version = version + 1
           WHERE id = ? AND version = ? AND state IN ('DRAFT','IN_PROGRESS')""".trimIndent(),
        at.atOffset(ZoneOffset.UTC), id, version,
    ) == 1

    fun markLineReceived(id: UUID, version: Long, quantity: BigDecimal, condition: AssetCondition,
        serials: List<String>, actor: UUID, at: Instant): Boolean = jdbc.update(
        """UPDATE receiving_line SET received_quantity = ?, condition_received = ?, serials = ?::jsonb,
               received_by = ?, received_at = ?, version = version + 1
           WHERE id = ? AND version = ? AND received_at IS NULL""".trimIndent(),
        quantity, condition.name, Json.encodeToString(serials), actor, at.atOffset(ZoneOffset.UTC), id, version,
    ) == 1

    fun complete(id: UUID, version: Long, state: ReceivingState, actor: UUID, at: Instant): Boolean = jdbc.update(
        """UPDATE receiving_record SET state = ?, received_by = ?, received_at = ?, updated_at = ?,
               version = version + 1 WHERE id = ? AND version = ? AND state = 'IN_PROGRESS'""".trimIndent(),
        state.name, actor, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), id, version,
    ) == 1

    /** A PO-line rejection is physical inspection truth, not inventory intake. */
    fun hasPoRejection(lineId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM procurement_po_receiving_rejection_event WHERE receiving_line_id=?)",
        Boolean::class.java, lineId,
    ) == true

    fun insertPoRejection(
        eventId: UUID, context: ReceivingCommandContext,
        record: ReceivingRecordView, lineId: UUID, rejected: BigDecimal,
        type: ReceivingDiscrepancyType, reason: String, at: Instant,
    ) {
        check(jdbc.update(
            """
            INSERT INTO procurement_po_receiving_rejection_event (
              id, operation_id, organization_id, receiving_record_id,
              receiving_line_id, rejected_quantity, rejection_type, reason,
              rejected_by_user_id, rejected_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            eventId, context.operationId, record.organizationId, record.id,
            lineId, rejected, type.name, reason, context.actor,
            at.atOffset(ZoneOffset.UTC), context.correlationId,
        ) == 1)
    }

    fun hasDiscrepancy(recordId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM receiving_discrepancy WHERE receiving_record_id = ?)",
        Boolean::class.java, recordId,
    ) == true

    fun insertDiscrepancy(id: UUID, record: ReceivingRecordView, lineId: UUID?, type: ReceivingDiscrepancyType,
        description: String, actor: UUID, at: Instant) {
        jdbc.update(
            """INSERT INTO receiving_discrepancy (id, receiving_record_id, receiving_line_id, organization_id,
                discrepancy_type, description, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            id, record.id, lineId, record.organizationId, type.name, description, actor, at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun ResultSet.record() = ReceivingRecordView(
        getObject("id", UUID::class.java), getObject("organization_id", UUID::class.java),
        getObject("destination_storage_location_id", UUID::class.java), ReceivingSourceType.valueOf(getString("source_type")),
        getString("source_ref"), getString("delivery_document_ref"), ReceivingState.valueOf(getString("state")),
        getObject("created_by", UUID::class.java), requireNotNull(instant("created_at")).toString(),
        requireNotNull(instant("updated_at")).toString(),
        getObject("received_by", UUID::class.java), instant("received_at")?.toString(),
        getString("correlation_id"), getLong("version"),
    )

    private fun ResultSet.line(): ReceivingLineView {
        val serials = Json.decodeFromString<List<String>>(getString("serials"))
        return ReceivingLineView(
            getObject("id", UUID::class.java), getObject("receiving_record_id", UUID::class.java),
            getObject("organization_id", UUID::class.java), ReceivingCandidateClass.valueOf(getString("candidate_class")),
            getObject("expected_stock_item_id", UUID::class.java), getObject("expected_asset_type_definition_id", UUID::class.java),
            getBigDecimal("expected_quantity")?.toPlainString(), getBigDecimal("received_quantity")?.toPlainString(),
            getString("unit_code"), getString("condition_received")?.let(AssetCondition::valueOf), serials,
            getString("source_line_ref"), getObject("received_asset_id", UUID::class.java),
            getObject("received_by", UUID::class.java), instant("received_at")?.toString(), getLong("version"),
        )
    }

    private fun ResultSet.discrepancy() = ReceivingDiscrepancyView(
        getObject("id", UUID::class.java), getObject("receiving_record_id", UUID::class.java),
        getObject("receiving_line_id", UUID::class.java), getObject("organization_id", UUID::class.java),
        ReceivingDiscrepancyType.valueOf(getString("discrepancy_type")), getString("description"),
        getObject("created_by", UUID::class.java), requireNotNull(instant("created_at")).toString(), getLong("version"),
    )

    private fun ResultSet.instant(name: String): Instant? = getObject(name, OffsetDateTime::class.java)?.toInstant()
}
