package com.hiltech.server.service

import com.hiltech.server.approval.*
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectLifecycleState
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectSourceType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServiceDeliveryApprovalSubjectTest {
    private val org = UUID.randomUUID(); private val project = UUID.randomUUID(); private val maker = UUID.randomUUID(); private val reviewer = UUID.randomUUID()
    private val at = Instant.parse("2026-11-25T14:00:00Z")
    private val repo = Mockito.mock(ServiceDeliveryRepository::class.java)
    private val sources = Mockito.mock(ServiceDeliverySources::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val eligibility = Mockito.mock(ServiceDeliveryReviewEligibilityPort::class.java)
    private val subject = ServiceDeliveryApprovalSubject(repo, sources, approvals, eligibility, Clock.fixed(at, ZoneOffset.UTC))
    private val workflow = DeliveryWorkflow.WARRANTY
    private val binding = ApprovalAuthorityBindingSnapshot(UUID.randomUUID(), org, workflow.reviewerKey, ApprovalPrincipalType.USER, reviewer, at.minusSeconds(60), null, 2)
    private val policy = ServiceDeliveryReviewPolicy(workflow, 1, binding.id, 2, reviewer)
    private val details = ServiceDeliveryDetails(reason = "Synthetic exact incident decision")
    private val proof = ServiceDeliveryEvidenceSource(UUID.randomUUID(), org, project, "Synthetic incident evidence", "INCIDENT", "b".repeat(64), "image/png", 100,
        "synthetic/incident", "SERVICE_DELIVERY_DOCUMENT", UUID.randomUUID(), "HIGHLY_RESTRICTED", "INTERNAL_ONLY")
    private val p = ServiceDeliveryProposal(UUID.randomUUID(), org, project, "WARRANTY_ASSESSMENT", "TICKET:synthetic", 0, details,
        "a".repeat(64), subject.version("a".repeat(64), maker, policy), 1, binding.id, 2, reviewer, maker, at,
        "Synthetic project", "PROJECT-1", "Synthetic client", "Synthetic incident")
    private val projectSource = ProjectSnapshot(project, org, "PROJECT-1", "Synthetic project", UUID.randomUUID(), "Synthetic client", ProjectSourceType.INTERNAL,
        null, ProjectLifecycleState.ACTIVE, null, null, null, 1, 1, at, at, 1)
    private val resolved = ServiceDeliveryResolvedSource(projectSource, p.stream, details, 0, p.sourceStamp, p.scopeName,
        listOf(ServiceDeliveryProposalProof(proof, true)), emptyList(), emptyList())
    private val context = ApprovalDecisionContext(UUID.randomUUID(), org, workflow.subjectType, p.id, p.reviewVersion, workflow.policyKey,
        1, maker, ApprovalRequestState.PENDING, 1, UUID.randomUUID(), workflow.reviewerKey, UUID.randomUUID(), ApprovalPrincipalType.USER, reviewer, "ASSIGNED")

    private fun configure() {
        Mockito.`when`(repo.proposal(org, p.id)).thenReturn(p)
        Mockito.`when`(repo.proposalProofs(p)).thenReturn(listOf(proof.id to (proof.sha256 to true)))
        Mockito.`when`(sources.resolve(project, maker, p.action, 0, details)).thenReturn(resolved)
        Mockito.`when`(eligibility.isEligible(reviewer, org)).thenReturn(true)
        Mockito.`when`(approvals.loadActivePolicy(org, workflow.policyKey, at)).thenReturn(
            ApprovalPolicySnapshot(org, workflow.policyKey, 1, true, ApprovalPolicyMode.SINGLE, workflow.reviewerKey, true))
        Mockito.`when`(approvals.resolveCurrentAuthority(org, workflow.reviewerKey, at)).thenReturn(binding)
    }

    @Test fun scopedIndependentReviewerNeedsNoGeneralProjectManagementButNeedsExactDisplayReceiptToApprove() {
        configure()
        assertFalse(sources.canManage(project, org, reviewer))
        assertTrue(subject.canRead(reviewer, p))
        assertFalse(subject.canRead(UUID.randomUUID(), p))
        assertFalse(subject.canDecide(reviewer, context, ApprovalDecisionType.APPROVE))
        assertTrue(subject.canDecide(reviewer, context, ApprovalDecisionType.REJECT))
        assertTrue(subject.canDecide(reviewer, context, ApprovalDecisionType.REQUEST_CHANGE))
        Mockito.`when`(repo.viewed(reviewer, p)).thenReturn(listOf(proof.id.toString()))
        assertTrue(subject.canDecide(reviewer, context, ApprovalDecisionType.APPROVE))
        assertFalse(subject.canDecide(maker, context, ApprovalDecisionType.APPROVE))
        assertFalse(subject.canDecide(reviewer, context.copy(subjectVersion = p.reviewVersion + 1), ApprovalDecisionType.APPROVE))
        assertFalse(subject.canDecide(reviewer, context.copy(requesterUserId = reviewer), ApprovalDecisionType.APPROVE))
    }

    @Test fun currentEmployeeMembershipSourcePolicyAndProofCohortCannotBeReplacedByOldAssignment() {
        configure()
        assertNotNull(subject.current(p))
        Mockito.`when`(eligibility.isEligible(reviewer, org)).thenReturn(false)
        assertFalse(subject.canRead(reviewer, p)); assertNull(subject.current(p))
        Mockito.`when`(eligibility.isEligible(reviewer, org)).thenReturn(true)
        Mockito.`when`(approvals.resolveCurrentAuthority(org, workflow.reviewerKey, at)).thenReturn(binding.copy(version = 3))
        assertNull(subject.current(p))
        Mockito.`when`(approvals.resolveCurrentAuthority(org, workflow.reviewerKey, at)).thenReturn(binding)
        Mockito.`when`(repo.proposalProofs(p)).thenReturn(listOf(proof.id to ("c".repeat(64) to true)))
        assertNull(subject.current(p))
        Mockito.`when`(repo.proposalProofs(p)).thenReturn(listOf(proof.id to (proof.sha256 to false)))
        assertNull(subject.current(p))
    }

    @Test fun sourceStalenessIsDistinctFromInfrastructureFailureAndNeverCreatesAFreshApproval() {
        configure()
        Mockito.`when`(sources.resolve(project, maker, p.action, 0, details)).thenThrow(ProductApiException("SERVICE_DELIVERY_SOURCE_CHANGED", "Changed", HttpStatus.CONFLICT))
        assertNull(subject.current(p))
        Mockito.doAnswer { throw IllegalStateException("Synthetic database outage") }.`when`(sources).resolve(project, maker, p.action, 0, details)
        assertThrows<IllegalStateException> { subject.current(p) }
    }
}
