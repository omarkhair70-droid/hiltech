package com.hiltech.server.finance

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.FinanceBankIntakeEvidenceBinding
import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Protected delivery of the exact uploaded source; never grants generic bank-file access. */
@Service
class FinancePayrollEvidenceService(
    private val repository: FinancePayrollDisbursementRepository,
    private val authority: FinancePayrollAuthorityPort,
    private val subject: FinancePayrollResultSubject,
    private val storage: EvidenceStorageAccessPort,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    internal fun canAccess(actor: UUID, org: UUID, proposalId: UUID): Boolean = access(actor, org, proposalId) != null

    private fun access(actor: UUID, org: UUID, proposalId: UUID): Pair<FinancePayrollProposalSource, FinancePayrollEvidenceSource>? {
        val proposal = repository.proposal(org, proposalId) ?: return null
        val instruction = repository.instruction(org, proposal.instructionId) ?: return null
        val source = repository.evidenceSource(org, proposalId) ?: return null
        val ownPreparedSource = authority.canPrepare(actor, org) && (actor == proposal.preparedBy || actor == source.uploader)
        val independentReviewer = authority.canReview(actor, org) && subject.policy(org)?.reviewerId == actor &&
            actor != proposal.preparedBy && !repository.isBeneficiary(actor, instruction.employeeId)
        return if (ownPreparedSource || independentReviewer) proposal to source else null
    }

    @Transactional
    fun read(actor: UUID, org: UUID, proposalId: UUID, correlation: String): FinancePayrollEvidenceDocument {
        val (proposal, source) = access(actor, org, proposalId) ?: unavailable()
        if (source.sizeBytes !in 1..FinanceBankIntakeEvidenceBinding.MAX_BYTES.toLong()) corrupt()
        val bytes = try {
            storage.readRestrictedObject(source.objectKey, source.sizeBytes) ?: storageUnavailable()
        } catch (failure: ProductApiException) { throw failure }
        catch (_: Exception) { storageUnavailable() }
        if (bytes.size.toLong() != source.sizeBytes) corrupt()
        val parsed = runCatching {
            FinanceBankCsvParser.decodeAndValidate(Base64.getEncoder().encodeToString(bytes), source.evidenceSha256)
        }.getOrElse { corrupt() }
        val matching = parsed.lines.singleOrNull { it.externalTransactionId == source.reference } ?: corrupt()
        if (matching.bookedOn != source.bookedOn || matching.flow.name != source.flow || matching.currency != source.currency ||
            BigDecimal(matching.amount).compareTo(source.amount) != 0 || matching.originalLineSha256 != source.originalLineSha256) corrupt()
        // An object-store read may take time. Revocation or source changes must stop delivery.
        val refreshed = access(actor, org, proposalId) ?: unavailable()
        if (refreshed.first != proposal || refreshed.second != source) unavailable()
        val receipt = UUID.randomUUID()
        repository.recordEvidenceView(receipt, actor, org, proposal, clock.instant(), correlation)
        audit.append(AuditEventRecord(actorUserId = actor, action = "FINANCE_PAYROLL_EVIDENCE_VIEWED", targetType = "Evidence", targetId = source.evidenceId,
            occurredAt = clock.instant(), correlationId = correlation, reason = "EXACT_PAYROLL_PROPOSAL_PROTECTED_DELIVERY",
            configRevisionRefsJson = """{"proposalId":"${proposal.id}","reviewVersion":${proposal.reviewVersion},"viewReceiptId":"$receipt"}"""))
        return FinancePayrollEvidenceDocument(org, proposalId, source.evidenceId, receipt,
            source.fileName.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 && it.code != 127 }.take(180).ifBlank { "bank-statement.csv" },
            source.contentType, source.sizeBytes, source.evidenceSha256, source.reference, bytes.toString(Charsets.UTF_8))
    }

    private fun unavailable(): Nothing = throw ProductApiException("FINANCE_PAYROLL_EVIDENCE_UNAVAILABLE", "The requested source document is not available for this responsibility.", HttpStatus.NOT_FOUND)
    private fun corrupt(): Nothing = throw ProductApiException("FINANCE_PAYROLL_EVIDENCE_SOURCE_MISMATCH", "The source document could not be verified against the approved bank record.", HttpStatus.CONFLICT)
    private fun storageUnavailable(): Nothing = throw ProductApiException("EVIDENCE_STORAGE_UNAVAILABLE", "The protected source document is temporarily unavailable.", HttpStatus.SERVICE_UNAVAILABLE, retryable = true)
}
