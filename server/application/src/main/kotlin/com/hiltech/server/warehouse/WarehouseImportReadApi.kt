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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Safe read model for the non-authoritative Phase 5 / Slice 01 import lane.
 *
 * Raw spreadsheets may embed arbitrary financial and personal data in columns
 * with misleading names. Do not publish raw JSON or candidate monetary values
 * through this endpoint. A separately reviewed, value-authorized preview
 * contract must precede any raw/value display in the Windows workspace.
 */
@Component
class WarehouseImportReadService(
    private val persistence: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
) {
    fun batchDetail(actorUserId: UUID, batchId: UUID): InventoryImportBatchDetail {
        val batch = persistence.importBatch(batchId) ?: throw importNotFound()
        if (!authorization.canManageImport(actorUserId, batch.organizationId)) {
            // Same public response whether a batch is absent or not visible.
            throw importNotFound()
        }
        val rows = persistence.importRows(batch.batchId)
            .filter { it.batchId == batch.batchId && it.organizationId == batch.organizationId }
        return InventoryImportBatchDetail(batch, rows)
    }

    private fun importNotFound() = ProductApiException(
        code = "INVENTORY_IMPORT_NOT_FOUND",
        message = "The inventory import is unavailable.",
        status = HttpStatus.NOT_FOUND,
    )
}

data class InventoryImportBatchReadResponse(
    val batchId: String,
    val organizationId: String,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceType: String,
    val cutoverDate: String?,
    val state: String,
    val version: Long,
    val rows: List<InventoryImportRowReadResponse>,
    val correlationId: String,
)

data class InventoryImportRowReadResponse(
    val rowId: String,
    val batchId: String,
    val sourceSheet: String,
    val sourceRowNumber: Int,
    val candidateClass: String,
    val normalizedBrand: String?,
    val normalizedPartNumber: String?,
    val normalizedDescription: String?,
    val candidateUom: String?,
    val candidateQuantity: String?,
    val issueCodes: List<String>,
    val reviewState: String,
    val version: Long,
)

@RestController
@RequestMapping("/v1/inventory-imports")
class WarehouseImportReadController(
    private val service: WarehouseImportReadService,
) {
    @GetMapping("/{batchId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable batchId: String,
    ): InventoryImportBatchReadResponse {
        val context = HiltechRequestContext.current(servletRequest)
        val batchIdValue = runCatching { UUID.fromString(batchId) }
            .getOrElse {
                throw ProductApiException(
                    code = "INVALID_IMPORT_BATCH_ID",
                    message = "The inventory import identifier is invalid.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
        val detail = service.batchDetail(context.requireImportIdentity(), batchIdValue)
        return InventoryImportBatchReadResponse(
            batchId = detail.batch.batchId.toString(),
            organizationId = detail.batch.organizationId.toString(),
            sourceFileName = detail.batch.sourceFileName,
            sourceSha256 = detail.batch.sourceSha256,
            sourceType = detail.batch.sourceType.name,
            cutoverDate = detail.batch.cutoverDate?.toString(),
            state = detail.batch.state.name,
            version = detail.batch.version,
            rows = detail.rows.map { row ->
                InventoryImportRowReadResponse(
                    rowId = row.rowId.toString(),
                    batchId = row.batchId.toString(),
                    sourceSheet = row.sourceSheet,
                    sourceRowNumber = row.sourceRowNumber,
                    candidateClass = row.candidateClass.name,
                    normalizedBrand = row.normalizedBrand,
                    normalizedPartNumber = row.normalizedPartNumber,
                    normalizedDescription = row.normalizedDescription,
                    candidateUom = row.candidateUom,
                    candidateQuantity = row.candidateQuantity?.toPlainString(),
                    issueCodes = row.issueCodes.map { it.name },
                    reviewState = row.reviewState.name,
                    version = row.version,
                )
            },
            correlationId = context.correlationId,
        )
    }
}

private fun HiltechRequestContextSnapshot.requireImportIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authentication is required.",
            status = HttpStatus.UNAUTHORIZED,
        )
