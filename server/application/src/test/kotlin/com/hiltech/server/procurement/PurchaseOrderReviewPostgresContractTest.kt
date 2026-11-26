package com.hiltech.server.procurement

import com.hiltech.server.approval.*
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.persistence.JdbcEvidencePersistence
import com.hiltech.server.documents.storage.EvidenceObjectStoragePort
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import com.hiltech.server.warehouse.JdbcWarehousePersistence
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.work.WorkPersistencePort
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/** Actual source reads, revision/proof schema, Engine decisions and command replay; storage contains synthetic bytes. */
class PurchaseOrderReviewPostgresContractTest {
    @Test fun reviewerOpensExactQuotationRequestsCorrectionAndApprovesImmutableReplacement() {
        assumeTrue(System.getenv("HILTECH_PAYROLL_9A_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        Flyway.configure().dataSource(url, user, password)
            .locations("filesystem:" + (System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations")).load().migrate()
        val jdbc = JdbcTemplate(DriverManagerDataSource(url, user, password))
        val now = Instant.now().minusSeconds(2)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val at = now.atOffset(ZoneOffset.UTC)
        val org = UUID.randomUUID(); val supplier = UUID.randomUUID()
        fun organization(id: UUID, type: String, name: String) = jdbc.update("""INSERT INTO organization
            (id,organization_code,legal_name,display_name,organization_type,status,created_at) VALUES(?,?,?, ?,?,'ACTIVE',?)""",
            id, "PO_" + id.toString().take(12), name, name, type, at)
        organization(org, "HILTECH", "Synthetic purchaser"); organization(supplier, "SUPPLIER", "Synthetic quotation supplier")
        fun identity(name: String): UUID {
            val person = UUID.randomUUID(); val id = UUID.randomUUID(); val employee = UUID.randomUUID()
            jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES(?,?,?,?)", person, name, at, at)
            jdbc.update("""INSERT INTO user_identity(id,person_id,auth_provider,auth_subject,status,primary_organization_id,created_at)
                VALUES(?,?,'synthetic-po-review',?,'ACTIVE',?,?)""", id, person, id.toString(), org, at)
            jdbc.update("""INSERT INTO employee(id,organization_id,person_id,employee_code,state,hire_date,created_at,updated_at)
                VALUES(?,?,?,?,'ACTIVE','2026-01-01',?,?)""", employee, org, person, "EMP_" + employee.toString().take(12), at, at)
            jdbc.update("""INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from)
                VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""", UUID.randomUUID(), org, id, at.minusDays(1))
            return id
        }
        val buyer = identity("Synthetic purchaser"); val reviewer = identity("Synthetic independent reviewer"); val other = identity("Synthetic unrelated employee")
        val category = UUID.randomUUID(); val item = UUID.randomUUID(); val warehouseId = UUID.randomUUID(); val location = UUID.randomUUID()
        jdbc.update("""INSERT INTO config_revision(id,scope_type,scope_organization_id,family,code,name,lifecycle_state,revision_number,
            created_at,created_by) VALUES(?,'ORGANIZATION',?,'stock-categories','SYNTHETIC','Synthetic category','ACTIVE',1,?,?)""", category, org, at, buyer)
        jdbc.update("INSERT INTO stock_item_category_definition(config_revision_id,default_unit_of_measure_code) VALUES(?,'EA')", category)
        jdbc.update("INSERT INTO stock_unit(organization_id,code,name,active) VALUES(?,'EA','Each',true)", org)
        jdbc.update("""INSERT INTO stock_item(id,organization_id,item_code,name,category_definition_id,category_revision,unit_of_measure_code,classification)
            VALUES(?,?,'ITEM-SYNTHETIC','Synthetic component',?,1,'EA','STOCK_QUANTITY')""", item, org, category)
        jdbc.update("INSERT INTO warehouse(id,organization_id,code,name) VALUES(?,?,'WH-SYNTHETIC','Synthetic warehouse')", warehouseId, org)
        jdbc.update("""INSERT INTO storage_location(id,organization_id,code,name,kind,warehouse_id,lifecycle_state)
            VALUES(?,?,'STORE-SYNTHETIC','Synthetic receiving location','MAIN_WAREHOUSE',?,'ACTIVE')""", location, org, warehouseId)
        val requirements = PurchaseRequirementDraftPersistence(jdbc)
        val source = PurchaseRequirementDraftRecord(UUID.randomUUID(), UUID.randomUUID(), org, null, null, item, 1,
            BigDecimal("4"), "EA", "Synthetic replenishment requirement", "WAREHOUSE_REVIEW_REQUIRED", null, now, buyer, now, "synthetic",
            sourceType = "WAREHOUSE_REPLENISHMENT", warehouseStorageLocationId = location, warehouseSourceVersion = 1)
        requirements.insert(source)
        requirements.appendTransition(PurchaseRequirementTransitionRecord(UUID.randomUUID(), UUID.randomUUID(), source.id, org, 1, 2,
            "SUBMIT", "DRAFT", "SUBMITTED", null, buyer, now, "synthetic"))
        requirements.appendTransition(PurchaseRequirementTransitionRecord(UUID.randomUUID(), UUID.randomUUID(), source.id, org, 2, 3,
            "TECHNICAL_READY", "SUBMITTED", "READY_FOR_SOURCING", null, buyer, now, "synthetic"))
        val sourcing = SupplierSourcingPersistence(jdbc)
        val rfq = SupplierRfqRecord(UUID.randomUUID(), UUID.randomUUID(), org, source.id, 3, item, BigDecimal("4"), "EA", buyer, now, "synthetic")
        sourcing.insertRfq(rfq)
        sourcing.insertSupplierLinkEvent(SupplierLinkEventRecord(UUID.randomUUID(), UUID.randomUUID(), org, supplier, 1, "ACTIVE",
            "Synthetic approved supplier relation", buyer, now, "synthetic"))
        val slot = SupplierQuoteDocumentSlot(UUID.randomUUID(), UUID.randomUUID(), org, rfq.id, supplier, buyer, now, "synthetic")
        sourcing.insertQuoteDocumentSlot(slot)
        val png = ByteArrayOutputStream().use { output -> ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", output); output.toByteArray() }
        val sha = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        val evidenceId = UUID.randomUUID()
        val objectKey = "org/$org/evidence/$evidenceId/objects/${UUID.randomUUID()}"
        jdbc.update("""INSERT INTO evidence(id,organization_id,target_type,target_id,evidence_requirement_key,evidence_type_code,
            content_type,size_bytes,sha256,captured_at,captured_by_user_id,storage_state,object_key_ref,finalized_at,
            classification_code,client_visibility_mode,security_scan_class,created_at)
            VALUES(?,?,'SUPPLIER_QUOTE_DOCUMENT',?,'SUPPLIER_QUOTE_BINARY','SUPPLIER_QUOTE','image/png',?,?,?,?,'READY',?,?,'HIGHLY_RESTRICTED','INTERNAL_ONLY','NATIVE_MEDIA',?)""",
            evidenceId, org, slot.id, png.size, sha, at, buyer, objectKey, at, at)
        jdbc.update("""INSERT INTO evidence_upload_session(id,evidence_id,expected_sha256,expected_size_bytes,expires_at,state,created_at,finalized_at,operation_id)
            VALUES(?,?,?,?,?,'READY',?,?,?)""", UUID.randomUUID(), evidenceId, sha, png.size, at.plusHours(1), at, at, UUID.randomUUID())
        val quote = SupplierQuoteEvidence(UUID.randomUUID(), rfq.id, supplier, "SYNTHETIC-QUOTE-1", 1, "EGP", BigDecimal("4"), "EA",
            BigDecimal("25"), BigDecimal.ZERO, 3, LocalDate.now(clock).plusDays(14), "Synthetic delivery terms", "Synthetic payment terms", null,
            "evidence:$evidenceId", sha)
        sourcing.insertQuote(SupplierQuoteRecord(quote, UUID.randomUUID(), org, buyer, now, "synthetic"))
        val technical = SupplierTechnicalPostgresWorkflow(jdbc, clock, org, buyer, other)
        val technicalPacket = technical.publish(quote.id)
        val technicalAssessment = technical.assess(quote.id, technicalPacket.packetId)

        val candidates = PurchaseOrderCandidatePersistence(jdbc)
        val original = PurchaseOrderCandidateRecord(UUID.randomUUID(), UUID.randomUUID(), org, rfq.id, source.id, 3, quote.id, 1,
            supplier, item, BigDecimal("4"), "EA", "EGP", BigDecimal("25"), BigDecimal.ZERO, BigDecimal("100"), sha,
            "Synthetic initial quotation selection", buyer, now, "synthetic", technicalAssessmentId = technicalAssessment.id)
        candidates.insert(original)
        val stock = StockLedgerPersistence(jdbc)
        val work = Mockito.mock(WorkPersistencePort::class.java)
        val evidence = JdbcEvidencePersistence(jdbc)
        val verifier = ProcurementDemandSourceVerifier(work, JdbcWarehousePersistence(jdbc), stock, clock,
            Mockito.mock(AssetPurchaseSource::class.java), Mockito.mock(OfficeStockSource::class.java))
        val guard = PurchaseOrderCandidateApprovalSubject(candidates, sourcing, requirements, work, stock, evidence, clock, true, verifier)
        assertTrue(guard.isCurrent(ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", original.id, 1)))
        val policies = JdbcApprovalPersistence(jdbc)
        val configuration = ProcurementReviewPolicyConfiguration("")
        jdbc.update("""INSERT INTO approval_policy_version(id,organization_id,policy_key,version_number,lifecycle_state,approval_required,
            step_mode,authority_key,reason_required,effective_from,created_by_user_id,created_at)
            VALUES(?,?,?,1,'ACTIVE',true,'SINGLE','PROCUREMENT_PO_REVIEWER',true,?,?,?)""", UUID.randomUUID(), org, configuration.key, at.minusDays(1), buyer, at)
        jdbc.update("""INSERT INTO approval_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,effective_from,active,
            created_by_user_id,created_at) VALUES(?,?,'PROCUREMENT_PO_REVIEWER','USER',?,?,true,?,?)""", UUID.randomUUID(), org, reviewer, at.minusDays(1), buyer, at)
        val authorization = object : ApprovalAuthorizationPort {
            override fun canDecide(actorUserId: UUID, approvalRequestId: UUID, assignmentPrincipalType: ApprovalPrincipalType, assignmentPrincipalId: UUID) =
                assignmentPrincipalType == ApprovalPrincipalType.USER && actorUserId == assignmentPrincipalId
        }
        val audit = JdbcAuditEventWriter(jdbc)
        val commands = JdbcIdempotentCommandExecutor(jdbc, DataSourceTransactionManager(jdbc.dataSource!!), clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
        val records = PurchaseOrderReviewPersistence(jdbc)
        val eligibility = CurrentProcurementCandidateReviewEligibility(jdbc, clock)
        val constraint = PurchaseOrderReviewDecisionConstraint(candidates, eligibility, policies, configuration, records, clock)
        val engine = ApprovalEngineService(policies, commands, AuthorizationProjectionIntentWriter {}, authorization,
            FailClosedApprovalSubjectVersionGuard(listOf(guard)), audit, ApplicationEventPublisher {}, clock, decisionConstraints = listOf(constraint))
        val storage = Mockito.mock(EvidenceObjectStoragePort::class.java)
        Mockito.`when`(storage.readRestrictedObject(objectKey, png.size.toLong())).thenReturn(png)
        val documents = SupplierQuoteProtectedDocumentService(sourcing, evidence, Mockito.mock(SupplierQuoteDocumentVerificationPort::class.java),
            Mockito.mock(SupplierSourcingAuthorizationPort::class.java), EvidenceStorageAccessPort { storage }, audit, clock, true)
        val service = PurchaseOrderReviewService(candidates, sourcing, requirements, records, eligibility,
            ApprovalReadService(policies, authorization, ApprovalCursorCodec("synthetic-purchase-review-cursor-at-least-32"), clock), policies, configuration,
            guard, documents, evidence, engine, commands, audit, clock, true)
        fun request(candidate: PurchaseOrderCandidateRecord): UUID = engine.requestException(buyer, UUID.randomUUID(),
            ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", candidate.id, 1), configuration.key, "PROCUREMENT_PO_CANDIDATE_FOR_APPROVAL", null,
            "synthetic", allowAuthorityBypass = false).approvalRequestId!!
        val firstRequest = request(original)
        val displayed = service.read(reviewer, original.id, firstRequest)
        assertEquals("Synthetic quotation supplier", displayed.supplierName)
        assertTrue(displayed.sourceLabel.contains("Synthetic receiving location"))
        assertEquals("100.000000", displayed.totalAmount)
        assertFalse(displayed.canApprove)
        assertThrows<ProductApiException> { service.read(other, original.id, firstRequest) }
        assertEquals("APPROVAL_SOURCE_REVIEW_NOT_ALLOWED", assertThrows<ProductApiException> {
            engine.decide(reviewer, UUID.randomUUID(), firstRequest, ApprovalDecisionType.APPROVE, null, "synthetic-no-proof")
        }.code)
        engine.decide(reviewer, UUID.randomUUID(), firstRequest, ApprovalDecisionType.REQUEST_CHANGE, "Clarify the supplier selection rationale", "synthetic-correction")
        val replacement = original.copy(id = UUID.randomUUID(), operationId = UUID.randomUUID(), revisionNumber = 2, replacesCandidateId = original.id,
            rationale = "Synthetic corrected selection rationale following review")
        candidates.insert(replacement)
        assertEquals(original.rationale, candidates.byId(original.id)?.rationale)
        assertEquals(1, candidates.byId(original.id)?.revisionNumber)
        assertNull(candidates.byId(original.id)?.replacesCandidateId)
        assertEquals(replacement.id, candidates.byRfq(rfq.id)?.id)
        assertFalse(guard.isCurrent(ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", original.id, 1)))
        val secondRequest = request(replacement)
        assertThrows<Exception> { candidates.insert(replacement.copy(id = UUID.randomUUID(), operationId = UUID.randomUUID(), revisionNumber = 3, replacesCandidateId = replacement.id)) }
        val second = service.read(reviewer, replacement.id, secondRequest)
        val exact = PurchaseReviewSourceDto(second.approvalRequestVersion, second.candidateRevision, second.quoteRevision,
            requireNotNull(second.quotation).evidenceId, second.quotation.sha256)
        assertThrows<ProductApiException> { service.quotationPage(reviewer, replacement.id, secondRequest,
            PurchaseQuotePageRequestDto(exact.copy(expectedSha256 = "b".repeat(64)), 0), "synthetic-wrong-source") }
        val page = service.quotationPage(reviewer, replacement.id, secondRequest, PurchaseQuotePageRequestDto(exact, 0), "synthetic-view")
        assertEquals(1, page.pageCount)
        assertTrue(ImageIO.read(Base64.getDecoder().decode(page.imagePngBase64).inputStream()).width > 0)
        assertFalse(service.read(reviewer, replacement.id, secondRequest).quotationOpened)
        assertEquals("APPROVAL_SOURCE_REVIEW_NOT_ALLOWED", assertThrows<ProductApiException> {
            engine.decide(reviewer, UUID.randomUUID(), secondRequest, ApprovalDecisionType.APPROVE, null, "synthetic-not-displayed")
        }.code)
        val open = OpenPurchaseReviewQuoteDto(UUID.randomUUID().toString(), page.deliveryId, exact)
        jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", org, reviewer)
        assertThrows<ProductApiException> { service.recordOpened(reviewer, replacement.id, secondRequest, open, "synthetic-revoked") }
        jdbc.update("UPDATE organization_membership SET state='ACTIVE' WHERE organization_id=? AND user_identity_id=?", org, reviewer)
        assertFalse(service.recordOpened(reviewer, replacement.id, secondRequest, open, "synthetic-displayed").replayed)
        assertTrue(service.recordOpened(reviewer, replacement.id, secondRequest, open, "synthetic-displayed").replayed)
        assertTrue(service.read(reviewer, replacement.id, secondRequest).canApprove)
        val decision = DecidePurchaseReviewDto(UUID.randomUUID().toString(), exact, "APPROVE", "Synthetic verified supplier terms")
        assertEquals("APPROVED", service.decide(reviewer, replacement.id, secondRequest, decision, "synthetic-decision").approvalState)
        assertTrue(service.decide(reviewer, replacement.id, secondRequest, decision, "synthetic-decision").replayed)
        assertEquals("APPROVED", service.completed(reviewer, secondRequest).approvalState)
        assertEquals("CHANGE_REQUESTED", service.completed(reviewer, firstRequest).approvalState)
        assertThrows<ProductApiException> { service.completed(other, secondRequest) }
        assertThrows<ProductApiException> { service.decide(reviewer, replacement.id, secondRequest, decision.copy(comment = "Changed replay"), "synthetic-decision") }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM procurement_purchase_order WHERE organization_id=?", Long::class.java, org))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM approval_decision WHERE approval_request_id IN (?,?)", Long::class.java, firstRequest, secondRequest))
        assertThrows<Exception> { jdbc.update("UPDATE procurement_po_review_quote_delivery SET source_sha256=? WHERE id=?", "b".repeat(64), UUID.fromString(page.deliveryId)) }

        // The source-owned wrapper must commit the Engine's stale close inside
        // its surrounding command transaction, without manufacturing a decision.
        val staleCandidate = replacement.copy(id = UUID.randomUUID(), operationId = UUID.randomUUID(), revisionNumber = 3,
            replacesCandidateId = replacement.id, rationale = "Synthetic third revision for source change recovery")
        candidates.insert(staleCandidate)
        val staleRequest = request(staleCandidate)
        val staleDetail = service.read(reviewer, staleCandidate.id, staleRequest)
        val staleSource = PurchaseReviewSourceDto(staleDetail.approvalRequestVersion, staleDetail.candidateRevision, staleDetail.quoteRevision,
            requireNotNull(staleDetail.quotation).evidenceId, staleDetail.quotation.sha256)
        fun supplierState(revision: Int, state: String) = sourcing.insertSupplierLinkEvent(SupplierLinkEventRecord(
            UUID.randomUUID(), UUID.randomUUID(), org, supplier, revision, state,
            "Synthetic source currentness change", buyer, now, "synthetic-stale-review"))
        supplierState(2, "INACTIVE")
        assertFalse(guard.isCurrent(ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", staleCandidate.id, 1)))
        val close = DecidePurchaseReviewDto(UUID.randomUUID().toString(), staleSource, "CLOSE_STALE", "Supplier is no longer eligible; prepare a current proposal")
        assertEquals("SUPERSEDED", service.decide(reviewer, staleCandidate.id, staleRequest, close, "synthetic-close").approvalState)
        assertTrue(service.decide(reviewer, staleCandidate.id, staleRequest, close, "synthetic-close").replayed)
        assertEquals("SUPERSEDED", service.completed(reviewer, staleRequest).approvalState)
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, staleRequest))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM approval_decision WHERE approval_request_id=?", Long::class.java, staleRequest))

        // The established generic API still commits the same terminal source
        // outcome and reports a conflict to its caller, including on replay.
        supplierState(3, "ACTIVE")
        val genericCandidate = staleCandidate.copy(id = UUID.randomUUID(), operationId = UUID.randomUUID(), revisionNumber = 4,
            replacesCandidateId = staleCandidate.id, rationale = "Synthetic fourth revision for generic conflict semantics")
        candidates.insert(genericCandidate)
        val genericRequest = request(genericCandidate)
        supplierState(4, "INACTIVE")
        val genericOperation = UUID.randomUUID()
        repeat(2) {
            assertEquals("APPROVAL_SUPERSEDED", assertThrows<ProductApiException> {
                engine.decide(reviewer, genericOperation, genericRequest, ApprovalDecisionType.REJECT, "Source changed before decision", "synthetic-generic-close")
            }.code)
        }
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, genericRequest))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM approval_decision WHERE approval_request_id=?", Long::class.java, genericRequest))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM procurement_purchase_order WHERE organization_id=?", Long::class.java, org))

        // Correcting the technical source invalidates the exact candidate pin.
        // Re-reviewing that source cannot silently resurrect the old proposal.
        supplierState(5, "ACTIVE")
        val packetTwo = technical.publish(quote.id, technical.fields().copy(model = "MODEL-CORRECTED", correctionReason = "Synthetic corrected model identification"))
        assertNull(sourcing.effectiveTechnicalAssessmentId(quote.id))
        assertFalse(guard.isCurrent(ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", genericCandidate.id, 1)))
        val assessmentTwo = technical.assess(quote.id, packetTwo.packetId)
        assertEquals(assessmentTwo.id, sourcing.effectiveTechnicalAssessmentId(quote.id))
        assertFalse(guard.isCurrent(ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", genericCandidate.id, 1)))
        assertEquals(technicalAssessment.id, candidates.byId(genericCandidate.id)?.technicalAssessmentId)
        val propose = ProposePurchaseOrderCandidateRequest(UUID.randomUUID(), quote.id, quote.revisionNumber,
            "Synthetic selection of the reviewed corrected technical source", genericCandidate.id, genericCandidate.revisionNumber)
        val successor = technical.candidateService.propose(ProcurementDraftContext(propose.operationId, buyer, "synthetic-technical-successor"), rfq.id, propose)
        val successorSource = requireNotNull(candidates.byId(successor.candidateId))
        assertEquals(assessmentTwo.id, successorSource.technicalAssessmentId)
        assertTrue(guard.isCurrent(ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", successorSource.id, 1)))
        assertEquals(technicalAssessment.id, candidates.byId(original.id)?.technicalAssessmentId)
    }
}
