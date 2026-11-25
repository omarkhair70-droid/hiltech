package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalDecisionContext
import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.payroll.PayrollRunPublicationPort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinancePayrollResultSubjectTest {
    private val org = UUID.randomUUID()
    private val proposer = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val run = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val instruction = UUID.randomUUID()
    private val bank = UUID.randomUUID()
    private val proof = UUID.randomUUID()
    private val at = Instant.parse("2026-11-25T15:00:00Z")
    private val repo = Mockito.mock(FinancePayrollDisbursementRepository::class.java)
    private val authority = Mockito.mock(FinancePayrollAuthorityPort::class.java)
    private val approval = Mockito.mock(ApprovalPersistencePort::class.java)
    private val publication = Mockito.mock(PayrollRunPublicationPort::class.java)
    private val binding = ApprovalAuthorityBindingSnapshot(UUID.randomUUID(), org, FINANCE_PAYROLL_RESULT_REVIEWER,
        ApprovalPrincipalType.USER, reviewer, at.minusSeconds(100), null, 3)
    private val policy = FinancePayrollReviewPolicy(2, binding.id, binding.version, reviewer)
    private val source = FinancePayrollInstructionSource(instruction, org, run, employee, BigDecimal("100.00"), "EGP", "a".repeat(64))
    private val bankSource = FinancePayrollBankSource(bank, BigDecimal("100.00"), "EGP", "DEBIT", proof, "b".repeat(64), BigDecimal("100.00"))
    private val draft = FinancePayrollProposalSource(UUID.randomUUID(), org, instruction, 0, "SUCCESS", bank, null, proof,
        "b".repeat(64), "Verified synthetic beneficiary", 1, proposer)
    private val proposal = draft.copy(reviewVersion = FinancePayrollResultRules.version(draft, source, policy))
    private val subject = FinancePayrollResultSubject(repo, authority, approval,
        StaticListableBeanFactory(mapOf("publication" to publication)).getBeanProvider(PayrollRunPublicationPort::class.java), Clock.fixed(at, ZoneOffset.UTC))

    private fun allow() {
        Mockito.`when`(repo.proposal(org, proposal.id)).thenReturn(proposal)
        Mockito.`when`(repo.instruction(org, instruction)).thenReturn(source)
        Mockito.`when`(repo.bank(org, bank)).thenReturn(bankSource)
        Mockito.`when`(repo.evidenceReady(org, proof, proposal.evidenceSha256)).thenReturn(true)
        Mockito.`when`(repo.latestReleased(org, run)).thenReturn(true)
        Mockito.`when`(authority.canPrepare(proposer, org)).thenReturn(true)
        Mockito.`when`(authority.canReview(reviewer, org)).thenReturn(true)
        Mockito.`when`(publication.canPublish(org, run, source.fingerprint)).thenReturn(true)
        Mockito.`when`(approval.loadActivePolicy(org, FINANCE_PAYROLL_RESULT_POLICY, at)).thenReturn(
            ApprovalPolicySnapshot(org, FINANCE_PAYROLL_RESULT_POLICY, 2, true, ApprovalPolicyMode.SINGLE, FINANCE_PAYROLL_RESULT_REVIEWER, true))
        Mockito.`when`(approval.resolveCurrentAuthority(org, FINANCE_PAYROLL_RESULT_REVIEWER, at)).thenReturn(binding)
    }

    @Test fun onlyExactCurrentReleasedAmountBankProofAndIndependentAuthorityCanBeReviewed() {
        allow()
        assertNotNull(subject.current(org, proposal.id))
        assertTrue(subject.isCurrent(ApprovalSubjectRef(org, FINANCE_PAYROLL_RESULT_SUBJECT, proposal.id, proposal.reviewVersion)))
        assertFalse(subject.isCurrent(ApprovalSubjectRef(org, FINANCE_PAYROLL_RESULT_SUBJECT, proposal.id, proposal.reviewVersion + 1)))
        listOf(bankSource.copy(amount = BigDecimal("99.00")), bankSource.copy(currency = "USD"),
            bankSource.copy(remaining = BigDecimal.ZERO), bankSource.copy(flow = "CREDIT"), bankSource.copy(reconciled = true),
            bankSource.copy(evidenceSha256 = "c".repeat(64))).forEach { changed ->
            Mockito.`when`(repo.bank(org, bank)).thenReturn(changed)
            assertNull(subject.current(org, proposal.id))
        }
        Mockito.`when`(repo.bank(org, bank)).thenReturn(bankSource)
        Mockito.`when`(repo.evidenceReady(org, proof, proposal.evidenceSha256)).thenReturn(false)
        assertNull(subject.current(org, proposal.id))
    }

    @Test fun reviewCannotOutliveOperationalRoleOrBeSelfBeneficiaryOrSupersededPayroll() {
        allow()
        Mockito.`when`(authority.canReview(reviewer, org)).thenReturn(false)
        assertNull(subject.current(org, proposal.id))
        Mockito.`when`(authority.canReview(reviewer, org)).thenReturn(true)
        Mockito.`when`(repo.isBeneficiary(reviewer, employee)).thenReturn(true)
        assertNull(subject.current(org, proposal.id))
        Mockito.`when`(repo.isBeneficiary(reviewer, employee)).thenReturn(false)
        Mockito.`when`(repo.latestReleased(org, run)).thenReturn(false)
        assertNull(subject.current(org, proposal.id))
        Mockito.`when`(repo.latestReleased(org, run)).thenReturn(true)
        Mockito.`when`(authority.canPrepare(proposer, org)).thenReturn(false)
        assertNull(subject.current(org, proposal.id))
    }

    @Test fun reviewerReassignmentAndNewResultInvalidatePriorApprovalVersion() {
        allow()
        Mockito.`when`(approval.resolveCurrentAuthority(org, FINANCE_PAYROLL_RESULT_REVIEWER, at)).thenReturn(binding.copy(version = 4))
        assertNull(subject.current(org, proposal.id))
        Mockito.`when`(approval.resolveCurrentAuthority(org, FINANCE_PAYROLL_RESULT_REVIEWER, at)).thenReturn(binding)
        Mockito.`when`(repo.latest(org, instruction)).thenReturn(FinancePayrollResultSource(UUID.randomUUID(), 1, "SUCCESS", "SUCCESS", proof, "b".repeat(64)))
        assertNull(subject.current(org, proposal.id))
    }

    @Test fun successReturnAndCorrectionHaveDistinctTransitions() {
        val success = FinancePayrollResultSource(UUID.randomUUID(), 1, "SUCCESS", "SUCCESS", proof, "b".repeat(64))
        assertTrue(FinancePayrollResultRules.transitionAllowed(null, "SUCCESS", null))
        assertFalse(FinancePayrollResultRules.transitionAllowed(success, "SUCCESS", null))
        assertTrue(FinancePayrollResultRules.transitionAllowed(success, "RETURNED", null))
        assertFalse(FinancePayrollResultRules.transitionAllowed(null, "RETURNED", null))
        assertTrue(FinancePayrollResultRules.transitionAllowed(success, "VOID_MATCH", success.id))
        assertFalse(FinancePayrollResultRules.transitionAllowed(success, "VOID_MATCH", UUID.randomUUID()))
        assertFalse(FinancePayrollResultRules.transitionAllowed(success.copy(action = "RETURNED", outcome = "RETURNED"), "VOID_MATCH", success.id))
        assertNotEquals(proposal.reviewVersion, FinancePayrollResultRules.version(proposal.copy(reason = "Changed verification reason"), source, policy))
        assertNotEquals(proposal.reviewVersion, FinancePayrollResultRules.version(proposal, source.copy(amount = BigDecimal("101.00")), policy))
    }

    @Test fun genericDecisionRequiresSameIndependentActorAndExactProofOpeningOnlyForApproval() {
        allow()
        val context = ApprovalDecisionContext(UUID.randomUUID(), org, FINANCE_PAYROLL_RESULT_SUBJECT, proposal.id, proposal.reviewVersion,
            FINANCE_PAYROLL_RESULT_POLICY, 2, proposer, ApprovalRequestState.PENDING, 1, UUID.randomUUID(),
            FINANCE_PAYROLL_RESULT_REVIEWER, UUID.randomUUID(), ApprovalPrincipalType.USER, reviewer, "ASSIGNED")
        assertFalse(subject.canDecide(reviewer, context, ApprovalDecisionType.APPROVE))
        assertTrue(subject.canDecide(reviewer, context, ApprovalDecisionType.REJECT))
        assertTrue(subject.canDecide(reviewer, context, ApprovalDecisionType.REQUEST_CHANGE))
        Mockito.`when`(repo.hasEvidenceView(reviewer, org, proposal)).thenReturn(true)
        assertTrue(subject.canDecide(reviewer, context, ApprovalDecisionType.APPROVE))
        assertFalse(subject.canDecide(proposer, context, ApprovalDecisionType.APPROVE))
        assertFalse(subject.canDecide(reviewer, context.copy(subjectVersion = proposal.reviewVersion + 1), ApprovalDecisionType.APPROVE))
        assertFalse(subject.canDecide(reviewer, context.copy(organizationId = UUID.randomUUID()), ApprovalDecisionType.APPROVE))
        Mockito.`when`(repo.isBeneficiary(reviewer, employee)).thenReturn(true)
        assertFalse(subject.canDecide(reviewer, context, ApprovalDecisionType.APPROVE))
        Mockito.`when`(repo.isBeneficiary(reviewer, employee)).thenReturn(false)
        Mockito.`when`(authority.canReview(reviewer, org)).thenReturn(false)
        assertFalse(subject.canDecide(reviewer, context, ApprovalDecisionType.APPROVE))
    }
}
