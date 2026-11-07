package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.HiltechRequestContextSnapshot
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Phase 5 / Slice 01 StorageLocation identity/setup only.
 *
 * Does not create stock, opening balances, custody, reservations or movements.
 * PostgreSQL enforces the canonical ProjectSite link, composite organization
 * foreign keys and hierarchy-cycle rejection in frozen migration V0026.
 */
@Component
class WarehouseStorageLocationCreateService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val projection: WarehouseAuthorizationProjectionBridge,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun create(command: CreateStorageLocationCommand): StorageLocationCommandResult {
        requireManager(command.actorUserId, command.organizationId)

        val normalized = command.copy(
            code = command.code.trim().uppercase(Locale.ROOT),
            name = command.name.trim(),
            addressOrLocationRef = command.addressOrLocationRef?.trim()?.takeIf { it.isNotEmpty() },
            notes = command.notes?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (normalized.code.isEmpty() || normalized.code.length > 64) {
            throw invalid("INVALID_STORAGE_LOCATION_CODE", "Storage location code is invalid.")
        }
        if (normalized.name.isEmpty() || normalized.name.length > 160) {
            throw invalid("INVALID_STORAGE_LOCATION_NAME", "Storage location name is invalid.")
        }
        if ((normalized.addressOrLocationRef?.length ?: 0) > 255) {
            throw invalid("INVALID_STORAGE_LOCATION_REF", "Storage location reference is too long.")
        }
        validateContext(normalized)
        requireCurrentContext(normalized)

        val locationId = UUID.nameUUIDFromBytes(
            ("hiltech:storage-location:" + normalized.operationId)
                .toByteArray(StandardCharsets.UTF_8),
        )
        val fingerprint = warehouseCommandFingerprint(
            normalized.organizationId, normalized.warehouseId,
            normalized.code, normalized.name, normalized.kind,
            normalized.parentStorageLocationId, normalized.projectId,
            normalized.siteId, normalized.temporary, normalized.activeFrom,
            normalized.restrictedAccess, normalized.addressOrLocationRef,
            normalized.notes, normalized.clientOccurredAt,
        )

        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = normalized.operationId,
                actorUserId = normalized.actorUserId,
                commandType = "STORAGE_LOCATION_CREATE",
                targetType = "STORAGE_LOCATION",
                targetId = locationId,
                requestFingerprint = fingerprint,
                correlationId = normalized.correlationId,
            ),
        ) {
            requireManager(normalized.actorUserId, normalized.organizationId)
            requireCurrentContext(normalized)

            try {
                persistence.insertStorageLocation(locationId, normalized)
            } catch (failure: DataIntegrityViolationException) {
                // Includes unique organization/code, wrong ProjectSite context,
                // cross-org composite FK and storage hierarchy integrity checks.
                throw ProductApiException(
                    code = "STORAGE_LOCATION_CONFLICT",
                    message = "Storage location conflicts with current authoritative context.",
                    status = HttpStatus.CONFLICT,
                )
            }

            val created = persistence.storageLocation(locationId)
                ?: error("Inserted StorageLocation is missing inside its transaction.")
            projection.storageCreated(created)
            audit.append(
                AuditEventRecord(
                    actorUserId = normalized.actorUserId,
                    action = "STORAGE_LOCATION_CREATED",
                    targetType = "STORAGE_LOCATION",
                    targetId = locationId,
                    newStateRef = StorageLocationLifecycle.ACTIVE.name,
                    safeDiffJson =
                        """{"fields":["code","name","kind","warehouse","project","site","parent","temporary","restrictedAccess"]}""",
                    occurredAt = clock.instant(),
                    correlationId = normalized.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "STORAGE_LOCATION_CREATED",
                resultPayloadJson = buildJsonObject {
                    put("storageLocationId", locationId.toString())
                }.toString(),
            )
        }

        val location = persistence.storageLocation(locationId)
            ?: throw ProductApiException(
                code = "STORAGE_LOCATION_NOT_FOUND",
                message = "The storage location could not be loaded.",
                status = HttpStatus.NOT_FOUND,
            )
        if (location.organizationId != normalized.organizationId) {
            throw ProductApiException(
                code = "STORAGE_LOCATION_CONFLICT",
                message = "The storage location is unavailable for this organization.",
                status = HttpStatus.CONFLICT,
            )
        }
        return StorageLocationCommandResult(location, execution.replayed)
    }

    private fun validateContext(command: CreateStorageLocationCommand) {
        when (command.kind) {
            StorageLocationKind.MAIN_WAREHOUSE,
            StorageLocationKind.WAREHOUSE -> {
                if (command.warehouseId == null || command.projectId != null ||
                    command.siteId != null || command.temporary
                ) {
                    throw invalid(
                        "INVALID_STORAGE_LOCATION_CONTEXT",
                        "Warehouse storage requires a Warehouse, without Project/Site context.",
                    )
                }
            }
            StorageLocationKind.PROJECT_STORAGE -> {
                if (command.projectId == null || command.siteId != null || !command.temporary) {
                    throw invalid(
                        "INVALID_STORAGE_LOCATION_CONTEXT",
                        "Project storage requires a Project and temporary=true.",
                    )
                }
            }
            StorageLocationKind.SITE_STORAGE -> {
                if (command.projectId == null || command.siteId == null || !command.temporary) {
                    throw invalid(
                        "INVALID_STORAGE_LOCATION_CONTEXT",
                        "Site storage requires a Project, Site and temporary=true.",
                    )
                }
            }
            StorageLocationKind.OTHER_TYPED -> {
                if (command.siteId != null && command.projectId == null) {
                    throw invalid(
                        "INVALID_STORAGE_LOCATION_CONTEXT",
                        "Site context requires a Project context.",
                    )
                }
            }
        }
    }

    private fun requireCurrentContext(command: CreateStorageLocationCommand) {
        command.warehouseId?.let { warehouseId ->
            val warehouse = persistence.warehouse(warehouseId)
            if (warehouse == null || warehouse.organizationId != command.organizationId ||
                !warehouse.active || !authorization.canManageWarehouse(command.actorUserId, warehouse)
            ) {
                throw ProductApiException(
                    code = "WAREHOUSE_CONTEXT_UNAVAILABLE",
                    message = "The Warehouse is unavailable for this storage operation.",
                    status = HttpStatus.FORBIDDEN,
                )
            }
        }

        command.parentStorageLocationId?.let { parentId ->
            val parent = persistence.storageLocation(parentId)
            if (parent == null || parent.organizationId != command.organizationId ||
                parent.lifecycleState != StorageLocationLifecycle.ACTIVE ||
                (parent.warehouseId != null &&
                    parent.warehouseId != command.warehouseId) ||
                (parent.projectId != null && command.projectId != null &&
                    parent.projectId != command.projectId) ||
                (parent.siteId != null && command.siteId != null &&
                    parent.siteId != command.siteId)
            ) {
                throw ProductApiException(
                    code = "STORAGE_PARENT_UNAVAILABLE",
                    message = "The StorageLocation parent is unavailable in this context.",
                    status = HttpStatus.CONFLICT,
                )
            }
            // Parent-specific warehouse authority is required, even if this
            // location is created through the standalone Project/Site route.
            parent.warehouseId?.let { parentWarehouseId ->
                val parentWarehouse = persistence.warehouse(parentWarehouseId)
                if (parentWarehouse == null ||
                    !parentWarehouse.active ||
                    parentWarehouse.organizationId != command.organizationId ||
                    !authorization.canManageWarehouse(command.actorUserId, parentWarehouse)
                ) {
                    throw ProductApiException(
                        code = "STORAGE_PARENT_UNAVAILABLE",
                        message = "The StorageLocation parent is unavailable in this context.",
                        status = HttpStatus.CONFLICT,
                    )
                }
            }
            // A newly generated StorageLocation cannot be an existing parent.
            // V0026 protects all subsequent hierarchy edits against cycles.
        }
    }

    private fun requireManager(actor: UUID, organization: UUID) {
        if (!authorization.canManageInventoryOrganization(actor, organization)) {
            throw ProductApiException(
                code = "WAREHOUSE_MANAGEMENT_FORBIDDEN",
                message = "Current Warehouse manager authority is required.",
                status = HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun invalid(code: String, message: String) =
        ProductApiException(code = code, message = message, status = HttpStatus.BAD_REQUEST)
}

data class CreateStorageLocationRequest(
    val operationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val kind: String,
    val parentStorageLocationId: String? = null,
    val projectId: String? = null,
    val siteId: String? = null,
    val temporary: Boolean = false,
    val activeFrom: String? = null,
    val restrictedAccess: Boolean = false,
    val addressOrLocationRef: String? = null,
    val notes: String? = null,
    val clientOccurredAt: String,
)

data class CreateStorageLocationResponse(
    val storageLocationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val kind: String,
    val warehouseId: String?,
    val projectId: String?,
    val siteId: String?,
    val temporary: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class WarehouseStorageLocationCreateController(
    private val service: WarehouseStorageLocationCreateService,
) {
    @PostMapping("/v1/warehouses/{warehouseId}/storage-locations")
    fun createWithinWarehouse(
        servletRequest: HttpServletRequest,
        @PathVariable warehouseId: String,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreateStorageLocationRequest,
    ): CreateStorageLocationResponse = createInternal(
        servletRequest, request, idempotencyKey,
        warehouseId.asStorageUuid("INVALID_WAREHOUSE_ID"),
    )

    @PostMapping("/v1/storage-locations")
    fun createStandaloneLocation(
        servletRequest: HttpServletRequest,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreateStorageLocationRequest,
    ): CreateStorageLocationResponse = createInternal(
        servletRequest, request, idempotencyKey, warehouseId = null,
    )

    private fun createInternal(
        servletRequest: HttpServletRequest,
        request: CreateStorageLocationRequest,
        idempotencyKey: String,
        warehouseId: UUID?,
    ): CreateStorageLocationResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.asStorageUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val kind = runCatching { enumValueOf<StorageLocationKind>(request.kind) }
            .getOrElse {
                throw ProductApiException(
                    code = "INVALID_STORAGE_LOCATION_KIND",
                    message = "Storage location kind is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        val activeFrom = request.activeFrom?.let { value ->
            runCatching { Instant.parse(value) }.getOrElse {
                throw ProductApiException(
                    code = "INVALID_STORAGE_LOCATION_DATE",
                    message = "Storage location activation date is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        }
        val occurredAt = runCatching { Instant.parse(request.clientOccurredAt) }
            .getOrElse {
                throw ProductApiException(
                    code = "INVALID_CLIENT_OCCURRED_AT",
                    message = "Request timestamp is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        val result = service.create(
            CreateStorageLocationCommand(
                operationId = operationId,
                warehouseId = warehouseId,
                organizationId = request.organizationId.asStorageUuid("INVALID_ORGANIZATION_ID"),
                code = request.code,
                name = request.name,
                kind = kind,
                parentStorageLocationId =
                    request.parentStorageLocationId?.asStorageUuid("INVALID_STORAGE_PARENT_ID"),
                projectId = request.projectId?.asStorageUuid("INVALID_PROJECT_ID"),
                siteId = request.siteId?.asStorageUuid("INVALID_SITE_ID"),
                temporary = request.temporary,
                activeFrom = activeFrom,
                restrictedAccess = request.restrictedAccess,
                addressOrLocationRef = request.addressOrLocationRef,
                notes = request.notes,
                actorUserId = context.requireStorageCreateIdentity(),
                clientOccurredAt = occurredAt,
                correlationId = context.correlationId,
            ),
        )
        return CreateStorageLocationResponse(
            storageLocationId = result.storageLocation.storageLocationId.toString(),
            organizationId = result.storageLocation.organizationId.toString(),
            code = result.storageLocation.code,
            name = result.storageLocation.name,
            kind = result.storageLocation.kind.name,
            warehouseId = result.storageLocation.warehouseId?.toString(),
            projectId = result.storageLocation.projectId?.toString(),
            siteId = result.storageLocation.siteId?.toString(),
            temporary = result.storageLocation.temporary,
            version = result.storageLocation.version,
            replayed = result.replayed,
            correlationId = context.correlationId,
        )
    }
}

private fun String.asStorageUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(
            code = code,
            message = "A storage identifier is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun HiltechRequestContextSnapshot.requireStorageCreateIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authentication is required.",
            status = HttpStatus.UNAUTHORIZED,
        )
