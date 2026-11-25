package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PurchaseOrderCandidateServiceTest {
    private val now = Instant.parse("2026-11-22T16:00:00Z")
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val rfqId = UUID.randomUUID()
    private val requirementId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()
    private val supplier = UUID.randomUUID()
    private val quoteId = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val ctx = ProcurementDraftContext(operation, actor, "s03-test")
    private val sourceSha = "a".repeat(64)
    private val sourceRef = "evidence:${UUID.randomUUID()}"
    private val source = SupplierRfqRecord(
        rfqId, UUID.randomUUID(), org, requirementId, 3L, itemId,
        BigDecimal("100.000000"), "M", actor, now, "s03-test",
    )
    private val quote = SupplierQuoteEvidence(
        quoteId, rfqId, supplier, "SUP-001", 1, "EGP",
        BigDecimal("100"), "M", BigDecimal("12.50"), BigDecimal("20"),
        2, LocalDate.parse("2026-12-01"), "Delivered to site", "Payment on acceptance",
        null, sourceRef, sourceSha,
    )
    private val row = SupplierQuoteComparisonRow(
        quoteId, supplier, 1, "SUP-001", "EGP", "100", "M",
        "12.50", "20", "1270", 2, LocalDate.parse("2026-12-01"),
        sourceRef, sourceSha, "COMPLIANT", emptyList(),
    )
    private val rfqView = SupplierRfqView(
        rfqId, requirementId, 3L, "100.000000", "M", true, 1,
    )
    private val comparison = SupplierRfqComparisonView(
        rfqView,
        SupplierQuoteComparison(
            expectedQuantity = "100.000000",
            expectedUnitCode = "M",
            evaluatedAt = LocalDate.parse("2026-11-22"),
            latestBySupplier = listOf(row),
            currencies = listOf("EGP"),
            allQuotesDirectlyComparable = true,
        ),
        1, 1,
    )

    private val sourcing = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val sourcingService = Mockito.mock(SupplierSourcingService::class.java)
    private val buyer = Mockito.mock(SupplierSourcingAuthorizationPort::class.java)
    private val document = Mockito.mock(SupplierQuoteDocumentVerificationPort::class.java)
    private val candidates = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var submitted: PurchaseOrderCandidateRecord? = null
    private val history = linkedMapOf<UUID, PurchaseOrderCandidateRecord>()
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) =
            IdempotentCommandExecution(action(), false)
    }

    private fun service(
        enabled: Boolean = true,
        candidateEnabled: Boolean = true,
    ) = PurchaseOrderCandidateService(
        sourcing, sourcingService, buyer, document, candidates, commands, audit,
        Clock.fixed(now, ZoneOffset.UTC), enabled, candidateEnabled,
    )

    private fun allow() {
        Mockito.`when`(sourcing.rfq(rfqId)).thenReturn(source)
        Mockito.`when`(sourcing.lockRfq(rfqId)).thenReturn(true)
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(true)
        Mockito.`when`(sourcingService.comparison(actor, rfqId)).thenReturn(comparison)
        Mockito.`when`(sourcing.isEligibleSupplier(org, supplier)).thenReturn(true)
        Mockito.`when`(sourcing.latestRevision(rfqId, supplier)).thenReturn(1)
        Mockito.`when`(document.isVerified(actor, org, rfqId, supplier, sourceRef, sourceSha))
            .thenReturn(true)
        Mockito.`when`(sourcing.quotes(rfqId)).thenReturn(listOf(quote))
        Mockito.doAnswer {
            submitted = it.getArgument(0)
            submitted?.let { candidate -> history[candidate.id] = candidate }
            Unit
        }.`when`(candidates).insert(
            Mockito.any(PurchaseOrderCandidateRecord::class.java)
                ?: PurchaseOrderCandidateRecord(
                    UUID.randomUUID(), operation, org, rfqId, requirementId, 3L,
                    quoteId, 1, supplier, itemId, BigDecimal("100"), "M", "EGP",
                    BigDecimal("12.50"), BigDecimal("20"), BigDecimal("1270"),
                    sourceSha, "Fixture candidate", actor, now, "s03-test",
                ),
        )
        Mockito.`when`(candidates.byRfq(rfqId)).thenAnswer { submitted }
        Mockito.`when`(candidates.byId(Mockito.any(UUID::class.java) ?: UUID.randomUUID())).thenAnswer { history[it.getArgument(0)] }
    }

    private fun request(revision: Int = 1) = ProposePurchaseOrderCandidateRequest(
        operation, quoteId, revision, "Project material delivery is required for the approved work",
    )

    @Test fun buyerPreviewDoesNotAwardOrIssuePurchaseOrder() {
        allow()
        val result = service().preview(actor, rfqId, quoteId)
        assertTrue(result.canPropose)
        assertEquals(emptyList<String>(), result.blockers)
        assertEquals("1270", result.quotedTotal)
        assertFalse(result.purchaseAuthorized)
        assertFalse(result.purchaseOrderIssued)
        assertEquals(null, submitted)
    }

    @Test fun explicitCandidateSnapshotFreezesQuoteAndRequirementWithoutIssue() {
        allow()
        val receipt = service().propose(ctx, rfqId, request())
        assertEquals("DRAFT_FOR_APPROVAL", receipt.state)
        assertEquals(3L, receipt.requirementVersion)
        assertEquals(1L, receipt.candidateVersion)
        assertFalse(receipt.purchaseAuthorized)
        assertFalse(receipt.purchaseOrderIssued)
        val saved = requireNotNull(submitted)
        assertEquals(quoteId, saved.quoteId)
        assertEquals(supplier, saved.supplierOrganizationId)
        assertEquals(0, saved.quantity.compareTo(BigDecimal("100.000000")))
        assertEquals(0, saved.totalAmount.compareTo(BigDecimal("1270.000000")))
        assertEquals(sourceSha, saved.sourceSha256)
        val events = Mockito.mockingDetails(audit).invocations
            .filter { it.method.name == "append" }
            .map { it.arguments.single() as AuditEventRecord }
        assertEquals(1, events.size)
        assertEquals("PROCUREMENT_PO_CANDIDATE_PROPOSED", events.single().action)
    }

    @Test fun revokedSupplierAndUnverifiedDocumentBlockCandidate() {
        allow()
        Mockito.`when`(sourcing.isEligibleSupplier(org, supplier)).thenReturn(false)
        var preview = service().preview(actor, rfqId, quoteId)
        assertTrue("SUPPLIER_NOT_LINKED" in preview.blockers)
        Mockito.`when`(sourcing.isEligibleSupplier(org, supplier)).thenReturn(true)
        Mockito.`when`(document.isVerified(actor, org, rfqId, supplier, sourceRef, sourceSha))
            .thenReturn(false)
        preview = service().preview(actor, rfqId, quoteId)
        assertTrue("QUOTE_DOCUMENT_UNVERIFIED" in preview.blockers)
        assertEquals("PROCUREMENT_PO_CANDIDATE_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().propose(ctx, rfqId, request())
        }.code)
        assertEquals(null, submitted)
    }

    @Test fun technicalNoncomplianceAndStaleRfqBlockSelection() {
        allow()
        Mockito.`when`(sourcingService.comparison(actor, rfqId)).thenReturn(
            comparison.copy(comparison = comparison.comparison.copy(
                latestBySupplier = listOf(row.copy(
                    technicalCompliance = "NON_COMPLIANT",
                    issues = listOf("TECHNICAL_NON_COMPLIANT"),
                )),
            )),
        )
        assertTrue("TECHNICAL_NON_COMPLIANT" in
            service().preview(actor, rfqId, quoteId).blockers)
        Mockito.`when`(sourcingService.comparison(actor, rfqId)).thenReturn(
            comparison.copy(rfq = rfqView.copy(sourceCurrent = false)),
        )
        assertTrue("SOURCE_NOT_CURRENT" in service().preview(actor, rfqId, quoteId).blockers)
    }

    @Test fun unauthorizedAndDisabledBuyerCannotSeePriceOrPropose() {
        allow()
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(false)
        assertEquals("PROCUREMENT_PO_CANDIDATE_UNAVAILABLE", assertThrows<ProductApiException> {
            service().preview(actor, rfqId, quoteId)
        }.code)
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(true)
        assertEquals("PROCUREMENT_PO_CANDIDATE_UNAVAILABLE", assertThrows<ProductApiException> {
            service(candidateEnabled = false).propose(ctx, rfqId, request())
        }.code)
        assertEquals(null, submitted)
    }

    @Test fun changedRevisionOrExistingCandidateCannotSilentlyOverwrite() {
        allow()
        assertEquals("PROCUREMENT_PO_CANDIDATE_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().propose(ctx, rfqId, request(revision = 2))
        }.code)
        Mockito.`when`(candidates.byRfq(rfqId)).thenReturn(
            PurchaseOrderCandidateRecord(
                UUID.randomUUID(), UUID.randomUUID(), org, rfqId, requirementId,
                3, quoteId, 1, supplier, itemId, BigDecimal("100"), "M", "EGP",
                BigDecimal("12.5"), BigDecimal("20"), BigDecimal("1270"),
                sourceSha, "Older supplier choice", actor, now, "s03-test",
            ),
        )
        assertTrue(service().preview(actor, rfqId, quoteId).canReplace)
        assertEquals("PROCUREMENT_PO_CANDIDATE_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().propose(ctx, rfqId, request())
        }.code)
    }

    @Test fun explicitCorrectionPreservesPreviousCandidateAndCannotReplacePendingOrIssuedOrder() {
        allow()
        val original = service().propose(ctx, rfqId, request())
        val first = requireNotNull(submitted)
        Mockito.`when`(candidates.hasPendingReview(first.id)).thenReturn(true)
        assertFalse(service().preview(actor, rfqId, quoteId).canReplace)
        val nextOp = UUID.randomUUID()
        val correction = request().copy(operationId = nextOp, replacesCandidateId = first.id, expectedCandidateRevision = 1,
            rationale = "Corrected delivery terms checked against the supplier quotation")
        val nextContext = ctx.copy(operationId = nextOp)
        assertThrows<ProductApiException> { service().propose(nextContext, rfqId, correction) }
        Mockito.`when`(candidates.hasPendingReview(first.id)).thenReturn(false)
        Mockito.`when`(candidates.hasIssuedOrder(rfqId)).thenReturn(true)
        assertThrows<ProductApiException> { service().propose(nextContext, rfqId, correction) }
        Mockito.`when`(candidates.hasIssuedOrder(rfqId)).thenReturn(false)
        val second = service().propose(nextContext, rfqId, correction)
        assertEquals(2, second.candidateRevision)
        assertEquals(first.id, submitted?.replacesCandidateId)
        assertEquals(first, history[original.candidateId])
        assertEquals(2, history.size)
    }
}
