package com.hiltech.server.procurement

import com.hiltech.server.approval.JdbcApprovalPersistence
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.documents.*
import com.hiltech.server.documents.persistence.JdbcEvidencePersistence
import com.hiltech.server.documents.storage.*
import com.hiltech.server.operationalaccess.JdbcOperationalAuthority
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import com.hiltech.server.warehouse.JdbcWarehousePersistence
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.work.WorkPersistencePort
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.ImageIO

/** Real JDBC source, operating authority, command and Evidence lifecycle; only binary storage is synthetic. */
internal class SupplierTechnicalPostgresWorkflow(
    val jdbc: JdbcTemplate, val clock: Clock, val org: UUID, val buyer: UUID, val reviewer: UUID,
) {
    private val now = clock.instant()
    private val at = now.atOffset(ZoneOffset.UTC)
    val sourcing = SupplierSourcingPersistence(jdbc)
    val requirements = PurchaseRequirementDraftPersistence(jdbc)
    val repository = SupplierTechnicalReviewRepository(jdbc)
    val stock = StockLedgerPersistence(jdbc)
    val work = Mockito.mock(WorkPersistencePort::class.java)
    val evidence = JdbcEvidencePersistence(jdbc)
    val verifier = ProcurementDemandSourceVerifier(work, JdbcWarehousePersistence(jdbc), stock, clock,
        Mockito.mock(AssetPurchaseSource::class.java), Mockito.mock(OfficeStockSource::class.java))
    val approvals = JdbcApprovalPersistence(jdbc)
    private val operating = JdbcOperationalAuthority(jdbc)
    val buyerAuthority = CurrentBuyerSourcingAuthority(approvals, clock, true, "", operating)
    val reviewAuthority = CurrentQuoteTechnicalReviewAuthority(approvals, sourcing, clock, true, "", operating)
    val source = SupplierTechnicalReviewSource(repository, requirements, verifier, buyerAuthority, reviewAuthority, clock)
    private val audit = JdbcAuditEventWriter(jdbc)
    private val commands = JdbcIdempotentCommandExecutor(jdbc, DataSourceTransactionManager(jdbc.dataSource!!), clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
    val service = SupplierTechnicalReviewService(repository, source, sourcing, commands, audit, clock)
    private val verification = JdbcSupplierQuoteDocumentVerification(jdbc, buyerAuthority, sourcing, true)
    val sourcingService = SupplierSourcingService(sourcing, requirements, work, stock, buyerAuthority, reviewAuthority, verification,
        commands, audit, clock, true, verifier,
        StaticListableBeanFactory(mapOf("technical" to source)).getBeanProvider(SupplierTechnicalAssessmentSourcePort::class.java))
    val candidates = PurchaseOrderCandidatePersistence(jdbc)
    val candidateService = PurchaseOrderCandidateService(sourcing, sourcingService, buyerAuthority, verification, candidates, commands, audit, clock, true, true)
    val candidateGuard = PurchaseOrderCandidateApprovalSubject(candidates, sourcing, requirements, work, stock, evidence, clock, true, verifier)
    private val target = SupplierTechnicalDocumentTarget(repository, source)
    val objects = mutableMapOf<String, ByteArray>()
    var beforeRead: () -> Unit = {}
    var beforeVerification: () -> Unit = {}
    var reads = 0
    val bytes = ByteArrayOutputStream().use { output -> ImageIO.write(BufferedImage(40, 28, BufferedImage.TYPE_INT_RGB), "png", output); output.toByteArray() }
    val sha = technicalHash(bytes)
    private val storage = object : EvidenceObjectStoragePort {
        override fun createUploadTarget(spec: EvidenceUploadSpec) = SignedEvidenceUploadTarget(spec.objectKey,
            "https://synthetic-technical.invalid/upload/${spec.evidenceId}", emptyMap(), now.plusSeconds(600))
        override fun verifyUploadedObject(spec: EvidenceUploadSpec): EvidenceFinalizeVerification {
            beforeVerification()
            val actual = objects[spec.objectKey] ?: return EvidenceFinalizeVerification.Retryable("SYNTHETIC_NOT_UPLOADED")
            val hash = technicalHash(actual)
            if (actual.size.toLong() != spec.expectedSizeBytes || hash != spec.expectedSha256Hex) return EvidenceFinalizeVerification.Rejected("SYNTHETIC_BYTES_CHANGED")
            return EvidenceFinalizeVerification.Verified(spec.objectKey, actual.size.toLong(), hash, null, "image/png")
        }
        override fun createDownloadTarget(objectKey: String): SignedEvidenceDownloadTarget = error("Technical proof never receives a public URL")
        override fun readRestrictedObject(objectKey: String, maxBytes: Long): ByteArray { reads++; beforeRead(); return objects.getValue(objectKey).also { check(it.size <= maxBytes) } }
    }
    val lifecycle = EvidenceLifecycleService(evidence, Mockito.mock(EvidenceTargetAuthorizationPort::class.java),
        Mockito.mock(EmployeeDocumentEvidenceTargetPort::class.java), Mockito.mock(SupplierQuoteDocumentEvidenceTargetPort::class.java),
        commands, AuthorizationProjectionIntentWriter {}, audit, EvidenceStorageAccessPort { storage }, EvidenceStorageProperties(enabled = true), ApplicationEventPublisher {}, clock,
        supplierTechnicalDocumentTargetProvider = StaticListableBeanFactory(mapOf("technical" to target)).getBeanProvider(SupplierTechnicalDocumentEvidenceTargetPort::class.java))
    val reader = SupplierTechnicalEvidenceReader(repository, source, EvidenceStorageAccessPort { storage }, audit, clock)
    val pricedReader = SupplierQuoteProtectedDocumentService(sourcing, evidence, verification, buyerAuthority, EvidenceStorageAccessPort { storage }, audit, clock, true)

    init { grant(buyer, "PROCUREMENT_BUYER"); grant(reviewer, "PROCUREMENT_TECHNICAL_REVIEW") }
    fun grant(actor: UUID, role: String) {
        jdbc.update("""INSERT INTO operational_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,active,effective_from,created_by_user_id,created_at)
            VALUES(?,?,?,'USER',?,true,?,?,?) ON CONFLICT DO NOTHING""", UUID.randomUUID(), org, role, actor, at.minusDays(1), buyer, at)
    }
    fun revoke(actor: UUID, role: String) {
        jdbc.update("UPDATE operational_authority_binding SET active=false,effective_to=?,version=version+1 WHERE organization_id=? AND authority_key=? AND principal_user_id=? AND active=true", at, org, role, actor)
    }
    fun fields(documents: List<String> = emptyList(), reason: String = "Synthetic source prepared for review") = SupplierTechnicalPacketFields(
        manufacturer = "Synthetic manufacturer", model = "MODEL-TEST", offeredSpecification = "Synthetic component with the exact required rating and mounting specification",
        deviations = "No deviations in the supplied specification", supplierSource = "Supplier technical statement", sourceReference = "TECH-SOURCE-TEST",
        sourceDate = LocalDate.now(clock).toString(), evidenceIds = documents, nonFinancialConfirmed = true, correctionReason = reason)
    fun publish(quote: UUID, details: SupplierTechnicalPacketFields = fields()): SupplierTechnicalPacketReceiptDto {
        val request = SupplierTechnicalPublishRequest(UUID.randomUUID().toString(), repository.latest(quote)?.revision ?: 0, details)
        val result = service.publish(buyer, quote, request, "synthetic-technical")
        val replay = service.publish(buyer, quote, request, "synthetic-technical-replay")
        assertTrue(replay.replayed); assertEquals(result.packetId, replay.packetId)
        return result
    }
    fun assess(quote: UUID, packet: String?, decision: String = "COMPLIANT", acknowledged: Boolean = true): SupplierQuoteTechnicalAssessmentReceipt {
        val request = RecordSupplierQuoteTechnicalAssessmentRequest(UUID.randomUUID(), sourcing.latestTechnicalReviewRevision(quote) + 1,
            decision, "Synthetic specification compared against the exact requirement", packet?.let(UUID::fromString), packet != null && acknowledged)
        val context = ProcurementDraftContext(request.operationId, reviewer, "synthetic-technical")
        val receipt = sourcingService.recordTechnicalAssessment(context, quote, request)
        assertTrue(sourcingService.recordTechnicalAssessment(context, quote, request).replayed)
        return receipt
    }
    fun reserve(quote: UUID, title: String = "Synthetic technical original"): ReserveEvidenceUploadResponse {
        val request = SupplierTechnicalDocumentRequest(UUID.randomUUID().toString(), title)
        val slot = service.createDocument(buyer, quote, request, "synthetic-technical-document")
        assertTrue(service.createDocument(buyer, quote, request, "synthetic-technical-document-replay").replayed)
        val body = ReserveEvidenceUploadRequest(UUID.randomUUID().toString(), "SUPPLIER_TECHNICAL_DOCUMENT", slot.slotId,
            evidenceRequirementKey = "SUPPLIER_TECHNICAL_SOURCE", evidenceTypeCode = "SUPPLIER_TECHNICAL_SOURCE", contentType = "image/png",
            originalFileName = "synthetic-technical.png", sizeBytes = bytes.size.toLong(), expectedSha256 = sha, capturedAt = now.toString())
        val reserved = lifecycle.reserve(buyer, body.operationId, "synthetic-technical-reserve", body)
        assertTrue(lifecycle.reserve(buyer, body.operationId, "synthetic-technical-reserve-replay", body).replayed)
        val key = jdbc.queryForObject("SELECT object_key_ref FROM evidence WHERE id=?", String::class.java, UUID.fromString(reserved.evidence.evidenceId))!!
        objects[key] = bytes
        return reserved
    }
    fun finish(reserved: ReserveEvidenceUploadResponse): UUID {
        val id = UUID.fromString(reserved.evidence.evidenceId)
        val request = FinalizeEvidenceRequest(UUID.randomUUID().toString(), reserved.evidence.uploadSessionId, sha, bytes.size.toLong())
        val result = lifecycle.finalize(buyer, id, request.operationId, "synthetic-technical-finalize", request)
        assertEquals("READY", result.evidence.storageState)
        assertTrue(lifecycle.finalize(buyer, id, request.operationId, "synthetic-technical-finalize-replay", request).replayed)
        return id
    }
}
