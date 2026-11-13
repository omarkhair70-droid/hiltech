package com.hiltech.server.warehouse

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ReportAssetIncidentRequest(
    val operationId: UUID, val baseVersion: Long, val type: AssetIncidentType, val description: String,
    val evidenceId: UUID? = null,
)
data class ChangeAssetIncidentRequest(
    val operationId: UUID, val baseVersion: Long, val incidentVersion: Long, val state: AssetIncidentState,
    val resolution: String? = null, val investigationOwnerId: UUID? = null,
)
data class RecordAssetCalibrationRequest(
    val operationId: UUID, val baseVersion: Long, val type: String, val result: CalibrationResult,
    val calibratedAt: String, val validUntil: String? = null, val providerRef: String? = null,
    val certificateEvidenceId: UUID? = null, val notes: String? = null,
)
data class SendAssetMaintenanceRequest(
    val operationId: UUID, val baseVersion: Long, val type: AssetServiceType, val description: String, val providerRef: String? = null,
)
data class CompleteAssetMaintenanceRequest(
    val operationId: UUID, val baseVersion: Long, val serviceId: UUID, val serviceVersion: Long, val notes: String,
)

@RestController
class AssetServiceController(private val service: AssetService) {
    @GetMapping("/v1/assets/service-queue")
    fun queue(request: HttpServletRequest, @RequestParam warehouseId: UUID, @RequestParam(required = false) afterCode: String?) =
        service.queue(request.assetActor(), warehouseId, afterCode)

    @GetMapping("/v1/assets/{id}/availability")
    fun availability(request: HttpServletRequest, @PathVariable id: UUID) = service.availability(request.assetActor(), id)

    @GetMapping("/v1/assets/{id}/service-history")
    fun history(request: HttpServletRequest, @PathVariable id: UUID) = service.history(request.assetActor(), id)

    @GetMapping("/v1/assets/{id}/incidents")
    fun incidents(request: HttpServletRequest, @PathVariable id: UUID) = service.incidents(request.assetActor(), id)

    @PostMapping("/v1/assets/{id}/incidents")
    fun report(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReportAssetIncidentRequest) =
        service.report(request.assetContext(body.operationId), id, body)

    @PostMapping("/v1/assets/{id}/incidents/{incidentId}/transition")
    fun transition(
        request: HttpServletRequest, @PathVariable id: UUID, @PathVariable incidentId: UUID, @RequestBody body: ChangeAssetIncidentRequest,
    ) = service.transition(request.assetContext(body.operationId), id, incidentId, body)

    @PostMapping("/v1/assets/{id}/calibrations")
    fun calibrate(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: RecordAssetCalibrationRequest) =
        service.calibrate(request.assetContext(body.operationId), id, body)

    @PostMapping("/v1/assets/{id}/maintenance/send")
    fun send(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: SendAssetMaintenanceRequest) =
        service.send(request.assetContext(body.operationId), id, body)

    @PostMapping("/v1/assets/{id}/maintenance/complete")
    fun complete(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: CompleteAssetMaintenanceRequest) =
        service.complete(request.assetContext(body.operationId), id, body)
}
