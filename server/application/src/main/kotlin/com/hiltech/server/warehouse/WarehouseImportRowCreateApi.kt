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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Non-authoritative legacy inventory row staging.
 *
 * Source labels, quantities and prices are evidence, not StockItem/Asset truth.
 * This command NEVER grants APPROVED review state or creates Stock/Asset objects.
 */
@Component
class WarehouseImportRowCreateService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun addRow(command: AddInventoryImportRowCommand): ImportRowCommandResult {
        val initialBatch = persistence.importBatch(command.batchId)
            ?: throw missingBatch()
        requireImportAuthority(command.actorUserId, initialBatch.organizationId)

        val normalized = normalize(command)
        val rowId = UUID.nameUUIDFromBytes(
            ("hiltech:inventory-import-row:" + command.operationId)
                .toByteArray(StandardCharsets.UTF_8),
        )
        val fingerprint = warehouseCommandFingerprint(
            normalized.batchId,
            normalized.sourceSheet,
            normalized.sourceRowNumber,
            normalized.rawPayloadJson,
            normalized.candidateClass,
            normalized.normalizedBrand,
            normalized.normalizedPartNumber,
            normalized.normalizedDescription,
            normalized.candidateUom,
            normalized.candidateQuantity,
            normalized.candidateValue,
            normalized.candidateCurrencyCode,
            normalized.sourceIssueCodes.sortedBy { it.name },
            normalized.clientOccurredAt,
        )

        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = normalized.operationId,
                actorUserId = normalized.actorUserId,
                commandType = "INVENTORY_IMPORT_ROW_ADD",
                targetType = "INVENTORY_IMPORT_ROW",
                targetId = rowId,
                requestFingerprint = fingerprint,
                correlationId = normalized.correlationId,
            ),
        ) {
            // The row lock makes this check atomic with INSERT and a CANCELLED
            // transition; do not trust the earlier controller-level read.
            val batch = persistence.lockImportBatch(normalized.batchId)
                ?: throw missingBatch()
            requireImportAuthority(normalized.actorUserId, batch.organizationId)
            requireOpenBatch(batch)

            val issues = deriveIssues(batch.sourceType, normalized)
            try {
                persistence.insertImportRow(
                    rowId = rowId,
                    organizationId = batch.organizationId,
                    command = normalized,
                    issueCodes = issues,
                    at = clock.instant(),
                )
            } catch (failure: DataIntegrityViolationException) {
                throw ProductApiException(
                    code = "INVENTORY_IMPORT_ROW_CONFLICT",
                    message = "The import row conflicts with current batch/source data.",
                    status = HttpStatus.CONFLICT,
                )
            }
            persistence.setImportBatchState(
                batch.batchId,
                InventoryImportBatchState.IN_REVIEW,
                clock.instant(),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = normalized.actorUserId,
                    action = "INVENTORY_IMPORT_ROW_STAGED",
                    targetType = "INVENTORY_IMPORT_ROW",
                    targetId = rowId,
                    newStateRef = InventoryImportReviewState.PENDING.name,
                    safeDiffJson =
                        """{"fields":["sourceSheet","sourceRowNumber","candidateClass","issueCodes"]}""",
                    occurredAt = clock.instant(),
                    correlationId = normalized.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "INVENTORY_IMPORT_ROW_STAGED",
                resultPayloadJson = buildJsonObject {
                    put("rowId", rowId.toString())
                }.toString(),
            )
        }

        val row = persistence.importRow(rowId)
            ?: throw ProductApiException(
                code = "INVENTORY_IMPORT_ROW_NOT_FOUND",
                message = "The staged import row could not be loaded.",
                status = HttpStatus.NOT_FOUND,
            )
        if (row.batchId != normalized.batchId ||
            row.organizationId != initialBatch.organizationId
        ) {
            throw ProductApiException(
                code = "INVENTORY_IMPORT_ROW_CONFLICT",
                message = "The row is unavailable for this batch.",
                status = HttpStatus.CONFLICT,
            )
        }
        return ImportRowCommandResult(row, execution.replayed)
    }

    private fun normalize(command: AddInventoryImportRowCommand): AddInventoryImportRowCommand {
        val sheet = command.sourceSheet.trim()
        if (sheet.isEmpty() || sheet.length > 160 || command.sourceRowNumber <= 0) {
            throw invalid("INVALID_SOURCE_ROW", "Source sheet/row is invalid.")
        }
        if (command.sourceIssueCodes.size > 64) {
            throw invalid("TOO_MANY_SOURCE_ISSUES", "The source issue list is too large.")
        }
        val raw = command.rawPayloadJson
        if (raw.length > 131_072 || raw.toByteArray(StandardCharsets.UTF_8).size > 131_072 ||
            runCatching { Json.parseToJsonElement(raw) is JsonObject }.getOrNull() != true
        ) {
            throw invalid(
                "INVALID_SOURCE_ROW_PAYLOAD",
                "Raw row payload must be a JSON object within the safe limit.",
            )
        }

        fun optional(rawValue: String?, max: Int, code: String): String? {
            val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.length > max) throw invalid(code, "An import candidate field is too long.")
            return value
        }
        val brand = optional(command.normalizedBrand, 160, "INVALID_CANDIDATE_BRAND")
        val part = optional(command.normalizedPartNumber, 160, "INVALID_CANDIDATE_PART_NUMBER")
        val description = optional(
            command.normalizedDescription, 10_000, "INVALID_CANDIDATE_DESCRIPTION",
        )
        val uom = optional(command.candidateUom, 32, "INVALID_CANDIDATE_UOM")
            ?.uppercase(Locale.ROOT)
        val currency = optional(command.candidateCurrencyCode, 3, "INVALID_CANDIDATE_CURRENCY")
            ?.uppercase(Locale.ROOT)
        if (currency != null && !Regex("^[A-Z]{3}$").matches(currency)) {
            throw invalid("INVALID_CANDIDATE_CURRENCY", "Candidate currency code is invalid.")
        }
        fun decimal(value: BigDecimal?, code: String): BigDecimal? {
            if (value == null) return null
            val exact = try {
                value.stripTrailingZeros()
            } catch (failure: ArithmeticException) {
                throw invalid(code, "Candidate decimal is outside numeric(20,6) limits.")
            }
            if (exact < BigDecimal.ZERO || exact.scale() > 6 ||
                (exact.precision().toLong() - exact.scale()) > 14
            ) {
                throw invalid(code, "Candidate decimal is outside numeric(20,6) limits.")
            }
            return exact
        }

        return command.copy(
            sourceSheet = sheet,
            normalizedBrand = brand,
            normalizedPartNumber = part,
            normalizedDescription = description,
            candidateUom = uom,
            candidateQuantity = decimal(command.candidateQuantity, "INVALID_CANDIDATE_QUANTITY"),
            candidateValue = decimal(command.candidateValue, "INVALID_CANDIDATE_VALUE"),
            candidateCurrencyCode = currency,
            sourceIssueCodes = command.sourceIssueCodes.distinct(),
        )
    }

    private fun deriveIssues(
        sourceType: InventoryImportSourceType,
        command: AddInventoryImportRowCommand,
    ): List<InventoryImportIssueCode> {
        val issues = LegacyInventorySource.issues(sourceType, command.sourceSheet, command.rawPayloadJson)
            .plus(command.sourceIssueCodes).toMutableSet()
        if (command.candidateClass == InventoryCandidateClass.UNCLASSIFIED) {
            issues += InventoryImportIssueCode.ITEM_CLASSIFICATION_REQUIRED
        }
        if (command.candidateUom == null) {
            issues += InventoryImportIssueCode.UNKNOWN_UOM
        }
        val part = command.normalizedPartNumber
        if (part == null) {
            issues += InventoryImportIssueCode.PART_NUMBER_MISSING
        } else if (part.uppercase(Locale.ROOT) in setOf("NONE", "N/A", "NA", "-", "UNKNOWN")) {
            issues += InventoryImportIssueCode.PART_NUMBER_PLACEHOLDER
        } else if (persistence.duplicatePartNumberExists(
                command.batchId, part, excludingRowId = null,
            )
        ) {
            issues += InventoryImportIssueCode.DUPLICATE_PART_NUMBER
        }
        if (command.candidateQuantity == null) {
            issues += InventoryImportIssueCode.QUANTITY_AMBIGUOUS
        }
        return issues.sortedBy { it.name }
    }

    private fun requireOpenBatch(batch: InventoryImportBatchSnapshot) {
        if (batch.state == InventoryImportBatchState.CANCELLED ||
            batch.state == InventoryImportBatchState.REVIEWED
        ) {
            throw ProductApiException(
                code = "INVENTORY_IMPORT_BATCH_CLOSED",
                message = "Rows cannot be added to a closed or reviewed batch.",
                status = HttpStatus.CONFLICT,
            )
        }
    }

    private fun requireImportAuthority(actor: UUID, organization: UUID) {
        if (!authorization.canManageImport(actor, organization)) {
            // A guessed batch ID must not reveal whether that batch exists.
            throw missingBatch()
        }
    }

    private fun missingBatch() = ProductApiException(
        code = "INVENTORY_IMPORT_NOT_FOUND",
        message = "The import batch is unavailable.",
        status = HttpStatus.NOT_FOUND,
    )

    private fun invalid(code: String, message: String) =
        ProductApiException(code = code, message = message, status = HttpStatus.BAD_REQUEST)
}

data class AddInventoryImportRowRequest(
    val operationId: String,
    val sourceSheet: String,
    val sourceRowNumber: Int,
    val rawPayloadJson: String,
    val candidateClass: String? = null,
    val normalizedBrand: String? = null,
    val normalizedPartNumber: String? = null,
    val normalizedDescription: String? = null,
    val candidateUom: String? = null,
    val candidateQuantity: String? = null,
    val candidateValue: String? = null,
    val candidateCurrencyCode: String? = null,
    val sourceIssueCodes: List<String>? = null,
    val clientOccurredAt: String,
)

data class AddInventoryImportRowResponse(
    val rowId: String,
    val batchId: String,
    val reviewState: String,
    val issueCodes: List<String>,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class WarehouseImportRowCreateController(
    private val service: WarehouseImportRowCreateService,
) {
    @PostMapping("/v1/inventory-imports/{batchId}/rows")
    fun create(
        servletRequest: HttpServletRequest,
        @PathVariable batchId: String,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: AddInventoryImportRowRequest,
    ): AddInventoryImportRowResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.asRowUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val candidateClass = runCatching {
            request.candidateClass?.let { enumValueOf<InventoryCandidateClass>(it) }
                ?: InventoryCandidateClass.UNCLASSIFIED
        }.getOrElse {
            throw ProductApiException(
                code = "INVALID_IMPORT_CANDIDATE_CLASS",
                message = "Import candidate classification is invalid.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        val sourceIssues = request.sourceIssueCodes.orEmpty().map { issue ->
            runCatching { enumValueOf<InventoryImportIssueCode>(issue) }.getOrElse {
                throw ProductApiException(
                    code = "INVALID_IMPORT_ISSUE_CODE",
                    message = "One or more source issue codes are invalid.",
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

        val result = service.addRow(
            AddInventoryImportRowCommand(
                operationId = operationId,
                batchId = batchId.asRowUuid("INVALID_IMPORT_BATCH_ID"),
                sourceSheet = request.sourceSheet,
                sourceRowNumber = request.sourceRowNumber,
                rawPayloadJson = request.rawPayloadJson,
                candidateClass = candidateClass,
                normalizedBrand = request.normalizedBrand,
                normalizedPartNumber = request.normalizedPartNumber,
                normalizedDescription = request.normalizedDescription,
                candidateUom = request.candidateUom,
                candidateQuantity = request.candidateQuantity.asRowDecimal("INVALID_CANDIDATE_QUANTITY"),
                candidateValue = request.candidateValue.asRowDecimal("INVALID_CANDIDATE_VALUE"),
                candidateCurrencyCode = request.candidateCurrencyCode,
                sourceIssueCodes = sourceIssues,
                actorUserId = context.requireRowIdentity(),
                clientOccurredAt = occurredAt,
                correlationId = context.correlationId,
            ),
        )

        return AddInventoryImportRowResponse(
            rowId = result.row.rowId.toString(),
            batchId = result.row.batchId.toString(),
            reviewState = result.row.reviewState.name,
            issueCodes = result.row.issueCodes.map { it.name },
            version = result.row.version,
            replayed = result.replayed,
            correlationId = context.correlationId,
        )
    }
}

private fun String.asRowUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(
            code = code,
            message = "An import identifier is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun String?.asRowDecimal(code: String): BigDecimal? =
    this?.let {
        if (it.length > 80) {
            throw ProductApiException(
                code = code,
                message = "An import decimal exceeds the safe length.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        runCatching { BigDecimal(it) }.getOrElse {
            throw ProductApiException(
                code = code,
                message = "An import decimal is invalid.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
    }

private fun HiltechRequestContextSnapshot.requireRowIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authentication is required.",
            status = HttpStatus.UNAUTHORIZED,
        )
