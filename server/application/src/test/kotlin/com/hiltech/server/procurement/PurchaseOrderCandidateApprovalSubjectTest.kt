package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.documents.EvidenceFinalizeRecord
import com.hiltech.server.documents.EvidencePersistencePort
import com.hiltech.server.warehouse.StockItemView
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PurchaseOrderCandidateApprovalSubjectTest {
    private val at = Instant.parse("2026-11-22T17:00:00Z")
    private val org = UUID.randomUUID()
    private val requirementId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val rfqId = UUID.randomUUID()
    private val quoteId = UUID.randomUUID()
    private val supplierId = UUID.randomUUID()
    private val candidateId = UUID.randomUUID()
    private val slotId = UUID.randomUUID()
    private val evidenceId = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val sha = "f".repeat(64)
    private val candidates = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
    private val sourcing = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val requirements = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val stock = Mockito.mock(StockLedgerPersistence::class.java)
    private val evidence = Mockito.mock(EvidencePersistencePort::class.java)
    private val job = Mockito.mock(WorkOrderSnapshot::class.java)
    private val stockItem = Mockito.mock(StockItemView::class.java)

    private val candidate = PurchaseOrderCandidateRecord(
        candidateId, UUID.randomUUID(), org, rfqId, requirementId,
        3, quoteId, 2, supplierId, itemId, BigDecimal("100.000000"), "M",
        "EGP", BigDecimal("12.5"), BigDecimal("20"), BigDecimal("1270"),
        sha, "Explicit supplier choice", actor, at, "test",
    )
    private val rfq = SupplierRfqRecord(
        rfqId, UUID.randomUUID(), org, requirementId, 3, itemId,
        BigDecimal("100"), "M", actor, at, "test",
    )
    private val source = PurchaseRequirementDraftRecord(
        requirementId, UUID.randomUUID(), org, workId, 7, itemId, 4,
        BigDecimal("100"), "M", "Cable", "WAREHOUSE_REVIEW_REQUIRED",
        null, at, actor, at, "test",
    )
    private val transition = PurchaseRequirementTransitionRecord(
        UUID.randomUUID(), UUID.randomUUID(), requirementId, org,
        2, 3, "TECHNICAL_READY", "SUBMITTED", "READY_FOR_SOURCING",
        null, actor, at, "test",
    )
    private val quote = SupplierQuoteEvidence(
        quoteId, rfqId, supplierId, "SUP-02", 2, "EGP",
        BigDecimal("100"), "M", BigDecimal("12.5"), BigDecimal("20"),
        3, LocalDate.parse("2026-12-01"), null, null, null,
        "evidence:$evidenceId", sha,
    )
    private val slot = SupplierQuoteDocumentSlot(
        slotId, UUID.randomUUID(), org, rfqId, supplierId, actor, at, "test",
    )
    private val recorded = EvidenceFinalizeRecord(
        evidenceId, UUID.randomUUID(), org, "SUPPLIER_QUOTE_DOCUMENT",
        slotId, null, null, actor,
        "SUPPLIER_QUOTE_BINARY", null, null, "SUPPLIER_QUOTE",
        "application/pdf", 12L, sha,
        "READY", "READY",
        "org/$org/evidence/$evidenceId/objects/${UUID.randomUUID()}",
        at.plusSeconds(600), "HIGHLY_RESTRICTED", "INTERNAL_ONLY",
        "SIGNED_DOCUMENT", 2L, 2L,
    )

    private fun guard(enabled: Boolean = true) = PurchaseOrderCandidateApprovalSubject(
        candidates, sourcing, requirements, work, stock, evidence,
        Clock.fixed(at, ZoneOffset.UTC), enabled,
    )
    private val subject = ApprovalSubjectRef(
        org, "PROCUREMENT_PO_CANDIDATE", candidateId, 1L,
    )

    private fun allow() {
        Mockito.`when`(candidates.byId(candidateId)).thenReturn(candidate)
        Mockito.`when`(candidates.byRfq(rfqId)).thenReturn(candidate)
        Mockito.`when`(sourcing.rfq(rfqId)).thenReturn(rfq)
        Mockito.`when`(requirements.byId(requirementId)).thenReturn(source)
        Mockito.`when`(requirements.latestTransition(requirementId)).thenReturn(transition)
        Mockito.`when`(work.workOrder(workId)).thenReturn(job)
        Mockito.`when`(stock.item(itemId)).thenReturn(stockItem)
        Mockito.`when`(job.organizationId).thenReturn(org)
        Mockito.`when`(job.version).thenReturn(7L)
        Mockito.`when`(job.lifecycleState).thenReturn(WorkOrderLifecycle.IN_PROGRESS)
        Mockito.`when`(stockItem.organizationId).thenReturn(org)
        Mockito.`when`(stockItem.version).thenReturn(4L)
        Mockito.`when`(stockItem.active).thenReturn(true)
        Mockito.`when`(stockItem.baseUnitCode).thenReturn("M")
        Mockito.`when`(sourcing.isEligibleSupplier(org, supplierId)).thenReturn(true)
        Mockito.`when`(sourcing.latestRevision(rfqId, supplierId)).thenReturn(2)
        Mockito.`when`(sourcing.quotes(rfqId)).thenReturn(listOf(quote))
        Mockito.`when`(sourcing.latestTechnicalAssessments(rfqId))
            .thenReturn(mapOf(quoteId to "COMPLIANT"))
        Mockito.`when`(evidence.loadEvidenceRecord(evidenceId)).thenReturn(recorded)
        Mockito.`when`(sourcing.quoteDocumentSlot(slotId)).thenReturn(slot)
    }

    @Test fun exactCanonicalApprovalSubjectIsCurrentOnlyWithLiveVersionedEvidence() {
        allow()
        assertTrue(guard().supports("PROCUREMENT_PO_CANDIDATE"))
        assertTrue(guard().isCurrent(subject))
        assertFalse(guard().isCurrent(subject.copy(subjectVersion = 2)))
        assertFalse(guard(false).isCurrent(subject))
        assertFalse(guard().isCurrent(subject.copy(organizationId = UUID.randomUUID())))
    }

    @Test fun newerSupplierQuoteAndRevokedSupplierInvalidatePendingApproval() {
        allow()
        Mockito.`when`(sourcing.latestRevision(rfqId, supplierId)).thenReturn(3)
        assertFalse(guard().isCurrent(subject))
        Mockito.`when`(sourcing.latestRevision(rfqId, supplierId)).thenReturn(2)
        Mockito.`when`(sourcing.isEligibleSupplier(org, supplierId)).thenReturn(false)
        assertFalse(guard().isCurrent(subject))
    }

    @Test fun workOrderChangesOrTechnicalRejectionSupersedeApprovalSource() {
        allow()
        Mockito.`when`(job.version).thenReturn(8L)
        assertFalse(guard().isCurrent(subject))
        Mockito.`when`(job.version).thenReturn(7L)
        Mockito.`when`(sourcing.latestTechnicalAssessments(rfqId))
            .thenReturn(mapOf(quoteId to "NON_COMPLIANT"))
        assertFalse(guard().isCurrent(subject))
    }

    @Test fun quarantineOrReplacedDocumentBlocksApproval() {
        allow()
        Mockito.`when`(evidence.loadEvidenceRecord(evidenceId))
            .thenReturn(recorded.copy(storageState = "QUARANTINED"))
        assertFalse(guard().isCurrent(subject))
        Mockito.`when`(evidence.loadEvidenceRecord(evidenceId))
            .thenReturn(recorded.copy(targetId = UUID.randomUUID()))
        assertFalse(guard().isCurrent(subject))
    }

    @Test fun correctedCandidateLeavesHistoricalSourceVisibleButNotCurrentForNewApproval() {
        allow()
        Mockito.`when`(candidates.byRfq(rfqId)).thenReturn(candidate.copy(id = UUID.randomUUID(), revisionNumber = 2, replacesCandidateId = candidate.id))
        assertFalse(guard().isCurrent(subject))
        assertTrue(candidates.byId(candidate.id) == candidate)
    }
}
