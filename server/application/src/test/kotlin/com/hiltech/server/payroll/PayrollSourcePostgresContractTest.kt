package com.hiltech.server.payroll

import com.hiltech.server.approval.*
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PayrollSourcePostgresContractTest {
    @Test
    fun approvedRecordedSourcesCalculateWithoutInventedValuesAndPreserveIndependentHistory() {
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
        val preparer = UUID.randomUUID()
        val reviewer = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val person = UUID.randomUUID()
        val employment = UUID.randomUUID()
        jdbc.update("INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at) VALUES(?,?,'Synthetic source','Synthetic source','HILTECH','ACTIVE',?)",
            org, "SRC_" + org.toString().take(12).uppercase(), at)
        for (identity in listOf(preparer, reviewer)) {
            jdbc.update("INSERT INTO user_identity(id,auth_provider,auth_subject,status,primary_organization_id,created_at) VALUES(?,'payroll-source-test',?,'ACTIVE',?,?)", identity, identity.toString(), org, at)
            jdbc.update("INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from) VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)", UUID.randomUUID(), org, identity, at.minusDays(1))
        }
        jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES(?,'Synthetic source employee',?,?)", person, at, at)
        jdbc.update("INSERT INTO employee(id,organization_id,person_id,employee_code,state,hire_date,created_at,updated_at) VALUES(?,?,?,'SOURCE_1','ACTIVE','2026-01-01',?,?)", employee, org, person, at, at)
        jdbc.update("INSERT INTO employment(id,employee_id,start_date,state,created_at,updated_at) VALUES(?,?,'2026-01-01','ACTIVE',?,?)", employment, employee, at, at)
        var reviewAuthorityCurrent = true
        val authority = object : Payroll9aAuthorityPort {
            override fun canRead(actorId: UUID, organizationId: UUID) = organizationId == org && actorId in setOf(preparer, reviewer)
            override fun canPrepare(actorId: UUID, organizationId: UUID) = organizationId == org && actorId == preparer
            override fun canReview(actorId: UUID, organizationId: UUID) = organizationId == org && actorId in setOf(preparer, reviewer) && reviewAuthorityCurrent
        }
        val audit = JdbcAuditEventWriter(jdbc)
        val periods = Payroll9aService(jdbc, authority, audit, clock)
        val period = tx.execute { periods.create(preparer, Payroll9aPeriodCommand(UUID.randomUUID(), org, "SOURCE_2026_11",
            LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), LocalDate.parse("2026-11-25"), null), "source-test").first }!!
        val periodId = UUID.fromString(period.period.periodId)
        tx.execute { periods.captureInputs(preparer, periodId, "source-test") }
        val repository = PayrollSourceRepository(jdbc, clock)
        val guard = PayrollSourceApprovalVersionSource(jdbc)
        val approvalPersistence = JdbcApprovalPersistence(jdbc)
        val engine = ApprovalEngineService(approvalPersistence,
            JdbcIdempotentCommandExecutor(jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {}),
            AuthorizationProjectionIntentWriter {}, object : ApprovalAuthorizationPort {
                override fun canDecide(actorUserId: UUID, approvalRequestId: UUID, assignmentPrincipalType: ApprovalPrincipalType, assignmentPrincipalId: UUID) =
                    actorUserId == reviewer && assignmentPrincipalType == ApprovalPrincipalType.USER && assignmentPrincipalId == reviewer
            }, FailClosedApprovalSubjectVersionGuard(listOf(guard)), audit, ApplicationEventPublisher {}, clock,
            decisionConstraints = listOf(PayrollReviewDecisionConstraint(jdbc, authority, approvalPersistence, PayrollRunPolicyConfiguration(""), clock)))
        val adapter = ApprovedPayrollSourceAdapter(repository, guard)
        val ports = StaticListableBeanFactory(mapOf("recorded" to adapter))
        val assembler = PayrollVerifiedInputAssembler(
            ports.getBeanProvider(PayrollValidatedStatutoryPolicySourcePort::class.java),
            ports.getBeanProvider(PayrollApprovedCompensationSourcePort::class.java),
            ports.getBeanProvider(PayrollApprovedAttendanceSourcePort::class.java),
            ports.getBeanProvider(PayrollApprovedFinanceInputSourcePort::class.java),
            StaticListableBeanFactory().getBeanProvider(PayrollVerifiedSeparationEligibilitySourcePort::class.java))
        val draftSources = StaticListableBeanFactory(mapOf("verified" to assembler))
        val source = PayrollSourceService(jdbc, periods, authority, repository, guard, engine, audit, clock,
            draftSources.getBeanProvider(PayrollApprovedInputSourcePort::class.java))
        val drafts = Payroll9bDraftService(jdbc, periods, authority,
            draftSources.getBeanProvider(PayrollApprovedInputSourcePort::class.java), audit, clock)
        fun save(request: PayrollSourceRequestDto) = tx.execute { source.create(preparer, periodId, request, "source-test") }!!
        val policyRequest = PayrollSourceRequestDto(UUID.randomUUID().toString(), "POLICY", sourceReference = "Synthetic signed accountant policy",
            currency = "EGP", fractionalDigits = 2, roundingMode = "HALF_EVEN", companyAuthorizationReference = "Synthetic company mandate",
            statutoryPolicyReference = "Synthetic statutory review", deductionPolicyReference = "Synthetic deduction limits")
        val policy = save(policyRequest)
        assertTrue(save(policyRequest).replayed)
        assertNull(adapter.approvedPolicy(org, periodId))
        assertFalse(source.list(preparer, periodId).reviewPolicyReady)
        assertThrows<ProductApiException> { tx.execute { source.requestApproval(preparer, UUID.fromString(policy.record.recordId), UUID.randomUUID(), "source-test") } }
        assertThrows<ProductApiException> { tx.execute { drafts.create(periodId, UUID.randomUUID(), preparer, "source-test") } }
        jdbc.update("INSERT INTO approval_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,effective_from,active,created_by_user_id,created_at) VALUES(?,?,'PAYROLL_SOURCE_REVIEWER','USER',?,?,true,?,?)",
            UUID.randomUUID(), org, reviewer, at.minusDays(1), preparer, at)
        jdbc.update("INSERT INTO approval_policy_version(id,organization_id,policy_key,version_number,lifecycle_state,approval_required,step_mode,authority_key,reason_required,effective_from,created_by_user_id,created_at) VALUES(?,?,'PAYROLL_SOURCE_REVIEW',1,'ACTIVE',true,'SINGLE','PAYROLL_SOURCE_REVIEWER',true,?,?,?)",
            UUID.randomUUID(), org, at.minusDays(1), preparer, at)
        fun approve(record: PayrollSourceRecordDto) {
            val id = UUID.fromString(record.recordId)
            tx.execute { source.requestApproval(preparer, id, UUID.randomUUID(), "source-test") }
            assertThrows<ProductApiException> { tx.execute { source.decide(preparer, id, PayrollSourceDecisionRequestDto(UUID.randomUUID().toString(), "APPROVE"), "source-test") } }
            val requestId = jdbc.queryForObject("SELECT id FROM approval_request WHERE subject_id=? AND subject_type='PAYROLL_SOURCE_RECORD'", UUID::class.java, id)!!
            reviewAuthorityCurrent = false
            assertEquals("APPROVAL_SOURCE_REVIEW_NOT_ALLOWED", assertThrows<ProductApiException> {
                tx.execute { engine.decide(reviewer, UUID.randomUUID(), requestId, ApprovalDecisionType.APPROVE, null, "synthetic-generic-revoked-review") }
            }.code)
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM approval_request WHERE id=?", String::class.java, requestId))
            reviewAuthorityCurrent = true
            tx.execute { source.decide(reviewer, id, PayrollSourceDecisionRequestDto(UUID.randomUUID().toString(), "APPROVE"), "source-test") }
        }
        approve(policy.record)
        val compensationRequest = PayrollSourceRequestDto(UUID.randomUUID().toString(), "COMPENSATION", employee.toString(),
            sourceReference = "Synthetic signed employment schedule", currency = "EGP",
            components = listOf(PayrollSourceComponentDto("BASE_PAY", "EARNING", "1000.00", description = "Synthetic base compensation")))
        val compensation = save(compensationRequest)
        approve(compensation.record)
        assertNull(adapter.approvedTimeAndAllowances(org, periodId, employee))
        val time = save(PayrollSourceRequestDto(UUID.randomUUID().toString(), "APPROVED_TIME", employee.toString(),
            sourceReference = "Synthetic reviewed attendance register", currency = "EGP", noInputs = true))
        approve(time.record)
        assertTrue(adapter.approvedTimeAndAllowances(org, periodId, employee)!!.components.isEmpty())
        val finance = save(PayrollSourceRequestDto(UUID.randomUUID().toString(), "FINANCE", employee.toString(),
            sourceReference = "Synthetic approved deduction register", currency = "EGP",
            components = listOf(PayrollSourceComponentDto("RECOVERY", "DEDUCTION", "25", "Synthetic deduction plan", "Synthetic recovery"))))
        approve(finance.record)
        assertTrue(source.list(preparer, periodId).calculationReady)
        val run = tx.execute { drafts.create(periodId, UUID.randomUUID(), preparer, "source-test") }!!.first
        assertEquals("975.000000", run.net)
        assertEquals(1, run.lines.size)
        assertEquals("1000.000000", run.gross)
        PayrollRunReviewPostgresScenario.verify(jdbc, tx, org, preparer, reviewer, UUID.fromString(run.runVersionId), drafts,
            draftSources.getBeanProvider(PayrollApprovedInputSourcePort::class.java), authority, clock)
        assertThrows<ProductApiException> { source.list(UUID.randomUUID(), periodId) }
        assertThrows<ProductApiException> { save(compensationRequest.copy(operationId = UUID.randomUUID().toString(), employeeId = UUID.randomUUID().toString())) }
        assertThrows<Exception> { jdbc.update("UPDATE payroll_source_record SET currency = 'USD' WHERE id = ?", UUID.fromString(compensation.record.recordId)) }
        assertThrows<Exception> { jdbc.update("INSERT INTO payroll_source_component(source_record_id,component_code,component_kind,description,amount) VALUES(?,'LATE','EARNING','Late component',10)", UUID.fromString(compensation.record.recordId)) }
        // Approved inputs can still be arithmetically invalid: surface that before draft submission.
        val excessive = save(PayrollSourceRequestDto(UUID.randomUUID().toString(), "FINANCE", employee.toString(),
            replacesRecordId = finance.record.recordId, sourceReference = "Synthetic reviewed correction", currency = "EGP",
            components = listOf(PayrollSourceComponentDto("RECOVERY", "DEDUCTION", "1250", "Synthetic deduction plan", "Synthetic excessive recovery"))))
        approve(excessive.record)
        val blocked = source.list(preparer, periodId)
        assertFalse(blocked.calculationReady)
        assertTrue("PAYROLL_INVALID_NET" in blocked.blockers)
        val restored = save(PayrollSourceRequestDto(UUID.randomUUID().toString(), "FINANCE", employee.toString(),
            replacesRecordId = excessive.record.recordId, sourceReference = "Synthetic corrected recovery", currency = "EGP",
            components = listOf(PayrollSourceComponentDto("RECOVERY", "DEDUCTION", "25", "Synthetic deduction plan", "Synthetic recovery"))))
        approve(restored.record)
        assertTrue(source.list(preparer, periodId).calculationReady)
        // Rotating reviewer/policy preserves a completed, exact historical source approval.
        jdbc.update("UPDATE approval_policy_version SET lifecycle_state = 'RETIRED', effective_to = ? WHERE organization_id = ? AND policy_key = 'PAYROLL_SOURCE_REVIEW'", at.plusSeconds(1), org)
        assertNotNull(adapter.approvedPolicy(org, periodId))
        assertEquals("1000", adapter.approvedCompensation(org, periodId, employee)!!.components.single().amount)
        val revised = save(compensationRequest.copy(operationId = UUID.randomUUID().toString(), replacesRecordId = compensation.record.recordId,
            components = listOf(PayrollSourceComponentDto("BASE_PAY", "EARNING", "1200", description = "Synthetic corrected compensation"))))
        assertEquals(2, revised.record.revision)
        assertNull(adapter.approvedCompensation(org, periodId, employee))
        assertFalse(source.list(preparer, periodId).calculationReady)
        assertThrows<ProductApiException> { tx.execute { drafts.create(periodId, UUID.randomUUID(), preparer, "source-test") } }
        assertEquals("975.000000", drafts.read(preparer, UUID.fromString(run.runVersionId)).net)
        assertThrows<ProductApiException> { save(compensationRequest.copy(operationId = UUID.randomUUID().toString(), replacesRecordId = compensation.record.recordId)) }
        assertTrue((jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_type = 'PAYROLL_SOURCE_RECORD' AND action = 'PAYROLL_SOURCE_RECORDED'", Int::class.java) ?: 0) >= 5)
    }
}
