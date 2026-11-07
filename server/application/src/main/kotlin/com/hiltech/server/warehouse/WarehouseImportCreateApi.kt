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
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Only prepares an inventory import batch; never creates StockItem, Asset,
 * movement, opening balance or current custody.
 */
@Component
class WarehouseImportCreateService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun create(command: CreateInventoryImportBatchCommand): ImportBatchCommandResult {
        requireImportAuthority(command.actorUserId, command.organizationId)

        val fileName = command.sourceFileName.trim()
        val sha256 = command.sourceSha256.trim().lowercase(Locale.ROOT)
        if (fileName.isEmpty() || fileName.length > 255) {
            throw invalid("INVALID_SOURCE_FILENAME", "The source filename is invalid.")
        }
        if (!Regex("^[0-9a-f]{64}$").matches(sha256)) {
            throw invalid("INVALID_SOURCE_SHA256", "Source SHA-256 must be a 64-digit hex digest.")
        }

        val batchId = UUID.nameUUIDFromBytes(
            ("hiltech:inventory-import:" + command.operationId).toByteArray(StandardCharsets.UTF_8),
        )
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(
                command.organizationId,
                sha256,
                fileName,
                command.sourceType.name,
                command.cutoverDate,
                command.clientOccurredAt,
            ).joinToString("|"),
        )

        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = command.actorUserId,
                commandType = "INVENTORY_IMPORT_CREATE",
                targetType = "INVENTORY_IMPORT_BATCH",
                targetId = batchId,
                requestFingerprint = fingerprint,
                correlationId = command.correlationId,
            ),
        ) {
            requireImportAuthority(command.actorUserId, command.organizationId)
            if (persistence.importBatchByHash(command.organizationId, sha256) != null) {
                throw ProductApiException(
                    code = "INVENTORY_IMPORT_DUPLICATE_SOURCE",
                    message = "An import batch already exists for this source digest.",
                    status = HttpStatus.CONFLICT,
                )
            }
            val normalized = command.copy(sourceFileName = fileName, sourceSha256 = sha256)
            try {
                persistence.insertImportBatch(batchId, normalized, clock.instant())
            } catch (failure: DataIntegrityViolationException) {
                throw ProductApiException(
                    code = "INVENTORY_IMPORT_CONFLICT",
                    message = "The import batch conflicts with existing source data.",
                    status = HttpStatus.CONFLICT,
                )
            }

            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "INVENTORY_IMPORT_BATCH_CREATED",
                    targetType = "INVENTORY_IMPORT_BATCH",
                    targetId = batchId,
                    newStateRef = InventoryImportBatchState.UPLOADED.name,
                    safeDiffJson = """{"fields":["sourceFileName","sourceSha256","sourceType","cutoverDate"]}""",
                    occurredAt = clock.instant(),
                    correlationId = command.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "INVENTORY_IMPORT_BATCH_CREATED",
                resultPayloadJson = buildJsonObject {
                    put("batchId", batchId.toString())
                }.toString(),
            )
        }

        val batch = persistence.importBatch(batchId)
            ?: throw ProductApiException(
                code = "INVENTORY_IMPORT_NOT_FOUND",
                message = "The import batch could not be loaded.",
                status = HttpStatus.NOT_FOUND,
            )
        if (batch.organizationId != command.organizationId) {
            throw ProductApiException(
                code = "INVENTORY_IMPORT_ORGANIZATION_CONFLICT",
                message = "The import batch is unavailable for this organization.",
                status = HttpStatus.CONFLICT,
            )
        }
        return ImportBatchCommandResult(batch, execution.replayed)
    }

    private fun requireImportAuthority(actor: UUID, organization: UUID) {
        if (!authorization.canManageImport(actor, organization)) {
            throw ProductApiException(
                code = "INVENTORY_IMPORT_FORBIDDEN",
                message = "Current Inventory import authority is required.",
                status = HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun invalid(code: String, message: String) =
        ProductApiException(code = code, message = message, status = HttpStatus.BAD_REQUEST)
}

data class CreateInventoryImportBatchRequest(
    val operationId: String,
    val organizationId: String,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceType: String,
    val cutoverDate: String? = null,
    val clientOccurredAt: String,
)

data class CreateInventoryImportBatchResponse(
    val batchId: String,
    val organizationId: String,
    val state: String,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/inventory-imports")
class WarehouseImportCreateController(
    private val service: WarehouseImportCreateService,
) {
    @PostMapping
    fun create(
        servletRequest: HttpServletRequest,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreateInventoryImportBatchRequest,
    ): CreateInventoryImportBatchResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.toImportUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val sourceType = runCatching { enumValueOf<InventoryImportSourceType>(request.sourceType) }
            .getOrElse {
                throw ProductApiException(
                    code = "INVALID_IMPORT_SOURCE_TYPE",
                    message = "The import source type is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        val cutoverDate = request.cutoverDate?.let {
            runCatching { LocalDate.parse(it) }.getOrElse {
                throw ProductApiException(
                    code = "INVALID_IMPORT_CUTOVER_DATE",
                    message = "The import cutover date is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        }
        val occurredAt = runCatching { Instant.parse(request.clientOccurredAt) }
            .getOrElse {
                throw ProductApiException(
                    code = "INVALID_CLIENT_OCCURRED_AT",
                    message = "The request timestamp is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        val result = service.create(
            CreateInventoryImportBatchCommand(
                operationId = operationId,
                organizationId = request.organizationId.toImportUuid("INVALID_ORGANIZATION_ID"),
                sourceFileName = request.sourceFileName,
                sourceSha256 = request.sourceSha256,
                sourceType = sourceType,
                cutoverDate = cutoverDate,
                actorUserId = context.requireImportCreateIdentity(),
                clientOccurredAt = occurredAt,
                correlationId = context.correlationId,
            ),
        )
        return CreateInventoryImportBatchResponse(
            batchId = result.batch.batchId.toString(),
            organizationId = result.batch.organizationId.toString(),
            state = result.batch.state.name,
            version = result.batch.version,
            replayed = result.replayed,
            correlationId = context.correlationId,
        )
    }
}

private fun String.toImportUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(
            code = code,
            message = "An inventory import identifier is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun HiltechRequestContextSnapshot.requireImportCreateIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authentication is required.",
            status = HttpStatus.UNAUTHORIZED,
        )
