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
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
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
import java.util.UUID

/**
 * StorageLocation delegated access, not physical stock authority.
 * Location viewer/operator/responsible roles do not confer adjustment
 * approval, finance visibility, Asset custody or Stock movement.
 */
@Component
class WarehouseStorageAuthorityCommandService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val idempotency: IdempotentCommandExecutor,
    private val projection: WarehouseAuthorizationProjectionBridge,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun currentBindings(actorUserId: UUID, storageLocationId: UUID): List<StorageAuthorityBindingSnapshot> {
        val location = requireManagedStorage(actorUserId, storageLocationId)
        return persistence.currentStorageAuthorityBindings(location.storageLocationId, clock.instant())
            .filter {
                it.current && it.organizationId == location.organizationId &&
                    it.storageLocationId == location.storageLocationId
            }
    }

    fun grant(command: GrantStorageAuthorityCommand): StorageBindingCommandResult {
        val storage = requireManagedStorage(command.actorUserId, command.storageLocationId)
        if (command.effectiveFrom > clock.instant()) {
            throw invalid(
                "STORAGE_FUTURE_GRANT_UNSUPPORTED",
                "Future-dated authority requires a scheduled projection contract.",
            )
        }
        requirePrincipalCurrent(command.principalType, command.principalId, storage.organizationId)
        val bindingId = UUID.nameUUIDFromBytes(
            ("hiltech:storage-authority:" + command.operationId).toByteArray(StandardCharsets.UTF_8),
        )
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(
                command.storageLocationId, command.authorityKey, command.principalType,
                command.principalId, command.effectiveFrom, command.clientOccurredAt,
            ).joinToString("|"),
        )
        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = command.actorUserId,
                commandType = "STORAGE_AUTHORITY_GRANT",
                targetType = "STORAGE_LOCATION_AUTHORITY_BINDING",
                targetId = bindingId,
                requestFingerprint = fingerprint,
                correlationId = command.correlationId,
            ),
        ) {
            val current = requireManagedStorage(command.actorUserId, command.storageLocationId)
            requirePrincipalCurrent(command.principalType, command.principalId, current.organizationId)
            try {
                persistence.insertStorageAuthorityBinding(
                    bindingId = bindingId,
                    organizationId = current.organizationId,
                    storageLocationId = current.storageLocationId,
                    authorityKey = command.authorityKey,
                    principalType = command.principalType,
                    principalId = command.principalId,
                    effectiveFrom = command.effectiveFrom,
                    actorUserId = command.actorUserId,
                    at = clock.instant(),
                )
            } catch (failure: DataIntegrityViolationException) {
                throw conflict(
                    "STORAGE_AUTHORITY_CONFLICT",
                    "The current storage authority exists or its principal is invalid.",
                )
            }
            projection.storageAuthority(bindingId)
            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "STORAGE_AUTHORITY_GRANTED",
                    targetType = "STORAGE_LOCATION_AUTHORITY_BINDING",
                    targetId = bindingId,
                    newStateRef = command.authorityKey.name,
                    safeDiffJson =
                        """{"fields":["storageLocationId","authorityKey","principalType","principalId","effectiveFrom"]}""",
                    occurredAt = clock.instant(),
                    correlationId = command.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "STORAGE_AUTHORITY_GRANTED",
                resultPayloadJson = buildJsonObject { put("bindingId", bindingId.toString()) }.toString(),
            )
        }
        return StorageBindingCommandResult(
            binding = requireBinding(bindingId, storage.organizationId),
            replayed = execution.replayed,
        )
    }

    fun end(command: EndStorageAuthorityCommand): StorageBindingCommandResult {
        if (command.baseVersion < 1) {
            throw invalid("INVALID_STORAGE_AUTHORITY_VERSION", "Positive binding version required.")
        }
        val storage = requireManagedStorage(command.actorUserId, command.storageLocationId)
        val original = requireBinding(command.bindingId, storage.organizationId)
        if (original.storageLocationId != storage.storageLocationId) throw unavailable()
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(command.storageLocationId, command.bindingId, command.baseVersion, command.clientOccurredAt)
                .joinToString("|"),
        )
        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = command.actorUserId,
                commandType = "STORAGE_AUTHORITY_END",
                targetType = "STORAGE_LOCATION_AUTHORITY_BINDING",
                targetId = command.bindingId,
                requestFingerprint = fingerprint,
                correlationId = command.correlationId,
            ),
        ) {
            val managed = requireManagedStorage(command.actorUserId, command.storageLocationId)
            val binding = requireBinding(command.bindingId, managed.organizationId)
            if (binding.storageLocationId != managed.storageLocationId) throw unavailable()
            if (!binding.current || binding.version != command.baseVersion ||
                !persistence.endStorageAuthorityBinding(
                    binding.bindingId, managed.storageLocationId, command.baseVersion, clock.instant(),
                )
            ) {
                throw conflict(
                    "STORAGE_AUTHORITY_VERSION_CONFLICT",
                    "The authority changed; reload before ending.",
                )
            }
            projection.storageAuthority(binding.bindingId)
            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "STORAGE_AUTHORITY_ENDED",
                    targetType = "STORAGE_LOCATION_AUTHORITY_BINDING",
                    targetId = binding.bindingId,
                    previousStateRef = binding.authorityKey.name,
                    newStateRef = "ENDED",
                    safeDiffJson = """{"fields":["effectiveTo","active","version"]}""",
                    occurredAt = clock.instant(),
                    correlationId = command.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "STORAGE_AUTHORITY_ENDED",
                resultPayloadJson = buildJsonObject {
                    put("bindingId", binding.bindingId.toString())
                }.toString(),
            )
        }
        return StorageBindingCommandResult(
            binding = requireBinding(command.bindingId, storage.organizationId),
            replayed = execution.replayed,
        )
    }

    private fun requireManagedStorage(actor: UUID, id: UUID): StorageLocationSnapshot {
        val location = persistence.storageLocation(id) ?: throw unavailable()
        if (location.lifecycleState != StorageLocationLifecycle.ACTIVE ||
            !authorization.canManageInventoryOrganization(actor, location.organizationId)
        ) {
            throw unavailable()
        }
        // Warehouse-linked StorageLocation must also be managed at the
        // specific Warehouse, never merely via a different Warehouse in org.
        location.warehouseId?.let { warehouseId ->
            val warehouse = persistence.warehouse(warehouseId) ?: throw unavailable()
            if (warehouse.organizationId != location.organizationId ||
                !authorization.canManageWarehouse(actor, warehouse)
            ) {
                throw unavailable()
            }
        }
        return location
    }

    private fun requireBinding(id: UUID, org: UUID): StorageAuthorityBindingSnapshot {
        val binding = persistence.storageAuthorityBinding(id, clock.instant()) ?: throw unavailable()
        if (binding.organizationId != org) throw unavailable()
        return binding
    }

    private fun requirePrincipalCurrent(type: InventoryPrincipalType, id: UUID, org: UUID) {
        if (type == InventoryPrincipalType.USER &&
            !sourceAuthority.isOrganizationMemberCurrent(id, org, clock.instant())
        ) {
            throw conflict("STORAGE_PRINCIPAL_NOT_CURRENT", "The user is not a current organization member.")
        }
        // V0026 database trigger verifies TEAM belongs to organization and
        // remains active. This check runs inside the idempotent transaction.
    }

    private fun unavailable() = ProductApiException(
        code = "STORAGE_AUTHORITY_UNAVAILABLE",
        message = "The storage location authority is unavailable.",
        status = HttpStatus.NOT_FOUND,
    )
    private fun invalid(code: String, message: String) = ProductApiException(
        code = code, message = message, status = HttpStatus.BAD_REQUEST,
    )
    private fun conflict(code: String, message: String) = ProductApiException(
        code = code, message = message, status = HttpStatus.CONFLICT,
    )
}

data class StorageBindingCommandResult(val binding: StorageAuthorityBindingSnapshot, val replayed: Boolean)
data class StorageAuthorityGrantRequest(
    val operationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val effectiveFrom: String,
    val clientOccurredAt: String,
)
data class StorageAuthorityEndRequest(
    val operationId: String,
    val baseVersion: Long,
    val clientOccurredAt: String,
)
data class StorageAuthorityCurrentResponse(
    val bindingId: String,
    val storageLocationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val version: Long,
)
data class StorageAuthorityCurrentListResponse(
    val items: List<StorageAuthorityCurrentResponse>,
    val correlationId: String,
)
data class StorageAuthorityCommandResponse(
    val bindingId: String,
    val storageLocationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val current: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class WarehouseStorageAuthorityController(private val service: WarehouseStorageAuthorityCommandService) {
    @org.springframework.web.bind.annotation.GetMapping("/v1/storage-locations/{storageLocationId}/authority")
    fun listCurrent(
        servletRequest: HttpServletRequest,
        @PathVariable storageLocationId: String,
    ): StorageAuthorityCurrentListResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val actor = context.requireStorageAuthIdentity()
        val id = storageLocationId.asStorageAuthUuid("INVALID_STORAGE_LOCATION_ID")
        return StorageAuthorityCurrentListResponse(
            items = service.currentBindings(actor, id).map {
                StorageAuthorityCurrentResponse(
                    bindingId = it.bindingId.toString(),
                    storageLocationId = it.storageLocationId.toString(),
                    authorityKey = it.authorityKey.name,
                    principalType = it.principalType.name,
                    principalId = it.principalId.toString(),
                    version = it.version,
                )
            },
            correlationId = context.correlationId,
        )
    }

    @PostMapping("/v1/storage-locations/{storageLocationId}/authority")
    fun grant(
        servletRequest: HttpServletRequest,
        @PathVariable storageLocationId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: StorageAuthorityGrantRequest,
    ): StorageAuthorityCommandResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val opId = request.operationId.asStorageAuthUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, opId)
        val result = service.grant(
            GrantStorageAuthorityCommand(
                operationId = opId,
                storageLocationId = storageLocationId.asStorageAuthUuid("INVALID_STORAGE_LOCATION_ID"),
                authorityKey = request.authorityKey.asStorageAuthorityKey(),
                principalType = request.principalType.asStoragePrincipalType(),
                principalId = request.principalId.asStorageAuthUuid("INVALID_PRINCIPAL_ID"),
                effectiveFrom = request.effectiveFrom.asStorageAuthInstant("INVALID_EFFECTIVE_FROM"),
                actorUserId = context.requireStorageAuthIdentity(),
                clientOccurredAt = request.clientOccurredAt.asStorageAuthInstant("INVALID_CLIENT_OCCURRED_AT"),
                correlationId = context.correlationId,
            ),
        )
        return result.toResponse(context.correlationId)
    }

    @PostMapping("/v1/storage-locations/{storageLocationId}/authority/{bindingId}/end")
    fun end(
        servletRequest: HttpServletRequest,
        @PathVariable storageLocationId: String,
        @PathVariable bindingId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: StorageAuthorityEndRequest,
    ): StorageAuthorityCommandResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val opId = request.operationId.asStorageAuthUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, opId)
        val result = service.end(
            EndStorageAuthorityCommand(
                operationId = opId,
                storageLocationId = storageLocationId.asStorageAuthUuid("INVALID_STORAGE_LOCATION_ID"),
                bindingId = bindingId.asStorageAuthUuid("INVALID_BINDING_ID"),
                baseVersion = request.baseVersion,
                actorUserId = context.requireStorageAuthIdentity(),
                clientOccurredAt = request.clientOccurredAt.asStorageAuthInstant("INVALID_CLIENT_OCCURRED_AT"),
                correlationId = context.correlationId,
            ),
        )
        return result.toResponse(context.correlationId)
    }
}

private fun StorageBindingCommandResult.toResponse(correlationId: String) = StorageAuthorityCommandResponse(
    bindingId = binding.bindingId.toString(),
    storageLocationId = binding.storageLocationId.toString(),
    authorityKey = binding.authorityKey.name,
    principalType = binding.principalType.name,
    principalId = binding.principalId.toString(),
    current = binding.current,
    version = binding.version,
    replayed = replayed,
    correlationId = correlationId,
)
private fun String.asStorageAuthUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(code = code, message = "Invalid storage authority identifier.", status = HttpStatus.BAD_REQUEST)
    }
private fun String.asStorageAuthInstant(code: String): Instant =
    runCatching { Instant.parse(this) }.getOrElse {
        throw ProductApiException(code = code, message = "Invalid storage authority timestamp.", status = HttpStatus.BAD_REQUEST)
    }
private fun String.asStorageAuthorityKey(): StorageLocationAuthorityKey =
    runCatching { enumValueOf<StorageLocationAuthorityKey>(this) }.getOrElse {
        throw ProductApiException(code = "INVALID_STORAGE_AUTHORITY_KEY", message = "Invalid StorageLocation authority key.", status = HttpStatus.BAD_REQUEST)
    }
private fun String.asStoragePrincipalType(): InventoryPrincipalType =
    runCatching { enumValueOf<InventoryPrincipalType>(this) }.getOrElse {
        throw ProductApiException(code = "INVALID_INVENTORY_PRINCIPAL_TYPE", message = "Invalid authority principal type.", status = HttpStatus.BAD_REQUEST)
    }
private fun HiltechRequestContextSnapshot.requireStorageAuthIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED", message = "Authentication required.", status = HttpStatus.UNAUTHORIZED,
        )
