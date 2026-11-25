package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.finance.FinancePayrollAuthorityPort
import com.hiltech.server.payroll.Payroll9aAuthorityPort
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PayrollReviewPolicyServiceTest {
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val identity = UUID.randomUUID()
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val authority = Mockito.mock(OrganizationAdministrationAuthorityPort::class.java)
    private val reviewers = Mockito.mock(CompanyReviewCandidateAuthorityPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val persistence = MemorySetup()
    private val commands = RecordingCommands()
    private val service = PayrollReviewPolicyService(authority, reviewers, persistence, commands, audit, Clock.fixed(at, ZoneOffset.UTC))
    private val candidate = PayrollReviewerCandidate(employee, identity, 3, "Synthetic reviewer", "SYNTH-REVIEWER")

    @Test
    fun policyAdministrationIsRequiredToReadOrChangeSetup() {
        assertEquals("APPROVAL_POLICY_ADMINISTRATION_DENIED",
            assertThrows<ProductApiException> { service.current(actor, organization, CompanyReviewWorkflow.PAYROLL_SOURCE) }.code)
        assertThrows<ProductApiException> { service.candidates(actor, organization, CompanyReviewWorkflow.PAYROLL_SOURCE, "") }
        assertThrows<ProductApiException> { service.configure(command()) }
        assertEquals(0, commands.calls)
        assertEquals(0, persistence.reads)
    }

    @Test
    fun revokedAdministrationAfterOrganizationLockCannotConfigure() {
        authorized()
        Mockito.`when`(authority.canManageApprovalPolicies(actor, organization)).thenReturn(true, false)
        assertEquals("APPROVAL_POLICY_ADMINISTRATION_DENIED", assertThrows<ProductApiException> { service.configure(command()) }.code)
        assertEquals(1, persistence.organizationLocks)
        assertEquals(0, persistence.replacements)
        Mockito.verifyNoInteractions(audit)
    }

    @Test
    fun stalePolicyAndBindingVersionsAreRejectedWithoutRetiringHistory() {
        authorized()
        val first = service.configure(command())
        assertTrue(first.current)
        val failure = assertThrows<ProductApiException> { service.configure(command()) }
        assertEquals("PAYROLL_REVIEW_SETUP_CHANGED", failure.code)
        assertEquals(1, persistence.replacements)
    }

    @Test
    fun missingExpectedVersionIsRejectedBeforeCommandExecution() {
        authorized()
        assertEquals("PAYROLL_REVIEW_SETUP_INVALID_VERSION", assertThrows<ProductApiException> {
            service.configure(command().copy(expectedPolicyId = UUID.randomUUID()))
        }.code)
        assertEquals(0, commands.calls)
    }

    @Test
    fun revokedReviewerCannotBeConfiguredEvenWithAnUnchangedEmployeeRecord() {
        authorized()
        Mockito.`when`(reviewers.canReview(CompanyReviewWorkflow.PAYROLL_SOURCE, identity, organization)).thenReturn(false)
        assertEquals("PAYROLL_REVIEWER_CHANGED", assertThrows<ProductApiException> { service.configure(command()) }.code)
        assertEquals(0, persistence.replacements)
    }

    @Test
    fun changedLinkedAccountOrEmployeeVersionIsRejected() {
        authorized()
        assertEquals("PAYROLL_REVIEWER_CHANGED", assertThrows<ProductApiException> {
            service.configure(command().copy(expectedReviewerIdentityId = UUID.randomUUID()))
        }.code)
        assertEquals("PAYROLL_REVIEWER_CHANGED", assertThrows<ProductApiException> {
            service.configure(command().copy(baseEmployeeVersion = 2))
        }.code)
        assertEquals(0, persistence.replacements)
    }

    @Test
    fun setupRecordsOneMutationAndAuditWithIdempotentRetry() {
        authorized()
        val request = command()
        val first = service.configure(request)
        val second = service.configure(request)
        assertEquals(first.policyId, second.policyId)
        assertEquals(first.bindingId, second.bindingId)
        assertTrue(second.replayed)
        assertEquals(1, persistence.replacements)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test
    fun revokedAdministratorCannotReplayAnOldReceipt() {
        authorized()
        val request = command()
        service.configure(request)
        Mockito.`when`(authority.canManageApprovalPolicies(actor, organization)).thenReturn(false)
        assertThrows<ProductApiException> { service.configure(request) }
        assertEquals(1, commands.calls)
    }

    @Test
    fun confirmingSameCurrentReviewerDoesNotRetirePolicyOrInvalidatePendingRequests() {
        authorized()
        service.configure(command())
        val state = persistence.currentState.getValue(CompanyReviewWorkflow.PAYROLL_SOURCE)
        persistence.currentState[CompanyReviewWorkflow.PAYROLL_SOURCE] = state.copy(pendingReviews = 4)
        val result = service.configure(command(previous = state))
        assertEquals(state.policy?.id.toString(), result.policyId)
        assertEquals(1, persistence.replacements)
        assertEquals(4, persistence.currentState.getValue(CompanyReviewWorkflow.PAYROLL_SOURCE).pendingReviews)
    }

    @Test
    fun receiptForEarlierSetupIsDistinctFromChangedCurrentConfiguration() {
        authorized()
        val first = command()
        service.configure(first)
        val state = persistence.currentState.getValue(CompanyReviewWorkflow.PAYROLL_SOURCE)
        persistence.currentState[CompanyReviewWorkflow.PAYROLL_SOURCE] = state.copy(
            policy = state.policy?.copy(id = UUID.randomUUID()), reviewer = state.reviewer?.copy(id = UUID.randomUUID()),
        )
        val replay = service.configure(first)
        assertTrue(replay.replayed)
        assertFalse(replay.current)
    }

    @Test
    fun onlyCurrentDomainReviewersAppearInNamedSearch() {
        authorized()
        val unprojected = candidate.copy(employeeId = UUID.randomUUID(), identityId = UUID.randomUUID())
        persistence.eligible += unprojected
        val result = service.candidates(actor, organization, CompanyReviewWorkflow.PAYROLL_SOURCE, "Synthetic")
        assertEquals(listOf(employee.toString()), result.items.map { it.employeeId })
        assertEquals("Synthetic", persistence.lastQuery)
    }

    @Test
    fun existingTeamReviewerRemainsVisibleAndExplicitReplacementUsesExpectedBinding() {
        authorized()
        val team = UUID.randomUUID()
        val state = PayrollReviewSetupRecord(PayrollReviewPolicyRecord(UUID.randomUUID(), 2, 4, true, true),
            PayrollReviewerBindingRecord(UUID.randomUUID(), 3, "TEAM", team, "Synthetic review team", null, true), 2)
        persistence.currentState[CompanyReviewWorkflow.PAYROLL_SOURCE] = state
        val visible = service.current(actor, organization, CompanyReviewWorkflow.PAYROLL_SOURCE)
        assertEquals("TEAM", visible.reviewerPrincipalType)
        assertEquals("Synthetic review team", visible.reviewerDisplayName)
        assertEquals(2, visible.pendingReviews)
        service.configure(command(previous = state))
        assertEquals("USER", persistence.currentState.getValue(CompanyReviewWorkflow.PAYROLL_SOURCE).reviewer?.principalType)
        assertEquals(state, persistence.lastPrevious)
    }

    @Test
    fun changingFinanceReviewDoesNotChangePayrollInputReview() {
        authorized()
        service.configure(command())
        val payrollState = persistence.currentState.getValue(CompanyReviewWorkflow.PAYROLL_SOURCE)
        Mockito.`when`(reviewers.canReview(CompanyReviewWorkflow.FINANCE_PAYROLL_RESULT, identity, organization)).thenReturn(true)
        val finance = service.configure(command(workflow = CompanyReviewWorkflow.FINANCE_PAYROLL_RESULT))
        assertEquals("FINANCE_PAYROLL_RESULT", finance.workflow)
        assertEquals(payrollState, persistence.currentState.getValue(CompanyReviewWorkflow.PAYROLL_SOURCE))
    }

    @Test
    fun boundedCatalogAndOwningAuthorityRemainDistinct() {
        val payroll = Mockito.mock(Payroll9aAuthorityPort::class.java)
        val finance = Mockito.mock(FinancePayrollAuthorityPort::class.java)
        val domain = DomainCompanyReviewCandidateAuthority(payroll, finance)
        Mockito.`when`(payroll.canReview(identity, organization)).thenReturn(true)
        assertTrue(domain.canReview(CompanyReviewWorkflow.PAYROLL_SOURCE, identity, organization))
        assertFalse(domain.canReview(CompanyReviewWorkflow.FINANCE_PAYROLL_RESULT, identity, organization))
        assertEquals(setOf("PAYROLL_SOURCE_REVIEW", "FINANCE_PAYROLL_RESULT_REVIEW", "PAYROLL_RUN_REVIEW"), CompanyReviewWorkflow.entries.map { it.policyKey }.toSet())
        assertTrue(CompanyReviewWorkflow.entries.none { it.authorityKey == "OWNER_FINAL" })
    }

    @Test
    fun independentlyGovernedExistingRunAuthorityIsVisibleAndCannotBeReplaced() {
        authorized()
        val prior = PayrollReviewSetupRecord(PayrollReviewPolicyRecord(UUID.randomUUID(), 1, 1, false, true, "OWNER_FINAL"),
            PayrollReviewerBindingRecord(UUID.randomUUID(), 1, "USER", identity, "Synthetic final reviewer", employee, true), 1)
        persistence.currentState[CompanyReviewWorkflow.PAYROLL_RUN] = prior
        val state = service.current(actor, organization, CompanyReviewWorkflow.PAYROLL_RUN)
        assertEquals("PROTECTED_EXISTING_AUTHORITY", state.status)
        assertEquals("Synthetic final reviewer", state.reviewerDisplayName)
        assertFalse(state.canConfigure)
        assertEquals("REVIEW_WORKFLOW_PROTECTED_AUTHORITY", assertThrows<ProductApiException> {
            service.configure(command(previous = prior, workflow = CompanyReviewWorkflow.PAYROLL_RUN))
        }.code)
        assertEquals(0, persistence.replacements)
        assertEquals(prior, persistence.currentState[CompanyReviewWorkflow.PAYROLL_RUN])
    }

    private fun authorized() {
        Mockito.`when`(authority.canManageApprovalPolicies(actor, organization)).thenReturn(true)
        Mockito.`when`(reviewers.canReview(CompanyReviewWorkflow.PAYROLL_SOURCE, identity, organization)).thenReturn(true)
        persistence.eligible += candidate
    }
    private fun command(previous: PayrollReviewSetupRecord? = null, workflow: CompanyReviewWorkflow = CompanyReviewWorkflow.PAYROLL_SOURCE) =
        ConfigurePayrollReviewPolicyCommand(UUID.randomUUID(), organization, employee, identity, 3,
            previous?.policy?.id, previous?.policy?.version, previous?.reviewer?.id, previous?.reviewer?.version,
            actor, "synthetic-review-setup", workflow)

    private class MemorySetup : PayrollReviewPolicyPersistencePort {
        val currentState = mutableMapOf<CompanyReviewWorkflow, PayrollReviewSetupRecord>()
        val eligible = mutableListOf<PayrollReviewerCandidate>()
        var reads = 0
        var organizationLocks = 0
        var replacements = 0
        var lastPrevious: PayrollReviewSetupRecord? = null
        var lastQuery: String? = null
        override fun lockOrganization(organizationId: UUID) { organizationLocks += 1 }
        override fun lockEmployee(employeeId: UUID) = Unit
        override fun current(organizationId: UUID, workflow: CompanyReviewWorkflow, at: Instant): PayrollReviewSetupRecord {
            reads += 1
            return currentState[workflow] ?: PayrollReviewSetupRecord(null, null, 0)
        }
        override fun candidates(organizationId: UUID, workflow: CompanyReviewWorkflow, query: String, at: Instant, limit: Int): List<PayrollReviewerCandidate> {
            lastQuery = query
            return eligible.take(limit)
        }
        override fun candidate(organizationId: UUID, workflow: CompanyReviewWorkflow, employeeId: UUID, identityId: UUID, at: Instant) =
            eligible.singleOrNull { it.employeeId == employeeId && it.identityId == identityId }
        override fun reviewerIdentities(organizationId: UUID, workflow: CompanyReviewWorkflow, reviewer: PayrollReviewerBindingRecord, at: Instant) =
            eligible.map { it.identityId }
        override fun replace(organizationId: UUID, workflow: CompanyReviewWorkflow, previous: PayrollReviewSetupRecord, reviewer: PayrollReviewerCandidate,
                             actor: UUID, operationId: UUID, at: Instant): PayrollReviewPolicyInstallation {
            replacements += 1
            lastPrevious = previous
            val policy = PayrollReviewPolicyRecord(UUID.randomUUID(), (previous.policy?.revision ?: 0) + 1, 1, true, true)
            val binding = PayrollReviewerBindingRecord(UUID.randomUUID(), 1, "USER", reviewer.identityId, reviewer.displayName, reviewer.employeeId, true)
            currentState[workflow] = PayrollReviewSetupRecord(policy, binding, previous.pendingReviews)
            return PayrollReviewPolicyInstallation(policy.id, policy.revision, binding.id)
        }
    }
    private class RecordingCommands : IdempotentCommandExecutor {
        var calls = 0
        private val receipts = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            calls += 1
            val prior = receipts[spec.operationId]
            if (prior != null) { require(prior.first == spec); return IdempotentCommandExecution(prior.second, true) }
            val result = action()
            receipts[spec.operationId] = spec to result
            return IdempotentCommandExecution(result, false)
        }
    }
}
