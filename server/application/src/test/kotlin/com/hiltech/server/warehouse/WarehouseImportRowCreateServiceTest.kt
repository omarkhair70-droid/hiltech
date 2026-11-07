package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Tests staging contract without assuming parser or legacy column labels are trusted. */
class WarehouseImportRowCreateServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val executor = RecordingExecutor()
    private val clock = Clock.fixed(
        Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC,
    )
    private val service = WarehouseImportRowCreateService(
        persistence, authorization, executor, audit, clock,
    )
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val batchId = UUID.randomUUID()

    @Test
    fun unauthorizedImportDoesNotEnterCommandTransaction() {
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        val error = assertThrows<ProductApiException> {
            service.addRow(command())
        }
        assertEquals("INVENTORY_IMPORT_NOT_FOUND", error.code)
        assertEquals(0, executor.calls)
        assertEquals(0, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test
    fun invalidSourceJsonCannotStageStockOrImportRows() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        val error = assertThrows<ProductApiException> {
            service.addRow(command(raw = """["not a row object"]"""))
        }
        assertEquals("INVALID_SOURCE_ROW_PAYLOAD", error.code)
        assertEquals(0, executor.calls)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations
                .none { it.method.name == "insertImportRow" },
        )
    }

    @Test
    fun cancelledBatchIsRejectedUnderTransactionalLock() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(
            batch(InventoryImportBatchState.CANCELLED),
        )
        val error = assertThrows<ProductApiException> {
            service.addRow(command())
        }
        assertEquals("INVENTORY_IMPORT_BATCH_CLOSED", error.code)
        assertEquals(1, executor.calls)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations
                .none { it.method.name == "insertImportRow" },
        )
    }

    @Test
    fun ambiguousSerialLabelNeverBecomesApprovedOrCreatesInventory() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())

        val request = command(raw = """{"الرقم التسلسلي":"LOCAL"}""")
        val rowId = UUID.nameUUIDFromBytes(
            ("hiltech:inventory-import-row:" + request.operationId)
                .toByteArray(StandardCharsets.UTF_8),
        )
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(
            row(rowId, request.rawPayloadJson),
        )

        val outcome = service.addRow(request)
        assertEquals(rowId, outcome.row.rowId)
        assertEquals(InventoryImportReviewState.PENDING, outcome.row.reviewState)
        assertEquals(1, executor.calls)

        val staged = Mockito.mockingDetails(persistence).invocations
            .single { it.method.name == "insertImportRow" }
        val sentIssues = staged.arguments[3] as List<*>
        assertTrue(
            sentIssues.contains(InventoryImportIssueCode.LEGACY_COLUMN_SEMANTICS_AMBIGUOUS),
        )
        assertTrue(sentIssues.contains(InventoryImportIssueCode.ITEM_CLASSIFICATION_REQUIRED))
        assertTrue(sentIssues.contains(InventoryImportIssueCode.UNKNOWN_UOM))
        assertTrue(sentIssues.contains(InventoryImportIssueCode.PART_NUMBER_MISSING))
        assertTrue(sentIssues.contains(InventoryImportIssueCode.QUANTITY_AMBIGUOUS))

        val transitions = Mockito.mockingDetails(persistence).invocations
            .filter { it.method.name == "setImportBatchState" }
        assertEquals(1, transitions.size)
        assertEquals(InventoryImportBatchState.IN_REVIEW, transitions.single().arguments[1])
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations.none {
                it.method.name.contains("Stock", ignoreCase = true) ||
                    it.method.name.contains("Asset", ignoreCase = true)
            },
        )
    }

    @Test
    fun duplicatePartNumberIsRecordedAsReviewIssue() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())
        Mockito.`when`(
            persistence.duplicatePartNumberExists(batchId, "14130646", null),
        ).thenReturn(true)

        val request = command(
            raw = """{"PART NUMBER":"14130646"}""",
            candidateClass = InventoryCandidateClass.STOCK_QUANTITY,
            normalizedPart = "14130646",
            candidateUom = "PC",
            candidateQuantity = BigDecimal("3"),
        )
        val rowId = UUID.nameUUIDFromBytes(
            ("hiltech:inventory-import-row:" + request.operationId)
                .toByteArray(StandardCharsets.UTF_8),
        )
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(row(rowId, request.rawPayloadJson))

        service.addRow(request)
        val sentIssues = Mockito.mockingDetails(persistence).invocations
            .single { it.method.name == "insertImportRow" }
            .arguments[3] as List<*>
        assertTrue(sentIssues.contains(InventoryImportIssueCode.DUPLICATE_PART_NUMBER))
    }

    private fun authorize() {
        Mockito.`when`(authorization.canManageImport(actor, organization)).thenReturn(true)
    }

    private fun command(
        raw: String = """{"serial":"LOCAL"}""",
        candidateClass: InventoryCandidateClass = InventoryCandidateClass.UNCLASSIFIED,
        normalizedPart: String? = null,
        candidateUom: String? = null,
        candidateQuantity: BigDecimal? = null,
    ) = AddInventoryImportRowCommand(
        operationId = UUID.randomUUID(),
        batchId = batchId,
        sourceSheet = "Accessories",
        sourceRowNumber = 2,
        rawPayloadJson = raw,
        candidateClass = candidateClass,
        normalizedBrand = null,
        normalizedPartNumber = normalizedPart,
        normalizedDescription = "Example from legacy spreadsheet",
        candidateUom = candidateUom,
        candidateQuantity = candidateQuantity,
        candidateValue = null,
        candidateCurrencyCode = null,
        sourceIssueCodes = emptyList(),
        actorUserId = actor,
        clientOccurredAt = clock.instant(),
        correlationId = "phase5-import-row-test",
    )

    private fun batch(state: InventoryImportBatchState = InventoryImportBatchState.UPLOADED) =
        InventoryImportBatchSnapshot(
            batchId = batchId,
            organizationId = organization,
            sourceFileName = "inventory.xlsx",
            sourceSha256 = "a".repeat(64),
            sourceType = InventoryImportSourceType.LEGACY_EXCEL,
            cutoverDate = null,
            state = state,
            createdByUserId = actor,
            createdAt = clock.instant(),
            updatedAt = clock.instant(),
            version = 1,
        )

    private fun row(rowId: UUID, raw: String) = InventoryImportRowSnapshot(
        rowId = rowId,
        batchId = batchId,
        organizationId = organization,
        sourceSheet = "Accessories",
        sourceRowNumber = 2,
        rawPayloadJson = raw,
        candidateClass = InventoryCandidateClass.UNCLASSIFIED,
        normalizedBrand = null,
        normalizedPartNumber = null,
        normalizedDescription = "Example from legacy spreadsheet",
        candidateUom = null,
        candidateQuantity = null,
        candidateValue = null,
        candidateCurrencyCode = null,
        issueCodes = emptyList(),
        reviewState = InventoryImportReviewState.PENDING,
        reviewNotes = null,
        reviewedByUserId = null,
        reviewedAt = null,
        approvedTargetType = null,
        approvedTargetId = null,
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
        version = 1,
    )

    private class RecordingExecutor : IdempotentCommandExecutor {
        var calls: Int = 0
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution {
            calls += 1
            return IdempotentCommandExecution(action(), replayed = false)
        }
    }
}
