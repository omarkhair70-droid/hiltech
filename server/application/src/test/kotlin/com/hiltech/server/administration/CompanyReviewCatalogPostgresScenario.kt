package com.hiltech.server.administration

import com.hiltech.server.approval.*
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.finance.FinancePayrollAuthorityPort
import com.hiltech.server.payroll.Payroll9aAuthorityPort
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.procurement.CurrentProcurementCandidateReviewEligibility
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.mock.env.MockEnvironment
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Real configuration/approval persistence; synthetic subjects isolate policy administration from money execution. */
object CompanyReviewCatalogPostgresScenario {
    fun verify(jdbc: JdbcTemplate, manager: DataSourceTransactionManager, org: UUID, administrator: UUID,
               currentReviewer: UUID, clock: Clock) {
        val now = clock.instant()
        val at = now.atOffset(ZoneOffset.UTC)
        val first = CompanyReviewWorkflow.FINANCE_EMPLOYEE_ADVANCE
        val second = CompanyReviewWorkflow.FINANCE_IMPREST
        val sharedKey = "SYNTHETIC_SHARED_MONEY_REVIEW"
        val legacyAuthority = "SYNTHETIC_COMPANY_REVIEWER"
        val person = UUID.randomUUID(); val reviewer = UUID.randomUUID(); val employee = UUID.randomUUID()
        jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES(?,'Synthetic bounded reviewer',?,?)", person, at, at)
        jdbc.update("""INSERT INTO user_identity(id,person_id,auth_provider,auth_subject,status,primary_organization_id,created_at)
            VALUES(?,?,'synthetic-policy-catalog',?,'ACTIVE',?,?)""", reviewer, person, reviewer.toString(), org, at)
        jdbc.update("""INSERT INTO employee(id,organization_id,person_id,employee_code,state,hire_date,created_at,updated_at)
            VALUES(?,?,?,?,'ACTIVE','2026-01-01',?,?)""", employee, org, person, "E_" + employee.toString().take(12), at, at)
        jdbc.update("""INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from)
            VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""", UUID.randomUUID(), org, reviewer, at.minusDays(1))
        val legacyBinding = UUID.randomUUID()
        jdbc.update("""INSERT INTO approval_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,
            effective_from,active,created_by_user_id,created_at,version) VALUES(?,?,?,'USER',?,?,true,?,?,1)""",
            legacyBinding, org, legacyAuthority, currentReviewer, at.minusDays(1), administrator, at)
        fun legacyPolicy(key: String): UUID = UUID.randomUUID().also { id ->
            jdbc.update("""INSERT INTO approval_policy_version(id,organization_id,policy_key,version_number,lifecycle_state,
                approval_required,step_mode,authority_key,reason_required,effective_from,created_by_user_id,created_at,version)
                VALUES(?,?,?,1,'ACTIVE',true,'SINGLE',?,true,?,?,?,1)""",
                id, org, key, legacyAuthority, at.minusDays(1), administrator, at)
        }
        val oldPolicy = legacyPolicy(sharedKey)
        val unrelatedPolicy = legacyPolicy("SYNTHETIC_UNRELATED_COMPANY_REVIEW")
        val environment = MockEnvironment().withProperty(first.configurationProperty!!, sharedKey)
            .withProperty(second.configurationProperty!!, sharedKey)
        val payroll = PayrollRunPolicyConfiguration("")
        val procurement = ProcurementReviewPolicyConfiguration("")
        val configuration = CompanyReviewWorkflowConfiguration(environment, payroll, procurement)
        val authority = JdbcOrganizationAdministrationAuthority(jdbc, clock)
        val policies = JdbcApprovalPersistence(jdbc)
        val commands = JdbcIdempotentCommandExecutor(jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
        val audit = JdbcAuditEventWriter(jdbc)
        val engine = ApprovalEngineService(policies, commands, AuthorizationProjectionIntentWriter {},
            object : ApprovalAuthorizationPort {
                override fun canDecide(actorUserId: UUID, approvalRequestId: UUID, assignmentPrincipalType: ApprovalPrincipalType,
                                       assignmentPrincipalId: UUID) = actorUserId == assignmentPrincipalId
            }, ApprovalSubjectVersionGuard { true }, audit, ApplicationEventPublisher {}, clock,
            JdbcApprovalPolicyMaintenancePersistence(jdbc),
            CompanyReviewPolicyMaintenanceAuthority(authority, payroll, procurement, configuration))
        val persistence = JdbcPayrollReviewPolicyPersistence(jdbc, payroll, procurement, configuration)
        val setup = PayrollReviewPolicyService(authority,
            DomainCompanyReviewCandidateAuthority(Mockito.mock(Payroll9aAuthorityPort::class.java), Mockito.mock(FinancePayrollAuthorityPort::class.java),
                CurrentProcurementCandidateReviewEligibility(jdbc, clock), company = CurrentCompanyEmployeeReviewEligibility(jdbc, clock)),
            persistence, commands, audit, clock, engine, payroll, procurement, configuration)
        fun command(workflow: CompanyReviewWorkflow, previous: PayrollReviewSetupResponse? = null) = ConfigurePayrollReviewPolicyCommand(
            UUID.randomUUID(), org, employee, reviewer, 1, previous?.policyId?.let(UUID::fromString), previous?.policyVersion,
            previous?.bindingId?.let(UUID::fromString), previous?.bindingVersion, administrator, "synthetic-policy-catalog", workflow,
            previous?.pendingPolicies.orEmpty(), "Assign the independent reviewer to the displayed sources",
            previous?.affectedWorkflows ?: configuration.group(workflow).map { it.name }, previous?.replacesLegacyAuthority == true)
        fun request(workflow: CompanyReviewWorkflow) = engine.requestException(administrator, UUID.randomUUID(),
            ApprovalSubjectRef(org, workflow.subjectType, UUID.randomUUID(), 1), sharedKey,
            "SYNTHETIC_POLICY_CONFIGURATION", null, "synthetic-catalog-request", allowAuthorityBypass = false).approvalRequestId!!

        val historical = request(first)
        engine.decide(currentReviewer, UUID.randomUUID(), historical, ApprovalDecisionType.APPROVE, "Synthetic prior independent review", "synthetic")
        val pending = listOf(request(first), request(second))
        val before = setup.current(administrator, org, first)
        assertEquals("LEGACY_AUTHORITY_PRESERVED", before.status)
        assertEquals(setOf(first.name, second.name), before.affectedWorkflows.toSet())
        assertEquals(2, before.pendingReviews)
        assertEquals(1, before.pendingPolicies.map { it.policyId }.toSet().size)
        assertEquals(setOf(first.name, second.name), before.pendingPolicies.map { it.workflow }.toSet())
        assertEquals("REVIEW_WORKFLOW_LEGACY_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            setup.configure(command(first, before).copy(legacyAuthorityReplacementConfirmed = false))
        }.code)
        assertEquals("APPROVAL_POLICY_PENDING_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            setup.configure(command(first, before).copy(pendingPoliciesToSupersede = before.pendingPolicies.take(1)))
        }.code)
        pending.forEach { assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, it)) }
        val operation = command(first, before)
        val receipt = setup.configure(operation)
        assertTrue(setup.configure(operation).replayed)
        assertEquals(2, receipt.policyRevision)
        assertEquals(setup.current(administrator, org, first).bindingId, setup.current(administrator, org, second).bindingId)
        assertEquals("READY", setup.current(administrator, org, second).status)
        pending.forEach {
            assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, it))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM approval_decision WHERE approval_request_id=?", Long::class.java, it))
        }
        assertEquals("APPROVED", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, historical))
        assertEquals("RETIRED", jdbc.queryForObject("SELECT lifecycle_state FROM approval_policy_version WHERE id=?", String::class.java, oldPolicy))
        assertEquals(true, jdbc.queryForObject("""SELECT p.effective_from<=a.completed_at AND p.effective_to>a.completed_at
            FROM approval_policy_version p JOIN approval_request a
              ON a.organization_id=p.organization_id AND a.policy_key=p.policy_key AND a.policy_version=p.version_number
            WHERE p.id=? AND a.id=?""", Boolean::class.java, oldPolicy, historical))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT lifecycle_state FROM approval_policy_version WHERE id=?", String::class.java, unrelatedPolicy))
        assertEquals(true, jdbc.queryForObject("SELECT active FROM approval_authority_binding WHERE id=?", Boolean::class.java, legacyBinding))
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM approval_authority_binding WHERE id=?", Long::class.java, legacyBinding))

        // All 21 newly exposed source workflows can be configured by an explicit
        // policy administrator using a named employee; no money/operating role is invented.
        CompanyReviewWorkflow.entries.drop(6).filter { it !in setOf(first, second) }.forEach { workflow ->
            val installed = setup.configure(command(workflow))
            assertTrue(installed.current)
            assertEquals("READY", setup.current(administrator, org, workflow).status)
            assertEquals(configuration.key(workflow), jdbc.queryForObject("SELECT policy_key FROM approval_policy_version WHERE id=?",
                String::class.java, UUID.fromString(installed.policyId)))
        }

        // A request outside the recognized source catalog is not silently
        // swept up by maintenance of these two workflows.
        engine.requestException(administrator, UUID.randomUUID(), ApprovalSubjectRef(org, "SYNTHETIC_EXTERNAL_SOURCE", UUID.randomUUID(), 1),
            sharedKey, "SYNTHETIC_EXTERNAL_CONFIGURATION", null, "synthetic", allowAuthorityBypass = false)
        val blocked = setup.current(administrator, org, first)
        assertFalse(blocked.canConfigure)
        assertEquals("EXTERNAL_SOURCE_DEPENDENCY", blocked.configurationBlockedReason)
        assertEquals("REVIEW_WORKFLOW_EXTERNAL_DEPENDENCY", assertThrows<ProductApiException> { setup.configure(command(first, blocked)) }.code)
    }
}
