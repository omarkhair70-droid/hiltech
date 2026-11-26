package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalDecisionConstraint
import com.hiltech.server.approval.ApprovalDecisionContext
import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import com.hiltech.server.payroll.PayrollRunPublicationPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

internal data class FinancePayrollReviewPolicy(val version: Int, val bindingId: UUID, val bindingVersion: Long, val reviewerId: UUID)
internal data class FinancePayrollVerifiedProposal(
    val proposal: FinancePayrollProposalSource, val instruction: FinancePayrollInstructionSource, val policy: FinancePayrollReviewPolicy,
)

internal object FinancePayrollResultRules {
    fun transitionAllowed(previous: FinancePayrollResultSource?, action: String, corrected: UUID?): Boolean = when (action) {
        "SUCCESS" -> previous?.outcome != "SUCCESS" && corrected == null
        "RETURNED" -> previous?.outcome == "SUCCESS" && corrected == null
        "VOID_MATCH" -> previous?.action == "SUCCESS" && previous.id == corrected
        else -> false
    }

    fun version(p: FinancePayrollProposalSource, source: FinancePayrollInstructionSource, policy: FinancePayrollReviewPolicy): Long {
        val text = listOf("FINANCE_PAYROLL_RESULT_V1", p.organizationId, p.instructionId, source.runId, source.employeeId,
            source.amount.stripTrailingZeros().toPlainString(), source.currency, source.fingerprint,
            p.baseVersion, p.action, p.bankLineId, p.correctsEventId, p.evidenceId, p.evidenceSha256, p.reason,
            p.preparedBy, policy.version, policy.bindingId, policy.bindingVersion, policy.reviewerId).joinToString("\u001f")
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(bytes, 0, 8).long and Long.MAX_VALUE).coerceAtLeast(1)
    }
}

@Component
class FinancePayrollResultSubject(
    private val repository: FinancePayrollDisbursementRepository,
    private val authority: FinancePayrollAuthorityPort,
    private val approvals: ApprovalPersistencePort,
    private val publication: ObjectProvider<PayrollRunPublicationPort>,
    private val clock: Clock,
) : ApprovalSubjectVersionSource, ApprovalDecisionConstraint {
    override fun supports(subjectType: String): Boolean = subjectType == FINANCE_PAYROLL_RESULT_SUBJECT
    override fun isCurrent(subject: ApprovalSubjectRef): Boolean = supports(subject.subjectType) &&
        current(subject.organizationId, subject.subjectId)?.proposal?.reviewVersion == subject.subjectVersion

    override fun canDecide(actor: UUID, context: ApprovalDecisionContext, decision: ApprovalDecisionType): Boolean {
        if (!supports(context.subjectType) || context.policyKey != FINANCE_PAYROLL_RESULT_POLICY) return false
        val source = current(context.organizationId, context.subjectId) ?: return false
        if (source.proposal.reviewVersion != context.subjectVersion || source.policy.version != context.policyVersion ||
            source.policy.reviewerId != actor || context.requesterUserId != source.proposal.preparedBy ||
            source.proposal.preparedBy == actor || !authority.canReview(actor, context.organizationId) ||
            repository.isBeneficiary(actor, source.instruction.employeeId)) return false
        return decision != ApprovalDecisionType.APPROVE || repository.hasEvidenceView(actor, context.organizationId, source.proposal)
    }

    internal fun policy(org: UUID): FinancePayrollReviewPolicy? {
        val at = clock.instant()
        val p = approvals.loadActivePolicy(org, FINANCE_PAYROLL_RESULT_POLICY, at)?.takeIf {
            it.organizationId == org && it.policyKey == FINANCE_PAYROLL_RESULT_POLICY && it.approvalRequired &&
                it.mode == ApprovalPolicyMode.SINGLE && it.authorityKey == FINANCE_PAYROLL_RESULT_REVIEWER && it.reasonRequired
        } ?: return null
        val binding = approvals.resolveCurrentAuthority(org, FINANCE_PAYROLL_RESULT_REVIEWER, at)?.takeIf {
            it.organizationId == org && it.authorityKey == FINANCE_PAYROLL_RESULT_REVIEWER && it.principalType == ApprovalPrincipalType.USER &&
                authority.canReview(it.principalId, org)
        } ?: return null
        return FinancePayrollReviewPolicy(p.versionNumber, binding.id, binding.version, binding.principalId)
    }

    fun published(org: UUID, run: UUID, fingerprint: String): Boolean =
        publication.ifAvailable?.canPublish(org, run, fingerprint) == true

    fun payable(org: UUID, run: UUID, fingerprint: String): Boolean =
        published(org, run, fingerprint) && repository.latestReleased(org, run)

    internal fun current(org: UUID, id: UUID): FinancePayrollVerifiedProposal? {
        val p = repository.proposal(org, id) ?: return null
        val source = repository.instruction(org, p.instructionId) ?: return null
        val policy = policy(org) ?: return null
        if (!authority.canPrepare(p.preparedBy, org) || p.preparedBy == policy.reviewerId ||
            repository.isBeneficiary(policy.reviewerId, source.employeeId)) return null
        val previous = repository.latest(org, source.id)
        if ((previous?.version ?: 0L) != p.baseVersion || !FinancePayrollResultRules.transitionAllowed(previous, p.action, p.correctsEventId)) return null
        if (!repository.evidenceReady(org, p.evidenceId, p.evidenceSha256)) return null
        if (p.action == "VOID_MATCH") {
            val prior = previous ?: return null
            if (prior.evidenceId != p.evidenceId || prior.evidenceSha256 != p.evidenceSha256 || p.bankLineId != null) return null
            if (!repository.canCorrectMatch(org, prior.id)) return null
        } else {
            val bank = p.bankLineId?.let { repository.bank(org, it) } ?: return null
            if (bank.reconciled || bank.amount.compareTo(source.amount) != 0 || bank.remaining < source.amount || bank.currency != source.currency ||
                bank.flow != (if (p.action == "SUCCESS") "DEBIT" else "CREDIT") || bank.evidenceId != p.evidenceId || bank.evidenceSha256 != p.evidenceSha256) return null
            if (p.action == "SUCCESS" && !payable(org, source.runId, source.fingerprint)) return null
        }
        if (FinancePayrollResultRules.version(p, source, policy) != p.reviewVersion) return null
        return FinancePayrollVerifiedProposal(p, source, policy)
    }
}
