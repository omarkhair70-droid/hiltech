package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.procurement.ProcurementCandidateReviewEligibilityPort
import com.hiltech.server.service.ServiceDeliveryReviewEligibilityPort
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
import org.springframework.mock.env.MockEnvironment
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
    private val engine = Mockito.mock(ApprovalEngineService::class.java)
    private val service = PayrollReviewPolicyService(authority, reviewers, persistence, commands, audit, Clock.fixed(at, ZoneOffset.UTC), engine)
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
        val policyId = UUID.randomUUID()
        val scopes = listOf(CompanyPendingReviewPolicy(policyId.toString(), 2, 4, 2))
        val state = PayrollReviewSetupRecord(PayrollReviewPolicyRecord(policyId, 2, 4, true, true),
            PayrollReviewerBindingRecord(UUID.randomUUID(), 3, "TEAM", team, "Synthetic review team", null, true), 2, scopes)
        persistence.currentState[CompanyReviewWorkflow.PAYROLL_SOURCE] = state
        val visible = service.current(actor, organization, CompanyReviewWorkflow.PAYROLL_SOURCE)
        assertEquals("TEAM", visible.reviewerPrincipalType)
        assertEquals("Synthetic review team", visible.reviewerDisplayName)
        assertEquals(2, visible.pendingReviews)
        assertEquals(scopes, visible.pendingPolicies)
        assertEquals("APPROVAL_POLICY_PENDING_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            service.configure(command(previous = state))
        }.code)
        assertEquals(0, persistence.replacements)
        service.configure(command(previous = state).copy(pendingPoliciesToSupersede = scopes, supersedeReason = "Replace the review team with the designated employee"))
        assertEquals("USER", persistence.currentState.getValue(CompanyReviewWorkflow.PAYROLL_SOURCE).reviewer?.principalType)
        assertEquals(state, persistence.lastPrevious)
        assertEquals(1, Mockito.mockingDetails(engine).invocations.count { it.method.name == "supersedePolicyRequests" })
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
        val procurement = Mockito.mock(ProcurementCandidateReviewEligibilityPort::class.java)
        val delivery = Mockito.mock(ServiceDeliveryReviewEligibilityPort::class.java)
        val company = Mockito.mock(CompanyEmployeeReviewEligibilityPort::class.java)
        val domain = DomainCompanyReviewCandidateAuthority(payroll, finance, procurement, delivery, company)
        Mockito.`when`(payroll.canReview(identity, organization)).thenReturn(true)
        assertTrue(domain.canReview(CompanyReviewWorkflow.PAYROLL_SOURCE, identity, organization))
        assertFalse(domain.canReview(CompanyReviewWorkflow.FINANCE_PAYROLL_RESULT, identity, organization))
        assertEquals(27, CompanyReviewWorkflow.entries.size)
        assertEquals(27, CompanyReviewWorkflow.entries.map { it.policyKey }.toSet().size)
        assertEquals(27, CompanyReviewWorkflow.entries.map { it.subjectType }.toSet().size)
        assertFalse(domain.canReview(CompanyReviewWorkflow.FINANCE_AP_POSTING, identity, organization))
        Mockito.`when`(company.isEligible(identity, organization)).thenReturn(true)
        assertTrue(domain.canReview(CompanyReviewWorkflow.FINANCE_AP_POSTING, identity, organization))
        assertTrue(domain.canReview(CompanyReviewWorkflow.PROCUREMENT_SERVICE_ORDER, identity, organization))
        assertEquals(1, Mockito.mockingDetails(finance).invocations.size) // Only the explicit payroll result check above.
        assertFalse(domain.canReview(CompanyReviewWorkflow.PROCUREMENT_PO, identity, organization))
        Mockito.`when`(procurement.isEligible(identity, organization)).thenReturn(true)
        assertTrue(domain.canReview(CompanyReviewWorkflow.PROCUREMENT_PO, identity, organization))
        assertFalse(domain.canReview(CompanyReviewWorkflow.SERVICE_HANDOVER, identity, organization))
        Mockito.`when`(delivery.isEligible(identity, organization)).thenReturn(true)
        assertTrue(domain.canReview(CompanyReviewWorkflow.SERVICE_HANDOVER, identity, organization))
        assertTrue(domain.canReview(CompanyReviewWorkflow.SERVICE_WARRANTY, identity, organization))
        assertTrue(CompanyReviewWorkflow.entries.none { it.authorityKey == "OWNER_FINAL" })
    }

    @Test
    fun legacyAuthorityNeedsExplicitMigrationConfirmationAndIsNeverSilentlyReplaced() {
        authorized()
        val prior = PayrollReviewSetupRecord(PayrollReviewPolicyRecord(UUID.randomUUID(), 1, 1, false, true, "OWNER_FINAL"),
            PayrollReviewerBindingRecord(UUID.randomUUID(), 1, "USER", identity, "Synthetic final reviewer", employee, true), 0)
        persistence.currentState[CompanyReviewWorkflow.PAYROLL_RUN] = prior
        val state = service.current(actor, organization, CompanyReviewWorkflow.PAYROLL_RUN)
        assertEquals("LEGACY_AUTHORITY_PRESERVED", state.status)
        assertEquals("Synthetic final reviewer", state.reviewerDisplayName)
        assertTrue(state.canConfigure)
        assertTrue(state.replacesLegacyAuthority)
        assertEquals("REVIEW_WORKFLOW_LEGACY_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            service.configure(command(previous = prior, workflow = CompanyReviewWorkflow.PAYROLL_RUN))
        }.code)
        assertEquals(0, persistence.replacements)
        assertEquals(prior, persistence.currentState[CompanyReviewWorkflow.PAYROLL_RUN])
        Mockito.`when`(reviewers.canReview(CompanyReviewWorkflow.PAYROLL_RUN, identity, organization)).thenReturn(true)
        service.configure(command(previous = prior, workflow = CompanyReviewWorkflow.PAYROLL_RUN)
            .copy(legacyAuthorityReplacementConfirmed = true))
        assertEquals(prior, persistence.lastPrevious)
        assertEquals(1, persistence.replacements)
    }

    @Test
    fun sharedInstalledKeyRequiresEveryAffectedWorkflowAndEligibleReviewer() {
        authorized()
        val first = CompanyReviewWorkflow.FINANCE_EMPLOYEE_ADVANCE
        val second = CompanyReviewWorkflow.FINANCE_IMPREST
        val environment = MockEnvironment().withProperty(first.configurationProperty!!, "SYNTHETIC_SHARED_REVIEW")
            .withProperty(second.configurationProperty!!, "SYNTHETIC_SHARED_REVIEW")
        val shared = PayrollReviewPolicyService(authority, reviewers, persistence, commands, audit, Clock.fixed(at, ZoneOffset.UTC), engine,
            workflowConfiguration = CompanyReviewWorkflowConfiguration(environment))
        val state = shared.current(actor, organization, first)
        assertEquals(setOf(first.name, second.name), state.affectedWorkflows.toSet())
        assertEquals("REVIEW_WORKFLOW_GROUP_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            shared.configure(command(workflow = first))
        }.code)
        Mockito.`when`(reviewers.canReview(first, identity, organization)).thenReturn(true)
        val confirmed = command(workflow = first).copy(affectedWorkflowsToConfirm = state.affectedWorkflows)
        assertEquals("PAYROLL_REVIEWER_CHANGED", assertThrows<ProductApiException> { shared.configure(confirmed) }.code)
        Mockito.`when`(reviewers.canReview(second, identity, organization)).thenReturn(true)
        assertTrue(shared.configure(confirmed).current)
        assertEquals(1, persistence.replacements)
    }

    @Test
    fun pendingMaintenanceKeepsTwoSourceScopesOnTheSamePolicyDistinct() {
        authorized()
        val first = CompanyReviewWorkflow.FINANCE_EMPLOYEE_ADVANCE
        val second = CompanyReviewWorkflow.FINANCE_IMPREST
        val environment = MockEnvironment().withProperty(first.configurationProperty!!, "SYNTHETIC_SHARED_REVIEW")
            .withProperty(second.configurationProperty!!, "SYNTHETIC_SHARED_REVIEW")
        val config = CompanyReviewWorkflowConfiguration(environment)
        val shared = PayrollReviewPolicyService(authority, reviewers, persistence, commands, audit, Clock.fixed(at, ZoneOffset.UTC), engine,
            workflowConfiguration = config)
        val policyId = UUID.randomUUID()
        val scopes = listOf(CompanyPendingReviewPolicy(policyId.toString(), 1, 1, 1, first.name),
            CompanyPendingReviewPolicy(policyId.toString(), 1, 1, 2, second.name))
        val prior = PayrollReviewSetupRecord(PayrollReviewPolicyRecord(policyId, 1, 1, true, true, config.authorityKey(first)),
            PayrollReviewerBindingRecord(UUID.randomUUID(), 1, "USER", UUID.randomUUID(), "Synthetic previous reviewer", UUID.randomUUID(), true), 3, scopes)
        persistence.currentState[first] = prior
        Mockito.`when`(reviewers.canReview(first, identity, organization)).thenReturn(true)
        Mockito.`when`(reviewers.canReview(second, identity, organization)).thenReturn(true)
        val request = command(prior, first).copy(pendingPoliciesToSupersede = scopes,
            supersedeReason = "Replace the shared reviewer with current independent employee", affectedWorkflowsToConfirm = listOf(first.name, second.name))
        shared.configure(request)
        val invocations = Mockito.mockingDetails(engine).invocations.filter { it.method.name == "supersedePolicyRequests" }
        assertEquals(2, invocations.size)
        assertEquals(2, invocations.map { it.arguments[1] }.toSet().size)
        assertEquals(setOf(first.subjectType, second.subjectType), invocations.map {
            (it.arguments[2] as com.hiltech.server.approval.ApprovalPolicyMaintenanceScope).subjectType }.toSet())
    }

    @Test
    fun unlistedSourceDependencyCannotBeChangedEvenWithLegacyConfirmation() {
        authorized()
        val previous = PayrollReviewSetupRecord(null, null, 0, configurationBlockedReason = "EXTERNAL_SOURCE_DEPENDENCY")
        persistence.currentState[CompanyReviewWorkflow.PAYROLL_SOURCE] = previous
        assertFalse(service.current(actor, organization, CompanyReviewWorkflow.PAYROLL_SOURCE).canConfigure)
        assertEquals("REVIEW_WORKFLOW_EXTERNAL_DEPENDENCY", assertThrows<ProductApiException> {
            service.configure(command().copy(legacyAuthorityReplacementConfirmed = true))
        }.code)
        assertEquals(0, persistence.replacements)
        Mockito.verifyNoInteractions(audit)
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
            currentState[workflow] = PayrollReviewSetupRecord(policy, binding, 0)
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
