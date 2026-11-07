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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Create Warehouse is an authoritative, idempotent business command.
 * All writes, projection intent and audit occur in the idempotency
 * executor's transaction. This is NOT an inventory-opening operation.
 *
 * First-warehouse provisioning must be handled by an explicit trusted
 * organization setup policy/seed. No implicit "any org member is admin"
 * bootstrap bypass is allowed here.
 */
@Component
class WarehouseCreateService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val projection: WarehouseAuthorizationProjectionBridge,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun create(command: CreateWarehouseCommand): WarehouseCommandResult {
        requireManager(command.actorUserId, command.organizationId)

        val normalizedCode = command.code.trim().uppercase(Locale.ROOT)
        val normalizedName = command.name.trim()
        val normalizedFacility = command.facilityRef?.trim()?.takeIf { it.isNotEmpty() }

        if (
            normalizedCode.isEmpty() || normalizedCode.length > 64 ||
            !Regex("^[A-Z0-9][A-Z0-9._-]*$").matches(normalizedCode)
        ) {
            throw invalid("INVALID_WAREHOUSE_CODE", "Warehouse code is invalid.")
        }
        if (normalizedName.isEmpty() || normalizedName.length > 160) {
            throw invalid("INVALID_WAREHOUSE_NAME", "Warehouse name is invalid.")
        }
        if (normalizedFacility != null && normalizedFacility.length > 320) {
            throw invalid("INVALID_WAREHOUSE_FACILITY_REF", "Facility reference is too long.")
        }

        val warehouseId = UUID.nameUUIDFromBytes(
            ("hiltech:warehouse:" + command.operationId).toByteArray(StandardCharsets.UTF_8),
        )
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(
                command.organizationId,
                normalizedCode,
                normalizedName,
                normalizedFacility,
                command.clientOccurredAt,
            ).joinToString("|"),
        )

        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = command.actorUserId,
                commandType = "WAREHOUSE_CREATE",
                targetType = "WAREHOUSE",
                targetId = warehouseId,
                requestFingerprint = fingerprint,
                correlationId = command.correlationId,
            ),
        ) {
            requireManager(command.actorUserId, command.organizationId)
            try {
                persistence.insertWarehouse(
                    warehouseId = warehouseId,
                    organizationId = command.organizationId,
                    code = normalizedCode,
                    name = normalizedName,
                    facilityRef = normalizedFacility,
                )
            } catch (failure: DataIntegrityViolationException) {
                throw ProductApiException(
                    code = "WAREHOUSE_CODE_CONFLICT",
                    message = "The warehouse conflicts with current organization data.",
                    status = HttpStatus.CONFLICT,
                )
            }

            val warehouse = persistence.warehouse(warehouseId)
                ?: error("Inserted Warehouse is unavailable within its transaction.")
            projection.warehouseCreated(warehouse, UUID.randomUUID())
            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "WAREHOUSE_CREATED",
                    targetType = "WAREHOUSE",
                    targetId = warehouseId,
                    newStateRef = "ACTIVE",
                    safeDiffJson = """{"fields":["code","name","facilityRef"]}""",
                    occurredAt = clock.instant(),
                    correlationId = command.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "WAREHOUSE_CREATED",
                resultPayloadJson = buildJsonObject {
                    put("warehouseId", warehouseId.toString())
                }.toString(),
            )
        }

        val created = persistence.warehouse(warehouseId)
            ?: throw ProductApiException(
                code = "WAREHOUSE_NOT_FOUND",
                message = "The warehouse could not be loaded.",
                status = HttpStatus.NOT_FOUND,
            )
        if (created.organizationId != command.organizationId) {
            throw ProductApiException(
                code = "WAREHOUSE_ORGANIZATION_CONFLICT",
                message = "The warehouse is unavailable for this organization.",
                status = HttpStatus.CONFLICT,
            )
        }
        return WarehouseCommandResult(created, execution.replayed)
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

data class CreateWarehouseRequest(
    val operationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val facilityRef: String? = null,
    val clientOccurredAt: String,
)

data class CreateWarehouseResponse(
    val warehouseId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val active: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/warehouses")
class WarehouseCreateController(
    private val service: WarehouseCreateService,
) {
    @PostMapping
    fun create(
        servletRequest: HttpServletRequest,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreateWarehouseRequest,
    ): CreateWarehouseResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.asWarehouseUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val occurredAt = runCatching { Instant.parse(request.clientOccurredAt) }
            .getOrElse {
                throw ProductApiException(
                    code = "INVALID_CLIENT_OCCURRED_AT",
                    message = "The request timestamp is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        val result = service.create(
            CreateWarehouseCommand(
                operationId = operationId,
                organizationId = request.organizationId.asWarehouseUuid("INVALID_ORGANIZATION_ID"),
                code = request.code,
                name = request.name,
                facilityRef = request.facilityRef,
                actorUserId = context.requireCreateWarehouseIdentity(),
                clientOccurredAt = occurredAt,
                correlationId = context.correlationId,
            ),
        )
        return CreateWarehouseResponse(
            warehouseId = result.warehouse.warehouseId.toString(),
            organizationId = result.warehouse.organizationId.toString(),
            code = result.warehouse.code,
            name = result.warehouse.name,
            active = result.warehouse.active,
            version = result.warehouse.version,
            replayed = result.replayed,
            correlationId = context.correlationId,
        )
    }
}

private fun String.asWarehouseUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(
            code = code,
            message = "A Warehouse identifier is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun HiltechRequestContextSnapshot.requireCreateWarehouseIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authentication is required.",
            status = HttpStatus.UNAUTHORIZED,
        )
