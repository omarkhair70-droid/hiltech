package com.hiltech.server.service

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalDecisionConstraint
import com.hiltech.server.approval.ApprovalDecisionContext
import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import com.hiltech.server.platform.ProductApiException
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class ServiceDeliveryApprovalSubject(
    private val repository: ServiceDeliveryRepository,
    private val sources: ServiceDeliverySources,
    private val approvals: ApprovalPersistencePort,
    private val eligibility: ServiceDeliveryReviewEligibilityPort,
    private val clock: Clock,
) : ApprovalSubjectVersionSource, ApprovalDecisionConstraint {
    override fun supports(subjectType: String): Boolean = DeliveryWorkflow.entries.any { it.subjectType == subjectType }
    override fun isCurrent(subject: ApprovalSubjectRef): Boolean = supports(subject.subjectType) &&
        repository.proposal(subject.organizationId, subject.subjectId)?.takeIf { it.workflow.subjectType == subject.subjectType && it.reviewVersion == subject.subjectVersion }
            ?.let { current(it) } != null

    override fun canDecide(actor: UUID, context: ApprovalDecisionContext, decision: ApprovalDecisionType): Boolean {
        val p = repository.proposal(context.organizationId, context.subjectId) ?: return false
        if (p.workflow.subjectType != context.subjectType || p.workflow.policyKey != context.policyKey || p.policyVersion != context.policyVersion ||
            p.reviewVersion != context.subjectVersion || p.preparedBy != context.requesterUserId || !assignedReviewer(actor, p)) return false
        val source = current(p) ?: return false
        val viewed = repository.viewed(actor, p).toSet()
        return decision != ApprovalDecisionType.APPROVE || source.proofs.none { it.requiredToOpen && it.source.id.toString() !in viewed }
    }

    internal fun policy(org: UUID, workflow: DeliveryWorkflow): ServiceDeliveryReviewPolicy? {
        val at = clock.instant()
        val policy = approvals.loadActivePolicy(org, workflow.policyKey, at)?.takeIf {
            it.organizationId == org && it.policyKey == workflow.policyKey && it.approvalRequired && it.reasonRequired &&
                it.mode == ApprovalPolicyMode.SINGLE && it.authorityKey == workflow.reviewerKey
        } ?: return null
        val binding = approvals.resolveCurrentAuthority(org, workflow.reviewerKey, at)?.takeIf {
            it.organizationId == org && it.authorityKey == workflow.reviewerKey && it.principalType == ApprovalPrincipalType.USER &&
                eligibility.isEligible(it.principalId, org)
        } ?: return null
        return ServiceDeliveryReviewPolicy(workflow, policy.versionNumber, binding.id, binding.version, binding.principalId)
    }

    internal fun version(stamp: String, actor: UUID, policy: ServiceDeliveryReviewPolicy): Long = deliveryVersion(
        listOf("SERVICE_DELIVERY_APPROVAL_V1", stamp, actor, policy.workflow.policyKey, policy.version,
            policy.bindingId, policy.bindingVersion, policy.reviewer).joinToString("\u001f"),
    )

    internal fun current(p: ServiceDeliveryProposal): ServiceDeliveryResolvedSource? {
        val policy = policy(p.organizationId, p.workflow) ?: return null
        if (policy.version != p.policyVersion || policy.bindingId != p.bindingId || policy.bindingVersion != p.bindingVersion ||
            policy.reviewer != p.reviewer || p.reviewer == p.preparedBy || version(p.sourceStamp, p.preparedBy, policy) != p.reviewVersion) return null
        val source = try { sources.resolve(p.projectId, p.preparedBy, p.action, p.baseEventNumber, p.details) }
            catch (_: ProductApiException) { return null }
        if (source.project.organizationId != p.organizationId || source.stamp != p.sourceStamp || source.stream != p.stream ||
            source.details != p.details || source.packageDocuments != p.packageDocuments || source.packageFindings != p.packageFindings) return null
        val stored = repository.proposalProofs(p).map { Triple(it.first, it.second.first, it.second.second) }.toSet()
        val current = source.proofs.map { Triple(it.source.id, it.source.sha256, it.requiredToOpen) }.toSet()
        return source.takeIf { stored == current }
    }

    /** This bounded assignment grants no general project navigation or unreferenced proof access. */
    internal fun assignedReviewer(actor: UUID, p: ServiceDeliveryProposal): Boolean = actor != p.preparedBy && actor == p.reviewer &&
        eligibility.isEligible(actor, p.organizationId) && policy(p.organizationId, p.workflow)?.reviewer == actor

    internal fun canRead(actor: UUID, p: ServiceDeliveryProposal): Boolean =
        sources.canManage(p.projectId, p.organizationId, actor) || assignedReviewer(actor, p)
}
