package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AssetRegistrationRequest(
    val operationId: UUID, val warehouseId: UUID, val assetTypeId: UUID, val assetCode: String, val name: String,
    val manufacturer: String? = null, val model: String? = null, val serialNumber: String? = null,
    val ownershipType: String, val condition: AssetCondition, val initialLocationId: UUID? = null,
)
data class IssueAssetTagRequest(val operationId: UUID, val baseVersion: Long, val tagType: String)
data class ReplaceAssetTagRequest(val operationId: UUID, val baseVersion: Long, val tagType: String, val previousTagId: UUID)
data class OpenAssetCustodyRequest(
    val operationId: UUID, val baseVersion: Long, val condition: AssetCondition,
    val locationId: UUID? = null, val employeeId: UUID? = null, val workOrderId: UUID? = null,
    val cutoverAt: String? = null,
)
data class CorrectAssetCustodyRequest(
    val operationId: UUID, val baseVersion: Long, val condition: AssetCondition, val sourceMovementId: UUID, val reason: String,
    val locationId: UUID? = null, val employeeId: UUID? = null, val workOrderId: UUID? = null,
)
data class ApproveAssetImportRequest(
    val operationId: UUID, val baseVersion: Long, val assetCode: String, val name: String,
    val ownershipType: String, val condition: AssetCondition,
)

@RestController
class AssetPassportController(private val service: AssetPassportService) {
    @GetMapping("/v1/asset-types")
    fun types(request: HttpServletRequest, @RequestParam warehouseId: UUID) = service.types(request.assetActor(), warehouseId)

    @GetMapping("/v1/assets")
    fun directory(
        request: HttpServletRequest, @RequestParam warehouseId: UUID,
        @RequestParam(required = false) search: String?, @RequestParam(required = false) afterCode: String?,
    ) = service.directory(request.assetActor(), warehouseId, search.orEmpty(), afterCode)

    @PostMapping("/v1/assets")
    fun register(request: HttpServletRequest, @RequestBody body: AssetRegistrationRequest) =
        service.register(request.assetContext(body.operationId), body)

    @GetMapping("/v1/assets/{id}")
    fun passport(request: HttpServletRequest, @PathVariable id: UUID) = service.passport(request.assetActor(), id)

    @GetMapping("/v1/assets/by-tag/{opaqueCode}")
    fun lookup(request: HttpServletRequest, @PathVariable opaqueCode: String): ResponseEntity<AssetTagLookupView> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lookup(request.assetActor(), opaqueCode))

    @PostMapping("/v1/assets/{id}/tags")
    fun issue(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: IssueAssetTagRequest) =
        service.tag(request.assetContext(body.operationId), id, body.baseVersion, body.tagType, null)

    @PostMapping("/v1/assets/{id}/replace-tag")
    fun replace(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReplaceAssetTagRequest) =
        service.tag(request.assetContext(body.operationId), id, body.baseVersion, body.tagType, body.previousTagId)

    @PostMapping("/v1/assets/{id}/opening-custody")
    fun opening(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: OpenAssetCustodyRequest) =
        service.opening(request.assetContext(body.operationId), id, body)

    @PostMapping("/v1/assets/{id}/correct-custody")
    fun correct(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: CorrectAssetCustodyRequest) =
        service.correct(request.assetContext(body.operationId), id, body)

    @GetMapping("/v1/assets/{id}/movements")
    fun movements(request: HttpServletRequest, @PathVariable id: UUID) = service.movements(request.assetActor(), id)

    @PostMapping("/v1/inventory-imports/{batchId}/rows/{rowId}/approve-asset")
    fun approve(
        request: HttpServletRequest, @PathVariable batchId: UUID, @PathVariable rowId: UUID, @RequestBody body: ApproveAssetImportRequest,
    ) = service.approve(request.assetContext(body.operationId), batchId, rowId, body)
}

internal fun HttpServletRequest.assetActor(): UUID =
    HiltechRequestContext.current(this).identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)

internal fun HttpServletRequest.assetContext(operationId: UUID): AssetCommandContext {
    val actor = assetActor()
    val context = HiltechRequestContext.current(this)
    IdempotencyKeyContract.requireMatches(context.idempotencyKey, operationId)
    return AssetCommandContext(operationId, actor, context.correlationId)
}
