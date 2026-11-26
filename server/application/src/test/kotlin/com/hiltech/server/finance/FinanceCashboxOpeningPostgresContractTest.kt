package com.hiltech.server.finance

import com.hiltech.server.administration.CompanyResponsibilityKey
import com.hiltech.server.administration.JdbcCompanyResponsibilityPersistence
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectStatus
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.JdbcApprovalPersistence
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.operationalaccess.JdbcOperationalAuthority
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Real SQL/command runtime, confined to synthetic rows in an enclosing rollback-only transaction. */
class FinanceCashboxOpeningPostgresContractTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    @Test fun directoryReadsCurrentNamedEmployeeAccountsInTheExactCompany() = database { f ->
        assertEquals(listOf(f.custodian), f.service.custodians(f.maker, f.org, "custodian").items.map { it.identityId })
        assertTrue(f.source.custodians(UUID.randomUUID(), "", at, 50).isEmpty())
        assertTrue(f.source.custodians(f.org, "%", at, 50).isEmpty())
        val first = requireNotNull(f.source.custodian(f.org, f.custodian, at))
        f.jdbc.update("UPDATE employee SET version=version+1 WHERE id=?", first.employeeId)
        assertNotEquals(first.sourceVersion, requireNotNull(f.source.custodian(f.org, f.custodian, at)).sourceVersion)
        f.jdbc.update("UPDATE organization_membership SET valid_until=? WHERE organization_id=? AND user_identity_id=?",
            at.atOffset(ZoneOffset.UTC), f.org, f.custodian)
        assertNull(f.source.custodian(f.org, f.custodian, at))
    }

    @Test fun formerEmployeeAndRestrictedProviderIdentityAreNotCustodianChoices() = database { f ->
        val choice = requireNotNull(f.source.custodian(f.org, f.custodian, at))
        f.jdbc.update("UPDATE employee SET state='OFFBOARDING' WHERE id=?", choice.employeeId)
        assertNull(f.source.custodian(f.org, f.custodian, at))
        f.jdbc.update("UPDATE employee SET state='ACTIVE' WHERE id=?", choice.employeeId)
        f.jdbc.update("UPDATE user_identity SET status='LOCKED' WHERE id=?", f.custodian)
        assertNull(f.source.custodian(f.org, f.custodian, at))
    }

    @Test fun realIdempotentPrepareStoresOneAutomaticCodeAndOneAuditWithoutOpeningMoney() = database { f ->
        val input = f.input()
        val first = f.service.prepare(f.maker, f.org, input, "synthetic-cashbox-pg")
        val replay = f.service.prepare(f.maker, f.org, input, "synthetic-cashbox-pg-retry")
        assertEquals("CBX-000001", first.cashboxCode)
        assertTrue(replay.replayed)
        assertEquals(first.cashboxCode, replay.cashboxCode)
        assertEquals("Synthetic custodian", replay.custodianDisplayName)
        assertEquals("0.000000", replay.openingBalance)
        assertEquals(1L, f.counter())
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM finance_cashbox_opening_proposal WHERE organization_id=?", Int::class.java, f.org))
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_user_id=? AND action='FINANCE_CASHBOX_OPENING_PROPOSED'", Int::class.java, f.maker))
        assertEquals("APPLIED", f.jdbc.queryForObject("SELECT state FROM idempotent_operation WHERE operation_id=?", String::class.java, input.operationId))
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM finance_cashbox_register WHERE organization_id=?", Int::class.java, f.org))
    }

    @Test fun existingManualCodesRemainUnchangedAndAreReservedByTheAutomaticCounter() = database { f ->
        val manual = f.input().copy(cashboxCode = "CBX-000001", expectedCustodianVersion = null, expectedReviewVersion = null)
        val old = f.service.prepare(f.maker, f.org, manual, "synthetic-cashbox-pg")
        val automatic = f.service.prepare(f.maker, f.org, f.input(), "synthetic-cashbox-pg")
        assertEquals("CBX-000002", automatic.cashboxCode)
        assertEquals("MANUAL", f.source.proposal(f.org, old.proposalId)?.codeSource)
        assertEquals("CBX-000001", f.source.proposal(f.org, old.proposalId)?.code)
        assertEquals(2L, f.counter())
    }

    @Test fun rollbackReleasesTheAllocatedCodeAndDoesNotLeavePartialCommandOrProposalRows() = database { f ->
        val savepoint = f.connection.setSavepoint()
        val input = f.input()
        f.service.prepare(f.maker, f.org, input, "synthetic-cashbox-pg")
        assertEquals(1L, f.counter())
        f.connection.rollback(savepoint)
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM finance_cashbox_number_counter WHERE organization_id=?", Int::class.java, f.org))
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM finance_cashbox_opening_proposal WHERE organization_id=?", Int::class.java, f.org))
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_operation WHERE operation_id=?", Int::class.java, input.operationId))
        assertEquals("CBX-000001", f.service.prepare(f.maker, f.org, input, "synthetic-cashbox-pg").cashboxCode)
    }

    @Test fun automaticCodeCannotForgeAnotherNumberAndSavedHistoryCannotBeRewritten() = database { f ->
        val first = f.service.prepare(f.maker, f.org, f.input(), "synthetic-cashbox-pg")
        val row = requireNotNull(f.source.proposal(f.org, first.proposalId))
        val before = f.connection.setSavepoint()
        assertThrows<DataIntegrityViolationException> {
            f.source.append(row.copy(id = UUID.randomUUID(), operationId = UUID.randomUUID(), code = "CBX-999999", codeSequence = 99))
        }
        f.connection.rollback(before)
        assertThrows<DataIntegrityViolationException> {
            f.jdbc.update("UPDATE finance_cashbox_opening_proposal SET cashbox_code='REWRITTEN' WHERE id=?", row.id)
        }
    }

    @Test fun lossOfCurrentEmployeeCustodyKeepsTheProposalVisibleButInvalidatesOpeningApprovalSource() = database { f ->
        val first = f.service.prepare(f.maker, f.org, f.input(), "synthetic-cashbox-pg")
        assertNotNull(f.subject.current(f.org, first.proposalId))
        f.jdbc.update("UPDATE employee SET state='OFFBOARDING' WHERE person_id=(SELECT person_id FROM user_identity WHERE id=?) AND organization_id=?",
            f.custodian, f.org)
        assertNull(f.subject.current(f.org, first.proposalId))
        val visible = f.service.workbench(f.maker, f.org).items.single()
        assertEquals(first.proposalId, visible.proposalId)
        assertEquals("REVIEW_REQUIRED", visible.state)
        assertFalse(visible.canActivate)
        assertFalse(visible.canRequestReview)
    }

    @Test fun authorizedCustodianPreparesAndActivatesOwnZeroOpeningAfterIndependentReview() = database { f ->
        val self = f.service.custodians(f.maker, f.org, "preparer").items.single()
        assertEquals(f.maker, self.identityId)
        val input = f.input().copy(custodianUserId = f.maker, expectedCustodianVersion = self.sourceVersion)
        val proposed = f.service.prepare(f.maker, f.org, input, "synthetic-self-custody")
        val source = requireNotNull(f.source.proposal(f.org, proposed.proposalId))
        assertNotNull(f.subject.current(f.org, source.id))
        val approval = f.approve(source, f.reviewer)
        val ref = ApprovalSubjectRef(f.org, FINANCE_CASHBOX_OPENING_SUBJECT, source.id, source.subjectVersion)
        Mockito.`when`(f.engine.subjectStatus(ref, source.policyKey)).thenReturn(
            ApprovalSubjectStatus(approval, ApprovalRequestState.APPROVED, source.subjectVersion, source.policyKey))
        val operation = UUID.randomUUID()
        val activated = f.service.activate(f.maker, f.org, source.id, operation, "synthetic-self-custody")
        assertEquals("ACTIVE", activated.state)
        assertEquals(f.maker, activated.custodianUserId)
        assertTrue(f.service.activate(f.maker, f.org, source.id, operation, "synthetic-self-custody-retry").replayed)
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM finance_cashbox_register WHERE organization_id=?", Int::class.java, f.org))
        assertTrue(f.jdbc.queryForObject("SELECT cash_on_hand=0 FROM finance_cashbox_balance WHERE organization_id=? AND cashbox_id=?",
            Boolean::class.java, f.org, source.id) == true)
    }

    @Test fun selfApprovalCannotOpenSelfCustodyEvenWhenAnApprovedEnvelopeWasFabricated() = database { f ->
        val self = requireNotNull(f.source.custodian(f.org, f.maker, at))
        val proposed = f.service.prepare(f.maker, f.org,
            f.input().copy(custodianUserId = f.maker, expectedCustodianVersion = self.sourceVersion), "synthetic-self-review")
        val source = requireNotNull(f.source.proposal(f.org, proposed.proposalId))
        val approval = f.approve(source, f.maker)
        val savepoint = f.connection.setSavepoint()
        assertThrows<DataIntegrityViolationException> {
            f.source.activate(source, UUID.randomUUID(), f.maker, approval, at)
        }
        f.connection.rollback(savepoint)
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM finance_cashbox_register WHERE organization_id=?", Int::class.java, f.org))
    }

    private inner class Fixture(val connection: Connection, val jdbc: JdbcTemplate, manager: DataSourceTransactionManager) {
        val org = UUID.randomUUID()
        val maker = UUID.randomUUID()
        val custodian = UUID.randomUUID()
        val reviewer = UUID.randomUUID()
        val policyKey = "FINANCE_CASHBOX_OPENING_REVIEW"
        val source = JdbcFinanceCashboxOpeningPersistence(jdbc)
        val approvals = JdbcApprovalPersistence(jdbc)
        val environment = MockEnvironment().withProperty("hiltech.finance.cashbox.enabled", "true")
            .withProperty("hiltech.finance.cashbox.opening-policy-key", policyKey)
        val subject = FinanceCashboxOpeningApprovalSubject(jdbc, approvals, environment, clock)
        val engine = Mockito.mock(ApprovalEngineService::class.java)
        val telemetry = Mockito.mock(HiltechTelemetryRuntime::class.java).also {
            Mockito.`when`(it.tracer).thenReturn(OpenTelemetry.noop().getTracer("synthetic-cashbox-contract"))
        }
        val service = FinanceCashboxOpeningService(source, subject, approvals, engine,
            JdbcIdempotentCommandExecutor(jdbc, manager, clock, telemetry), JdbcAuditEventWriter(jdbc), environment, clock, JdbcOperationalAuthority(jdbc))
        init {
            val time = at.minusSeconds(60).atOffset(ZoneOffset.UTC)
            jdbc.update("""INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at)
                VALUES (?,?,'Synthetic cash company','Synthetic cash company','HILTECH','ACTIVE',?)""", org, "SYNTH_${org}", time)
            for ((identity, name) in listOf(maker to "Synthetic preparer", custodian to "Synthetic custodian", reviewer to "Synthetic reviewer")) {
                val person = UUID.randomUUID()
                jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES (?,?,?,?)", person, name, time, time)
                jdbc.update("INSERT INTO user_identity(id,auth_provider,auth_subject,person_id,status,created_at) VALUES (?,'synthetic-cashbox',?,?,'ACTIVE',?)",
                    identity, identity.toString(), person, time)
                jdbc.update("INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from) VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)",
                    UUID.randomUUID(), org, identity, time)
                jdbc.update("INSERT INTO employee(id,organization_id,person_id,employee_code,state,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE',?,?)",
                    UUID.randomUUID(), org, person, "SYNTH_${identity}", time, time)
            }
            JdbcCompanyResponsibilityPersistence(jdbc).grant(CompanyResponsibilityKey.FINANCE_CASH_PREPARE, UUID.randomUUID(), org, maker, maker, UUID.randomUUID(), at)
            jdbc.update("""INSERT INTO approval_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,
                effective_from,active,created_by_user_id,created_at) VALUES (?,?,'SYNTH_CASHBOX_REVIEWER','USER',?,?,true,?,?)""",
                UUID.randomUUID(), org, reviewer, time, maker, time)
            jdbc.update("""INSERT INTO approval_policy_version(id,organization_id,policy_key,version_number,lifecycle_state,
                approval_required,step_mode,authority_key,reason_required,effective_from,created_by_user_id,created_at)
                VALUES (?,?,?,1,'ACTIVE',true,'SINGLE','SYNTH_CASHBOX_REVIEWER',true,?,?,?)""", UUID.randomUUID(), org, policyKey, time, maker, time)
        }
        fun input(): FinanceCashboxOpeningPrepare {
            val choices = service.custodians(maker, org, "custodian")
            val choice = choices.items.single()
            return FinanceCashboxOpeningPrepare(UUID.randomUUID(), currency = "EGP", custodianUserId = custodian,
                companyCustodyReference = "Synthetic company custody record", expectedCustodianVersion = choice.sourceVersion,
                expectedReviewVersion = requireNotNull(choices.reviewSourceVersion))
        }
        fun counter(): Long? = jdbc.queryForObject("SELECT last_issued FROM finance_cashbox_number_counter WHERE organization_id=?", Long::class.java, org)

        fun approve(proposal: FinanceCashboxOpeningStored, decisionActor: UUID): UUID {
            val request = UUID.randomUUID()
            val step = UUID.randomUUID()
            val assignment = UUID.randomUUID()
            val timestamp = at.atOffset(ZoneOffset.UTC)
            jdbc.update("""INSERT INTO approval_request(id,organization_id,subject_type,subject_id,subject_version,
                policy_key,policy_version,requester_user_id,state,reason_code,created_at,completed_at)
                VALUES (?,?,'FINANCE_CASHBOX_OPENING',?,?,?,1,?,'APPROVED','FINANCE_CASHBOX_OPENING_REVIEW',?,?)""",
                request, org, proposal.id, proposal.subjectVersion, policyKey, maker, timestamp, timestamp)
            jdbc.update("""INSERT INTO approval_step(id,approval_request_id,sequence_number,mode,authority_key,state)
                VALUES (?,?,1,'SINGLE','SYNTH_CASHBOX_REVIEWER','COMPLETED')""", step, request)
            jdbc.update("""INSERT INTO approval_assignment(id,approval_request_id,approval_step_id,principal_type,
                principal_user_id,state,assigned_at,acted_at) VALUES (?,?,?,'USER',?,'ACTED',?,?)""",
                assignment, request, step, decisionActor, timestamp, timestamp)
            jdbc.update("""INSERT INTO approval_decision(id,approval_request_id,approval_assignment_id,actor_user_id,
                decision,decided_at,subject_version,operation_id) VALUES (?,?,?,?,'APPROVE',?,?,?)""",
                UUID.randomUUID(), request, assignment, decisionActor, timestamp, proposal.subjectVersion, UUID.randomUUID())
            return request
        }
    }

    private fun database(action: (Fixture) -> Unit) {
        assumeTrue(System.getenv("HILTECH_RESPONSIBILITY_CONTRACT_TEST") == "1")
        DriverManager.getConnection(requireNotNull(System.getenv("HILTECH_DB_URL")), requireNotNull(System.getenv("HILTECH_DB_USER")),
            requireNotNull(System.getenv("HILTECH_DB_PASSWORD"))).use { connection ->
            val dataSource = SingleConnectionDataSource(connection, true)
            val manager = DataSourceTransactionManager(dataSource)
            TransactionTemplate(manager).execute { status ->
                try { action(Fixture(connection, JdbcTemplate(dataSource), manager)) }
                finally { status.setRollbackOnly() }
            }
        }
    }
}
