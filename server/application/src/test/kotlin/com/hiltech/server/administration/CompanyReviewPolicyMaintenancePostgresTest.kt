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
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Real policy, assignment, idempotency and transaction boundaries; source currentness is synthetic here. */
class CompanyReviewPolicyMaintenancePostgresTest {
    @Test fun namedReviewerReplacementEndsExactPendingWorkWhenFormerReviewerHasLostAccess() {
        assumeTrue(System.getenv("HILTECH_PAYROLL_9A_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        Flyway.configure().dataSource(url, user, password)
            .locations("filesystem:" + (System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations")).load().migrate()
        val jdbc = JdbcTemplate(DriverManagerDataSource(url, user, password))
        val manager = DataSourceTransactionManager(jdbc.dataSource!!)
        val tx = TransactionTemplate(manager)
        val now = Instant.now().minusSeconds(2)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val at = now.atOffset(ZoneOffset.UTC)
        val org = UUID.randomUUID()
        jdbc.update("""INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at)
            VALUES(?,?,'Synthetic review setup','Synthetic review setup','HILTECH','ACTIVE',?)""", org, "RV_" + org.toString().take(12), at)
        fun employee(name: String): Pair<UUID, UUID> {
            val person = UUID.randomUUID(); val identity = UUID.randomUUID(); val employee = UUID.randomUUID()
            jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES(?,?,?,?)", person, name, at, at)
            jdbc.update("""INSERT INTO user_identity(id,person_id,auth_provider,auth_subject,status,primary_organization_id,created_at)
                VALUES(?,?,'synthetic-review-setup',?,'ACTIVE',?,?)""", identity, person, identity.toString(), org, at)
            jdbc.update("""INSERT INTO employee(id,organization_id,person_id,employee_code,state,hire_date,created_at,updated_at)
                VALUES(?,?,?,?,'ACTIVE','2026-01-01',?,?)""", employee, org, person, "E_" + employee.toString().take(12), at, at)
            jdbc.update("""INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from)
                VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""", UUID.randomUUID(), org, identity, at.minusDays(1))
            return employee to identity
        }
        val (_, administrator) = employee("Synthetic policy administrator")
        val (oldEmployee, oldReviewer) = employee("Synthetic former reviewer")
        val (newEmployee, newReviewer) = employee("Synthetic replacement reviewer")
        jdbc.update("""INSERT INTO organization_administration_authority_binding(id,organization_id,identity_id,authority_key,
            effective_from,created_by_user_id,created_at) VALUES(?,?,?,'APPROVAL_POLICY_ADMIN',?,?,?)""",
            UUID.randomUUID(), org, administrator, at.minusDays(1), administrator, at)
        val authority = JdbcOrganizationAdministrationAuthority(jdbc, clock)
        val policies = JdbcApprovalPersistence(jdbc)
        val workflow = CompanyReviewWorkflow.PROCUREMENT_PO
        val configured = ProcurementReviewPolicyConfiguration("")
        val audit = JdbcAuditEventWriter(jdbc)
        val commands = JdbcIdempotentCommandExecutor(jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
        val events = mutableListOf<Any>()
        val engine = ApprovalEngineService(policies, commands, AuthorizationProjectionIntentWriter {},
            object : ApprovalAuthorizationPort {
                override fun canDecide(actorUserId: UUID, approvalRequestId: UUID, assignmentPrincipalType: ApprovalPrincipalType,
                    assignmentPrincipalId: UUID) = assignmentPrincipalType == ApprovalPrincipalType.USER && actorUserId == assignmentPrincipalId
            }, ApprovalSubjectVersionGuard { true }, audit, ApplicationEventPublisher { events += it }, clock,
            JdbcApprovalPolicyMaintenancePersistence(jdbc), CompanyReviewPolicyMaintenanceAuthority(authority, PayrollRunPolicyConfiguration(""), configured))
        val store = JdbcPayrollReviewPolicyPersistence(jdbc, procurementPolicy = configured)
        val setup = PayrollReviewPolicyService(authority,
            DomainCompanyReviewCandidateAuthority(Mockito.mock(Payroll9aAuthorityPort::class.java), Mockito.mock(FinancePayrollAuthorityPort::class.java),
                CurrentProcurementCandidateReviewEligibility(jdbc, clock)), store, commands, audit, clock, engine, procurementPolicy = configured)
        fun configure(employee: UUID, identity: UUID, previous: PayrollReviewSetupResponse? = null) = ConfigurePayrollReviewPolicyCommand(
            UUID.randomUUID(), org, employee, identity, 1, previous?.policyId?.let(UUID::fromString), previous?.policyVersion,
            previous?.bindingId?.let(UUID::fromString), previous?.bindingVersion, administrator, "synthetic-policy-rotation", workflow,
            previous?.pendingPolicies.orEmpty(), if (previous?.pendingReviews?.let { it > 0 } == true) "The old reviewer left; prepare current replacement reviews" else null)
        setup.configure(configure(oldEmployee, oldReviewer))
        fun request(): UUID = engine.requestException(administrator, UUID.randomUUID(),
            ApprovalSubjectRef(org, workflow.subjectType, UUID.randomUUID(), 1), configured.key,
            "SYNTHETIC_POLICY_MAINTENANCE_SOURCE", null, "synthetic-policy-request", allowAuthorityBypass = false).approvalRequestId!!
        val historical = request()
        engine.decide(oldReviewer, UUID.randomUUID(), historical, ApprovalDecisionType.APPROVE, null, "synthetic-historical-decision")
        val firstPending = request()
        val beforeRace = setup.current(administrator, org, workflow)
        assertEquals(1, beforeRace.pendingReviews)
        val anotherPending = request()
        assertEquals("APPROVAL_POLICY_PENDING_CONFIRMATION_REQUIRED", assertThrows<ProductApiException> {
            setup.configure(configure(newEmployee, newReviewer, beforeRace))
        }.code)
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, firstPending))

        // The former reviewer is not asked to recover a workflow after losing membership.
        jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", org, oldReviewer)
        assertThrows<ProductApiException> { engine.decide(oldReviewer, UUID.randomUUID(), firstPending, ApprovalDecisionType.APPROVE, null, "synthetic-revoked-reviewer") }
        val impact = setup.current(administrator, org, workflow)
        assertEquals(2, impact.pendingReviews)
        val command = configure(newEmployee, newReviewer, impact)
        val configuredResult = setup.configure(command)
        assertTrue(setup.configure(command).replayed)
        assertEquals(2, configuredResult.policyRevision)
        assertEquals("READY", setup.current(administrator, org, workflow).status)
        assertEquals(0, setup.current(administrator, org, workflow).pendingReviews)
        for (pending in listOf(firstPending, anotherPending)) {
            assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, pending))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM approval_decision WHERE approval_request_id=?", Long::class.java, pending))
            assertEquals("REVOKED", jdbc.queryForObject("SELECT state FROM approval_assignment WHERE approval_request_id=?", String::class.java, pending))
        }
        assertEquals("APPROVED", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, historical))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM approval_decision WHERE approval_request_id=?", Long::class.java, historical))
        assertEquals(2, events.count { it is ApprovalSuperseded })
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='APPROVAL_SUPERSEDED' AND target_id IN (?,?)",
            Long::class.java, firstPending, anotherPending))
        assertThrows<ProductApiException> { setup.configure(configure(oldEmployee, oldReviewer, impact)) }

        val fresh = request()
        assertThrows<ProductApiException> { engine.decide(oldReviewer, UUID.randomUUID(), fresh, ApprovalDecisionType.APPROVE, null, "synthetic-old-reviewer-new-request") }
        assertEquals(ApprovalRequestState.APPROVED, engine.decide(newReviewer, UUID.randomUUID(), fresh, ApprovalDecisionType.APPROVE, null, "synthetic-new-reviewer").state)
        val stalePolicy = impact.pendingPolicies.single()
        assertEquals("APPROVAL_POLICY_CHANGED", assertThrows<ProductApiException> {
            engine.supersedePolicyRequests(administrator, UUID.randomUUID(), ApprovalPolicyMaintenanceScope(org, UUID.fromString(stalePolicy.policyId),
                configured.key, stalePolicy.policyRevision, stalePolicy.policyVersion, workflow.subjectType, stalePolicy.pendingReviews), "Synthetic stale policy version", "synthetic")
        }.code)
        jdbc.update("UPDATE organization_administration_authority_binding SET active=false WHERE organization_id=? AND identity_id=?", org, administrator)
        assertEquals("APPROVAL_POLICY_ADMINISTRATION_DENIED", assertThrows<ProductApiException> { setup.configure(command) }.code)
        assertFalse(authority.canManageApprovalPolicies(newReviewer, org))
        assertTrue(tx.execute { policies.loadDecisionContextForUpdate(fresh)?.requestState == ApprovalRequestState.APPROVED } == true)
    }
}
