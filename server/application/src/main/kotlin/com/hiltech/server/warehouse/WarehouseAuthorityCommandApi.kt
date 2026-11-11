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
 * Restricted Phase-5 delegated Warehouse grants:
 *
 * Manager assignment, asset master control, value visibility and adjustment
 * approval are deliberately NOT granted here. Those capabilities need the
 * trusted provisioning and approval contract, including first-manager setup.
 * PostgreSQL is authority source; OpenFGA projection is not source truth.
 */
@Component
class WarehouseAuthorityCommandService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val idempotency: IdempotentCommandExecutor,
    private val projection: WarehouseAuthorizationProjectionBridge,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun currentBindings(actorUserId: UUID, warehouseId: UUID): List<WarehouseAuthorityBindingSnapshot> {
        val warehouse = requireManagedWarehouse(actorUserId, warehouseId)
        return persistence.currentWarehouseAuthorityBindings(warehouse.warehouseId, clock.instant())
            .filter {
                it.current && it.organizationId == warehouse.organizationId &&
                    it.warehouseId == warehouse.warehouseId
            }
    }

    fun grant(command: GrantWarehouseAuthorityCommand): WarehouseBindingCommandResult {
        val warehouse = requireManagedWarehouse(command.actorUserId, command.warehouseId)
        requireDelegableKey(command.authorityKey)
        val now = clock.instant()
        if (command.effectiveFrom > now) {
            throw invalid(
                "WAREHOUSE_FUTURE_GRANT_UNSUPPORTED",
                "Future-dated authority needs a scheduled projection contract.",
            )
        }
        requirePrincipalCurrent(command.principalType, command.principalId, warehouse.organizationId, now)
        val bindingId = deterministic("warehouse-authority", command.operationId)
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(
                command.warehouseId, command.authorityKey, command.principalType,
                command.principalId, command.effectiveFrom, command.clientOccurredAt,
            ).joinToString("|"),
        )
        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = command.actorUserId,
                commandType = "WAREHOUSE_AUTHORITY_GRANT",
                targetType = "WAREHOUSE_AUTHORITY_BINDING",
                targetId = bindingId,
                requestFingerprint = fingerprint,
                correlationId = command.correlationId,
            ),
        ) {
            val current = requireManagedWarehouse(command.actorUserId, command.warehouseId)
            requirePrincipalCurrent(command.principalType, command.principalId, current.organizationId, clock.instant())
            try {
                persistence.insertWarehouseAuthorityBinding(
                    bindingId = bindingId,
                    organizationId = current.organizationId,
                    warehouseId = current.warehouseId,
                    authorityKey = command.authorityKey,
                    principalType = command.principalType,
                    principalId = command.principalId,
                    effectiveFrom = command.effectiveFrom,
                    actorUserId = command.actorUserId,
                    at = clock.instant(),
                )
            } catch (failure: DataIntegrityViolationException) {
                throw conflict("WAREHOUSE_AUTHORITY_CONFLICT", "The current authority already exists or principal is invalid.")
            }
            projection.warehouseAuthority(bindingId)
            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "WAREHOUSE_AUTHORITY_GRANTED",
                    targetType = "WAREHOUSE_AUTHORITY_BINDING",
                    targetId = bindingId,
                    newStateRef = command.authorityKey.name,
                    safeDiffJson = """{"fields":["warehouseId","authorityKey","principalType","principalId","effectiveFrom"]}""",
                    occurredAt = clock.instant(),
                    correlationId = command.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "WAREHOUSE_AUTHORITY_GRANTED",
                resultPayloadJson = buildJsonObject { put("bindingId", bindingId.toString()) }.toString(),
            )
        }
        return WarehouseBindingCommandResult(
            binding = requireWarehouseBinding(bindingId, warehouse.organizationId),
            replayed = execution.replayed,
        )
    }

    fun end(command: EndWarehouseAuthorityCommand): WarehouseBindingCommandResult {
        if (command.baseVersion < 1) {
            throw invalid("INVALID_WAREHOUSE_AUTHORITY_VERSION", "Positive binding version required.")
        }
        val warehouse = requireManagedWarehouse(command.actorUserId, command.warehouseId)
        val before = requireWarehouseBinding(command.bindingId, warehouse.organizationId)
        if (before.warehouseId != warehouse.warehouseId) throw unavailable()
        requireDelegableKey(before.authorityKey)
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(command.warehouseId, command.bindingId, command.baseVersion, command.clientOccurredAt)
                .joinToString("|"),
        )
        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = command.actorUserId,
                commandType = "WAREHOUSE_AUTHORITY_END",
                targetType = "WAREHOUSE_AUTHORITY_BINDING",
                targetId = command.bindingId,
                requestFingerprint = fingerprint,
                correlationId = command.correlationId,
            ),
        ) {
            val currentWarehouse = requireManagedWarehouse(command.actorUserId, command.warehouseId)
            val current = requireWarehouseBinding(command.bindingId, currentWarehouse.organizationId)
            if (current.warehouseId != currentWarehouse.warehouseId) throw unavailable()
            requireDelegableKey(current.authorityKey)
            if (!current.current || current.version != command.baseVersion ||
                !persistence.endWarehouseAuthorityBinding(
                    command.bindingId, command.warehouseId, command.baseVersion, clock.instant(),
                )
            ) {
                throw conflict("WAREHOUSE_AUTHORITY_VERSION_CONFLICT", "Authority changed; reload before ending.")
            }
            projection.warehouseAuthority(command.bindingId)
            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "WAREHOUSE_AUTHORITY_ENDED",
                    targetType = "WAREHOUSE_AUTHORITY_BINDING",
                    targetId = command.bindingId,
                    previousStateRef = current.authorityKey.name,
                    newStateRef = "ENDED",
                    safeDiffJson = """{"fields":["effectiveTo","active","version"]}""",
                    occurredAt = clock.instant(),
                    correlationId = command.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "WAREHOUSE_AUTHORITY_ENDED",
                resultPayloadJson = buildJsonObject {
                    put("bindingId", command.bindingId.toString())
                }.toString(),
            )
        }
        return WarehouseBindingCommandResult(
            binding = requireWarehouseBinding(command.bindingId, warehouse.organizationId),
            replayed = execution.replayed,
        )
    }

    private fun requireManagedWarehouse(actor: UUID, warehouseId: UUID): WarehouseSnapshot {
        val warehouse = persistence.warehouse(warehouseId) ?: throw unavailable()
        if (!warehouse.active || !authorization.canManageWarehouse(actor, warehouse)) throw unavailable()
        return warehouse
    }

    private fun requireWarehouseBinding(id: UUID, orgId: UUID): WarehouseAuthorityBindingSnapshot {
        val binding = persistence.warehouseAuthorityBinding(id, clock.instant()) ?: throw unavailable()
        if (binding.organizationId != orgId) throw unavailable()
        return binding
    }

    private fun requireDelegableKey(key: WarehouseAuthorityKey) {
        if (key !in setOf(WarehouseAuthorityKey.WAREHOUSE_OPERATOR, WarehouseAuthorityKey.WAREHOUSE_VIEWER)) {
            throw ProductApiException(
                code = "WAREHOUSE_PRIVILEGED_AUTHORITY_REQUIRES_POLICY",
                message = "This authority requires a separate trusted provisioning policy.",
                status = HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun requirePrincipalCurrent(type: InventoryPrincipalType, id: UUID, org: UUID, now: Instant) {
        if (type == InventoryPrincipalType.USER &&
            !sourceAuthority.isOrganizationMemberCurrent(id, org, now)
        ) {
            throw conflict("WAREHOUSE_PRINCIPAL_NOT_CURRENT", "The user is not a current organization member.")
        }
        // TEAM principals are checked by V0026 trigger: current active
        // TEAM must belong to this organization, not just share its name.
    }

    private fun unavailable() = ProductApiException(
        code = "WAREHOUSE_AUTHORITY_UNAVAILABLE",
        message = "The warehouse authority is unavailable.",
        status = HttpStatus.NOT_FOUND,
    )
    private fun invalid(code: String, message: String) = ProductApiException(
        code = code, message = message, status = HttpStatus.BAD_REQUEST,
    )
    private fun conflict(code: String, message: String) = ProductApiException(
        code = code, message = message, status = HttpStatus.CONFLICT,
    )
    private fun deterministic(namespace: String, operationId: UUID): UUID =
        UUID.nameUUIDFromBytes(("hiltech:$namespace:$operationId").toByteArray(StandardCharsets.UTF_8))
}

data class WarehouseBindingCommandResult(
    val binding: WarehouseAuthorityBindingSnapshot,
    val replayed: Boolean,
)

data class WarehouseAuthorityGrantRequest(
    val operationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val effectiveFrom: String,
    val clientOccurredAt: String,
)
data class WarehouseAuthorityEndRequest(
    val operationId: String,
    val baseVersion: Long,
    val clientOccurredAt: String,
)
data class WarehouseAuthorityCurrentResponse(
    val bindingId: String,
    val warehouseId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val version: Long,
)
data class WarehouseAuthorityCurrentListResponse(
    val items: List<WarehouseAuthorityCurrentResponse>,
    val correlationId: String,
)
data class WarehouseAuthorityCommandResponse(
    val bindingId: String,
    val warehouseId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val current: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class WarehouseAuthorityCommandController(private val service: WarehouseAuthorityCommandService) {
    @org.springframework.web.bind.annotation.GetMapping("/v1/warehouses/{warehouseId}/authority")
    fun listCurrent(
        servletRequest: HttpServletRequest,
        @PathVariable warehouseId: String,
    ): WarehouseAuthorityCurrentListResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val actor = context.requireWarehouseAuthIdentity()
        val id = warehouseId.asWarehouseAuthUuid("INVALID_WAREHOUSE_ID")
        return WarehouseAuthorityCurrentListResponse(
            items = service.currentBindings(actor, id).map {
                WarehouseAuthorityCurrentResponse(
                    bindingId = it.bindingId.toString(),
                    warehouseId = it.warehouseId.toString(),
                    authorityKey = it.authorityKey.name,
                    principalType = it.principalType.name,
                    principalId = it.principalId.toString(),
                    version = it.version,
                )
            },
            correlationId = context.correlationId,
        )
    }

    @PostMapping("/v1/warehouses/{warehouseId}/authority")
    fun grant(
        servletRequest: HttpServletRequest,
        @PathVariable warehouseId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: WarehouseAuthorityGrantRequest,
    ): WarehouseAuthorityCommandResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.asWarehouseAuthUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val result = service.grant(
            GrantWarehouseAuthorityCommand(
                operationId = operationId,
                warehouseId = warehouseId.asWarehouseAuthUuid("INVALID_WAREHOUSE_ID"),
                authorityKey = request.authorityKey.asWarehouseKey(),
                principalType = request.principalType.asInventoryPrincipalType(),
                principalId = request.principalId.asWarehouseAuthUuid("INVALID_PRINCIPAL_ID"),
                effectiveFrom = request.effectiveFrom.asWarehouseAuthInstant("INVALID_EFFECTIVE_FROM"),
                actorUserId = context.requireWarehouseAuthIdentity(),
                clientOccurredAt = request.clientOccurredAt.asWarehouseAuthInstant("INVALID_CLIENT_OCCURRED_AT"),
                correlationId = context.correlationId,
            ),
        )
        return result.toResponse(context.correlationId)
    }

    @PostMapping("/v1/warehouses/{warehouseId}/authority/{bindingId}/end")
    fun end(
        servletRequest: HttpServletRequest,
        @PathVariable warehouseId: String,
        @PathVariable bindingId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: WarehouseAuthorityEndRequest,
    ): WarehouseAuthorityCommandResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.asWarehouseAuthUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val result = service.end(
            EndWarehouseAuthorityCommand(
                operationId = operationId,
                warehouseId = warehouseId.asWarehouseAuthUuid("INVALID_WAREHOUSE_ID"),
                bindingId = bindingId.asWarehouseAuthUuid("INVALID_BINDING_ID"),
                baseVersion = request.baseVersion,
                actorUserId = context.requireWarehouseAuthIdentity(),
                clientOccurredAt = request.clientOccurredAt.asWarehouseAuthInstant("INVALID_CLIENT_OCCURRED_AT"),
                correlationId = context.correlationId,
            ),
        )
        return result.toResponse(context.correlationId)
    }
}

private fun WarehouseBindingCommandResult.toResponse(correlationId: String) = WarehouseAuthorityCommandResponse(
    bindingId = binding.bindingId.toString(),
    warehouseId = binding.warehouseId.toString(),
    authorityKey = binding.authorityKey.name,
    principalType = binding.principalType.name,
    principalId = binding.principalId.toString(),
    current = binding.current,
    version = binding.version,
    replayed = replayed,
    correlationId = correlationId,
)
private fun String.asWarehouseAuthUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(code = code, message = "Invalid authority identifier.", status = HttpStatus.BAD_REQUEST)
    }
private fun String.asWarehouseAuthInstant(code: String): Instant =
    runCatching { Instant.parse(this) }.getOrElse {
        throw ProductApiException(code = code, message = "Invalid authority timestamp.", status = HttpStatus.BAD_REQUEST)
    }
private fun String.asWarehouseKey(): WarehouseAuthorityKey =
    runCatching { enumValueOf<WarehouseAuthorityKey>(this) }.getOrElse {
        throw ProductApiException(code = "INVALID_WAREHOUSE_AUTHORITY_KEY", message = "Invalid Warehouse authority key.", status = HttpStatus.BAD_REQUEST)
    }
private fun String.asInventoryPrincipalType(): InventoryPrincipalType =
    runCatching { enumValueOf<InventoryPrincipalType>(this) }.getOrElse {
        throw ProductApiException(code = "INVALID_INVENTORY_PRINCIPAL_TYPE", message = "Invalid authority principal type.", status = HttpStatus.BAD_REQUEST)
    }
private fun HiltechRequestContextSnapshot.requireWarehouseAuthIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED", message = "Authentication required.", status = HttpStatus.UNAUTHORIZED,
        )
