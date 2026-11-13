package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AssetServiceChanged(
    val assetId: UUID, val organizationId: UUID, val sourceId: UUID, val action: String,
    val version: Long, val actorUserId: UUID, val correlationId: String, val occurredAt: Instant,
)

@Service
class AssetService(
    private val passports: AssetPassportService,
    private val assets: AssetPassportPersistence,
    private val records: AssetServicePersistence,
    private val authorization: WarehouseAuthorizationPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
) {
    fun queue(actor: UUID, warehouseId: UUID, afterCode: String?): List<AssetServiceQueueEntry> {
        passports.viewableWarehouse(actor, warehouseId)
        return records.queue(warehouseId, afterCode?.take(64), clock.instant())
    }

    fun availability(actor: UUID, id: UUID): AssetAvailabilityView {
        readable(actor, id)
        return evaluate(id)
    }

    fun incidents(actor: UUID, id: UUID): List<AssetIncidentView> {
        detailAccess(actor, id)
        return records.incidents(id)
    }

    fun history(actor: UUID, id: UUID): AssetServiceDetail {
        val asset = readable(actor, id)
        val facility = passports.facility(asset)
        val manage = authorization.canManageAssetService(actor, facility)
        val operate = authorization.canOperateAssetService(actor, facility)
        val active = asset.lifecycle == "ACTIVE"
        return AssetServiceDetail(evaluate(id), if (operate) records.incidents(id) else emptyList(),
            if (operate) records.calibrations(id) else emptyList(), if (operate) records.maintenance(id) else emptyList(),
            AssetServiceCapabilities(active && operate, active && operate, active && manage))
    }

    fun report(context: AssetCommandContext, id: UUID, request: ReportAssetIncidentRequest): AssetIncidentView {
        val source = commandId(context)
        val description = serviceText(request.description, "Incident description")
        val permit: (AssetIdentityView) -> Boolean = { asset ->
            authorization.canOperateAssetService(context.actor, passports.facility(asset)) ||
                passports.isCustodian(context.actor, asset, assets.custody(id),
                    if (request.type == AssetIncidentType.DAMAGE) "can_report_damage" else "can_report_missing")
        }
        change(context, id, source, request.baseVersion, "ASSET_INCIDENT_REPORTED", permit,
            request.type, description, request.evidenceId) { asset, at ->
            requireEvidence(asset, request.evidenceId)
            records.report(source, asset, request, description, assets.custody(id), context, at)
        }
        return requireNotNull(records.incident(id, source))
    }

    fun transition(context: AssetCommandContext, id: UUID, incident: UUID, request: ChangeAssetIncidentRequest): AssetIncidentView {
        val reason = request.resolution?.let { serviceText(it, "Resolution") }
        if ((request.state == AssetIncidentState.RESOLVED) != (reason != null) ||
            (request.investigationOwnerId != null && request.state != AssetIncidentState.UNDER_REVIEW) ||
            request.state == AssetIncidentState.OPEN) throw invalid("Select a valid incident transition and supply the required resolution.")
        change(context, id, incident, request.baseVersion, "ASSET_INCIDENT_" + request.state.name, manager(context.actor),
            request.incidentVersion, reason, request.investigationOwnerId) { asset, at ->
            val current = records.incident(id, incident) ?: throw unavailable()
            if (current.version != request.incidentVersion) throw conflict("ASSET_INCIDENT_VERSION_CONFLICT", "Reload the incident.")
            if (request.investigationOwnerId?.let { assets.employeeActive(asset.organizationId, it) } == false) throw unavailable()
            if (records.transitionIncident(id, current, request, reason, context.actor, at) != 1) {
                throw conflict("ASSET_INCIDENT_TRANSITION_CONFLICT", "The incident cannot move to that state.")
            }
        }
        return requireNotNull(records.incident(id, incident))
    }

    fun calibrate(context: AssetCommandContext, id: UUID, request: RecordAssetCalibrationRequest): AssetCalibrationView {
        val source = commandId(context)
        val type = serviceText(request.type, "Calibration type", 64)
        val provider = request.providerRef?.let { serviceText(it, "Provider reference", 160) }
        val notes = request.notes?.let { serviceText(it, "Calibration notes") }
        val calibrated = serviceInstant(request.calibratedAt)
        val valid = request.validUntil?.let(::serviceInstant)
        if ((request.result == CalibrationResult.PASSED) != (valid != null) || (valid != null && valid <= calibrated)) {
            throw invalid("A passing calibration requires a validity end after the calibration. A failed record has no validity end.")
        }
        change(context, id, source, request.baseVersion, "ASSET_CALIBRATION_RECORDED", manager(context.actor),
            type, provider, notes, calibrated, valid, request.result, request.certificateEvidenceId) { asset, at ->
            if (calibrated > at) throw invalid("Calibration must have already occurred.")
            requireEvidence(asset, request.certificateEvidenceId)
            records.recordCalibration(source, asset, request, type, provider, notes, calibrated, valid, context.actor, at)
        }
        return requireNotNull(records.calibration(id, source))
    }

    fun send(context: AssetCommandContext, id: UUID, request: SendAssetMaintenanceRequest): AssetMaintenanceView {
        val source = commandId(context)
        val description = serviceText(request.description, "Service description")
        val provider = request.providerRef?.let { serviceText(it, "Provider reference", 160) }
        change(context, id, source, request.baseVersion, "ASSET_SERVICE_SENT",
            { authorization.canOperateAssetService(context.actor, passports.facility(it)) },
            request.type, description, provider) { asset, at ->
            records.send(source, asset, request.type, description, provider, context.actor, at)
        }
        return requireNotNull(records.service(id, source))
    }

    fun complete(context: AssetCommandContext, id: UUID, request: CompleteAssetMaintenanceRequest): AssetMaintenanceView {
        val notes = serviceText(request.notes, "Completion notes")
        change(context, id, request.serviceId, request.baseVersion, "ASSET_SERVICE_COMPLETED", manager(context.actor),
            request.serviceVersion, notes) { _, at ->
            val current = records.service(id, request.serviceId) ?: throw unavailable()
            if (records.complete(id, current.id, request.serviceVersion, notes, context.actor, at) != 1) {
                throw conflict("ASSET_SERVICE_VERSION_CONFLICT", "Reload the current service record.")
            }
        }
        return requireNotNull(records.service(id, request.serviceId))
    }

    private fun readable(actor: UUID, id: UUID): AssetIdentityView {
        val asset = assets.asset(id) ?: throw unavailable()
        passports.viewableWarehouse(actor, passports.facility(asset).warehouseId)
        return asset
    }

    private fun detailAccess(actor: UUID, id: UUID) {
        val asset = readable(actor, id)
        if (!authorization.canOperateAssetService(actor, passports.facility(asset))) throw unavailable()
    }

    private fun evaluate(id: UUID) = records.availability(id, clock.instant()) ?: throw unavailable()

    private fun manager(actor: UUID): (AssetIdentityView) -> Boolean =
        { authorization.canManageAssetService(actor, passports.facility(it)) }

    private fun requireEvidence(asset: AssetIdentityView, id: UUID?) {
        if (id != null && !records.hasAssetEvidence(asset, id)) throw unavailable()
    }

    private fun change(
        context: AssetCommandContext, id: UUID, source: UUID, baseVersion: Long, action: String,
        permit: (AssetIdentityView) -> Boolean, vararg fingerprint: Any?, write: (AssetIdentityView, Instant) -> Unit,
    ) {
        val visible = assets.asset(id) ?: throw unavailable()
        if (!permit(visible)) throw unavailable()
        try {
            commands.execute(IdempotentCommandSpec(context.operationId, context.actor, action, "ASSET", id,
                warehouseCommandFingerprint(source, baseVersion, *fingerprint), context.correlationId)) {
                val locked = assets.asset(id, lock = true)?.takeIf { it.lifecycle == "ACTIVE" } ?: throw unavailable()
                if (!permit(locked)) throw unavailable()
                if (locked.version != baseVersion) throw conflict("ASSET_VERSION_CONFLICT", "Reload the Asset Passport.")
                val at = clock.instant()
                write(locked, at)
                records.advanceVersion(id)
                audit.append(AuditEventRecord(actorUserId = context.actor, action = action, targetType = "ASSET", targetId = id,
                    newStateRef = source.toString(), occurredAt = at, correlationId = context.correlationId))
                events.publishEvent(AssetServiceChanged(id, locked.organizationId, source, action,
                    locked.version + 1, context.actor, context.correlationId, at))
                IdempotentCommandOutcome(action, """{"sourceId":"$source"}""")
            }
        } catch (_: DataIntegrityViolationException) {
            throw conflict("ASSET_SERVICE_CONSTRAINT_CONFLICT", "The record conflicts with current Asset service history.")
        }
    }

    private fun commandId(context: AssetCommandContext): UUID =
        UUID.nameUUIDFromBytes("hiltech:asset-service:${context.operationId}".toByteArray(Charsets.UTF_8))
}

private fun serviceText(value: String, label: String, maximum: Int = 2000): String =
    value.trim().takeIf { it.isNotEmpty() && it.length <= maximum } ?: throw invalid("$label is invalid.")

private fun serviceInstant(value: String): Instant =
    runCatching { Instant.parse(value).truncatedTo(ChronoUnit.MICROS) }
        .getOrElse { throw invalid("Enter a timestamp with an offset.") }

private fun unavailable() = ProductApiException("ASSET_UNAVAILABLE", "Asset or service record is unavailable.", HttpStatus.NOT_FOUND)
private fun invalid(message: String) = ProductApiException("INVALID_ASSET_SERVICE_COMMAND", message, HttpStatus.BAD_REQUEST)
private fun conflict(code: String, message: String) = ProductApiException(code, message, HttpStatus.CONFLICT)
