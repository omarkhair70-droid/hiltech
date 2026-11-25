package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class FinancePayrollDisbursementService(
    private val repository: FinancePayrollDisbursementRepository,
    private val authority: FinancePayrollAuthorityPort,
    private val subject: FinancePayrollResultSubject,
    private val approvals: ApprovalEngineService,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val evidence: FinancePayrollEvidenceService,
) {
    fun capabilities(actor: UUID, org: UUID): FinancePayrollCapability {
        val prepare = authority.canPrepare(actor, org)
        val review = authority.canReview(actor, org)
        return FinancePayrollCapability(org, prepare, review, (prepare || review) && subject.policy(org) != null)
    }

    fun batches(actor: UUID, org: UUID): List<FinancePayrollBatchView> {
        readable(actor, org)
        return repository.batches(org)
    }

    fun run(actor: UUID, org: UUID, run: UUID): FinancePayrollRunView {
        readable(actor, org)
        val proposals = repository.proposalViews(org, run).map { view ->
            val current = subject.current(org, view.proposalId)
            view.copy(
                state = if (view.state in setOf("PENDING", "PROPOSED", "APPROVED") && current == null) "STALE" else view.state,
                canReview = current != null && current.policy.reviewerId == actor && current.proposal.preparedBy != actor &&
                    authority.canReview(actor, org) && view.state in setOf("PENDING", "APPROVED"),
                canOpenEvidence = evidence.canAccess(actor, org, view.proposalId),
                evidenceViewed = current?.proposal?.let { repository.hasEvidenceView(actor, org, it) } == true,
                canCloseStale = current == null && view.state == "PENDING" && canCloseStale(actor, org, view.proposalId),
            )
        }
        return FinancePayrollRunView(org, run, repository.instructionViews(org, run), proposals)
    }

    fun prepareBatch(actor: UUID, org: UUID, reviewBatch: UUID, operation: UUID, correlation: String): FinancePayrollCommandReceipt {
        preparer(actor, org)
        val id = derivedId("batch", operation)
        val execution = commands.execute(spec(operation, actor, "FINANCE_PAYROLL_BATCH_PREPARED", id, listOf(org, reviewBatch), correlation)) {
            val source = repository.batches(org).singleOrNull { it.reviewBatchId == reviewBatch } ?: unavailable()
            if (!repository.lockRunPeriod(org, source.runVersionId)) unavailable()
            preparer(actor, org)
            if (repository.batches(org).any { it.reviewBatchId == reviewBatch && it.prepared }) fail("FINANCE_PAYROLL_BATCH_ALREADY_PREPARED", HttpStatus.CONFLICT)
            if (!subject.payable(org, source.runVersionId, source.sourceFingerprint)) stale()
            if (repository.differentialCorrection(source.runVersionId)) fail("FINANCE_PAYROLL_DIFFERENTIAL_SOURCE_REQUIRED", HttpStatus.CONFLICT)
            repository.prepareBatch(id, org, source, operation, actor, clock.instant())
            audit(actor, "FINANCE_PAYROLL_BATCH_PREPARED", id, correlation, "INTERNAL_INSTRUCTIONS_NO_BANK_EXECUTION")
            IdempotentCommandOutcome("FINANCE_PAYROLL_BATCH_PREPARED", """{"id":"$id"}""")
        }
        return FinancePayrollCommandReceipt(operation, org, id, "PREPARED", execution.replayed)
    }

    fun bankChoices(actor: UUID, org: UUID, instruction: UUID, action: String): List<FinancePayrollBankLineView> {
        preparer(actor, org)
        if (action !in setOf("SUCCESS", "RETURNED")) invalid()
        val source = repository.instruction(org, instruction) ?: unavailable()
        return repository.bankChoices(source, if (action == "SUCCESS") "DEBIT" else "CREDIT")
    }

    fun propose(actor: UUID, org: UUID, instruction: UUID, request: FinancePayrollPrepareResult, correlation: String): FinancePayrollCommandReceipt {
        preparer(actor, org)
        val reason = request.reason.trim()
        if (request.baseResultVersion < 0 || request.baseResultVersion == Long.MAX_VALUE || reason.length !in 8..500 ||
            reason.any { it.code < 32 || it.code == 127 } || request.action !in setOf("SUCCESS", "RETURNED", "VOID_MATCH") ||
            (request.action != "VOID_MATCH" && !request.beneficiaryConfirmed)) invalid()
        val id = derivedId("proposal", request.operationId)
        val execution = commands.execute(spec(request.operationId, actor, "FINANCE_PAYROLL_RESULT_PROPOSED", id,
            listOf(org, instruction, request.baseResultVersion, request.action, request.bankStatementLineId,
                request.correctsEventId, request.beneficiaryConfirmed, reason), correlation)) {
            val source = repository.instruction(org, instruction, lock = true) ?: unavailable()
            preparer(actor, org)
            val policy = subject.policy(org) ?: fail("FINANCE_PAYROLL_REVIEW_SETUP_REQUIRED", HttpStatus.CONFLICT)
            if (actor == policy.reviewerId || repository.isBeneficiary(policy.reviewerId, source.employeeId)) {
                fail("FINANCE_PAYROLL_INDEPENDENT_REVIEWER_REQUIRED", HttpStatus.CONFLICT)
            }
            val previous = repository.latest(org, instruction)
            if ((previous?.version ?: 0L) != request.baseResultVersion ||
                !FinancePayrollResultRules.transitionAllowed(previous, request.action, request.correctsEventId)) stale()
            val proof = if (request.action == "VOID_MATCH") {
                if (request.bankStatementLineId != null || previous == null) invalid()
                if (!repository.canCorrectMatch(org, previous.id, lock = true)) stale()
                previous.evidenceId to previous.evidenceSha256
            } else {
                if (request.correctsEventId != null) invalid()
                val bank = request.bankStatementLineId?.let { repository.bank(org, it, lock = true) } ?: unavailable()
                if (bank.reconciled || bank.amount.compareTo(source.amount) != 0 || bank.currency != source.currency || bank.remaining < source.amount ||
                    bank.flow != (if (request.action == "SUCCESS") "DEBIT" else "CREDIT")) stale()
                if (request.action == "SUCCESS" && !subject.payable(org, source.runId, source.fingerprint)) stale()
                bank.evidenceId to bank.evidenceSha256
            }
            if (!repository.evidenceReady(org, proof.first, proof.second)) fail("FINANCE_PAYROLL_PROOF_NOT_READY", HttpStatus.CONFLICT)
            val draft = FinancePayrollProposalSource(id, org, instruction, request.baseResultVersion, request.action,
                request.bankStatementLineId, request.correctsEventId, proof.first, proof.second, reason, 1, actor)
            val proposal = draft.copy(reviewVersion = FinancePayrollResultRules.version(draft, source, policy))
            repository.insertProposal(proposal, request.operationId, clock.instant())
            val review = approvals.requestException(actorUserId = actor, operationId = derivedId("review-request", request.operationId),
                subject = ApprovalSubjectRef(org, FINANCE_PAYROLL_RESULT_SUBJECT, id, proposal.reviewVersion),
                policyKey = FINANCE_PAYROLL_RESULT_POLICY, reasonCode = "PAYROLL_DISBURSEMENT_EVIDENCE_REVIEW",
                safeReasonSummary = null, correlationId = correlation, allowAuthorityBypass = false)
            check(review.approvalRequired && review.approvalRequestId != null && review.state == ApprovalRequestState.PENDING)
            audit(actor, "FINANCE_PAYROLL_RESULT_PROPOSED", id, correlation, request.action)
            IdempotentCommandOutcome("FINANCE_PAYROLL_RESULT_PROPOSED", """{"id":"$id"}""")
        }
        return FinancePayrollCommandReceipt(request.operationId, org, id, "SUBMITTED_FOR_REVIEW", execution.replayed)
    }

    fun review(actor: UUID, org: UUID, proposal: UUID, request: FinancePayrollReviewResult, correlation: String): FinancePayrollCommandReceipt {
        reviewer(actor, org)
        val closingStale = request.decision == "CLOSE_STALE"
        val decision = when (request.decision) { "APPROVE" -> ApprovalDecisionType.APPROVE; "REJECT", "CLOSE_STALE" -> ApprovalDecisionType.REJECT; else -> invalid() }
        val comment = request.comment?.trim()?.takeIf { it.isNotEmpty() }
        if ((decision == ApprovalDecisionType.REJECT && !closingStale && comment == null) || (comment != null && (comment.length > 500 || comment.any { it.code < 32 }))) invalid()
        val eventId = derivedId("result", request.operationId)
        val execution = commands.execute(spec(request.operationId, actor, "FINANCE_PAYROLL_RESULT_REVIEWED", proposal,
            listOf(org, proposal, request.decision, comment), correlation)) {
            val sourceProposal = repository.proposal(org, proposal) ?: unavailable()
            repository.instruction(org, sourceProposal.instructionId, lock = true) ?: unavailable()
            if (closingStale) {
                if (!canCloseStale(actor, org, proposal) || subject.current(org, proposal) != null) stale()
                val reference = ApprovalSubjectRef(org, FINANCE_PAYROLL_RESULT_SUBJECT, proposal, sourceProposal.reviewVersion)
                val status = approvals.subjectStatus(reference, FINANCE_PAYROLL_RESULT_POLICY)?.takeIf { it.state == ApprovalRequestState.PENDING } ?: stale()
                val closed = approvals.decideForSourceWorkflow(actor, derivedId("review-decision", request.operationId), status.approvalRequestId,
                    ApprovalDecisionType.REJECT, "SOURCE_CHANGED_CLOSE_OUTDATED_PAYROLL_MATCH", correlation)
                // Engine alone checks current source and performs SUPERSEDED. Never turn this command into rejection, approval or payment.
                if (closed.state != ApprovalRequestState.SUPERSEDED) stale()
                audit(actor, "FINANCE_PAYROLL_REVIEW_SUPERSEDED", proposal, correlation, "SOURCE_CHANGED")
                return@execute IdempotentCommandOutcome("FINANCE_PAYROLL_REVIEW_SUPERSEDED", """{"id":"$proposal"}""")
            }
            sourceProposal.bankLineId?.let { repository.bank(org, it, lock = true) ?: stale() }
            sourceProposal.correctsEventId?.let { if (!repository.canCorrectMatch(org, it, lock = true)) stale() }
            if (sourceProposal.action == "SUCCESS") {
                val instruction = repository.instruction(org, sourceProposal.instructionId) ?: unavailable()
                if (!repository.lockRunPeriod(org, instruction.runId)) stale()
            }
            reviewer(actor, org)
            val current = subject.current(org, proposal) ?: stale()
            if (current.policy.reviewerId != actor || current.proposal.preparedBy == actor || repository.isBeneficiary(actor, current.instruction.employeeId)) unavailable()
            if (decision == ApprovalDecisionType.APPROVE && !repository.hasEvidenceView(actor, org, current.proposal)) {
                fail("FINANCE_PAYROLL_EVIDENCE_REVIEW_REQUIRED", HttpStatus.CONFLICT)
            }
            val reference = ApprovalSubjectRef(org, FINANCE_PAYROLL_RESULT_SUBJECT, proposal, current.proposal.reviewVersion)
            val status = approvals.subjectStatus(reference, FINANCE_PAYROLL_RESULT_POLICY) ?: stale()
            if (status.state == ApprovalRequestState.PENDING) {
                val result = approvals.decide(actor, derivedId("review-decision", request.operationId), status.approvalRequestId, decision, comment, correlation)
                if (result.state != if (decision == ApprovalDecisionType.APPROVE) ApprovalRequestState.APPROVED else ApprovalRequestState.REJECTED) stale()
            } else if (decision != ApprovalDecisionType.APPROVE || status.state != ApprovalRequestState.APPROVED) stale()
            if (decision == ApprovalDecisionType.APPROVE) {
                repository.recordResult(eventId, request.operationId, current.proposal, current.instruction, status.approvalRequestId, actor, clock.instant(), correlation)
            }
            audit(actor, "FINANCE_PAYROLL_RESULT_REVIEWED", proposal, correlation, decision.name)
            IdempotentCommandOutcome("FINANCE_PAYROLL_RESULT_REVIEWED", """{"id":"$proposal"}""")
        }
        return FinancePayrollCommandReceipt(request.operationId, org, proposal,
            if (closingStale) "SUPERSEDED" else if (decision == ApprovalDecisionType.APPROVE) "RECORDED" else "REJECTED", execution.replayed)
    }

    private fun canCloseStale(actor: UUID, org: UUID, id: UUID): Boolean {
        if (!authority.canReview(actor, org) || subject.policy(org)?.reviewerId != actor ||
            !repository.pendingAssignedReviewer(org, id, actor)) return false
        val p = repository.proposal(org, id) ?: return false
        val instruction = repository.instruction(org, p.instructionId) ?: return false
        return p.preparedBy != actor && !repository.isBeneficiary(actor, instruction.employeeId)
    }

    private fun spec(op: UUID, actor: UUID, type: String, target: UUID, values: List<Any?>, correlation: String) =
        IdempotentCommandSpec(op, actor, type, "FINANCE_PAYROLL", target,
            IdempotencyKeyContract.fingerprint(values.joinToString("\u001f")), correlation)
    private fun derivedId(kind: String, op: UUID): UUID = UUID.nameUUIDFromBytes("hiltech:finance:payroll-$kind:$op".toByteArray(Charsets.UTF_8))
    private fun audit(actor: UUID, action: String, target: UUID, correlation: String, reason: String) = audit.append(AuditEventRecord(
        actorUserId = actor, action = action, targetType = "FINANCE_PAYROLL", targetId = target,
        occurredAt = clock.instant(), correlationId = correlation, reason = reason,
    ))
    private fun readable(actor: UUID, org: UUID) { if (!authority.canPrepare(actor, org) && !authority.canReview(actor, org)) unavailable() }
    private fun preparer(actor: UUID, org: UUID) { if (!authority.canPrepare(actor, org)) unavailable() }
    private fun reviewer(actor: UUID, org: UUID) { if (!authority.canReview(actor, org)) unavailable() }
    private fun unavailable(): Nothing = fail("FINANCE_PAYROLL_UNAVAILABLE", HttpStatus.NOT_FOUND)
    private fun invalid(): Nothing = fail("FINANCE_PAYROLL_INPUT_INVALID", HttpStatus.BAD_REQUEST)
    private fun stale(): Nothing = fail("FINANCE_PAYROLL_SOURCE_CHANGED", HttpStatus.CONFLICT)
    private fun fail(code: String, status: HttpStatus): Nothing = throw ProductApiException(code, "Review the current payroll payment source and authority.", status)
}
