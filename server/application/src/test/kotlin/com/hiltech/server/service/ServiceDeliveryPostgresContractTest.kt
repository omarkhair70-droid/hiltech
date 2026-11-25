package com.hiltech.server.service

import com.hiltech.server.approval.*
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.documents.*
import com.hiltech.server.documents.persistence.JdbcEvidencePersistence
import com.hiltech.server.documents.storage.*
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.projects.*
import com.hiltech.server.security.*
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

/**
 * Actual PostgreSQL, source-authority adapters, canonical records, ApprovalEngine,
 * immutable history, original-byte verification and package export. FGA transport
 * is an exact synthetic principal boundary and private storage supplies fixture
 * bytes; this does not claim a device, real S3 upload or client signature.
 */
class ServiceDeliveryPostgresContractTest {
    @Test fun canonicalServiceDocumentReservationAndFinalizeRecheckCurrentCreatorAuthorityAfterStorageVerification() = withFixture { f, _ ->
        val reserved = f.reserveDocument("AGREEMENT", "Synthetic source agreement")
        assertEquals("RESERVED", reserved.evidence.storageState)
        assertEquals("HIGHLY_RESTRICTED", reserved.evidence.classificationCode)
        assertEquals("INTERNAL_ONLY", reserved.evidence.clientVisibilityMode)
        val source = f.lifecycle
        val evidence = UUID.fromString(reserved.evidence.evidenceId)
        val request = FinalizeEvidenceRequest(UUID.randomUUID().toString(), reserved.evidence.uploadSessionId, reserved.evidence.sha256, reserved.evidence.sizeBytes)
        assertThrows<ProductApiException> { source.finalize(f.reviewer, evidence, request.operationId, "synthetic-not-creator", request) }
        assertEquals(0, f.verificationReads)
        f.beforeVerification = { f.jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", f.org, f.maker) }
        assertEquals("OBJECT_NOT_VISIBLE", assertThrows<ProductApiException> {
            source.finalize(f.maker, evidence, request.operationId, "synthetic-revoked-during-verification", request)
        }.code)
        assertEquals(1, f.verificationReads)
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT storage_state FROM evidence WHERE id=?", String::class.java, evidence))
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT state FROM evidence_upload_session WHERE evidence_id=?", String::class.java, evidence))
        assertTrue(f.repository.events(f.org, f.project).isEmpty())
    }

    @Test fun completeHandoverResponseWithdrawalWarrantyDecisionAndCorrectionPreserveOriginalRecords() = withFixture { f, tx ->
        val agreement = f.document("AGREEMENT", "Synthetic signed project agreement")
        val snapshot = f.snapshot()
        val release = f.propose("HANDOVER_RELEASE", 0, f.releaseDetails(snapshot, agreement))
        val prepared = f.repository.proposal(f.org, UUID.fromString(release.recordId))!!
        assertFalse(f.projectAuthority.canManageProject(f.reviewer, f.projectStore.project(f.project, f.at)!!))
        assertTrue(f.service.reviewQueue(f.reviewer, f.org).items.single().canReview)
        assertTrue(f.service.proposal(f.reviewer, f.org, prepared.id).viewedEvidenceIds.isEmpty())
        val request = f.engine.subjectStatus(ApprovalSubjectRef(f.org, prepared.workflow.subjectType, prepared.id, prepared.reviewVersion), prepared.workflow.policyKey)!!
        assertThrows<ProductApiException> { f.engine.decide(f.reviewer, UUID.randomUUID(), request.approvalRequestId, ApprovalDecisionType.APPROVE, null, "synthetic-no-proof") }
        val page = f.reader.proposalPage(f.reviewer, f.org, prepared.id, agreement, 0, "synthetic-delivered-page")
        assertTrue(f.repository.viewed(f.reviewer, prepared).isEmpty(), "Page delivery alone must not confirm native display")
        assertThrows<ProductApiException> { f.engine.decide(f.reviewer, UUID.randomUUID(), request.approvalRequestId, ApprovalDecisionType.APPROVE, null, "synthetic-not-displayed") }
        f.reader.confirmDisplayed(f.reviewer, f.org, prepared.id, UUID.fromString(page.viewReceiptId), "synthetic-displayed-page")
        assertEquals(ApprovalRequestState.APPROVED, f.engine.decide(f.reviewer, UUID.randomUUID(), request.approvalRequestId, ApprovalDecisionType.APPROVE, null, "synthetic-generic-approval").state)
        assertNull(f.repository.activeRelease(f.org, f.project), "Generic approval alone does not issue a package")
        val decision = ServiceDeliveryReviewRequest(UUID.randomUUID().toString(), "APPROVE", null)
        assertEquals("RECORDED", f.service.review(f.reviewer, f.org, prepared.id, decision, "synthetic-result").result)
        assertTrue(f.service.review(f.reviewer, f.org, prepared.id, decision, "synthetic-result").replayed)
        val released = f.repository.activeRelease(f.org, f.project)!!
        assertEquals("ISSUED", f.service.workspace(f.maker, f.project).deliveryState)
        val zip = f.packages.download(f.maker, f.project, released.id, null, "synthetic-package")
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(Base64.getDecoder().decode(zip.base64))).use { input ->
            while (true) { val entry = input.nextEntry ?: break; entries[entry.name] = input.readBytes() }
        }
        assertEquals(2, entries.size)
        assertTrue(entries.containsKey("Handover-index.pdf"))
        assertArrayEquals(f.workBytes, entries.filterKeys { it.startsWith("Documents/") }.values.single())
        assertFalse(entries.keys.any { it.contains("agreement", true) || it.contains("response", true) })

        val responseProof = f.document("CLIENT_RESPONSE", "Synthetic actual client acceptance")
        val response = ServiceDeliveryDetails(releaseEventId = released.id.toString(), proofEvidenceId = responseProof.toString(), responseOutcome = "ACCEPTED",
            responseOn = f.today, representativeName = "Synthetic client delegate", representativeCapacity = "Named project representative",
            representativeAuthoritySource = "The client nominated this representative in the attached response", representativeVerified = true,
            reason = "Actual client response and representative source checked")
        val responseProposal = f.propose("HANDOVER_CLIENT_RESPONSE", 1, response)
        f.approve(responseProposal)
        assertEquals("CLIENT_ACCEPTED", f.service.workspace(f.maker, f.project).deliveryState)
        val firstResponse = f.repository.latest(f.org, f.project, "HANDOVER")!!
        f.approve(f.propose("HANDOVER_CLIENT_RESPONSE", 2, response.copy(correctsEventId = firstResponse.id.toString(), responseOutcome = "ACCEPTED_WITH_RESERVATIONS",
            reason = "Correcting the reservations transcribed from the same original response")))
        assertEquals("CLIENT_ACCEPTED_WITH_RESERVATIONS", f.service.workspace(f.maker, f.project).deliveryState)
        assertEquals("ACCEPTED", f.repository.event(f.org, firstResponse.id)!!.proposal.details.responseOutcome)
        val withdraw = f.propose("HANDOVER_WITHDRAW", 3, ServiceDeliveryDetails(releaseEventId = released.id.toString(), reason = "A corrected delivery package must replace the earlier issue"))
        f.approve(withdraw)
        assertNull(f.repository.activeRelease(f.org, f.project))
        assertThrows<ProductApiException> { f.packages.download(f.maker, f.project, released.id, null, "synthetic-withdrawn") }
        val replacement = f.propose("HANDOVER_RELEASE", 4, f.releaseDetails(snapshot, agreement))
        f.approve(replacement)
        val replacementRelease = f.repository.activeRelease(f.org, f.project)!!
        assertThrows<ProductApiException> { f.propose("HANDOVER_CLIENT_RESPONSE", 5, response.copy(releaseEventId = replacementRelease.id.toString())) }

        val terms = ServiceDeliveryDetails(agreementEvidenceId = agreement.toString(), contractReference = "SYNTHETIC-AGREEMENT",
            scopeType = "SITE", scopeId = f.site.toString(), coveredItem = "Installed control equipment",
            startsOn = f.at.atOffset(ZoneOffset.UTC).toLocalDate().minusMonths(1).toString(), endsOn = f.at.atOffset(ZoneOffset.UTC).toLocalDate().plusMonths(11).toString(),
            writtenTerms = "Synthetic agreed repairs for this exact installation scope", exclusions = "Damage caused by unauthorized modification",
            responseObligations = "Response terms remain the explicit original agreement", scopeConfirmed = true, reason = "Exact written terms and dates transcribed from the original")
        val termsProposal = f.propose("WARRANTY_TERMS", 0, terms)
        f.approve(termsProposal)
        val termsEvent = f.repository.currentTerms(f.org, f.project).single()
        val incident = f.document("INCIDENT", "Synthetic incident report")
        val assessment = ServiceDeliveryDetails(termsEventId = termsEvent.id.toString(), ticketId = f.ticket.toString(), proofEvidenceId = incident.toString(),
            incidentOn = f.today, coverageDecision = "COVERED", incidentDescription = "Synthetic control unit failure within the agreed scope",
            scopeConfirmed = true, reason = "Scope and incident date match the agreed covered condition")
        val coveredProposal = f.propose("WARRANTY_ASSESSMENT", 0, assessment)
        f.approve(coveredProposal)
        assertEquals("COVERED", JdbcSupportDeskStore(f.jdbc).ticket(f.ticket, f.project)!!.coverageState)
        val covered = f.repository.ticketCoverage(f.project, f.ticket)!!
        val correction = f.propose("WARRANTY_ASSESSMENT", 1, assessment.copy(correctsEventId = covered.id.toString(), coverageDecision = "NOT_COVERED",
            exclusionApplies = true, exclusionReason = "Original incident investigation confirms unauthorized modification", reason = "Correcting the earlier decision after the documented investigation"))
        f.approve(correction)
        assertEquals("NOT_COVERED", JdbcSupportDeskStore(f.jdbc).ticket(f.ticket, f.project)!!.coverageState)
        assertEquals("COVERED", f.repository.event(f.org, covered.id)!!.proposal.details.coverageDecision)
        assertEquals(8, f.repository.events(f.org, f.project).size)
        f.rejectSql(tx) { f.jdbc.update("UPDATE service_delivery_event SET event_number=99 WHERE id=?", covered.id) }
        f.rejectSql(tx) { f.jdbc.update("DELETE FROM service_delivery_proposal WHERE id=?", prepared.id) }
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM service_delivery_event WHERE proposal_id=?", Int::class.java, prepared.id))
    }

    @Test fun staleSourceIndependentScopeAndRevocationDuringFileReadCannotProduceARecordedDecision() = withFixture { f, _ ->
        val agreement = f.document("AGREEMENT", "Synthetic agreement")
        val pending = f.propose("HANDOVER_RELEASE", 0, f.releaseDetails(f.snapshot(), agreement))
        val id = UUID.fromString(pending.recordId)
        f.jdbc.update("UPDATE work_order SET version=version+1 WHERE id=?", f.work)
        val stale = f.service.proposal(f.reviewer, f.org, id)
        assertEquals("STALE", stale.state); assertTrue(stale.canCloseStale); assertFalse(stale.canReview)
        val close = ServiceDeliveryReviewRequest(UUID.randomUUID().toString(), "CLOSE_STALE", null)
        assertEquals("SUPERSEDED", f.service.review(f.reviewer, f.org, id, close, "synthetic-stale").result)
        assertTrue(f.service.review(f.reviewer, f.org, id, close, "synthetic-stale").replayed)
        val closedSource = f.repository.proposal(f.org, id)!!
        assertEquals(ApprovalRequestState.SUPERSEDED, f.engine.subjectStatus(ApprovalSubjectRef(f.org,
            closedSource.workflow.subjectType, id, closedSource.reviewVersion), closedSource.workflow.policyKey)!!.state)
        assertTrue(f.repository.events(f.org, f.project).isEmpty())

        val refreshed = f.propose("HANDOVER_RELEASE", 0, f.releaseDetails(f.snapshot(), agreement))
        val next = UUID.fromString(refreshed.recordId)
        val reads = f.storageReads
        assertThrows<ProductApiException> { f.reader.proposalPage(f.outsider, f.org, next, agreement, 0, "synthetic-outsider") }
        assertThrows<ProductApiException> { f.reader.proposalPage(f.reviewer, UUID.randomUUID(), next, agreement, 0, "synthetic-other-company") }
        assertThrows<ProductApiException> { f.reader.proposalPage(f.reviewer, f.org, next, UUID.randomUUID(), 0, "synthetic-other-proof") }
        assertEquals(reads, f.storageReads)
        assertThrows<ProductApiException> { f.service.review(f.maker, f.org, next, ServiceDeliveryReviewRequest(UUID.randomUUID().toString(), "APPROVE", null), "synthetic-self-review") }
        f.beforeRead = { f.jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", f.org, f.reviewer) }
        assertThrows<ProductApiException> { f.reader.proposalPage(f.reviewer, f.org, next, agreement, 0, "synthetic-during-revoke") }
        assertTrue(f.repository.viewed(f.reviewer, f.repository.proposal(f.org, next)!!).isEmpty())
        assertFalse(f.service.reviewQueue(f.reviewer, f.org).canReview)
        assertThrows<ProductApiException> { f.service.review(f.reviewer, f.org, next, ServiceDeliveryReviewRequest(UUID.randomUUID().toString(), "APPROVE", null), "synthetic-revoked") }
        assertTrue(f.repository.events(f.org, f.project).isEmpty())
    }

    private fun withFixture(block: (Fixture, TransactionStatus) -> Unit) {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val ds = DriverManagerDataSource(System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech",
            System.getenv("HILTECH_DB_USER") ?: "hiltech", System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech")
        Flyway.configure().dataSource(ds).locations("filesystem:" + (System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations")).load().migrate()
        TransactionTemplate(DataSourceTransactionManager(ds)).execute { tx ->
            try { block(Fixture(ds), tx) } finally { tx.setRollbackOnly() }
        }
    }

    private class Fixture(ds: DriverManagerDataSource) {
        val jdbc = JdbcTemplate(ds)
        val at = Instant.now(); private val timestamp = at.atOffset(ZoneOffset.UTC); val today = timestamp.toLocalDate().toString()
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val org = organization("Synthetic service company", "HILTECH"); private val client = organization("Synthetic service client", "CLIENT")
        val maker = member("Synthetic project manager"); val reviewer = member("Synthetic independent reviewer"); val outsider = member("Synthetic unassigned employee")
        val project = UUID.randomUUID(); val site = UUID.randomUUID(); private val projectSite = UUID.randomUUID(); val work = UUID.randomUUID(); val ticket: UUID
        private val objects = mutableMapOf<String, ByteArray>()
        val workBytes = png()
        private val workEvidence: UUID
        val projectStore = JdbcProjectsPersistence(jdbc)
        private val fga = AuthorizationCheckPort { request -> request.checkTuple.subjectId == maker.toString() }
        val projectAuthority = SpringProjectAuthorization(StaticListableBeanFactory(mapOf("fga" to fga)).getBeanProvider(AuthorizationCheckPort::class.java),
            JdbcRoleTeamSourceAuthority(jdbc), JdbcProjectAuthoritySource(jdbc), clock)
        val repository = ServiceDeliveryRepository(jdbc)
        private val handoverSource = JdbcHandoverSource(jdbc)
        private val source = ServiceDeliverySources(projectStore, projectAuthority, repository, handoverSource, clock)
        private val approvals = JdbcApprovalPersistence(jdbc)
        private val audit = JdbcAuditEventWriter(jdbc)
        private val commands = JdbcIdempotentCommandExecutor(jdbc, DataSourceTransactionManager(ds), clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
        private val subject = ServiceDeliveryApprovalSubject(repository, source, approvals, CurrentServiceDeliveryReviewEligibility(jdbc, clock), clock)
        val engine = ApprovalEngineService(approvals, commands, AuthorizationProjectionIntentWriter {}, object : ApprovalAuthorizationPort {
            override fun canDecide(actorUserId: UUID, approvalRequestId: UUID, assignmentPrincipalType: ApprovalPrincipalType, assignmentPrincipalId: UUID) =
                actorUserId == reviewer && assignmentPrincipalType == ApprovalPrincipalType.USER && assignmentPrincipalId == reviewer
        }, FailClosedApprovalSubjectVersionGuard(listOf(subject)), audit, ApplicationEventPublisher {}, clock, decisionConstraints = listOf(subject))
        private val handover = HandoverService(projectStore, projectAuthority, handoverSource, commands, audit, clock)
        private val support = SupportDeskService(projectStore, projectAuthority, JdbcSupportDeskStore(jdbc), JdbcSupportCaseStore(jdbc), commands, audit, clock)
        val service = ServiceDeliveryService(repository, source, subject, handover, engine, commands, audit, clock, projectAuthority)
        var beforeVerification: () -> Unit = {}; var verificationReads = 0
        private val binaryStorage = object : EvidenceObjectStoragePort {
            override fun createUploadTarget(spec: EvidenceUploadSpec) = SignedEvidenceUploadTarget(spec.objectKey,
                "https://synthetic-storage.invalid/upload/${spec.evidenceId}", emptyMap(), at.plusSeconds(600))
            override fun verifyUploadedObject(spec: EvidenceUploadSpec): EvidenceFinalizeVerification {
                verificationReads++; beforeVerification()
                val bytes = objects[spec.objectKey] ?: return EvidenceFinalizeVerification.Retryable("SYNTHETIC_NOT_UPLOADED")
                val actualSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (actualSha != spec.expectedSha256Hex || bytes.size.toLong() != spec.expectedSizeBytes) return EvidenceFinalizeVerification.Rejected("SYNTHETIC_BYTES_MISMATCH")
                return EvidenceFinalizeVerification.Verified(spec.objectKey, bytes.size.toLong(), actualSha, null, "image/png")
            }
            override fun createDownloadTarget(objectKey: String): SignedEvidenceDownloadTarget = error("Private service originals must not produce a download URL")
        }
        val lifecycle: EvidenceLifecycleService by lazy {
            val target = ServiceDeliveryDocumentTarget(repository, projectStore, projectAuthority, clock)
            EvidenceLifecycleService(JdbcEvidencePersistence(jdbc), Mockito.mock(EvidenceTargetAuthorizationPort::class.java),
                Mockito.mock(EmployeeDocumentEvidenceTargetPort::class.java), Mockito.mock(SupplierQuoteDocumentEvidenceTargetPort::class.java),
                commands, AuthorizationProjectionIntentWriter {}, audit, EvidenceStorageAccessPort { binaryStorage },
                EvidenceStorageProperties(enabled = true), ApplicationEventPublisher {}, clock,
                serviceDeliveryDocumentTargetProvider = StaticListableBeanFactory(mapOf("target" to target)).getBeanProvider(ServiceDeliveryDocumentEvidenceTargetPort::class.java))
        }
        var beforeRead: () -> Unit = {}; var storageReads = 0
        val reader = ServiceDeliveryEvidenceReader(repository, source, subject, object : EvidenceStorageAccessPort {
            override fun current(): EvidenceObjectStoragePort? = null
            override fun readRestrictedObject(objectKey: String, maxBytes: Long): ByteArray { storageReads++; beforeRead(); return objects.getValue(objectKey).also { check(it.size <= maxBytes) } }
        }, audit, clock)
        val packages = ServiceDeliveryPackageService(repository, source, reader, audit, clock)

        init {
            jdbc.update("""INSERT INTO project_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,effective_from,created_by_user_id,created_at)
                VALUES (?,?,'PROJECT_ADMIN','USER',?,?,?,?)""", UUID.randomUUID(), org, maker, timestamp.minusDays(1), maker, timestamp)
            jdbc.update("""INSERT INTO project(id,organization_id,project_code,name,client_organization_id,lifecycle_state,source_type,created_at,created_by,updated_at)
                VALUES (?,?,?,'Synthetic installation',?,'ACTIVE','INTERNAL',?,?,?)""", project, org, "PROJECT-$project", client, timestamp, maker, timestamp)
            jdbc.update("""INSERT INTO site(id,organization_id,client_organization_id,site_code,name,status,created_at,created_by,updated_at)
                VALUES (?,?,?,?,'Synthetic plant','ACTIVE',?,?,?)""", site, org, client, "SITE-$site", timestamp, maker, timestamp)
            jdbc.update("INSERT INTO project_site(id,organization_id,project_id,site_id,lifecycle_state) VALUES (?,?,?,?,'PLANNED')", projectSite, org, project, site)
            jdbc.update("""INSERT INTO work_order(id,organization_id,project_id,site_id,project_site_id,work_order_code,title,lifecycle_state,readiness_state,priority_code,baseline_version,created_at,created_by,updated_at)
                VALUES (?,?,?,?,?,?,'Synthetic equipment installation','DRAFT','NOT_EVALUATED','NORMAL',1,?,?,?)""", work, org, project, site, projectSite, "WORK-$work", timestamp, maker, timestamp)
            val configs = listOf("work-types", "assignment-policies", "readiness-policies", "evidence-policies", "review-policies").map { family ->
                UUID.randomUUID().also { id -> jdbc.update("""INSERT INTO config_revision(id,scope_type,family,code,name,lifecycle_state,revision_number,created_at,created_by,activated_at,activated_by)
                    VALUES (?,'SYSTEM',?,?,'Synthetic service source','ACTIVE',1,?,?,?,?)""", id, family, "SERVICE_$id", timestamp, maker, timestamp, maker) }
            }
            val binding = UUID.randomUUID(); val instruction = UUID.randomUUID()
            jdbc.update("""INSERT INTO work_policy_binding(id,work_order_id,binding_revision,work_type_definition_id,work_type_revision,assignment_policy_id,assignment_policy_revision,
                readiness_policy_id,readiness_policy_revision,evidence_policy_id,evidence_policy_revision,review_policy_id,review_policy_revision,binding_created_at,binding_created_by)
                VALUES (?,?,1,?,1,?,1,?,1,?,1,?,1,?,?)""", binding, work, configs[0], configs[1], configs[2], configs[3], configs[4], timestamp, maker)
            jdbc.update("""INSERT INTO work_instruction_revision(id,work_order_id,instruction_revision,payload_schema_version,structured_payload_json,created_at,created_by,correlation_id)
                VALUES (?,?,1,1,'{}',?,?,'synthetic-service')""", instruction, work, timestamp, maker)
            jdbc.update("UPDATE work_order SET current_policy_binding_id=?,current_instruction_revision_id=?,lifecycle_state='ACCEPTED',readiness_state='READY',version=version+1 WHERE id=?", binding, instruction, work)
            jdbc.update("""INSERT INTO work_requirement_instance(id,work_order_id,requirement_family,requirement_key,requirement_type_code,required,satisfaction_state,updated_at)
                VALUES (?,?,'DOCUMENT','AS_BUILT','DOCUMENT',true,'SATISFIED',?)""", UUID.randomUUID(), work, timestamp)
            workEvidence = original("WORK_ORDER", work, "AS_BUILT", workBytes)
            val doc = UUID.randomUUID(); val revision = UUID.randomUUID()
            policy("SYNTHETIC_WORK_RELEASE", "SYNTHETIC_DOCUMENT_REVIEWER", false)
            jdbc.update("""INSERT INTO work_document(id,organization_id,work_order_id,requirement_key,document_code,title,approval_policy_key,created_by_user_id,created_at)
                VALUES (?,?,?,'AS_BUILT','DOC-SYNTHETIC','Synthetic approved as-built','SYNTHETIC_WORK_RELEASE',?,?)""", doc, org, work, maker, timestamp)
            jdbc.update("""INSERT INTO work_document_revision(id,organization_id,work_order_id,document_id,revision_sequence,revision_label,evidence_id,created_by_user_id,created_at)
                VALUES (?,?,?,?,1,'A',?,?,?)""", revision, org, work, doc, workEvidence, maker, timestamp)
            jdbc.update("""INSERT INTO work_document_revision_release(revision_id,organization_id,work_order_id,document_id,policy_key,policy_version,release_mode,created_at)
                VALUES (?,?,?,?,'SYNTHETIC_WORK_RELEASE',1,'NO_APPROVAL_REQUIRED',?)""", revision, org, work, doc, timestamp)
            DeliveryWorkflow.entries.forEach { policy(it.policyKey, it.reviewerKey, true) }
            val incidentOperation = UUID.randomUUID()
            val projectVersion = projectStore.project(project, at)!!.version
            val opened = support.open(project, maker, incidentOperation, projectVersion, "Synthetic equipment incident", "Actual fixture incident", "MAJOR", "P2", "synthetic-ticket-open")
            ticket = UUID.fromString(opened.ticketId)
            assertEquals("NOT_EVALUATED", opened.coverageState)
            assertEquals("NOT_CONFIGURED", opened.slaState)
            assertEquals("INTERNAL_ONLY", opened.clientVisibility)
            assertFalse(opened.duplicateReplay)
            val replay = support.open(project, maker, incidentOperation, projectVersion, "Synthetic equipment incident", "Actual fixture incident", "MAJOR", "P2", "synthetic-ticket-retry")
            assertEquals(ticket.toString(), replay.ticketId)
            assertTrue(replay.duplicateReplay)
            assertEquals(1, support.tickets(project, maker).size)
        }

        fun snapshot() = handover.createDraft(project, maker, UUID.randomUUID(), projectStore.project(project, at)!!.version,
            handoverSource.latest(project)?.versionNumber ?: 0, "synthetic-service").packageVersionId
        fun releaseDetails(version: String, agreement: UUID) = ServiceDeliveryDetails(packageVersionId = version, agreementEvidenceId = agreement.toString(),
            contractReference = "SYNTHETIC-AGREEMENT", scopeConfirmed = true, disclosureConfirmed = true, reason = "Exact approved package and agreement reviewed for disclosure")
        fun propose(action: String, base: Int, details: ServiceDeliveryDetails): ServiceDeliveryReceipt {
            val request = ServiceDeliveryProposeRequest(UUID.randomUUID().toString(), action, base, details)
            val first = service.propose(maker, project, request, "synthetic-service")
            assertTrue(service.propose(maker, project, request, "synthetic-service").replayed)
            return first
        }
        fun approve(receipt: ServiceDeliveryReceipt) {
            val p = service.proposal(reviewer, org, UUID.fromString(receipt.recordId))
            p.requiredEvidenceIds.forEach { evidence ->
                val page = reader.proposalPage(reviewer, org, UUID.fromString(p.proposalId), UUID.fromString(evidence), 0, "synthetic-proof")
                reader.confirmDisplayed(reviewer, org, UUID.fromString(p.proposalId), UUID.fromString(page.viewReceiptId), "synthetic-displayed")
            }
            val request = ServiceDeliveryReviewRequest(UUID.randomUUID().toString(), "APPROVE", null)
            assertEquals("RECORDED", service.review(reviewer, org, UUID.fromString(p.proposalId), request, "synthetic-service").result)
            assertTrue(service.review(reviewer, org, UUID.fromString(p.proposalId), request, "synthetic-service").replayed)
        }
        fun document(purpose: String, title: String): UUID {
            val reserved = reserveDocument(purpose, title)
            val id = UUID.fromString(reserved.evidence.evidenceId)
            val request = FinalizeEvidenceRequest(UUID.randomUUID().toString(), reserved.evidence.uploadSessionId, reserved.evidence.sha256, reserved.evidence.sizeBytes)
            assertEquals("READY", lifecycle.finalize(maker, id, request.operationId, "synthetic-finalize", request).evidence.storageState)
            assertTrue(lifecycle.finalize(maker, id, request.operationId, "synthetic-finalize-replay", request).replayed)
            return id
        }
        fun reserveDocument(purpose: String, title: String): ReserveEvidenceUploadResponse {
            val slot = service.createDocument(maker, project, ServiceDeliveryDocumentRequest(UUID.randomUUID().toString(), purpose, title), "synthetic-service")
            val bytes = png(); val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val request = ReserveEvidenceUploadRequest(UUID.randomUUID().toString(), "SERVICE_DELIVERY_DOCUMENT", slot.slotId,
                evidenceRequirementKey = "SERVICE_DELIVERY_$purpose", evidenceTypeCode = "SERVICE_DELIVERY_$purpose", contentType = "image/png",
                originalFileName = "synthetic-original.png", sizeBytes = bytes.size.toLong(), expectedSha256 = sha, capturedAt = at.toString())
            val reserved = lifecycle.reserve(maker, request.operationId, "synthetic-reserve", request)
            val replay = lifecycle.reserve(maker, request.operationId, "synthetic-reserve-replay", request)
            assertTrue(replay.replayed); assertEquals(reserved.evidence.evidenceId, replay.evidence.evidenceId)
            val key = jdbc.queryForObject("SELECT object_key_ref FROM evidence WHERE id=?", String::class.java, UUID.fromString(reserved.evidence.evidenceId))!!
            objects[key] = bytes
            return reserved
        }
        private fun original(type: String, target: UUID, evidenceType: String, bytes: ByteArray): UUID {
            val id = UUID.randomUUID(); val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }; val key = "synthetic/service/$id"
            objects[key] = bytes
            jdbc.update("""INSERT INTO evidence(id,organization_id,target_type,target_id,work_order_id,evidence_requirement_key,evidence_type_code,content_type,original_file_name,size_bytes,sha256,
                captured_at,captured_by_user_id,storage_state,object_key_ref,finalized_at,classification_code,client_visibility_mode,created_at,security_scan_class)
                VALUES (?,?,?,?,?,?,?,'image/png','synthetic-original.png',?,?,?,?,'READY',?,?,?,'INTERNAL_ONLY',?,'NATIVE_MEDIA')""",
                id, org, type, target, work.takeIf { type == "WORK_ORDER" }, evidenceType, evidenceType, bytes.size, sha, timestamp, maker, key, timestamp,
                if (type == "WORK_ORDER") "INTERNAL" else "HIGHLY_RESTRICTED", timestamp)
            jdbc.update("""INSERT INTO evidence_upload_session(id,evidence_id,expected_sha256,expected_size_bytes,expires_at,state,created_at,finalized_at,operation_id)
                VALUES (?,?,?,?,?,'READY',?,?,?)""", UUID.randomUUID(), id, sha, bytes.size, timestamp.plusHours(2), timestamp, timestamp, UUID.randomUUID())
            return id
        }
        private fun organization(name: String, type: String): UUID = UUID.randomUUID().also { id ->
            jdbc.update("INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at) VALUES (?,?,?,?,?,'ACTIVE',?)", id, "TEST-$id", name, name, type, timestamp)
        }
        private fun member(name: String): UUID = UUID.randomUUID().also { id ->
            val person = UUID.randomUUID()
            jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES (?,?,?,?)", person, name, timestamp, timestamp)
            jdbc.update("INSERT INTO user_identity(id,auth_provider,auth_subject,person_id,status,created_at) VALUES (?,'synthetic-service',?,?,'ACTIVE',?)", id, id.toString(), person, timestamp)
            jdbc.update("INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from) VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)", UUID.randomUUID(), org, id, timestamp.minusDays(1))
            jdbc.update("INSERT INTO employee(id,organization_id,person_id,employee_code,state,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE',?,?)", UUID.randomUUID(), org, person, "EMP-$id", timestamp, timestamp)
        }
        private fun policy(key: String, authority: String, required: Boolean) {
            if (required) jdbc.update("""INSERT INTO approval_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,effective_from,active,created_by_user_id,created_at)
                VALUES (?,?,?,'USER',?,?,true,?,?)""", UUID.randomUUID(), org, authority, reviewer, timestamp.minusDays(1), maker, timestamp)
            jdbc.update("""INSERT INTO approval_policy_version(id,organization_id,policy_key,version_number,lifecycle_state,approval_required,step_mode,authority_key,reason_required,effective_from,created_by_user_id,created_at)
                VALUES (?,?,?,1,'ACTIVE',?,?,?,true,?,?,?)""", UUID.randomUUID(), org, key, required,
                if (required) "SINGLE" else "NO_APPROVAL_REQUIRED", if (required) authority else null, timestamp.minusDays(1), maker, timestamp)
        }
        fun rejectSql(tx: TransactionStatus, block: () -> Unit) {
            val save = tx.createSavepoint()
            try { assertThrows<DataIntegrityViolationException> { block() } }
            finally { tx.rollbackToSavepoint(save); tx.releaseSavepoint(save) }
        }
        private fun png() = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png", output); output.toByteArray()
        }
    }
}
