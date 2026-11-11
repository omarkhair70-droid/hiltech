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
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Manual review of a staged, immutable raw spreadsheet row.
 *
 * Approval requires verified source and mapping evidence. Slice 01 currently
 * records candidate edits and rejection without granting approval.
 */
@Component
class WarehouseImportRowReviewService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun review(command: ReviewInventoryImportRowCommand): ImportRowCommandResult {
        val batch = persistence.importBatch(command.batchId) ?: throw unavailable()
        requireImportAccess(command.actorUserId, batch.organizationId)
        if (command.candidateValue != null || command.candidateCurrencyCode != null) {
            throw ProductApiException(
                code = "INVENTORY_VALUE_REVIEW_REQUIRES_SEPARATE_AUTHORITY",
                message = "Restricted source values require a separately authorized review workflow.",
                status = HttpStatus.FORBIDDEN,
            )
        }
        if (command.baseVersion <= 0) {
            throw invalid("INVALID_ROW_VERSION", "A positive import row version is required.")
        }
        val normalized = normalize(command)
        val fingerprint = warehouseCommandFingerprint(
            normalized.batchId, normalized.rowId, normalized.baseVersion,
            normalized.candidateClass, normalized.normalizedBrand,
            normalized.normalizedPartNumber, normalized.normalizedDescription,
            normalized.candidateUom, normalized.candidateQuantity,
            normalized.candidateValue, normalized.candidateCurrencyCode,
            normalized.issueCodes.sortedBy { it.name }, normalized.reviewState,
            normalized.reviewNotes, normalized.clientOccurredAt,
        )
        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = normalized.operationId,
                actorUserId = normalized.actorUserId,
                commandType = "INVENTORY_IMPORT_ROW_REVIEW",
                targetType = "INVENTORY_IMPORT_ROW",
                targetId = normalized.rowId,
                requestFingerprint = fingerprint,
                correlationId = normalized.correlationId,
            ),
        ) {
            val locked = persistence.lockImportBatch(normalized.batchId)
                ?: throw unavailable()
            requireImportAccess(normalized.actorUserId, locked.organizationId)
            if (locked.state == InventoryImportBatchState.CANCELLED ||
                locked.state == InventoryImportBatchState.REVIEWED
            ) {
                throw ProductApiException(
                    code = "INVENTORY_IMPORT_BATCH_CLOSED",
                    message = "The batch cannot be reviewed in its current state.",
                    status = HttpStatus.CONFLICT,
                )
            }
            val previous = persistence.importRow(normalized.rowId) ?: throw unavailable()
            if (previous.batchId != locked.batchId ||
                previous.organizationId != locked.organizationId
            ) {
                throw unavailable()
            }
            if (previous.version != normalized.baseVersion) {
                throw ProductApiException(
                    code = "INVENTORY_IMPORT_ROW_VERSION_CONFLICT",
                    message = "The row was updated; reload before reviewing.",
                    status = HttpStatus.CONFLICT,
                    currentVersion = previous.version,
                )
            }
            if (normalized.reviewState == InventoryImportReviewState.PENDING) {
                throw invalid(
                    "INVALID_IMPORT_REVIEW_STATE",
                    "A review decision must be NEEDS_REVIEW, REJECTED or APPROVED.",
                )
            }

            val safeReview = normalized.copy(
                candidateValue = previous.candidateValue,
                candidateCurrencyCode = previous.candidateCurrencyCode,
            )
            val requestedClears = previous.issueCodes.toSet() - normalized.issueCodes.toSet()
            val issues = deriveMandatoryIssues(locked.sourceType, previous, normalized)
                .plus(normalized.issueCodes)
                .plus(requestedClears.filterNot { it in candidateReviewIssues })
                .distinct()
                .sortedBy { it.name }
            val removedIssues = previous.issueCodes.toSet() - issues.toSet()
            if (removedIssues.isNotEmpty() && normalized.reviewNotes.isNullOrBlank()) {
                throw invalid(
                    "IMPORT_ISSUE_RESOLUTION_REASON_REQUIRED",
                    "Explain which source issues are resolved before clearing them.",
                )
            }
            if (normalized.reviewState == InventoryImportReviewState.REJECTED &&
                normalized.reviewNotes.isNullOrBlank()
            ) {
                throw invalid(
                    "IMPORT_REJECTION_REASON_REQUIRED",
                    "Rejected import rows require an explanation.",
                )
            }
            if (normalized.reviewState == InventoryImportReviewState.APPROVED) {
                throw ProductApiException(
                    code = "INVENTORY_IMPORT_ROW_REVIEW_BLOCKED",
                    message = "Approval requires verified source and mapping evidence.",
                    status = HttpStatus.CONFLICT,
                )
            }
            if (!persistence.updateImportRowReview(safeReview, issues, clock.instant())) {
                throw ProductApiException(
                    code = "INVENTORY_IMPORT_ROW_VERSION_CONFLICT",
                    message = "The row was changed concurrently.",
                    status = HttpStatus.CONFLICT,
                )
            }
            audit.append(
                AuditEventRecord(
                    actorUserId = normalized.actorUserId,
                    action = "INVENTORY_IMPORT_ROW_REVIEWED",
                    targetType = "INVENTORY_IMPORT_ROW",
                    targetId = normalized.rowId,
                    previousStateRef = previous.reviewState.name,
                    newStateRef = normalized.reviewState.name,
                    safeDiffJson =
                        """{"fields":["candidateClass","candidateUom","candidateQuantity","issueCodes","reviewState","reviewNotes"]}""",
                    occurredAt = clock.instant(),
                    correlationId = normalized.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                resultCode = "INVENTORY_IMPORT_ROW_REVIEWED",
                resultPayloadJson = buildJsonObject {
                    put("rowId", normalized.rowId.toString())
                }.toString(),
            )
        }

        val saved = persistence.importRow(normalized.rowId) ?: throw unavailable()
        if (saved.batchId != normalized.batchId ||
            saved.organizationId != batch.organizationId
        ) {
            throw unavailable()
        }
        return ImportRowCommandResult(saved, execution.replayed)
    }

    private fun deriveMandatoryIssues(
        sourceType: InventoryImportSourceType,
        previous: InventoryImportRowSnapshot,
        command: ReviewInventoryImportRowCommand,
    ): List<InventoryImportIssueCode> {
        val issues = LegacyInventorySource.issues(sourceType, previous.sourceSheet, previous.rawPayloadJson)
            .toMutableSet()
        if (command.candidateClass == InventoryCandidateClass.UNCLASSIFIED) {
            issues += InventoryImportIssueCode.ITEM_CLASSIFICATION_REQUIRED
        }
        if (command.candidateUom == null) {
            issues += InventoryImportIssueCode.UNKNOWN_UOM
        }
        if (command.normalizedBrand == null &&
            InventoryImportIssueCode.BRAND_NORMALIZATION_REQUIRED in previous.issueCodes
        ) {
            issues += InventoryImportIssueCode.BRAND_NORMALIZATION_REQUIRED
        }
        val part = command.normalizedPartNumber
        if (part == null) {
            issues += InventoryImportIssueCode.PART_NUMBER_MISSING
        } else if (part.uppercase(Locale.ROOT) in setOf("NONE", "N/A", "NA", "-", "UNKNOWN")) {
            issues += InventoryImportIssueCode.PART_NUMBER_PLACEHOLDER
        } else if (persistence.duplicatePartNumberExists(
                command.batchId, part, excludingRowId = command.rowId,
            )
        ) {
            issues += InventoryImportIssueCode.DUPLICATE_PART_NUMBER
        }
        if (command.candidateQuantity == null) {
            issues += InventoryImportIssueCode.QUANTITY_AMBIGUOUS
        }
        return issues.toList()
    }

    private fun normalize(command: ReviewInventoryImportRowCommand): ReviewInventoryImportRowCommand {
        fun optional(value: String?, max: Int, code: String): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > max) {
                throw invalid(code, "An import candidate field is too long.")
            }
            return trimmed
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
            throw invalid("INVALID_CANDIDATE_CURRENCY", "Candidate currency is invalid.")
        }
        fun decimal(value: BigDecimal?, code: String): BigDecimal? {
            if (value == null) return null
            val exact = try {
                value.stripTrailingZeros()
            } catch (failure: ArithmeticException) {
                throw invalid(code, "Candidate number is outside numeric(20,6) limits.")
            }
            if (exact < BigDecimal.ZERO || exact.scale() > 6 ||
                exact.precision().toLong() - exact.scale() > 14
            ) {
                throw invalid(code, "Candidate number is outside numeric(20,6) limits.")
            }
            return exact
        }
        val notes = optional(command.reviewNotes, 4_000, "INVALID_IMPORT_REVIEW_NOTES")
        return command.copy(
            normalizedBrand = brand,
            normalizedPartNumber = part,
            normalizedDescription = description,
            candidateUom = uom,
            candidateQuantity = decimal(command.candidateQuantity, "INVALID_CANDIDATE_QUANTITY"),
            candidateValue = decimal(command.candidateValue, "INVALID_CANDIDATE_VALUE"),
            candidateCurrencyCode = currency,
            reviewNotes = notes,
            issueCodes = command.issueCodes.distinct(),
        )
    }

    private fun requireImportAccess(actor: UUID, organization: UUID) {
        if (!authorization.canManageImport(actor, organization)) throw unavailable()
    }

    private fun unavailable() = ProductApiException(
        code = "INVENTORY_IMPORT_ROW_UNAVAILABLE",
        message = "The import row is unavailable.",
        status = HttpStatus.NOT_FOUND,
    )

    private fun invalid(code: String, message: String) =
        ProductApiException(code = code, message = message, status = HttpStatus.BAD_REQUEST)
}

private val candidateReviewIssues = setOf(
    InventoryImportIssueCode.ITEM_CLASSIFICATION_REQUIRED,
    InventoryImportIssueCode.UNKNOWN_UOM,
    InventoryImportIssueCode.BRAND_NORMALIZATION_REQUIRED,
    InventoryImportIssueCode.PART_NUMBER_MISSING,
    InventoryImportIssueCode.PART_NUMBER_PLACEHOLDER,
    InventoryImportIssueCode.DUPLICATE_PART_NUMBER,
    InventoryImportIssueCode.QUANTITY_AMBIGUOUS,
)

data class ReviewInventoryImportRowRequest(
    val operationId: String,
    val baseVersion: Long,
    val candidateClass: String,
    val normalizedBrand: String? = null,
    val normalizedPartNumber: String? = null,
    val normalizedDescription: String? = null,
    val candidateUom: String? = null,
    val candidateQuantity: String? = null,
    val candidateValue: String? = null,
    val candidateCurrencyCode: String? = null,
    val issueCodes: List<String>? = null,
    val reviewState: String,
    val reviewNotes: String? = null,
    val clientOccurredAt: String,
)

data class ReviewInventoryImportRowResponse(
    val rowId: String,
    val batchId: String,
    val reviewState: String,
    val issueCodes: List<String>,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class WarehouseImportRowReviewController(
    private val service: WarehouseImportRowReviewService,
) {
    @PostMapping("/v1/inventory-imports/{batchId}/rows/{rowId}/review")
    fun review(
        servletRequest: HttpServletRequest,
        @PathVariable batchId: String,
        @PathVariable rowId: String,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: ReviewInventoryImportRowRequest,
    ): ReviewInventoryImportRowResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.asReviewUuid("INVALID_OPERATION_ID")
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val candidateClass = runCatching {
            enumValueOf<InventoryCandidateClass>(request.candidateClass)
        }.getOrElse {
            throw invalidRequest("INVALID_CANDIDATE_CLASS", "Candidate class is invalid.")
        }
        val reviewState = runCatching {
            enumValueOf<InventoryImportReviewState>(request.reviewState)
        }.getOrElse {
            throw invalidRequest("INVALID_REVIEW_STATE", "Review state is invalid.")
        }
        val issueCodes = request.issueCodes.orEmpty().map { issue ->
            runCatching { enumValueOf<InventoryImportIssueCode>(issue) }.getOrElse {
                throw invalidRequest("INVALID_ISSUE_CODE", "Review issue code is invalid.")
            }
        }
        if (issueCodes.size > 64) {
            throw invalidRequest("TOO_MANY_REVIEW_ISSUES", "Issue list is too large.")
        }
        val occurredAt = runCatching { Instant.parse(request.clientOccurredAt) }
            .getOrElse {
                throw invalidRequest("INVALID_CLIENT_OCCURRED_AT", "Review timestamp is invalid.")
            }
        fun decimal(raw: String?, code: String): BigDecimal? {
            if (raw == null) return null
            if (raw.length > 80) throw invalidRequest(code, "Candidate decimal is too long.")
            return runCatching { BigDecimal(raw) }.getOrElse {
                throw invalidRequest(code, "Candidate decimal is invalid.")
            }
        }

        val result = service.review(
            ReviewInventoryImportRowCommand(
                operationId = operationId,
                batchId = batchId.asReviewUuid("INVALID_IMPORT_BATCH_ID"),
                rowId = rowId.asReviewUuid("INVALID_IMPORT_ROW_ID"),
                baseVersion = request.baseVersion,
                candidateClass = candidateClass,
                normalizedBrand = request.normalizedBrand,
                normalizedPartNumber = request.normalizedPartNumber,
                normalizedDescription = request.normalizedDescription,
                candidateUom = request.candidateUom,
                candidateQuantity = decimal(request.candidateQuantity, "INVALID_CANDIDATE_QUANTITY"),
                candidateValue = decimal(request.candidateValue, "INVALID_CANDIDATE_VALUE"),
                candidateCurrencyCode = request.candidateCurrencyCode,
                issueCodes = issueCodes,
                reviewState = reviewState,
                reviewNotes = request.reviewNotes,
                actorUserId = context.requireReviewIdentity(),
                clientOccurredAt = occurredAt,
                correlationId = context.correlationId,
            ),
        )
        return ReviewInventoryImportRowResponse(
            rowId = result.row.rowId.toString(),
            batchId = result.row.batchId.toString(),
            reviewState = result.row.reviewState.name,
            issueCodes = result.row.issueCodes.map { it.name },
            version = result.row.version,
            replayed = result.replayed,
            correlationId = context.correlationId,
        )
    }

    private fun invalidRequest(code: String, message: String) =
        ProductApiException(code = code, message = message, status = HttpStatus.BAD_REQUEST)
}

private fun String.asReviewUuid(code: String): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw ProductApiException(
            code = code,
            message = "An inventory review identifier is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun HiltechRequestContextSnapshot.requireReviewIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authentication is required.",
            status = HttpStatus.UNAUTHORIZED,
        )
