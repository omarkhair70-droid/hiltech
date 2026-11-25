package com.hiltech.server.procurement

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SupplierTechnicalReviewSourceTest {
    private val now = Instant.parse("2026-11-25T10:00:00Z")
    private val org = UUID.randomUUID(); private val quoteId = UUID.randomUUID(); private val rfq = UUID.randomUUID()
    private val actor = UUID.randomUUID(); private val item = UUID.randomUUID(); private val need = UUID.randomUUID()
    private val supplier = UUID.randomUUID(); private val packetId = UUID.randomUUID()
    private val repo = Mockito.mock(SupplierTechnicalReviewRepository::class.java)
    private val requirements = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val verifier = Mockito.mock(ProcurementDemandSourceVerifier::class.java)
    private val buyer = Mockito.mock(SupplierSourcingAuthorizationPort::class.java)
    private val reviewer = Mockito.mock(SupplierQuoteTechnicalReviewerAuthorizationPort::class.java)
    private val source = SupplierTechnicalReviewSource(repo, requirements, verifier, buyer, reviewer, Clock.fixed(now, ZoneOffset.UTC))
    private val root = PurchaseRequirementDraftRecord(need, UUID.randomUUID(), org, null, null, item, 1, BigDecimal("4"), "EA", "Synthetic exact component specification",
        "WAREHOUSE_REVIEW_REQUIRED", null, now, actor, now, "synthetic", sourceType = "WAREHOUSE_REPLENISHMENT", warehouseStorageLocationId = UUID.randomUUID(), warehouseSourceVersion = 1)
    private val ready = PurchaseRequirementTransitionRecord(UUID.randomUUID(), UUID.randomUUID(), need, org, 2, 3, "TECHNICAL_READY", "SUBMITTED", "READY_FOR_SOURCING", null, actor, now, "synthetic")
    private val quote = SupplierTechnicalQuoteSource(quoteId, org, rfq, supplier, "Supplier name", "SUP-000123", "QUOTE-001", 1, BigDecimal("4"), "EA",
        item, "Component name", "ITEM-001", need, 3, BigDecimal("4"), "EA", "Warehouse name", true)
    private val fields = SupplierTechnicalPacketFields(offeredSpecification = "Synthetic exact rating and mounting requirements", deviations = "No deviations",
        supplierSource = "Manufacturer technical sheet", sourceReference = "TECH-001", nonFinancialConfirmed = true, correctionReason = "Initial synthetic technical source")

    private fun configure(): SupplierTechnicalPacket {
        Mockito.`when`(repo.quote(quoteId)).thenReturn(quote)
        Mockito.`when`(requirements.byId(need)).thenReturn(root)
        Mockito.`when`(requirements.latestTransition(need)).thenReturn(ready)
        Mockito.`when`(verifier.isCurrent(root, ready)).thenReturn(true)
        Mockito.`when`(reviewer.canReviewOrganization(actor, org)).thenReturn(true)
        Mockito.`when`(reviewer.canReviewQuote(actor, org, rfq)).thenReturn(true)
        val needSnapshot = source.describe(quote)
        val packet = SupplierTechnicalPacket(packetId, org, quoteId, rfq, 1, 3, needSnapshot, fields,
            SupplierTechnicalPacketRules.hash(needSnapshot, fields, emptyList()), UUID.randomUUID(), now)
        Mockito.`when`(repo.latest(quoteId)).thenReturn(packet)
        Mockito.`when`(repo.proofReferences(packetId)).thenReturn(emptyList())
        Mockito.`when`(repo.displayed(actor, packet)).thenReturn(emptyList())
        return packet
    }

    @Test fun textSourceNeedsExplicitSpecificationDeviationsReferenceAndDisclosureButNoDuplicateFile() {
        val normalized = SupplierTechnicalPacketRules.normalize(fields.copy(sourceDate = "2026-11-25"), LocalDate.of(2026, 11, 25))
        assertTrue(normalized.evidenceIds.isEmpty())
        listOf(fields.copy(offeredSpecification = ""), fields.copy(deviations = ""), fields.copy(sourceReference = ""), fields.copy(nonFinancialConfirmed = false),
            fields.copy(sourceDate = "2026-11-26"), fields.copy(sourceDate = "bad-date"), fields.copy(evidenceIds = listOf(packetId.toString(), packetId.toString()))).forEach {
            assertEquals("SUPPLIER_TECHNICAL_INPUT_INVALID", assertThrows<ProductApiException> { SupplierTechnicalPacketRules.normalize(it, LocalDate.of(2026,11,25)) }.code)
        }
    }

    @Test fun compliantTechnicalJudgmentBindsTheExactCurrentNonfinancialSource() {
        val packet = configure()
        assertTrue(source.current(packet))
        source.requireCurrentAssessment(actor, quoteId, packet.id, true, "COMPLIANT")
        assertEquals("SUPPLIER_TECHNICAL_REVIEW_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            source.requireCurrentAssessment(actor, quoteId, packet.id, false, "COMPLIANT")
        }.code)
        Mockito.`when`(repo.latest(quoteId)).thenReturn(packet.copy(id = UUID.randomUUID(), revision = 2))
        assertFalse(source.current(packet))
        assertEquals("SUPPLIER_TECHNICAL_SOURCE_CHANGED", assertThrows<ProductApiException> { source.requireCurrentAssessment(actor, quoteId, packet.id, true, "COMPLIANT") }.code)
        Mockito.`when`(repo.latest(quoteId)).thenReturn(null)
        source.requireCurrentAssessment(actor, quoteId, null, false, "NEEDS_CLARIFICATION")
        assertEquals("SUPPLIER_TECHNICAL_PACKET_REQUIRED", assertThrows<ProductApiException> { source.requireCurrentAssessment(actor, quoteId, null, false, "COMPLIANT") }.code)
    }

    @Test fun attachedOriginalRequiresConfirmedDisplayForThisPacketAndActor() {
        val base = configure(); val proofId = UUID.randomUUID()
        val original = SupplierTechnicalEvidenceSource(proofId, org, quoteId, UUID.randomUUID(), "Separate technical original", "image/png", 100, "a".repeat(64), "org/$org/evidence/$proofId/objects/${UUID.randomUUID()}")
        val detailed = fields.copy(evidenceIds = listOf(proofId.toString()))
        val packet = base.copy(details = detailed, hash = SupplierTechnicalPacketRules.hash(base.need, detailed, listOf(original)))
        Mockito.`when`(repo.latest(quoteId)).thenReturn(packet)
        Mockito.`when`(repo.proofReferences(packetId)).thenReturn(listOf(proofId to original.sha))
        Mockito.`when`(repo.evidence(org, quoteId, proofId)).thenReturn(original)
        Mockito.`when`(repo.displayed(actor, packet)).thenReturn(emptyList())
        assertTrue(source.current(packet))
        assertEquals("SUPPLIER_TECHNICAL_ORIGINAL_REVIEW_REQUIRED", assertThrows<ProductApiException> { source.requireCurrentAssessment(actor, quoteId, packetId, true, "COMPLIANT") }.code)
        Mockito.`when`(repo.displayed(actor, packet)).thenReturn(listOf(proofId.toString()))
        source.requireCurrentAssessment(actor, quoteId, packetId, true, "COMPLIANT")
        Mockito.`when`(repo.evidence(org, quoteId, proofId)).thenReturn(null)
        assertFalse(source.current(packet))
        assertThrows<ProductApiException> { source.requireCurrentAssessment(actor, quoteId, packetId, true, "COMPLIANT") }
        // A reviewer can still describe the missing/invalid source without claiming compliance.
        source.requireCurrentAssessment(actor, quoteId, packetId, false, "NEEDS_CLARIFICATION")
    }

    @Test fun companyAndCurrentRoleAreCheckedSeparatelyFromSourceIntegrity() {
        val packet = configure()
        assertEquals(quoteId, source.accessibleQuote(actor, quoteId).id)
        assertThrows<ProductApiException> { source.accessibleQuote(actor, quoteId, prepare = true) }
        Mockito.`when`(reviewer.canReviewOrganization(actor, org)).thenReturn(false)
        assertThrows<ProductApiException> { source.accessibleQuote(actor, quoteId) }
        Mockito.`when`(reviewer.canReviewOrganization(actor, org)).thenReturn(true)
        Mockito.`when`(verifier.isCurrent(root, ready)).thenReturn(false)
        assertFalse(source.current(packet))
        assertThrows<ProductApiException> { source.requireCurrentAssessment(actor, quoteId, packetId, true, "COMPLIANT") }
        Mockito.`when`(repo.quote(quoteId)).thenReturn(quote.copy(organizationId = UUID.randomUUID()))
        assertThrows<ProductApiException> { source.accessibleQuote(actor, quoteId) }
    }

    @Test fun historicalNamesStayInSnapshotWhileMaterialSpecificationAndNeedChangesInvalidateTheHash() {
        val packet = configure()
        val renamed = packet.need.copy(supplierName = "Corrected current company supplier name", itemName = "Current display name", sourceLabel = "Current location name")
        assertEquals(packet.hash, SupplierTechnicalPacketRules.hash(renamed, fields, emptyList()))
        assertEquals("Supplier name", packet.need.supplierName)
        assertNotEquals(packet.hash, SupplierTechnicalPacketRules.hash(packet.need.copy(needVersion = 4), fields, emptyList()))
        assertNotEquals(packet.hash, SupplierTechnicalPacketRules.hash(packet.need, fields.copy(deviations = "Changed technical rating"), emptyList()))
        assertNotEquals(packet.hash, SupplierTechnicalPacketRules.hash(packet.need.copy(organizationId = UUID.randomUUID().toString()), fields, emptyList()))
    }
}
