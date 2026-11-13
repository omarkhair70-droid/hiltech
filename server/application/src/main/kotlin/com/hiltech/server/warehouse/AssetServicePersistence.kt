package com.hiltech.server.warehouse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class AssetIncidentType { DAMAGE, MISSING, LOST }
enum class AssetIncidentState { OPEN, UNDER_REVIEW, RESOLVED, CLOSED }
enum class CalibrationResult { PASSED, FAILED }
enum class AssetServiceType { MAINTENANCE, CALIBRATION }

data class AssetAvailabilityReason(
    val state: String, val code: String, val sourceType: String, val sourceId: UUID?,
    val sourceVersion: Long?, val dueAt: String?,
)
data class AssetAvailabilityView(
    val state: String, val assetVersion: Long, val evaluatedAt: String, val reasons: List<AssetAvailabilityReason>,
    val calibrationStatus: String, val calibrationDueAt: String?, val certificateOnFile: Boolean,
    val maintenanceStatus: String, val maintenanceDueAt: String?,
)
data class SafeAssetAvailabilityView(
    val state: String, val reasonCodes: List<String>, val calibrationStatus: String, val calibrationDueAt: String?,
    val certificateOnFile: Boolean, val incidentTypes: List<String>,
)
data class AssetServiceQueueEntry(val id: UUID, val assetCode: String, val name: String?, val availability: AssetAvailabilityView)
data class AssetIncidentView(
    val id: UUID, val type: AssetIncidentType, val state: AssetIncidentState, val description: String,
    val reportedBy: UUID, val reportedAt: String, val projectId: UUID?, val siteId: UUID?, val workOrderId: UUID?,
    val evidenceOnFile: Boolean, val investigationOwnerId: UUID?, val resolution: String?, val resolvedBy: UUID?,
    val resolvedAt: String?, val closedBy: UUID?, val closedAt: String?, val version: Long,
)
data class AssetCalibrationView(
    val id: UUID, val type: String, val result: CalibrationResult, val calibratedAt: String, val validUntil: String?,
    val providerRef: String?, val certificateOnFile: Boolean, val notes: String?, val recordedBy: UUID, val recordedAt: String,
)
data class AssetMaintenanceView(
    val id: UUID, val type: AssetServiceType, val state: String, val description: String, val providerRef: String?,
    val sentBy: UUID, val sentAt: String, val completedBy: UUID?, val completedAt: String?,
    val completionNotes: String?, val version: Long,
)
data class AssetServiceCapabilities(val canReport: Boolean, val canSend: Boolean, val canManageService: Boolean)
data class AssetServiceDetail(
    val availability: AssetAvailabilityView, val incidents: List<AssetIncidentView>, val calibrations: List<AssetCalibrationView>,
    val maintenance: List<AssetMaintenanceView>, val capabilities: AssetServiceCapabilities,
)

@Repository
class AssetServicePersistence(private val jdbc: JdbcTemplate) {
    fun availability(assetId: UUID, at: Instant): AssetAvailabilityView? =
        readAvailability("a.id = ?", "", at, assetId).singleOrNull()?.availability

    fun queue(warehouseId: UUID, afterCode: String?, at: Instant): List<AssetServiceQueueEntry> = readAvailability(
        "a.warehouse_id = ? AND a.asset_code > coalesce(?::varchar, '')",
        "WHERE q.state <> 'AVAILABLE' OR q.calibration_status = 'DUE' OR q.maintenance_status <> 'NONE' ORDER BY q.asset_code LIMIT 100",
        at, warehouseId, afterCode,
    )

    private fun readAvailability(
        filter: String, page: String, at: Instant, vararg parameters: Any?,
    ): List<AssetServiceQueueEntry> = jdbc.query(
        """
        WITH evaluation AS (SELECT ?::timestamptz AS at), passports AS (
            SELECT a.id, a.asset_code, a.display_name, a.version, e.at, a.created_at,
                t.calibration_required, t.calibration_due_lead_days, policy.interval_days
            FROM asset a CROSS JOIN evaluation e
            LEFT JOIN asset_type_definition t ON t.config_revision_id = a.asset_type_definition_id
            LEFT JOIN asset_maintenance_policy policy ON policy.config_revision_id = t.maintenance_policy_id
            WHERE $filter
        ), queue AS (
            SELECT a.*, coalesce(reasons.first_state, 'AVAILABLE') AS state, reasons.entries,
                c.valid_until AS calibration_due_at, c.certificate_evidence_id IS NOT NULL AS certificate_on_file,
                CASE WHEN service.service_type = 'CALIBRATION' THEN 'IN_PROGRESS'
                    WHEN a.calibration_required IS NULL THEN 'POLICY_UNAVAILABLE'
                    WHEN NOT a.calibration_required THEN 'NOT_REQUIRED'
                    WHEN c.id IS NULL THEN 'MISSING' WHEN c.result = 'FAILED' THEN 'FAILED'
                    WHEN c.valid_until <= a.at THEN 'EXPIRED'
                    WHEN c.valid_until <= a.at + a.calibration_due_lead_days * interval '24 hours' THEN 'DUE'
                    ELSE 'VALID' END AS calibration_status,
                schedule.due AS maintenance_due_at,
                CASE WHEN service.service_type = 'MAINTENANCE' THEN 'IN_PROGRESS'
                     WHEN schedule.due <= a.at THEN 'DUE' ELSE 'NONE' END AS maintenance_status
            FROM passports a
            LEFT JOIN LATERAL (
                SELECT (array_agg(r.state ORDER BY r.rank, r.source_id))[1] AS first_state,
                    json_agg(json_build_object('state', r.state, 'code', r.code, 'sourceType', r.source_type,
                        'sourceId', r.source_id, 'sourceVersion', r.source_version, 'dueAt', r.due_at)
                        ORDER BY r.rank, r.source_id) AS entries
                FROM asset_availability_reasons(a.id, a.at) r
            ) reasons ON true
            LEFT JOIN LATERAL (
                SELECT id, result, valid_until, certificate_evidence_id FROM asset_calibration_record
                WHERE asset_id = a.id AND calibrated_at <= a.at ORDER BY calibrated_at DESC LIMIT 1
            ) c ON true
            LEFT JOIN asset_maintenance_record service ON service.asset_id = a.id AND service.state = 'IN_PROGRESS'
            LEFT JOIN LATERAL (
                SELECT completed_at FROM asset_maintenance_record
                WHERE asset_id = a.id AND service_type = 'MAINTENANCE' AND state = 'COMPLETED'
                ORDER BY completed_at DESC, id DESC LIMIT 1
            ) last_service ON true
            CROSS JOIN LATERAL (
                SELECT coalesce(last_service.completed_at, a.created_at) + a.interval_days * interval '24 hours' AS due
            ) schedule
        )
        SELECT * FROM queue q $page
        """.trimIndent(), { rs, _ ->
            val reasons = rs.getString("entries")?.let(::decodeAvailabilityReasons).orEmpty()
            AssetServiceQueueEntry(rs.uuid("id"), rs.getString("asset_code"), rs.getString("display_name"),
                AssetAvailabilityView(rs.getString("state"), rs.getLong("version"), requireNotNull(rs.time("at")),
                    reasons, rs.getString("calibration_status"), rs.time("calibration_due_at"), rs.getBoolean("certificate_on_file"),
                    rs.getString("maintenance_status"), rs.time("maintenance_due_at")))
        }, at.utc(), *parameters,
    )

    fun incidents(assetId: UUID): List<AssetIncidentView> = jdbc.query(
        """SELECT * FROM asset_incident WHERE asset_id = ?
            ORDER BY (state IN ('OPEN','UNDER_REVIEW')) DESC, reported_at DESC, id DESC LIMIT 100""",
        { rs, _ -> rs.incident() }, assetId,
    )

    fun incident(assetId: UUID, id: UUID): AssetIncidentView? = jdbc.query(
        "SELECT * FROM asset_incident WHERE asset_id = ? AND id = ?",
        { rs, _ -> rs.incident() }, assetId, id,
    ).singleOrNull()

    fun calibrations(assetId: UUID): List<AssetCalibrationView> = jdbc.query(
        "SELECT * FROM asset_calibration_record WHERE asset_id = ? ORDER BY calibrated_at DESC LIMIT 100",
        { rs, _ -> rs.calibration() }, assetId,
    )

    fun calibration(assetId: UUID, id: UUID): AssetCalibrationView? = jdbc.query(
        "SELECT * FROM asset_calibration_record WHERE asset_id = ? AND id = ?",
        { rs, _ -> rs.calibration() }, assetId, id,
    ).singleOrNull()

    fun maintenance(assetId: UUID): List<AssetMaintenanceView> = jdbc.query(
        """SELECT * FROM asset_maintenance_record WHERE asset_id = ?
            ORDER BY (state = 'IN_PROGRESS') DESC, sent_at DESC, id DESC LIMIT 100""",
        { rs, _ -> rs.maintenance() }, assetId,
    )

    fun service(assetId: UUID, id: UUID): AssetMaintenanceView? = jdbc.query(
        "SELECT * FROM asset_maintenance_record WHERE asset_id = ? AND id = ?",
        { rs, _ -> rs.maintenance() }, assetId, id,
    ).singleOrNull()

    fun report(
        id: UUID, asset: AssetIdentityView, request: ReportAssetIncidentRequest, description: String,
        custody: AssetCustodyView?, context: AssetCommandContext, at: Instant,
    ) {
        jdbc.update(
            """INSERT INTO asset_incident (id, asset_id, organization_id, incident_type, state, description,
                reported_by, reported_at, project_id, site_id, work_order_id, evidence_id)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?)""",
            id, asset.id, asset.organizationId, request.type.name, description, context.actor, at.utc(),
            custody?.projectId, custody?.siteId, custody?.workOrderId, request.evidenceId,
        )
    }

    fun transitionIncident(
        id: UUID, current: AssetIncidentView, request: ChangeAssetIncidentRequest,
        reason: String?, actor: UUID, at: Instant,
    ): Int = when (request.state) {
        AssetIncidentState.UNDER_REVIEW -> jdbc.update(
            """UPDATE asset_incident SET state = 'UNDER_REVIEW', investigation_owner_id = ?, version = version + 1
                WHERE asset_id = ? AND id = ? AND state = 'OPEN' AND version = ?""",
            request.investigationOwnerId, id, current.id, current.version,
        )
        AssetIncidentState.RESOLVED -> jdbc.update(
            """UPDATE asset_incident SET state = 'RESOLVED', resolution = ?, resolved_by = ?, resolved_at = ?, version = version + 1
                WHERE asset_id = ? AND id = ? AND state IN ('OPEN','UNDER_REVIEW') AND version = ?""",
            reason, actor, at.utc(), id, current.id, current.version,
        )
        AssetIncidentState.CLOSED -> jdbc.update(
            """UPDATE asset_incident SET state = 'CLOSED', closed_by = ?, closed_at = ?, version = version + 1
                WHERE asset_id = ? AND id = ? AND state = 'RESOLVED' AND version = ?""",
            actor, at.utc(), id, current.id, current.version,
        )
        AssetIncidentState.OPEN -> 0
    }

    fun recordCalibration(
        id: UUID, asset: AssetIdentityView, request: RecordAssetCalibrationRequest,
        type: String, provider: String?, notes: String?, calibrated: Instant, validUntil: Instant?, actor: UUID, at: Instant,
    ) {
        jdbc.update(
            """INSERT INTO asset_calibration_record (id, asset_id, organization_id, calibration_type, provider_ref,
                certificate_evidence_id, result, calibrated_at, valid_until, notes, recorded_by, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, asset.id, asset.organizationId, type, provider, request.certificateEvidenceId,
            request.result.name, calibrated.utc(), validUntil?.utc(), notes, actor, at.utc(),
        )
    }

    fun send(
        id: UUID, asset: AssetIdentityView, type: AssetServiceType, description: String, provider: String?, actor: UUID, at: Instant,
    ) {
        jdbc.update(
            """INSERT INTO asset_maintenance_record (id, asset_id, organization_id, service_type, state, description,
                provider_ref, sent_by, sent_at) VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)""",
            id, asset.id, asset.organizationId, type.name, description, provider, actor, at.utc(),
        )
    }

    fun complete(asset: UUID, service: UUID, version: Long, notes: String, actor: UUID, at: Instant): Int = jdbc.update(
        """UPDATE asset_maintenance_record SET state = 'COMPLETED', completed_by = ?, completed_at = ?,
            completion_notes = ?, version = version + 1 WHERE asset_id = ? AND id = ? AND version = ? AND state = 'IN_PROGRESS'""",
        actor, at.utc(), notes, asset, service, version,
    )

    fun advanceVersion(id: UUID) {
        jdbc.update("UPDATE asset SET version = version + 1 WHERE id = ?", id)
    }

    fun hasAssetEvidence(asset: AssetIdentityView, evidenceId: UUID): Boolean = jdbc.queryForObject(
        """SELECT EXISTS (SELECT 1 FROM evidence WHERE id = ? AND organization_id = ? AND target_type = 'ASSET'
            AND target_id = ? AND storage_state = 'READY')""",
        Boolean::class.java, evidenceId, asset.organizationId, asset.id,
    ) == true

    private fun decodeAvailabilityReasons(encoded: String): List<AssetAvailabilityReason> =
        Json.parseToJsonElement(encoded).jsonArray.map { reason ->
            val fields = reason.jsonObject
            fun field(key: String) = fields.getValue(key).jsonPrimitive.contentOrNull
            AssetAvailabilityReason(requireNotNull(field("state")), requireNotNull(field("code")),
                requireNotNull(field("sourceType")), field("sourceId")?.let(UUID::fromString),
                field("sourceVersion")?.toLong(), field("dueAt")?.let { OffsetDateTime.parse(it).toInstant().toString() })
        }

    private fun ResultSet.incident() = AssetIncidentView(uuid("id"), AssetIncidentType.valueOf(getString("incident_type")),
        AssetIncidentState.valueOf(getString("state")), getString("description"), uuid("reported_by"), requireNotNull(time("reported_at")),
        optUuid("project_id"), optUuid("site_id"), optUuid("work_order_id"), optUuid("evidence_id") != null,
        optUuid("investigation_owner_id"), getString("resolution"), optUuid("resolved_by"), time("resolved_at"),
        optUuid("closed_by"), time("closed_at"), getLong("version"))

    private fun ResultSet.calibration() = AssetCalibrationView(uuid("id"), getString("calibration_type"),
        CalibrationResult.valueOf(getString("result")), requireNotNull(time("calibrated_at")), time("valid_until"),
        getString("provider_ref"), optUuid("certificate_evidence_id") != null, getString("notes"),
        uuid("recorded_by"), requireNotNull(time("recorded_at")))

    private fun ResultSet.maintenance() = AssetMaintenanceView(uuid("id"), AssetServiceType.valueOf(getString("service_type")),
        getString("state"), getString("description"), getString("provider_ref"), uuid("sent_by"), requireNotNull(time("sent_at")),
        optUuid("completed_by"), time("completed_at"), getString("completion_notes"), getLong("version"))

    private fun ResultSet.uuid(column: String) = requireNotNull(optUuid(column))
    private fun ResultSet.optUuid(column: String): UUID? = getObject(column, UUID::class.java)
    private fun ResultSet.time(column: String): String? = getObject(column, OffsetDateTime::class.java)?.toInstant()?.toString()
    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
