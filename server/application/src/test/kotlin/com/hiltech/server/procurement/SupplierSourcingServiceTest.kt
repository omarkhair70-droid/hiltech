package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.StockClassification
import com.hiltech.server.warehouse.StockItemView
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkPersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SupplierSourcingServiceTest {
    private val at = Instant.parse("2026-11-22T08:00:00Z")
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val requirementId = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()
    private val supplierId = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val rfqId = UUID.nameUUIDFromBytes(
        "hiltech:procurement:supplier-rfq:$op".toByteArray(Charsets.UTF_8),
    )
    private val sourcing = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val requirements = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val stock = Mockito.mock(StockLedgerPersistence::class.java)
    private val auth = Mockito.mock(SupplierSourcingAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val order = Mockito.mock(WorkOrderSnapshot::class.java)
    private var createdRfq: SupplierRfqRecord? = null
    private var createdQuote: SupplierQuoteRecord? = null
    private val root = PurchaseRequirementDraftRecord(
        id = requirementId, operationId = UUID.randomUUID(), organizationId = org,
        workOrderId = workId, sourceWorkVersion = 5, stockItemId = itemId, stockItemVersion = 3,
        requestedQuantity = BigDecimal("100"), unitCode = "M", justification = "Fiber cable",
        assessmentCode = "WAREHOUSE_REVIEW_REQUIRED", visibleFreeQuantity = null,
        assessedAt = at, requestedBy = actor, createdAt = at,
        correlationId = "test",
    )
    private val ready = PurchaseRequirementTransitionRecord(
        UUID.randomUUID(), UUID.randomUUID(), requirementId, org,
        2, 3, "TECHNICAL_READY", "SUBMITTED", "READY_FOR_SOURCING",
        null, UUID.randomUUID(), at, "test",
    )
    private val item = StockItemView(
        itemId, org, "CABLE-01", "Cable", null, null, null, UUID.randomUUID(), 1,
        "M", StockClassification.STOCK_QUANTITY, false, false, true, null, 3,
    )
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) =
            IdempotentCommandExecution(action(), false)
    }
    private val context = ProcurementDraftContext(op, actor, "test")
    private fun service(enabled: Boolean = true) = SupplierSourcingService(
        sourcing, requirements, work, stock, auth, commands, audit,
        Clock.fixed(at, ZoneOffset.UTC), enabled,
    )

    private fun allow() {
        Mockito.`when`(auth.canManageSourcing(actor, org)).thenReturn(true)
        Mockito.`when`(requirements.byId(requirementId)).thenReturn(root)
        Mockito.`when`(requirements.latestTransition(requirementId)).thenReturn(ready)
        Mockito.`when`(requirements.lock(requirementId)).thenReturn(true)
        Mockito.`when`(order.organizationId).thenReturn(org)
        Mockito.`when`(order.version).thenReturn(5)
        Mockito.`when`(order.lifecycleState).thenReturn(WorkOrderLifecycle.IN_PROGRESS)
        Mockito.`when`(work.workOrder(workId)).thenReturn(order)
        Mockito.`when`(stock.item(itemId)).thenReturn(item)
        val placeholder = SupplierRfqRecord(
            rfqId, op, org, requirementId, 3, itemId, BigDecimal("100"), "M",
            actor, at, "test",
        )
        Mockito.doAnswer { invocation ->
            createdRfq = invocation.getArgument<SupplierRfqRecord>(0)
            Unit
        }.`when`(sourcing).insertRfq(any(SupplierRfqRecord::class.java) ?: placeholder)
        Mockito.`when`(sourcing.rfq(rfqId)).thenAnswer { createdRfq }
        Mockito.`when`(sourcing.lockRfq(rfqId)).thenReturn(true)
        Mockito.`when`(sourcing.supplierTypeAndStatus(supplierId))
            .thenReturn("SUPPLIER" to "ACTIVE")
        val sample = SupplierQuoteRecord(
            SupplierQuoteEvidence(
                UUID.randomUUID(), rfqId, supplierId, "Q-01", 1, "EGP",
                BigDecimal("100"), "M", BigDecimal("12.50"), BigDecimal.ZERO,
                null, null, null, null, null, "internal:doc:quote",
                "a".repeat(64),
            ), op, org, actor, at, "test",
        )
        Mockito.doAnswer { invocation ->
            createdQuote = invocation.getArgument<SupplierQuoteRecord>(0)
            Unit
        }.`when`(sourcing).insertQuote(any(SupplierQuoteRecord::class.java) ?: sample)
    }

    private fun quoteRequest(expectedRevision: Int = 1) = RecordSupplierQuoteRequest(
        operationId = UUID.randomUUID(), supplierOrganizationId = supplierId,
        expectedRevisionNumber = expectedRevision,
        supplierQuoteReference = "SUP-421", currency = "EGP",
        quotedQuantity = "100", quotedUnitCode = "M",
        unitPrice = "12.50", taxAmount = "1.25",
        deliveryDays = 4, validUntil = LocalDate.parse("2026-12-15"),
        deliveryTerms = "Site delivery", paymentTerms = "Upon contract",
        warrantyTerms = null, sourceEvidenceRef = "internal:quote-evidence:421",
        sourceSha256 = "f".repeat(64),
    )

    @Test fun featureIsDisabledWithoutTouchingSensitiveSupplierEvidence() {
        val error = assertThrows<ProductApiException> {
            service(false).openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        }
        assertEquals("SOURCING_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(sourcing, requirements, stock, work, auth)
    }

    @Test fun anOrdinaryProjectRequesterDoesNotBecomeAnAuthorizedBuyer() {
        allow()
        Mockito.`when`(auth.canManageSourcing(actor, org)).thenReturn(false)
        assertEquals("SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        }.code)
        assertEquals(null, createdRfq)
    }

    @Test fun authorizedSourcingRoundCapturesTheFrozenTechnicalRequirementVersion() {
        allow()
        val result = service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        assertEquals(rfqId, result.rfq.id)
        assertEquals(3L, result.rfq.capturedRequirementVersion)
        assertTrue(result.rfq.sourceCurrent)
        assertFalse(result.rfq.purchaseAuthorized)
        assertEquals("100", createdRfq?.sourceQuantity?.toPlainString())
        assertEquals(org, createdRfq?.organizationId)
    }

    @Test fun noRfqCanOpenForChangedOrNonreadyTechnicalSource() {
        allow()
        Mockito.`when`(order.version).thenReturn(6)
        assertEquals("SOURCING_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        }.code)
        Mockito.`when`(order.version).thenReturn(5)
        Mockito.`when`(requirements.latestTransition(requirementId))
            .thenReturn(ready.copy(toState = "WITHDRAWN", resultVersion = 4))
        assertEquals("SOURCING_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        }.code)
    }

    @Test fun completedWorkOrderCannotBeSourcedEvenWithTechnicallyReadyEvent() {
        allow()
        Mockito.`when`(order.lifecycleState).thenReturn(WorkOrderLifecycle.CLOSED)
        assertEquals("SOURCING_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        }.code)
    }

    @Test fun eachSupplierRevisionIsAnAppendOnlyRestrictedEvidenceRecord() {
        allow()
        service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        val request = quoteRequest()
        val quote = service().recordQuote(
            ProcurementDraftContext(request.operationId, actor, "quote-test"), rfqId, request,
        )
        assertEquals(1, quote.revisionNumber)
        assertNotNull(createdQuote)
        assertEquals(supplierId, createdQuote?.evidence?.supplierOrganizationId)
        assertEquals("f".repeat(64), createdQuote?.evidence?.sourceSha256)
        assertEquals("NOT_ASSESSED", createdQuote?.evidence?.technicalCompliance)
        assertEquals(1, createdQuote?.evidence?.revisionNumber)
    }

    @Test fun revisionSequenceAndSupplierStatusCannotBeFaked() {
        allow()
        service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        val badRevision = quoteRequest(expectedRevision = 2)
        assertEquals("SOURCING_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().recordQuote(
                ProcurementDraftContext(badRevision.operationId, actor, "bad-revision"),
                rfqId, badRevision,
            )
        }.code)
        Mockito.`when`(sourcing.supplierTypeAndStatus(supplierId))
            .thenReturn("CLIENT" to "ACTIVE")
        val invalid = quoteRequest()
        assertEquals("INVALID_SUPPLIER_QUOTE", assertThrows<ProductApiException> {
            service().recordQuote(
                ProcurementDraftContext(invalid.operationId, actor, "bad-supplier"),
                rfqId, invalid,
            )
        }.code)
    }

    @Test fun buyerEligibleNeedsAreSourcedFromCurrentTechnicalReadyRequirementsOnly() {
        allow()
        Mockito.`when`(sourcing.eligibleRequirementIds(org)).thenReturn(listOf(requirementId))
        val needs = service().eligibleNeeds(actor, org)
        assertEquals(1, needs.size)
        assertEquals(requirementId, needs.single().requirementId)
        assertEquals("READY_FOR_SOURCING", needs.single().technicalState)
        assertTrue(needs.single().sourceCurrent)
        assertFalse(needs.single().sourcingRoundExists)

        Mockito.`when`(sourcing.byRequirementAndVersion(requirementId, 3))
            .thenReturn(SupplierRfqRecord(rfqId, op, org, requirementId, 3, itemId,
                BigDecimal("100"), "M", actor, at, "test"))
        assertTrue(service().eligibleNeeds(actor, org).single().sourcingRoundExists)

        Mockito.`when`(auth.canManageSourcing(actor, org)).thenReturn(false)
        assertEquals("SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service().eligibleNeeds(actor, org)
        }.code)
    }

    @Test fun staleEligibilityIsFlaggedNeverPresentedAsPurchaseAuthorization() {
        allow()
        Mockito.`when`(sourcing.eligibleRequirementIds(org)).thenReturn(listOf(requirementId))
        Mockito.`when`(order.version).thenReturn(6)
        val need = service().eligibleNeeds(actor, org).single()
        assertFalse(need.sourceCurrent)
        assertFalse(need.sourcingRoundExists)
    }

    @Test fun buyerRoundListIsOrganizationScopedAndDoesNotExposeUnrelatedSource() {
        allow()
        service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        Mockito.`when`(sourcing.roundsForRequirement(org, requirementId))
            .thenReturn(listOf(createdRfq!!))
        Mockito.`when`(sourcing.countQuotes(rfqId)).thenReturn(2)
        val records = service().roundsForRequirement(actor, requirementId)
        assertEquals(1, records.size)
        assertEquals(2, records.single().quoteCount)
        assertEquals(requirementId, records.single().requirementId)
        assertFalse(records.single().purchaseAuthorized)

        Mockito.`when`(auth.canManageSourcing(actor, org)).thenReturn(false)
        assertEquals("SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service().roundsForRequirement(actor, requirementId)
        }.code)
    }

    @Test fun buyerRoundListRefusesCrossOrganizationRowsEvenIfPersistenceIsCorrupted() {
        allow()
        service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        Mockito.`when`(sourcing.roundsForRequirement(org, requirementId))
            .thenReturn(listOf(createdRfq!!.copy(organizationId = UUID.randomUUID())))
        assertEquals("SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service().roundsForRequirement(actor, requirementId)
        }.code)
    }

    @Test fun restrictedComparisonCannotApproveCheapestVendor() {
        allow()
        service().openRfq(context, requirementId, OpenSupplierRfqRequest(op, 3))
        Mockito.`when`(sourcing.quotes(rfqId)).thenReturn(listOf(
            SupplierQuoteEvidence(
                UUID.randomUUID(), rfqId, supplierId, "Q1", 1, "EGP",
                BigDecimal("100"), "M", BigDecimal("10"), BigDecimal.ZERO,
                null, LocalDate.parse("2026-12-15"), null, null, null,
                "internal:doc:quote", "a".repeat(64),
            ),
        ))
        val comparison = service().comparison(actor, rfqId)
        assertEquals(1, comparison.recordedSupplierCount)
        assertFalse(comparison.comparison.supplierSelected)
        assertFalse(comparison.comparison.purchaseAuthorized)
        Mockito.`when`(auth.canManageSourcing(actor, org)).thenReturn(false)
        assertEquals("SOURCING_UNAVAILABLE", assertThrows<ProductApiException> {
            service().comparison(actor, rfqId)
        }.code)
    }
}
