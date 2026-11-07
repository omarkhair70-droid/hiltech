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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Review decisions cannot erase raw evidence or authorize stock/custody mutation. */
class WarehouseImportRowReviewServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val executor = RecordingExecutor()
    private val clock = Clock.fixed(
        Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC,
    )
    private val service = WarehouseImportRowReviewService(
        persistence, authorization, executor, audit, clock,
    )
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val batchId = UUID.randomUUID()
    private val rowId = UUID.randomUUID()

    @Test
    fun deniedUserCannotReviewEvenWhenBatchExists() {
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        val failure = assertThrows<ProductApiException> {
            service.review(command())
        }
        assertEquals("INVENTORY_IMPORT_ROW_UNAVAILABLE", failure.code)
        assertEquals(0, executor.calls)
        assertEquals(0, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test
    fun staleRowVersionIsRejectedUnderBatchLock() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(row(version = 3))

        val failure = assertThrows<ProductApiException> {
            service.review(command(baseVersion = 2))
        }
        assertEquals("INVENTORY_IMPORT_ROW_VERSION_CONFLICT", failure.code)
        assertEquals(3L, failure.currentVersion)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations.none {
                it.method.name == "updateImportRowReview"
            },
        )
    }

    @Test
    fun legacySerialHeaderBlocksApprovalEvenWhenReviewerClearsIssueCodes() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(
            row(raw = """{"الرقم التسلسلي":"LOCAL"}"""),
        )
        val failure = assertThrows<ProductApiException> {
            service.review(command(
                reviewState = InventoryImportReviewState.APPROVED,
                notes = "Reviewed as candidate stock, but source serial label unresolved.",
            ))
        }
        assertEquals("INVENTORY_IMPORT_ROW_REVIEW_BLOCKED", failure.code)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations.none {
                it.method.name == "updateImportRowReview"
            },
        )
    }

    @Test
    fun clearingHistoricalIssueRequiresExplicitReviewerExplanation() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(
            row(
                raw = """{"ITEM#":"1"}""",
                issues = listOf(InventoryImportIssueCode.BRAND_NORMALIZATION_REQUIRED),
            ),
        )
        val failure = assertThrows<ProductApiException> {
            service.review(command(reviewState = InventoryImportReviewState.NEEDS_REVIEW))
        }
        assertEquals("IMPORT_ISSUE_RESOLUTION_REASON_REQUIRED", failure.code)
    }

    @Test
    fun cleanCandidateCanBeReviewedWithoutCreatingStockOrAsset() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())
        val before = row(raw = """{"ITEM#":"1"}""")
        val after = before.copy(
            version = 2,
            reviewState = InventoryImportReviewState.APPROVED,
            reviewedByUserId = actor,
            reviewedAt = clock.instant(),
        )
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(before, after)
        val request = command(reviewState = InventoryImportReviewState.APPROVED)
        Mockito.`when`(
            persistence.updateImportRowReview(request, emptyList(), clock.instant()),
        ).thenReturn(true)

        val outcome = service.review(request)
        assertEquals(InventoryImportReviewState.APPROVED, outcome.row.reviewState)
        assertEquals(2L, outcome.row.version)
        assertEquals(1, executor.calls)
        Mockito.verify(persistence).updateImportRowReview(
            request, emptyList(), clock.instant(),
        )
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test
    fun safeReviewerEditPreservesSourceCostAndCurrencyThatTheUiCannotSee() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())
        val previous = row(raw = """{"Unit Cost":240.25,"Currency":"EGP"}""")
            .copy(candidateValue = BigDecimal("240.25"), candidateCurrencyCode = "EGP")
        val after = previous.copy(
            version = 2,
            reviewState = InventoryImportReviewState.NEEDS_REVIEW,
            reviewedByUserId = actor,
            reviewedAt = clock.instant(),
        )
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(previous, after)
        val request = command(reviewState = InventoryImportReviewState.NEEDS_REVIEW)
        val expected = request.copy(
            candidateValue = BigDecimal("240.25"),
            candidateCurrencyCode = "EGP",
        )
        Mockito.`when`(
            persistence.updateImportRowReview(expected, emptyList(), clock.instant()),
        ).thenReturn(true)

        val result = service.review(request)
        assertEquals(2L, result.row.version)
        Mockito.verify(persistence).updateImportRowReview(
            expected, emptyList(), clock.instant(),
        )
    }

    @Test
    fun ordinaryReviewCannotRewriteRestrictedSourceCost() {
        authorize()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.lockImportBatch(batchId)).thenReturn(batch())
        Mockito.`when`(persistence.importRow(rowId)).thenReturn(
            row().copy(candidateValue = BigDecimal("240.25"), candidateCurrencyCode = "EGP"),
        )
        val request = command().copy(candidateValue = BigDecimal("9999.99"))
        val failure = assertThrows<ProductApiException> { service.review(request) }
        assertEquals("INVENTORY_VALUE_REVIEW_REQUIRES_SEPARATE_AUTHORITY", failure.code)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations.none {
                it.method.name == "updateImportRowReview"
            },
        )
    }

    private fun authorize() {
        Mockito.`when`(authorization.canManageImport(actor, organization)).thenReturn(true)
    }

    private fun command(
        baseVersion: Long = 1,
        reviewState: InventoryImportReviewState = InventoryImportReviewState.NEEDS_REVIEW,
        notes: String? = null,
    ) = ReviewInventoryImportRowCommand(
        operationId = UUID.randomUUID(),
        batchId = batchId,
        rowId = rowId,
        baseVersion = baseVersion,
        candidateClass = InventoryCandidateClass.STOCK_QUANTITY,
        normalizedBrand = "PANDUIT",
        normalizedPartNumber = "PUL6AV04WH-EG",
        normalizedDescription = "PANDUIT network accessory",
        candidateUom = "PC",
        candidateQuantity = BigDecimal("5"),
        candidateValue = null,
        candidateCurrencyCode = null,
        issueCodes = emptyList(),
        reviewState = reviewState,
        reviewNotes = notes,
        actorUserId = actor,
        clientOccurredAt = clock.instant(),
        correlationId = "phase5-row-review-test",
    )

    private fun batch() = InventoryImportBatchSnapshot(
        batchId = batchId,
        organizationId = organization,
        sourceFileName = "legacy.xlsx",
        sourceSha256 = "a".repeat(64),
        sourceType = InventoryImportSourceType.LEGACY_EXCEL,
        cutoverDate = null,
        state = InventoryImportBatchState.IN_REVIEW,
        createdByUserId = actor,
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
        version = 2,
    )

    private fun row(
        version: Long = 1,
        raw: String = """{"ITEM#":"1"}""",
        issues: List<InventoryImportIssueCode> = emptyList(),
    ) = InventoryImportRowSnapshot(
        rowId = rowId,
        batchId = batchId,
        organizationId = organization,
        sourceSheet = "Main",
        sourceRowNumber = 2,
        rawPayloadJson = raw,
        candidateClass = InventoryCandidateClass.UNCLASSIFIED,
        normalizedBrand = null,
        normalizedPartNumber = null,
        normalizedDescription = null,
        candidateUom = null,
        candidateQuantity = null,
        candidateValue = null,
        candidateCurrencyCode = null,
        issueCodes = issues,
        reviewState = InventoryImportReviewState.NEEDS_REVIEW,
        reviewNotes = null,
        reviewedByUserId = null,
        reviewedAt = null,
        approvedTargetType = null,
        approvedTargetId = null,
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
        version = version,
    )

    private class RecordingExecutor : IdempotentCommandExecutor {
        var calls = 0
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution {
            calls += 1
            return IdempotentCommandExecution(action(), replayed = false)
        }
    }
}
