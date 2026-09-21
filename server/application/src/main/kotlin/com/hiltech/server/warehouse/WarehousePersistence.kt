package com.hiltech.server.warehouse

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Array as SqlArray
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface WarehousePersistencePort {
    fun warehouse(warehouseId: UUID): WarehouseSnapshot?
    fun warehouses(organizationId: UUID): List<WarehouseSnapshot>
    fun insertWarehouse(
        warehouseId: UUID,
        organizationId: UUID,
        code: String,
        name: String,
        facilityRef: String?,
    )
    fun storageLocation(storageLocationId: UUID): StorageLocationSnapshot?
    fun storageLocations(
        organizationId: UUID,
        warehouseId: UUID?,
    ): List<StorageLocationSnapshot>
    fun insertStorageLocation(
        storageLocationId: UUID,
        command: CreateStorageLocationCommand,
    )

    fun warehouseAuthorityBinding(
        bindingId: UUID,
        at: Instant,
    ): WarehouseAuthorityBindingSnapshot?
    fun currentWarehouseAuthorityBindings(
        warehouseId: UUID,
        at: Instant,
    ): List<WarehouseAuthorityBindingSnapshot>
    fun currentOrganizationWarehouseBindings(
        organizationId: UUID,
        at: Instant,
    ): List<WarehouseAuthorityBindingSnapshot>
    fun insertWarehouseAuthorityBinding(
        bindingId: UUID,
        organizationId: UUID,
        warehouseId: UUID,
        authorityKey: WarehouseAuthorityKey,
        principalType: InventoryPrincipalType,
        principalId: UUID,
        effectiveFrom: Instant,
        actorUserId: UUID,
        at: Instant,
    )
    fun endWarehouseAuthorityBinding(
        bindingId: UUID,
        warehouseId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun storageAuthorityBinding(
        bindingId: UUID,
        at: Instant,
    ): StorageAuthorityBindingSnapshot?
    fun currentStorageAuthorityBindings(
        storageLocationId: UUID,
        at: Instant,
    ): List<StorageAuthorityBindingSnapshot>
    fun insertStorageAuthorityBinding(
        bindingId: UUID,
        organizationId: UUID,
        storageLocationId: UUID,
        authorityKey: StorageLocationAuthorityKey,
        principalType: InventoryPrincipalType,
        principalId: UUID,
        effectiveFrom: Instant,
        actorUserId: UUID,
        at: Instant,
    )
    fun endStorageAuthorityBinding(
        bindingId: UUID,
        storageLocationId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun importBatch(batchId: UUID): InventoryImportBatchSnapshot?
    fun importBatchByHash(
        organizationId: UUID,
        sourceSha256: String,
    ): InventoryImportBatchSnapshot?
    fun importBatches(organizationId: UUID): List<InventoryImportBatchSnapshot>
    fun insertImportBatch(
        batchId: UUID,
        command: CreateInventoryImportBatchCommand,
        at: Instant,
    )
    fun setImportBatchState(
        batchId: UUID,
        state: InventoryImportBatchState,
        at: Instant,
    )

    fun importRow(rowId: UUID): InventoryImportRowSnapshot?
    fun importRows(batchId: UUID): List<InventoryImportRowSnapshot>
    fun duplicatePartNumberExists(
        batchId: UUID,
        partNumber: String,
        excludingRowId: UUID?,
    ): Boolean
    fun insertImportRow(
        rowId: UUID,
        organizationId: UUID,
        command: AddInventoryImportRowCommand,
        issueCodes: List<InventoryImportIssueCode>,
        at: Instant,
    )
    fun updateImportRowReview(
        command: ReviewInventoryImportRowCommand,
        issueCodes: List<InventoryImportIssueCode>,
        at: Instant,
    ): Boolean
}

@Component
class JdbcWarehousePersistence(
    private val jdbc: JdbcTemplate,
) : WarehousePersistencePort {
    override fun warehouse(
        warehouseId: UUID,
    ): WarehouseSnapshot? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                code,
                name,
                facility_ref,
                active,
                version
            FROM warehouse
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                WarehouseSnapshot(
                    warehouseId = rs.getObject("id", UUID::class.java),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    facilityRef = rs.getString("facility_ref"),
                    active = rs.getBoolean("active"),
                    version = rs.getLong("version"),
                )
            },
            warehouseId,
        ).singleOrNull()

    override fun warehouses(
        organizationId: UUID,
    ): List<WarehouseSnapshot> =
        jdbc.query(
            """
            SELECT id, organization_id, code, name, facility_ref, active, version
            FROM warehouse
            WHERE organization_id = ?
            ORDER BY active DESC, code, id
            """.trimIndent(),
            { rs, _ ->
                WarehouseSnapshot(
                    warehouseId = rs.getObject("id", UUID::class.java),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    facilityRef = rs.getString("facility_ref"),
                    active = rs.getBoolean("active"),
                    version = rs.getLong("version"),
                )
            },
            organizationId,
        )

    override fun insertWarehouse(
        warehouseId: UUID,
        organizationId: UUID,
        code: String,
        name: String,
        facilityRef: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO warehouse (
                id,
                organization_id,
                code,
                name,
                facility_ref,
                active,
                version
            )
            VALUES (?, ?, ?, ?, ?, true, 1)
            """.trimIndent(),
            warehouseId,
            organizationId,
            code,
            name,
            facilityRef,
        )
    }

    override fun storageLocation(
        storageLocationId: UUID,
    ): StorageLocationSnapshot? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                code,
                name,
                kind,
                parent_storage_location_id,
                warehouse_id,
                project_id,
                site_id,
                temporary,
                lifecycle_state,
                active_from,
                active_until,
                restricted_access,
                address_or_location_ref,
                notes,
                version
            FROM storage_location
            WHERE id = ?
            """.trimIndent(),
            { rs, _ -> rs.toStorageLocation() },
            storageLocationId,
        ).singleOrNull()

    override fun storageLocations(
        organizationId: UUID,
        warehouseId: UUID?,
    ): List<StorageLocationSnapshot> {
        val sql =
            if (warehouseId == null) {
                """
                SELECT
                    id, organization_id, code, name, kind,
                    parent_storage_location_id, warehouse_id,
                    project_id, site_id, temporary, lifecycle_state,
                    active_from, active_until, restricted_access,
                    address_or_location_ref, notes, version
                FROM storage_location
                WHERE organization_id = ?
                ORDER BY lifecycle_state, code, id
                """.trimIndent()
            } else {
                """
                SELECT
                    id, organization_id, code, name, kind,
                    parent_storage_location_id, warehouse_id,
                    project_id, site_id, temporary, lifecycle_state,
                    active_from, active_until, restricted_access,
                    address_or_location_ref, notes, version
                FROM storage_location
                WHERE organization_id = ?
                  AND warehouse_id = ?
                ORDER BY lifecycle_state, code, id
                """.trimIndent()
            }

        return if (warehouseId == null) {
            jdbc.query(sql, { rs, _ -> rs.toStorageLocation() }, organizationId)
        } else {
            jdbc.query(sql, { rs, _ -> rs.toStorageLocation() }, organizationId, warehouseId)
        }
    }

    override fun insertStorageLocation(
        storageLocationId: UUID,
        command: CreateStorageLocationCommand,
    ) {
        jdbc.update(
            """
            INSERT INTO storage_location (
                id,
                organization_id,
                code,
                name,
                kind,
                parent_storage_location_id,
                warehouse_id,
                project_id,
                site_id,
                temporary,
                active_from,
                active_until,
                responsible_relationship_code,
                restricted_access,
                address_or_location_ref,
                notes,
                version,
                lifecycle_state
            )
            VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, NULL, NULL, ?, ?, ?, 1, 'ACTIVE'
            )
            """.trimIndent(),
            storageLocationId,
            command.organizationId,
            command.code,
            command.name,
            command.kind.name,
            command.parentStorageLocationId,
            command.warehouseId,
            command.projectId,
            command.siteId,
            command.temporary,
            command.activeFrom?.atOffset(ZoneOffset.UTC),
            command.restrictedAccess,
            command.addressOrLocationRef,
            command.notes,
        )
    }

    override fun warehouseAuthorityBinding(
        bindingId: UUID,
        at: Instant,
    ): WarehouseAuthorityBindingSnapshot? =
        jdbc.query(
            warehouseBindingSql("b.id = ?"),
            { rs, _ -> rs.toWarehouseBinding(at) },
            bindingId,
        ).singleOrNull()

    override fun currentWarehouseAuthorityBindings(
        warehouseId: UUID,
        at: Instant,
    ): List<WarehouseAuthorityBindingSnapshot> =
        jdbc.query(
            warehouseBindingSql(
                """
                b.warehouse_id = ?
                AND b.active = true
                AND b.effective_from <= ?
                AND (b.effective_to IS NULL OR b.effective_to > ?)
                """.trimIndent(),
            ),
            { rs, _ -> rs.toWarehouseBinding(at) },
            warehouseId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

    override fun currentOrganizationWarehouseBindings(
        organizationId: UUID,
        at: Instant,
    ): List<WarehouseAuthorityBindingSnapshot> =
        jdbc.query(
            warehouseBindingSql(
                """
                b.organization_id = ?
                AND b.active = true
                AND b.effective_from <= ?
                AND (b.effective_to IS NULL OR b.effective_to > ?)
                """.trimIndent(),
            ),
            { rs, _ -> rs.toWarehouseBinding(at) },
            organizationId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

    override fun insertWarehouseAuthorityBinding(
        bindingId: UUID,
        organizationId: UUID,
        warehouseId: UUID,
        authorityKey: WarehouseAuthorityKey,
        principalType: InventoryPrincipalType,
        principalId: UUID,
        effectiveFrom: Instant,
        actorUserId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO warehouse_authority_binding (
                id,
                organization_id,
                warehouse_id,
                authority_key,
                principal_type,
                principal_user_id,
                principal_team_id,
                effective_from,
                effective_to,
                active,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, NULL, true, ?, ?, 1
            )
            """.trimIndent(),
            bindingId,
            organizationId,
            warehouseId,
            authorityKey.name,
            principalType.name,
            if (principalType == InventoryPrincipalType.USER) principalId else null,
            if (principalType == InventoryPrincipalType.TEAM) principalId else null,
            effectiveFrom.atOffset(ZoneOffset.UTC),
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    override fun endWarehouseAuthorityBinding(
        bindingId: UUID,
        warehouseId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE warehouse_authority_binding
            SET effective_to = ?,
                active = false,
                version = version + 1
            WHERE id = ?
              AND warehouse_id = ?
              AND version = ?
              AND active = true
              AND effective_to IS NULL
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            bindingId,
            warehouseId,
            expectedVersion,
        ) == 1

    override fun storageAuthorityBinding(
        bindingId: UUID,
        at: Instant,
    ): StorageAuthorityBindingSnapshot? =
        jdbc.query(
            storageBindingSql("b.id = ?"),
            { rs, _ -> rs.toStorageBinding(at) },
            bindingId,
        ).singleOrNull()

    override fun currentStorageAuthorityBindings(
        storageLocationId: UUID,
        at: Instant,
    ): List<StorageAuthorityBindingSnapshot> =
        jdbc.query(
            storageBindingSql(
                """
                b.storage_location_id = ?
                AND b.active = true
                AND b.effective_from <= ?
                AND (b.effective_to IS NULL OR b.effective_to > ?)
                """.trimIndent(),
            ),
            { rs, _ -> rs.toStorageBinding(at) },
            storageLocationId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

    override fun insertStorageAuthorityBinding(
        bindingId: UUID,
        organizationId: UUID,
        storageLocationId: UUID,
        authorityKey: StorageLocationAuthorityKey,
        principalType: InventoryPrincipalType,
        principalId: UUID,
        effectiveFrom: Instant,
        actorUserId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO storage_location_authority_binding (
                id,
                organization_id,
                storage_location_id,
                authority_key,
                principal_type,
                principal_user_id,
                principal_team_id,
                effective_from,
                effective_to,
                active,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, NULL, true, ?, ?, 1
            )
            """.trimIndent(),
            bindingId,
            organizationId,
            storageLocationId,
            authorityKey.name,
            principalType.name,
            if (principalType == InventoryPrincipalType.USER) principalId else null,
            if (principalType == InventoryPrincipalType.TEAM) principalId else null,
            effectiveFrom.atOffset(ZoneOffset.UTC),
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    override fun endStorageAuthorityBinding(
        bindingId: UUID,
        storageLocationId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE storage_location_authority_binding
            SET effective_to = ?,
                active = false,
                version = version + 1
            WHERE id = ?
              AND storage_location_id = ?
              AND version = ?
              AND active = true
              AND effective_to IS NULL
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            bindingId,
            storageLocationId,
            expectedVersion,
        ) == 1

    override fun importBatch(
        batchId: UUID,
    ): InventoryImportBatchSnapshot? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                source_file_name,
                source_sha256,
                source_type,
                cutover_date,
                state,
                created_by_user_id,
                created_at,
                updated_at,
                version
            FROM inventory_import_batch
            WHERE id = ?
            """.trimIndent(),
            { rs, _ -> rs.toImportBatch() },
            batchId,
        ).singleOrNull()

    override fun importBatchByHash(
        organizationId: UUID,
        sourceSha256: String,
    ): InventoryImportBatchSnapshot? =
        jdbc.query(
            """
            SELECT
                id, organization_id, source_file_name, source_sha256,
                source_type, cutover_date, state, created_by_user_id,
                created_at, updated_at, version
            FROM inventory_import_batch
            WHERE organization_id = ?
              AND source_sha256 = ?
            """.trimIndent(),
            { rs, _ -> rs.toImportBatch() },
            organizationId,
            sourceSha256,
        ).singleOrNull()

    override fun importBatches(
        organizationId: UUID,
    ): List<InventoryImportBatchSnapshot> =
        jdbc.query(
            """
            SELECT
                id, organization_id, source_file_name, source_sha256,
                source_type, cutover_date, state, created_by_user_id,
                created_at, updated_at, version
            FROM inventory_import_batch
            WHERE organization_id = ?
            ORDER BY created_at DESC, id
            """.trimIndent(),
            { rs, _ -> rs.toImportBatch() },
            organizationId,
        )

    override fun insertImportBatch(
        batchId: UUID,
        command: CreateInventoryImportBatchCommand,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO inventory_import_batch (
                id,
                organization_id,
                source_file_name,
                source_sha256,
                source_type,
                cutover_date,
                state,
                created_by_user_id,
                created_at,
                updated_at,
                version
            )
            VALUES (?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?, ?, 1)
            """.trimIndent(),
            batchId,
            command.organizationId,
            command.sourceFileName,
            command.sourceSha256,
            command.sourceType.name,
            command.cutoverDate,
            command.actorUserId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
    }

    override fun setImportBatchState(
        batchId: UUID,
        state: InventoryImportBatchState,
        at: Instant,
    ) {
        jdbc.update(
            """
            UPDATE inventory_import_batch
            SET state = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state <> ?
            """.trimIndent(),
            state.name,
            at.atOffset(ZoneOffset.UTC),
            batchId,
            state.name,
        )
    }

    override fun importRow(
        rowId: UUID,
    ): InventoryImportRowSnapshot? =
        jdbc.query(
            importRowSql("r.id = ?"),
            { rs, _ -> rs.toImportRow() },
            rowId,
        ).singleOrNull()

    override fun importRows(
        batchId: UUID,
    ): List<InventoryImportRowSnapshot> =
        jdbc.query(
            importRowSql("r.batch_id = ?") +
                " ORDER BY r.source_sheet, r.source_row_number, r.id",
            { rs, _ -> rs.toImportRow() },
            batchId,
        )

    override fun duplicatePartNumberExists(
        batchId: UUID,
        partNumber: String,
        excludingRowId: UUID?,
    ): Boolean {
        val sql =
            """
            SELECT EXISTS (
                SELECT 1
                FROM inventory_import_row
                WHERE batch_id = ?
                  AND upper(trim(normalized_part_number)) = upper(trim(?))
                  AND (?::uuid IS NULL OR id <> ?::uuid)
            )
            """.trimIndent()
        return jdbc.queryForObject(
            sql,
            Boolean::class.java,
            batchId,
            partNumber,
            excludingRowId,
            excludingRowId,
        ) == true
    }

    override fun insertImportRow(
        rowId: UUID,
        organizationId: UUID,
        command: AddInventoryImportRowCommand,
        issueCodes: List<InventoryImportIssueCode>,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO inventory_import_row (
                id,
                batch_id,
                organization_id,
                source_sheet,
                source_row_number,
                raw_payload,
                candidate_class,
                normalized_brand,
                normalized_part_number,
                normalized_description,
                candidate_uom,
                candidate_quantity,
                candidate_value,
                candidate_currency_code,
                issue_codes,
                review_state,
                review_notes,
                reviewed_by_user_id,
                reviewed_at,
                approved_target_type,
                approved_target_id,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?, CAST(? AS jsonb), ?,
                ?, ?, ?, ?, ?, ?, ?,
                ?, 'PENDING', NULL, NULL, NULL, NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            rowId,
            command.batchId,
            organizationId,
            command.sourceSheet,
            command.sourceRowNumber,
            command.rawPayloadJson,
            command.candidateClass.name,
            command.normalizedBrand,
            command.normalizedPartNumber,
            command.normalizedDescription,
            command.candidateUom,
            command.candidateQuantity,
            command.candidateValue,
            command.candidateCurrencyCode,
            issueCodes.map { it.name }.toTypedArray(),
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
    }

    override fun updateImportRowReview(
        command: ReviewInventoryImportRowCommand,
        issueCodes: List<InventoryImportIssueCode>,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE inventory_import_row
            SET candidate_class = ?,
                normalized_brand = ?,
                normalized_part_number = ?,
                normalized_description = ?,
                candidate_uom = ?,
                candidate_quantity = ?,
                candidate_value = ?,
                candidate_currency_code = ?,
                issue_codes = ?,
                review_state = ?,
                review_notes = ?,
                reviewed_by_user_id = ?,
                reviewed_at = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND batch_id = ?
              AND version = ?
            """.trimIndent(),
            command.candidateClass.name,
            command.normalizedBrand,
            command.normalizedPartNumber,
            command.normalizedDescription,
            command.candidateUom,
            command.candidateQuantity,
            command.candidateValue,
            command.candidateCurrencyCode,
            issueCodes.map { it.name }.toTypedArray(),
            command.reviewState.name,
            command.reviewNotes,
            command.actorUserId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            command.rowId,
            command.batchId,
            command.baseVersion,
        ) == 1

    private fun warehouseBindingSql(
        predicate: String,
    ): String =
        """
        SELECT
            b.id,
            b.organization_id,
            b.warehouse_id,
            b.authority_key,
            b.principal_type,
            COALESCE(b.principal_user_id, b.principal_team_id) AS principal_id,
            b.effective_from,
            b.effective_to,
            b.active,
            b.version
        FROM warehouse_authority_binding b
        WHERE $predicate
        """.trimIndent()

    private fun storageBindingSql(
        predicate: String,
    ): String =
        """
        SELECT
            b.id,
            b.organization_id,
            b.storage_location_id,
            b.authority_key,
            b.principal_type,
            COALESCE(b.principal_user_id, b.principal_team_id) AS principal_id,
            b.effective_from,
            b.effective_to,
            b.active,
            b.version
        FROM storage_location_authority_binding b
        WHERE $predicate
        """.trimIndent()

    private fun importRowSql(
        predicate: String,
    ): String =
        """
        SELECT
            r.id,
            r.batch_id,
            r.organization_id,
            r.source_sheet,
            r.source_row_number,
            r.raw_payload::text AS raw_payload_json,
            r.candidate_class,
            r.normalized_brand,
            r.normalized_part_number,
            r.normalized_description,
            r.candidate_uom,
            r.candidate_quantity,
            r.candidate_value,
            r.candidate_currency_code,
            r.issue_codes,
            r.review_state,
            r.review_notes,
            r.reviewed_by_user_id,
            r.reviewed_at,
            r.approved_target_type,
            r.approved_target_id,
            r.created_at,
            r.updated_at,
            r.version
        FROM inventory_import_row r
        WHERE $predicate
        """.trimIndent()
}

private fun java.sql.ResultSet.toStorageLocation() =
    StorageLocationSnapshot(
        storageLocationId = getObject("id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        code = getString("code"),
        name = getString("name"),
        kind = StorageLocationKind.valueOf(getString("kind")),
        parentStorageLocationId =
            getObject("parent_storage_location_id", UUID::class.java),
        warehouseId = getObject("warehouse_id", UUID::class.java),
        projectId = getObject("project_id", UUID::class.java),
        siteId = getObject("site_id", UUID::class.java),
        temporary = getBoolean("temporary"),
        lifecycleState =
            StorageLocationLifecycle.valueOf(getString("lifecycle_state")),
        activeFrom =
            getObject("active_from", OffsetDateTime::class.java)?.toInstant(),
        activeUntil =
            getObject("active_until", OffsetDateTime::class.java)?.toInstant(),
        restrictedAccess = getBoolean("restricted_access"),
        addressOrLocationRef = getString("address_or_location_ref"),
        notes = getString("notes"),
        version = getLong("version"),
    )

private fun java.sql.ResultSet.toWarehouseBinding(
    at: Instant,
) =
    WarehouseAuthorityBindingSnapshot(
        bindingId = getObject("id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        warehouseId = getObject("warehouse_id", UUID::class.java),
        authorityKey = WarehouseAuthorityKey.valueOf(getString("authority_key")),
        principalType = InventoryPrincipalType.valueOf(getString("principal_type")),
        principalId = getObject("principal_id", UUID::class.java),
        effectiveFrom =
            getObject("effective_from", OffsetDateTime::class.java).toInstant(),
        effectiveTo =
            getObject("effective_to", OffsetDateTime::class.java)?.toInstant(),
        current =
            getBoolean("active") &&
                !getObject("effective_from", OffsetDateTime::class.java)
                    .toInstant().isAfter(at) &&
                (
                    getObject("effective_to", OffsetDateTime::class.java)
                        ?.toInstant()
                        ?.isAfter(at)
                        ?: true
                ),
        version = getLong("version"),
    )

private fun java.sql.ResultSet.toStorageBinding(
    at: Instant,
) =
    StorageAuthorityBindingSnapshot(
        bindingId = getObject("id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        storageLocationId =
            getObject("storage_location_id", UUID::class.java),
        authorityKey =
            StorageLocationAuthorityKey.valueOf(getString("authority_key")),
        principalType =
            InventoryPrincipalType.valueOf(getString("principal_type")),
        principalId = getObject("principal_id", UUID::class.java),
        effectiveFrom =
            getObject("effective_from", OffsetDateTime::class.java).toInstant(),
        effectiveTo =
            getObject("effective_to", OffsetDateTime::class.java)?.toInstant(),
        current =
            getBoolean("active") &&
                !getObject("effective_from", OffsetDateTime::class.java)
                    .toInstant().isAfter(at) &&
                (
                    getObject("effective_to", OffsetDateTime::class.java)
                        ?.toInstant()
                        ?.isAfter(at)
                        ?: true
                ),
        version = getLong("version"),
    )

private fun java.sql.ResultSet.toImportBatch() =
    InventoryImportBatchSnapshot(
        batchId = getObject("id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        sourceFileName = getString("source_file_name"),
        sourceSha256 = getString("source_sha256"),
        sourceType =
            InventoryImportSourceType.valueOf(getString("source_type")),
        cutoverDate = getObject("cutover_date", LocalDate::class.java),
        state = InventoryImportBatchState.valueOf(getString("state")),
        createdByUserId =
            getObject("created_by_user_id", UUID::class.java),
        createdAt =
            getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt =
            getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        version = getLong("version"),
    )

private fun java.sql.ResultSet.toImportRow(): InventoryImportRowSnapshot {
    val sqlArray: SqlArray? = getArray("issue_codes")
    val issueCodes =
        (sqlArray?.array as? Array<*>)
            ?.mapNotNull { value ->
                value?.toString()?.let(InventoryImportIssueCode::valueOf)
            }
            ?: emptyList()
    sqlArray?.free()

    return InventoryImportRowSnapshot(
        rowId = getObject("id", UUID::class.java),
        batchId = getObject("batch_id", UUID::class.java),
        organizationId = getObject("organization_id", UUID::class.java),
        sourceSheet = getString("source_sheet"),
        sourceRowNumber = getInt("source_row_number"),
        rawPayloadJson = getString("raw_payload_json"),
        candidateClass =
            InventoryCandidateClass.valueOf(getString("candidate_class")),
        normalizedBrand = getString("normalized_brand"),
        normalizedPartNumber = getString("normalized_part_number"),
        normalizedDescription = getString("normalized_description"),
        candidateUom = getString("candidate_uom"),
        candidateQuantity = getBigDecimal("candidate_quantity"),
        candidateValue = getBigDecimal("candidate_value"),
        candidateCurrencyCode = getString("candidate_currency_code"),
        issueCodes = issueCodes,
        reviewState =
            InventoryImportReviewState.valueOf(getString("review_state")),
        reviewNotes = getString("review_notes"),
        reviewedByUserId =
            getObject("reviewed_by_user_id", UUID::class.java),
        reviewedAt =
            getObject("reviewed_at", OffsetDateTime::class.java)?.toInstant(),
        approvedTargetType = getString("approved_target_type"),
        approvedTargetId =
            getObject("approved_target_id", UUID::class.java),
        createdAt =
            getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt =
            getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        version = getLong("version"),
    )
}
