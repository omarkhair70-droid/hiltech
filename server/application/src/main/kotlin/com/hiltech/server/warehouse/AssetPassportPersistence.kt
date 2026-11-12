package com.hiltech.server.warehouse

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class AssetCondition { UNKNOWN, GOOD, FAIR, DAMAGED, UNFIT }
enum class AssetCustodyMovement { REGISTER_INITIAL_LOCATION, INITIALIZE_OPENING_CUSTODY, CONTROLLED_CORRECTION }

data class AssetTypeView(
    val id: UUID, val name: String, val revision: Int, val serialRequired: Boolean, val calibrationRequired: Boolean,
    val highValueOrRestricted: Boolean, val allowedTagTypes: List<String>, val operatorMayTag: Boolean,
    val maintenancePolicyId: UUID?, val accessoryTemplateId: UUID?, val inspectionRequiredOnReturn: Boolean,
)
data class AssetIdentityView(
    val id: UUID, val organizationId: UUID, val warehouseId: UUID?, val assetCode: String,
    val name: String?, val manufacturer: String?, val model: String?, val serialNumber: String?,
    val typeId: UUID, val typeName: String, val typeRevision: Int, val condition: AssetCondition,
    val lifecycle: String, val duplicateSerial: Boolean, val sourceImportRowId: UUID?, val version: Long,
)
data class AssetCustodyView(
    val storageLocationId: UUID?, val locationCode: String?, val employeeId: UUID?, val employeeCode: String?,
    val projectId: UUID?, val siteId: UUID?, val workOrderId: UUID?, val sourceMovementId: UUID, val since: String?,
)
data class AssetTagView(
    val id: UUID, val code: String, val type: String, val active: Boolean, val primary: Boolean,
    val replacesTagId: UUID?, val issuedAt: String, val endedAt: String?,
)
data class AssetMovementView(
    val id: UUID, val type: String, val fromLocationId: UUID?, val toLocationId: UUID?,
    val fromEmployeeId: UUID?, val toEmployeeId: UUID?, val projectId: UUID?, val siteId: UUID?, val workOrderId: UUID?,
    val correctedMovementId: UUID?, val sourceImportRowId: UUID?, val condition: String?, val occurredAt: String, val reason: String?,
)
internal data class AssetDestination(
    val locationId: UUID?, val employeeId: UUID?, val projectId: UUID?, val siteId: UUID?, val workOrderId: UUID?,
)
internal data class AssetImportVerification(
    val rowVersion: Long, val digest: String, val warehouseId: UUID, val typeId: UUID,
    val manufacturer: String?, val model: String?, val serial: String?, val locationId: UUID?, val employeeId: UUID?,
    val cutoverAt: Instant?, val verifiedBy: UUID, val approver: UUID, val verifiedAt: Instant, val resolvedIssues: Set<String>,
)

@Repository
class AssetPassportPersistence(private val jdbc: JdbcTemplate) {
    fun asset(id: UUID, lock: Boolean = false): AssetIdentityView? = jdbc.query(
        "$assetSelect WHERE a.id = ?" + if (lock) " FOR NO KEY UPDATE OF a" else "",
        { rs, _ -> rs.asset() }, id,
    ).singleOrNull()

    fun search(warehouseId: UUID, search: String, afterCode: String?): List<AssetIdentityView> = jdbc.query(
        """
        $assetSelect WHERE a.warehouse_id = ? AND a.asset_code > coalesce(?::varchar, '')
          AND (position(lower(?) in lower(concat_ws(' ', a.asset_code, a.display_name, a.manufacturer, a.model,
               a.serial_number, r.name))) > 0 OR EXISTS (
               SELECT 1 FROM asset_tag t WHERE t.asset_id = a.id AND t.active AND t.public_opaque_code = ?
          ))
        ORDER BY a.asset_code LIMIT 100
        """.trimIndent(),
        { rs, _ -> rs.asset() }, warehouseId, afterCode, search, search,
    )

    fun activeTagAsset(code: String): UUID? = jdbc.query(
        "SELECT asset_id FROM asset_tag WHERE public_opaque_code = ? AND active",
        { rs, _ -> rs.uuid("asset_id") }, code,
    ).singleOrNull()

    fun types(organizationId: UUID, at: Instant, id: UUID? = null): List<AssetTypeView> = jdbc.query(
        """
        SELECT r.id, r.name, r.revision_number, t.* FROM config_revision r
        JOIN asset_type_definition t ON t.config_revision_id = r.id
        WHERE r.family = 'asset-types' AND (r.scope_type = 'SYSTEM' OR r.scope_organization_id = ?)
          AND ((?::uuid IS NOT NULL AND r.id = ?) OR (?::uuid IS NULL AND r.lifecycle_state = 'ACTIVE'
               AND (r.effective_from IS NULL OR r.effective_from <= ?) AND (r.effective_to IS NULL OR r.effective_to > ?)))
        ORDER BY r.name, r.id
        """.trimIndent(), { rs, _ -> rs.assetType() }, organizationId, id, id, id, at.utc(), at.utc(),
    )

    internal fun insert(
        id: UUID, org: UUID, warehouseId: UUID, code: String, name: String, type: AssetTypeView,
        manufacturer: String?, model: String?, serial: String?, ownership: String, condition: AssetCondition,
        rowId: UUID?, actor: UUID, now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO asset (id, organization_id, warehouse_id, asset_code, display_name, asset_type_definition_id,
                asset_type_revision, manufacturer, model, serial_number, ownership_type_code, lifecycle_state,
                condition_state, source_import_row_id, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
            """.trimIndent(), id, org, warehouseId, code, name, type.id, type.revision, manufacturer, model, serial,
            ownership, condition.name, rowId, actor, now.utc(),
        )
    }

    fun tags(assetId: UUID): List<AssetTagView> = jdbc.query(
        "SELECT * FROM asset_tag WHERE asset_id = ? ORDER BY issued_at DESC, id DESC",
        { rs, _ -> rs.tag() }, assetId,
    )

    fun tag(assetId: UUID, id: UUID): AssetTagView? = jdbc.query(
        "SELECT * FROM asset_tag WHERE asset_id = ? AND id = ?", { rs, _ -> rs.tag() }, assetId, id,
    ).singleOrNull()

    fun issueTag(assetId: UUID, id: UUID, code: String, type: String, predecessor: UUID?, at: Instant) {
        jdbc.update(
            """
            INSERT INTO asset_tag (id, asset_id, tag_type_code, public_opaque_code, active, is_primary, issued_at, replaced_tag_id)
            VALUES (?, ?, ?, ?, true, true, ?, ?)
            """.trimIndent(), id, assetId, type, code, at.utc(), predecessor,
        )
        jdbc.update("UPDATE asset SET version = version + 1 WHERE id = ?", assetId)
    }

    fun endTag(assetId: UUID, id: UUID, at: Instant): Int = jdbc.update(
        "UPDATE asset_tag SET active = false, is_primary = false, ended_at = ? WHERE asset_id = ? AND id = ? AND active AND is_primary",
        at.utc(), assetId, id,
    )

    fun custody(assetId: UUID): AssetCustodyView? = jdbc.query(
        """
        SELECT c.*, s.code AS location_code, e.employee_code FROM asset_custody_projection c
        LEFT JOIN storage_location s ON s.id = c.current_storage_location_id
        LEFT JOIN employee e ON e.id = c.current_custodian_target_id AND c.current_custodian_target_type = 'EMPLOYEE'
        WHERE c.asset_id = ?
        """.trimIndent(), { rs, _ ->
            AssetCustodyView(rs.optionalUuid("current_storage_location_id"), rs.getString("location_code"),
                rs.optionalUuid("current_custodian_target_id").takeIf { rs.getString("current_custodian_target_type") == "EMPLOYEE" },
                rs.getString("employee_code"), rs.optionalUuid("current_project_id"), rs.optionalUuid("current_site_id"),
                rs.optionalUuid("current_work_order_id"), rs.uuid("source_movement_id"), rs.instant("custody_started_at")?.toString())
        }, assetId,
    ).singleOrNull()

    fun movements(assetId: UUID): List<AssetMovementView> = jdbc.query(
        "SELECT * FROM asset_movement WHERE asset_id = ? ORDER BY ledger_version DESC NULLS LAST, recorded_at DESC, id DESC LIMIT 100",
        { rs, _ ->
            AssetMovementView(rs.uuid("id"), rs.getString("movement_type"), rs.optionalUuid("from_storage_location_id"),
                rs.optionalUuid("to_storage_location_id"),
                rs.optionalUuid("from_custodian_target_id").takeIf { rs.getString("from_custodian_target_type") == "EMPLOYEE" },
                rs.optionalUuid("to_custodian_target_id").takeIf { rs.getString("to_custodian_target_type") == "EMPLOYEE" },
                rs.optionalUuid("project_id"), rs.optionalUuid("site_id"), rs.optionalUuid("work_order_id"),
                rs.optionalUuid("correction_of_movement_id"), rs.optionalUuid("source_import_row_id"),
                rs.getString("condition_at_transfer"), requireNotNull(rs.instant("occurred_at")).toString(), rs.getString("notes"))
        }, assetId,
    )

    fun employeeActive(org: UUID, employeeId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM employee WHERE id = ? AND organization_id = ? AND state = 'ACTIVE')",
        Boolean::class.java, employeeId, org,
    ) == true

    fun employeeIdentities(org: UUID, employeeId: UUID): List<UUID> = jdbc.query(
        """
        SELECT u.id FROM employee e JOIN user_identity u ON u.person_id = e.person_id
        WHERE e.id = ? AND e.organization_id = ?
        """.trimIndent(), { rs, _ -> rs.uuid("id") }, employeeId, org,
    )

    internal fun employeeWork(org: UUID, employee: UUID, workId: UUID): AssetDestination? = jdbc.query(
        """
        SELECT project_id, site_id FROM work_order WHERE id = ? AND organization_id = ?
          AND lifecycle_state NOT IN ('CLOSED', 'CANCELLED')
        """.trimIndent(), { rs, _ ->
            AssetDestination(null, employee, rs.uuid("project_id"), rs.uuid("site_id"), workId)
        }, workId, org,
    ).singleOrNull()

    internal fun verification(rowId: UUID, lock: Boolean = false): AssetImportVerification? = jdbc.query(
        "SELECT * FROM asset_import_verification WHERE source_row_id = ?" + if (lock) " FOR SHARE" else "",
        { rs, _ ->
            AssetImportVerification(rs.getLong("source_row_version"), rs.getString("source_sha256"),
                rs.uuid("warehouse_id"), rs.uuid("asset_type_id"), rs.getString("manufacturer"), rs.getString("model"),
                rs.getString("verified_serial"), rs.optionalUuid("opening_location_id"), rs.optionalUuid("opening_employee_id"),
                rs.instant("cutover_at"), rs.uuid("verified_by"), rs.uuid("approver_id"), requireNotNull(rs.instant("verified_at")),
                rs.strings("resolved_issue_codes").toSet())
        }, rowId,
    ).singleOrNull()

    fun approveImport(rowId: UUID, assetId: UUID, actor: UUID, at: Instant, version: Long): Int = jdbc.update(
        """
        UPDATE inventory_import_row SET review_state = 'APPROVED', approved_target_type = 'ASSET',
            approved_target_id = ?, reviewed_by_user_id = ?, reviewed_at = ?, updated_at = ?, version = version + 1
        WHERE id = ? AND version = ? AND review_state = 'NEEDS_REVIEW'
        """.trimIndent(), assetId, actor, at.utc(), at.utc(), rowId, version,
    )

    internal fun append(
        asset: AssetIdentityView, context: AssetCommandContext, kind: AssetCustodyMovement, prior: AssetCustodyView?,
        target: AssetDestination, condition: AssetCondition, effectiveAt: Instant, now: Instant, reason: String?, source: UUID?,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO asset_movement (id, asset_id, organization_id, movement_type, from_storage_location_id,
                to_storage_location_id, from_custodian_target_type, from_custodian_target_id, to_custodian_target_type,
                to_custodian_target_id, project_id, site_id, work_order_id, occurred_at, recorded_at, recorded_by,
                source_operation_id, condition_at_transfer, reason_code, notes, correction_of_movement_id,
                correlation_id, source_import_row_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), id, asset.id, asset.organizationId, kind.name, prior?.storageLocationId,
            target.locationId, prior?.employeeId?.let { "EMPLOYEE" }, prior?.employeeId, target.employeeId?.let { "EMPLOYEE" },
            target.employeeId, target.projectId, target.siteId, target.workOrderId, effectiveAt.utc(), now.utc(),
            context.actor, context.operationId, condition.name, kind.name, reason, prior?.sourceMovementId,
            context.correlationId, source,
        )
        return id
    }

    private fun ResultSet.asset() = AssetIdentityView(uuid("id"), uuid("organization_id"), optionalUuid("warehouse_id"),
        getString("asset_code"), getString("display_name"), getString("manufacturer"), getString("model"), getString("serial_number"),
        uuid("asset_type_definition_id"), getString("type_name"), getInt("asset_type_revision"),
        AssetCondition.valueOf(getString("condition_state")), getString("lifecycle_state"), getBoolean("duplicate_serial"),
        optionalUuid("source_import_row_id"), getLong("version"))

    private fun ResultSet.assetType() = AssetTypeView(uuid("id"), getString("name"), getInt("revision_number"),
        getBoolean("serial_required"), getBoolean("calibration_required"), getBoolean("high_value_or_restricted"),
        strings("allowed_tag_types"), getBoolean("operator_may_tag"), optionalUuid("maintenance_policy_id"),
        optionalUuid("accessory_template_id"), getBoolean("inspection_required_on_return"))

    private fun ResultSet.tag() = AssetTagView(uuid("id"), getString("public_opaque_code"), getString("tag_type_code"),
        getBoolean("active"), getBoolean("is_primary"), optionalUuid("replaced_tag_id"), requireNotNull(instant("issued_at")).toString(),
        instant("ended_at")?.toString())

    private fun ResultSet.strings(column: String): List<String> {
        val values = getArray(column)
        return try { (values.array as Array<*>).filterIsInstance<String>() } finally { values.free() }
    }

    private fun ResultSet.uuid(column: String) = requireNotNull(optionalUuid(column))
    private fun ResultSet.optionalUuid(column: String): UUID? = getObject(column, UUID::class.java)
    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private val assetSelect = """
        SELECT a.id, a.organization_id, a.warehouse_id, a.asset_code, a.display_name, a.manufacturer, a.model,
            a.serial_number, a.asset_type_definition_id, a.asset_type_revision, a.condition_state, a.lifecycle_state,
            a.source_import_row_id, a.version, r.name AS type_name, EXISTS (SELECT 1 FROM asset other
            WHERE other.organization_id = a.organization_id AND other.id <> a.id
              AND lower(btrim(other.serial_number)) = lower(btrim(a.serial_number))) AS duplicate_serial
        FROM asset a JOIN config_revision r ON r.id = a.asset_type_definition_id
    """.trimIndent()
}
