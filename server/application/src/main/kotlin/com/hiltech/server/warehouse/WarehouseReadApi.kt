package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.HiltechRequestContextSnapshot
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Phase 5 / Slice 01: read-only warehouse and import-preparation context.
 *
 * Reads never create stock, custody, or authoritative import results. The
 * authorization port checks current source authority as well as OpenFGA.
 */
@Component
class WarehouseReadService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
) {
    fun warehouses(actorUserId: UUID, organizationId: UUID): List<WarehouseSnapshot> =
        persistence.warehouses(organizationId)
            .filter { it.organizationId == organizationId }
            .filter { authorization.canViewWarehouse(actorUserId, it) }

    fun warehouse(actorUserId: UUID, warehouseId: UUID): WarehouseSnapshot {
        val warehouse = persistence.warehouse(warehouseId)
            ?: throw warehouseNotFound()
        if (!authorization.canViewWarehouse(actorUserId, warehouse)) {
            // Do not disclose whether an unpermitted warehouse exists.
            throw warehouseNotFound()
        }
        return warehouse
    }

    fun storageLocations(
        actorUserId: UUID,
        organizationId: UUID,
        warehouseId: UUID?,
    ): List<StorageLocationSnapshot> =
        persistence.storageLocations(organizationId, warehouseId)
            .filter { it.organizationId == organizationId }
            .filter { warehouseId == null || it.warehouseId == warehouseId }
            .filter { authorization.canViewStorage(actorUserId, it) }

    fun storageLocation(actorUserId: UUID, storageLocationId: UUID): StorageLocationSnapshot {
        val storage = persistence.storageLocation(storageLocationId)
            ?: throw storageNotFound()
        if (!authorization.canViewStorage(actorUserId, storage)) {
            throw storageNotFound()
        }
        return storage
    }

    private fun warehouseNotFound() = ProductApiException(
        code = "WAREHOUSE_NOT_FOUND",
        message = "The warehouse is unavailable.",
        status = HttpStatus.NOT_FOUND,
    )

    private fun storageNotFound() = ProductApiException(
        code = "STORAGE_LOCATION_NOT_FOUND",
        message = "The storage location is unavailable.",
        status = HttpStatus.NOT_FOUND,
    )
}

data class WarehouseReadResponse(
    val warehouseId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val active: Boolean,
    val version: Long,
)

data class WarehouseListResponse(
    val items: List<WarehouseReadResponse>,
    val correlationId: String,
)

data class StorageLocationReadResponse(
    val storageLocationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val kind: String,
    val parentStorageLocationId: String?,
    val warehouseId: String?,
    val projectId: String?,
    val siteId: String?,
    val temporary: Boolean,
    val lifecycleState: String,
    val version: Long,
)

data class StorageLocationListResponse(
    val items: List<StorageLocationReadResponse>,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/warehouses")
class WarehouseReadController(
    private val service: WarehouseReadService,
) {
    @GetMapping
    fun list(
        servletRequest: HttpServletRequest,
        @RequestParam organizationId: String,
    ): WarehouseListResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val items = service.warehouses(
            context.requireWarehouseIdentity(),
            organizationId.toWarehouseUuid("INVALID_ORGANIZATION_ID"),
        )
        return WarehouseListResponse(
            items = items.map { it.toReadResponse() },
            correlationId = context.correlationId,
        )
    }

    @GetMapping("/{warehouseId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable warehouseId: String,
    ): WarehouseReadResponse {
        val context = HiltechRequestContext.current(servletRequest)
        return service.warehouse(
            context.requireWarehouseIdentity(),
            warehouseId.toWarehouseUuid("INVALID_WAREHOUSE_ID"),
        ).toReadResponse()
    }

    @GetMapping("/{warehouseId}/storage-locations")
    fun storageLocations(
        servletRequest: HttpServletRequest,
        @PathVariable warehouseId: String,
    ): StorageLocationListResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val warehouse = service.warehouse(
            context.requireWarehouseIdentity(),
            warehouseId.toWarehouseUuid("INVALID_WAREHOUSE_ID"),
        )
        val items = service.storageLocations(
            context.requireWarehouseIdentity(),
            warehouse.organizationId,
            warehouse.warehouseId,
        )
        return StorageLocationListResponse(
            items = items.map { it.toReadResponse() },
            correlationId = context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/storage-locations")
class StorageLocationReadController(
    private val service: WarehouseReadService,
) {
    @GetMapping
    fun list(
        servletRequest: HttpServletRequest,
        @RequestParam organizationId: String,
    ): StorageLocationListResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val items = service.storageLocations(
            context.requireWarehouseIdentity(),
            organizationId.toWarehouseUuid("INVALID_ORGANIZATION_ID"),
            warehouseId = null,
        )
        return StorageLocationListResponse(
            items = items.map { it.toReadResponse() },
            correlationId = context.correlationId,
        )
    }

    @GetMapping("/{storageLocationId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable storageLocationId: String,
    ): StorageLocationReadResponse {
        val context = HiltechRequestContext.current(servletRequest)
        return service.storageLocation(
            context.requireWarehouseIdentity(),
            storageLocationId.toWarehouseUuid("INVALID_STORAGE_LOCATION_ID"),
        ).toReadResponse()
    }
}

private fun WarehouseSnapshot.toReadResponse() = WarehouseReadResponse(
    warehouseId = warehouseId.toString(),
    organizationId = organizationId.toString(),
    code = code,
    name = name,
    active = active,
    version = version,
)

private fun StorageLocationSnapshot.toReadResponse() = StorageLocationReadResponse(
    storageLocationId = storageLocationId.toString(),
    organizationId = organizationId.toString(),
    code = code,
    name = name,
    kind = kind.name,
    parentStorageLocationId = parentStorageLocationId?.toString(),
    warehouseId = warehouseId?.toString(),
    projectId = projectId?.toString(),
    siteId = siteId?.toString(),
    temporary = temporary,
    lifecycleState = lifecycleState.name,
    version = version,
)

private fun String.toWarehouseUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(
            code = code,
            message = "A warehouse identifier is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun HiltechRequestContextSnapshot.requireWarehouseIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authentication is required.",
            status = HttpStatus.UNAUTHORIZED,
        )
