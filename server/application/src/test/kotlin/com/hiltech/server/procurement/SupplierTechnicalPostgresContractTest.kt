package com.hiltech.server.procurement

import com.hiltech.server.documents.FinalizeEvidenceRequest
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.encodeToString
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual PostgreSQL/source/authority checks; object transport deliberately uses synthetic in-memory bytes. */
class SupplierTechnicalPostgresContractTest {
    @Test fun textSourceSupportsRealReviewWithoutDuplicatedPdfAndRetainsCompanyScopedHistoricalNames() {
        val f = fixture(); val w = f.workflow
        val queue = w.service.queue(f.reviewer, f.org, "SUP-000321", 0, 40)
        assertTrue(queue.canReview); assertFalse(queue.canPrepare)
        assertEquals("Synthetic local supplier", queue.items.single().quote.supplierName)
        assertEquals("Synthetic component", queue.items.single().quote.itemName)
        assertTrue(queue.items.single().quote.sourceLabel.contains("Synthetic receiving location"))
        assertTrue(w.service.queue(f.reviewer, f.org, "%", 0, 40).items.isEmpty())
        assertThrows<ProductApiException> { w.service.queue(f.reviewer, f.otherOrg, "", 0, 40) }
        assertThrows<ProductApiException> { w.service.detail(f.otherIdentity, f.quote.id) }
        assertThrows<ProductApiException> { w.service.detail(f.unassigned, f.quote.id) }
        assertThrows<ProductApiException> { w.sourcingService.comparison(f.reviewer, f.rfq.id) }
        assertThrows<ProductApiException> { w.pricedReader.read(f.reviewer, f.quote.id, "synthetic-no-prices") }
        assertEquals(0, w.reads)
        val packet = w.publish(f.quote.id)
        assertTrue(w.service.detail(f.reviewer, f.quote.id).availableDocuments.isEmpty())
        assertEquals("SUPPLIER_TECHNICAL_REVIEW_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            w.assess(f.quote.id, packet.packetId, acknowledged = false)
        }.code)
        val result = w.assess(f.quote.id, packet.packetId)
        assertEquals(result.id, w.sourcing.effectiveTechnicalAssessmentId(f.quote.id))
        val detail = w.service.detail(f.reviewer, f.quote.id)
        assertEquals("COMPLIANT", detail.effectiveDecision)
        assertTrue(detail.packets.single().sourceCurrent)
        assertTrue(detail.packets.single().documents.isEmpty())
        val wire = technicalJson.encodeToString(detail)
        listOf("unitPrice", "taxAmount", "paymentTerms", "currency", "sourceEvidenceRef", f.pricedEvidence.toString()).forEach { assertFalse(wire.contains(it), "Technical projection exposed $it") }
        f.jdbc.update("UPDATE procurement_supplier_profile SET display_name='Synthetic renamed supplier',version=2,updated_at=? WHERE organization_id=? AND supplier_organization_id=?", f.at, f.org, f.supplier)
        val renamed = w.service.detail(f.reviewer, f.quote.id)
        assertEquals("Synthetic renamed supplier", renamed.quote.supplierName)
        assertEquals("Synthetic local supplier", renamed.packets.single().needSnapshot.supplierName)
        assertTrue(renamed.packets.single().sourceCurrent)
        assertEquals(detail.packets.single().sourceHash, renamed.packets.single().sourceHash)
        w.revoke(f.reviewer, "PROCUREMENT_TECHNICAL_REVIEW")
        assertThrows<ProductApiException> { w.service.detail(f.reviewer, f.quote.id) }
        assertEquals("COMPLIANT", w.service.detail(f.buyer, f.quote.id).effectiveDecision)
    }

    @Test fun canonicalTechnicalUploadRequiresExactPublishedSourceAndActualDisplayBeforeCompliance() {
        val f = fixture(); val w = f.workflow
        val reserved = w.reserve(f.quote.id)
        assertEquals("HIGHLY_RESTRICTED", reserved.evidence.classificationCode)
        assertEquals("INTERNAL_ONLY", reserved.evidence.clientVisibilityMode)
        val proof = w.finish(reserved)
        assertThrows<ProductApiException> { w.lifecycle.metadata(f.reviewer, proof) }
        assertThrows<ProductApiException> { w.reader.buyerPage(f.reviewer, f.quote.id, proof, 0, "synthetic-unpublished") }
        assertEquals("SUPPLIER_TECHNICAL_ORIGINAL_UNAVAILABLE", assertThrows<ProductApiException> {
            w.publish(f.quote.id, w.fields(listOf(f.pricedEvidence.toString())))
        }.code)
        val packet = w.publish(f.quote.id, w.fields(listOf(proof.toString())))
        val packetId = UUID.fromString(packet.packetId)
        assertEquals("SUPPLIER_TECHNICAL_ORIGINAL_REVIEW_REQUIRED", assertThrows<ProductApiException> { w.assess(f.quote.id, packet.packetId) }.code)
        val page = w.reader.packetPage(f.reviewer, f.quote.id, packetId, proof, 0, "synthetic-page")
        assertEquals(w.sha, page.originalSha256); assertEquals(1, page.pageCount)
        assertEquals("SUPPLIER_TECHNICAL_ORIGINAL_REVIEW_REQUIRED", assertThrows<ProductApiException> { w.assess(f.quote.id, packet.packetId) }.code)
        val receipt = UUID.fromString(page.viewReceiptId)
        assertThrows<ProductApiException> { w.reader.confirmDisplayed(f.buyer, f.quote.id, packetId, receipt, "wrong-viewer") }
        assertTrue(w.reader.confirmDisplayed(f.reviewer, f.quote.id, packetId, receipt, "synthetic-display").confirmed)
        assertTrue(w.reader.confirmDisplayed(f.reviewer, f.quote.id, packetId, receipt, "synthetic-display-replay").confirmed)
        w.assess(f.quote.id, packet.packetId)
        val corrected = w.publish(f.quote.id, w.fields(listOf(proof.toString()), "Synthetic correction with the same original").copy(model = "MODEL-CORRECTED"))
        assertNull(w.sourcing.effectiveTechnicalAssessmentId(f.quote.id))
        assertEquals("SUPPLIER_TECHNICAL_ORIGINAL_REVIEW_REQUIRED", assertThrows<ProductApiException> { w.assess(f.quote.id, corrected.packetId) }.code)
        val newPage = w.reader.packetPage(f.reviewer, f.quote.id, UUID.fromString(corrected.packetId), proof, 0, "synthetic-corrected-page")
        w.reader.confirmDisplayed(f.reviewer, f.quote.id, UUID.fromString(corrected.packetId), UUID.fromString(newPage.viewReceiptId), "synthetic-corrected-display")
        w.assess(f.quote.id, corrected.packetId)
        assertEquals(2, w.service.detail(f.reviewer, f.quote.id).history.size)
        assertThrows<DataIntegrityViolationException> { f.jdbc.update("DELETE FROM procurement_supplier_technical_packet WHERE id=?", packetId) }
        assertThrows<DataIntegrityViolationException> { f.jdbc.update("UPDATE procurement_supplier_technical_evidence_display SET displayed_at=displayed_at WHERE view_receipt_id=?", receipt) }
        val before = f.countViews()
        w.objects.replaceAll { _, bytes -> bytes + 0.toByte() }
        assertThrows<ProductApiException> { w.reader.packetPage(f.reviewer, f.quote.id, UUID.fromString(corrected.packetId), proof, 0, "synthetic-tampered") }
        assertEquals(before, f.countViews())
    }

    @Test fun revokedAuthorityDuringStorageReadOrFinalizeCannotDeliverProofOrMarkItReady() {
        val f = fixture(); val w = f.workflow
        val proof = w.finish(w.reserve(f.quote.id))
        val packet = w.publish(f.quote.id, w.fields(listOf(proof.toString())))
        w.beforeRead = { w.revoke(f.reviewer, "PROCUREMENT_TECHNICAL_REVIEW") }
        assertThrows<ProductApiException> { w.reader.packetPage(f.reviewer, f.quote.id, UUID.fromString(packet.packetId), proof, 0, "synthetic-revoked-during-read") }
        assertEquals(0L, f.countViews())
        w.beforeRead = {}; w.grant(f.reviewer, "PROCUREMENT_TECHNICAL_REVIEW")
        assertNotNull(w.reader.packetPage(f.reviewer, f.quote.id, UUID.fromString(packet.packetId), proof, 0, "synthetic-current-reviewer").viewReceiptId)
        val reserved = w.reserve(f.quote.id, "Synthetic second original")
        w.beforeVerification = { w.revoke(f.buyer, "PROCUREMENT_BUYER") }
        val request = FinalizeEvidenceRequest(UUID.randomUUID().toString(), reserved.evidence.uploadSessionId, w.sha, w.bytes.size.toLong())
        assertEquals("OBJECT_NOT_VISIBLE", assertThrows<ProductApiException> {
            w.lifecycle.finalize(f.buyer, UUID.fromString(reserved.evidence.evidenceId), request.operationId, "synthetic-revoked-finalize", request)
        }.code)
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT storage_state FROM evidence WHERE id=?", String::class.java, UUID.fromString(reserved.evidence.evidenceId)))
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT state FROM evidence_upload_session WHERE id=?", String::class.java, UUID.fromString(reserved.evidence.uploadSessionId)))
    }

    @Test fun concurrentPublicationOfOneBaseRevisionCreatesOnlyOneImmutableSuccessor() {
        val f = fixture(); val w = f.workflow
        val pool = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) { SupplierTechnicalPublishRequest(UUID.randomUUID().toString(), 0, w.fields().copy(model = "MODEL-$it")) }
            val futures = requests.map { request -> pool.submit(Callable { runCatching { w.service.publish(f.buyer, f.quote.id, request, "synthetic-concurrent") } }) }
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, w.repository.packets(f.quote.id).size)
            val winner = results.indexOfFirst { it.isSuccess }
            assertTrue(w.service.publish(f.buyer, f.quote.id, requests[winner], "synthetic-concurrent-replay").replayed)
        } finally { pool.shutdownNow() }
    }

    private fun fixture(): Fixture {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1" || System.getenv("HILTECH_PAYROLL_9A_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        Flyway.configure().dataSource(url, user, password).locations("filesystem:" + (System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations")).load().migrate()
        return Fixture(JdbcTemplate(DriverManagerDataSource(url, user, password)))
    }

    private class Fixture(val jdbc: JdbcTemplate) {
        private val now = Instant.now().minusSeconds(2)
        private val clock = Clock.fixed(now, ZoneOffset.UTC)
        val at = now.atOffset(ZoneOffset.UTC)
        val org = organization("Synthetic technical purchaser", "HILTECH")
        val otherOrg = organization("Synthetic other company", "HILTECH")
        val supplier = organization("Synthetic global supplier", "SUPPLIER")
        val buyer = identity(org, "Synthetic buyer")
        val reviewer = identity(org, "Synthetic technical reviewer")
        val unassigned = identity(org, "Synthetic unassigned employee")
        val otherIdentity = identity(otherOrg, "Synthetic other-company employee")
        val workflow = SupplierTechnicalPostgresWorkflow(jdbc, clock, org, buyer, reviewer)
        val rfq: SupplierRfqRecord
        val quote: SupplierQuoteEvidence
        val pricedEvidence = UUID.randomUUID()
        init {
            val category = UUID.randomUUID(); val item = UUID.randomUUID(); val warehouse = UUID.randomUUID(); val location = UUID.randomUUID()
            jdbc.update("""INSERT INTO config_revision(id,scope_type,scope_organization_id,family,code,name,lifecycle_state,revision_number,created_at,created_by)
                VALUES(?,'ORGANIZATION',?,'stock-categories','SYNTHETIC','Synthetic category','ACTIVE',1,?,?)""", category, org, at, buyer)
            jdbc.update("INSERT INTO stock_item_category_definition(config_revision_id,default_unit_of_measure_code) VALUES(?,'EA')", category)
            jdbc.update("INSERT INTO stock_unit(organization_id,code,name,active) VALUES(?,'EA','Synthetic unit',true)", org)
            jdbc.update("INSERT INTO stock_item(id,organization_id,item_code,name,category_definition_id,category_revision,unit_of_measure_code,classification) VALUES(?,?,'ITEM-TECH','Synthetic component',?,1,'EA','STOCK_QUANTITY')", item, org, category)
            jdbc.update("INSERT INTO warehouse(id,organization_id,code,name) VALUES(?,?,'WH-TECH','Synthetic warehouse')", warehouse, org)
            jdbc.update("INSERT INTO storage_location(id,organization_id,code,name,kind,warehouse_id,lifecycle_state) VALUES(?,?,'STORE-TECH','Synthetic receiving location','MAIN_WAREHOUSE',?,'ACTIVE')", location, org, warehouse)
            val need = PurchaseRequirementDraftRecord(UUID.randomUUID(), UUID.randomUUID(), org, null, null, item, 1, BigDecimal("4"), "EA",
                "Synthetic requirement for a specified control component", "WAREHOUSE_REVIEW_REQUIRED", null, now, buyer, now, "synthetic-technical",
                sourceType = "WAREHOUSE_REPLENISHMENT", warehouseStorageLocationId = location, warehouseSourceVersion = 1)
            workflow.requirements.insert(need)
            workflow.requirements.appendTransition(PurchaseRequirementTransitionRecord(UUID.randomUUID(), UUID.randomUUID(), need.id, org, 1, 2, "SUBMIT", "DRAFT", "SUBMITTED", null, buyer, now, "synthetic"))
            workflow.requirements.appendTransition(PurchaseRequirementTransitionRecord(UUID.randomUUID(), UUID.randomUUID(), need.id, org, 2, 3, "TECHNICAL_READY", "SUBMITTED", "READY_FOR_SOURCING", null, buyer, now, "synthetic"))
            rfq = SupplierRfqRecord(UUID.randomUUID(), UUID.randomUUID(), org, need.id, 3, item, BigDecimal("4"), "EA", buyer, now, "synthetic")
            workflow.sourcing.insertRfq(rfq)
            workflow.sourcing.insertSupplierLinkEvent(SupplierLinkEventRecord(UUID.randomUUID(), UUID.randomUUID(), org, supplier, 1, "ACTIVE", "Synthetic current supplier", buyer, now, "synthetic"))
            jdbc.update("""INSERT INTO procurement_supplier_profile(organization_id,supplier_organization_id,supplier_code,legal_name,display_name,version,created_by_user_id,created_at,updated_by_user_id,updated_at)
                VALUES(?,?,'SUP-000321','Synthetic supplier legal name','Synthetic local supplier',1,?,?,?,?)""", org, supplier, buyer, at, buyer, at)
            val slot = SupplierQuoteDocumentSlot(UUID.randomUUID(), UUID.randomUUID(), org, rfq.id, supplier, buyer, now, "synthetic")
            workflow.sourcing.insertQuoteDocumentSlot(slot)
            val key = "org/$org/evidence/$pricedEvidence/objects/${UUID.randomUUID()}"
            jdbc.update("""INSERT INTO evidence(id,organization_id,target_type,target_id,evidence_requirement_key,evidence_type_code,content_type,size_bytes,sha256,captured_at,captured_by_user_id,
                storage_state,object_key_ref,finalized_at,classification_code,client_visibility_mode,security_scan_class,created_at)
                VALUES(?,?,'SUPPLIER_QUOTE_DOCUMENT',?,'SUPPLIER_QUOTE_BINARY','SUPPLIER_QUOTE','image/png',?,?,?,?,'READY',?,?,'HIGHLY_RESTRICTED','INTERNAL_ONLY','NATIVE_MEDIA',?)""",
                pricedEvidence, org, slot.id, workflow.bytes.size, workflow.sha, at, buyer, key, at, at)
            jdbc.update("""INSERT INTO evidence_upload_session(id,evidence_id,expected_sha256,expected_size_bytes,expires_at,state,created_at,finalized_at,operation_id)
                VALUES(?,?,?,?,?,'READY',?,?,?)""", UUID.randomUUID(), pricedEvidence, workflow.sha, workflow.bytes.size, at.plusHours(1), at, at, UUID.randomUUID())
            workflow.objects[key] = workflow.bytes
            quote = SupplierQuoteEvidence(UUID.randomUUID(), rfq.id, supplier, "QUOTE-TECH-001", 1, "EGP", BigDecimal("4"), "EA", BigDecimal("25"), BigDecimal.ZERO, 3,
                LocalDate.now(clock).plusDays(14), "Synthetic delivery terms", "Sensitive price-related payment terms", null, "evidence:$pricedEvidence", workflow.sha)
            workflow.sourcing.insertQuote(SupplierQuoteRecord(quote, UUID.randomUUID(), org, buyer, now, "synthetic"))
        }
        fun countViews(): Long = jdbc.queryForObject("SELECT count(*) FROM procurement_supplier_technical_evidence_view WHERE organization_id=?", Long::class.java, org)!!
        private fun organization(name: String, type: String): UUID = UUID.randomUUID().also { id ->
            jdbc.update("INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at) VALUES(?,?,?,?,?,'ACTIVE',?)", id, "TEST-$id", name, name, type, at)
        }
        private fun identity(company: UUID, name: String): UUID = UUID.randomUUID().also { id ->
            val person = UUID.randomUUID()
            jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES(?,?,?,?)", person, name, at, at)
            jdbc.update("INSERT INTO user_identity(id,person_id,auth_provider,auth_subject,status,primary_organization_id,created_at) VALUES(?,?,'synthetic-technical',?,'ACTIVE',?,?)", id, person, id.toString(), company, at)
            jdbc.update("INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from) VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)", UUID.randomUUID(), company, id, at.minusDays(1))
            jdbc.update("INSERT INTO employee(id,organization_id,person_id,employee_code,state,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',?,?)", UUID.randomUUID(), company, person, "EMP-$id", at, at)
        }
    }
}
