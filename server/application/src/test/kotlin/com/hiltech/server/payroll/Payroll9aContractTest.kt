package com.hiltech.server.payroll

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class Payroll9aContractTest {
    @Test
    fun policyRejectsInventedDatesAndDetectsSourceMutation() {
        val command = Payroll9aPeriodCommand(
            operationId = UUID.randomUUID(),
            organizationId = UUID.randomUUID(),
            periodCode = "PAY_2026_NOV",
            startDate = LocalDate.parse("2026-11-01"),
            endDate = LocalDate.parse("2026-11-30"),
            cutoffDate = LocalDate.parse("2026-11-30"),
            targetPaymentDate = null,
        )
        Payroll9aPolicy.validatePeriod(command)
        assertThrows<ProductApiException> {
            Payroll9aPolicy.validatePeriod(command.copy(cutoffDate = LocalDate.parse("2026-12-01")))
        }
        assertThrows<ProductApiException> {
            Payroll9aPolicy.validatePeriod(command.copy(periodCode = "unsafe/currency"))
        }
        val assignment = UUID.randomUUID()
        assertFalse(
            Payroll9aPolicy.sourceChanged(
                3, 2, assignment, 4, 3, 2, assignment, 4,
            ),
        )
        assertTrue(
            Payroll9aPolicy.sourceChanged(
                3, 2, assignment, 4, 3, 2, UUID.randomUUID(), 4,
            ),
        )
        assertTrue(
            Payroll9aPolicy.sourceChanged(
                3, 2, assignment, 4, 4, 2, assignment, 4,
            ),
        )
    }


    @Test
    fun payrollAuthorityFailsClosedWithoutSourceMembershipAndOpenFga() {
        val actor = UUID.randomUUID()
        val organization = UUID.randomUUID()
        val now = Instant.parse("2026-11-22T20:00:00Z")
        val source = Mockito.mock(RoleTeamSourceAuthorityPort::class.java)
        val provider = StaticListableBeanFactory()
            .getBeanProvider(AuthorizationCheckPort::class.java)
        val permission = JdbcPayroll9aAuthority(
            jdbc = JdbcTemplate(),
            source = source,
            authorizationProvider = provider,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        assertFalse(permission.canRead(actor, organization))
        assertFalse(permission.canPrepare(actor, organization))
        assertFalse(permission.canReview(actor, organization))
        Mockito.`when`(
            source.isOrganizationMemberCurrent(actor, organization, now),
        ).thenReturn(true)
        // Current People/organization membership remains insufficient
        // without a positive OpenFGA check AND explicit payroll grant.
        assertFalse(permission.canRead(actor, organization))
        assertFalse(permission.canPrepare(actor, organization))
        assertFalse(permission.canReview(actor, organization))
    }

    @Test
    fun dataContractsDoNotExposeBankSalaryOrCalculation() {
        val fields = PayrollEmployeeInputDto::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("grossAmount" in fields)
        assertFalse("netAmount" in fields)
        assertFalse("bankAccount" in fields)
        assertFalse("baseSalary" in fields)
        assertFalse("taxRate" in fields)
        val detail = PayrollPeriodDetailDto(
            PayrollPeriodSummaryDto(
                periodId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
                periodCode = "TEST",
                startDate = "2026-11-01",
                endDate = "2026-11-30",
                cutoffDate = "2026-11-30",
                targetPaymentDate = null,
                state = "INPUTS_SNAPSHOTTED",
                version = 2,
                snapshotAt = "2026-11-22T00:00:00Z",
                employeeCount = 0,
                exceptionCount = 0,
            ),
            emptyList(),
        )
        assertFalse(detail.calculationAllowed)
        assertFalse(detail.approvalAllowed)
        assertFalse(detail.paymentAllowed)
    }

    @Test
    fun postgresSnapshotIsIsolatedImmutableAndReviewOnly() {
        assumeTrue(System.getenv("HILTECH_PAYROLL_9A_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val path = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:" + path)
            .load()
            .migrate()

        val source = DriverManagerDataSource(url, user, password)
        val jdbc = JdbcTemplate(source)
        val transaction = TransactionTemplate(DataSourceTransactionManager(source))
        val at = Instant.parse("2026-11-22T20:00:00Z")
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val actor = UUID.randomUUID()
        val orgOne = UUID.randomUUID()
        val orgTwo = UUID.randomUUID()

        seedOrganization(jdbc, orgOne, at)
        seedOrganization(jdbc, orgTwo, at)
        jdbc.update(
            """
            INSERT INTO user_identity (id, auth_provider, auth_subject, status,
                primary_organization_id, created_at)
            VALUES (?, 'payroll-test', ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            actor, actor.toString(), orgOne, at.atOffset(ZoneOffset.UTC),
        )
        val active = seedEmployee(jdbc, orgOne, "ACTIVE", null, at)
        val ended = seedEmployee(jdbc, orgOne, "FORMER", LocalDate.parse("2026-11-12"), at)
        val preboarding = seedEmployee(jdbc, orgOne, "PREBOARDING", null, at)
        val outsider = seedEmployee(jdbc, orgTwo, "ACTIVE", null, at)

        val authority = object : Payroll9aAuthorityPort {
            override fun canRead(actorId: UUID, organizationId: UUID) =
                actorId == actor && organizationId == orgOne
            override fun canPrepare(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
            override fun canReview(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
        }
        val service = Payroll9aService(jdbc, authority, AuditEventWriter.NOOP, clock)
        val command = Payroll9aPeriodCommand(
            operationId = UUID.randomUUID(),
            organizationId = orgOne,
            periodCode = "PAYROLL_2026_11",
            startDate = LocalDate.parse("2026-11-01"),
            endDate = LocalDate.parse("2026-11-30"),
            cutoffDate = LocalDate.parse("2026-11-22"),
            targetPaymentDate = null,
        )
        val created = transaction.execute { service.create(actor, command, "payroll-test") }!!
        assertFalse(created.second)
        assertEquals("OPEN", created.first.period.state)
        val replay = transaction.execute { service.create(actor, command, "payroll-test") }!!
        assertTrue(replay.second)
        // A second authorized Payroll preparer cannot hijack the same
        // request ID as an idempotent replay of another user's command.
        val secondPreparer = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO user_identity (id, auth_provider, auth_subject, status, primary_organization_id, created_at) " +
                "VALUES (?, 'payroll-test', ?, 'ACTIVE', ?, ?)",
            secondPreparer, secondPreparer.toString(), orgOne, at.atOffset(ZoneOffset.UTC),
        )
        val dualAuthority = object : Payroll9aAuthorityPort {
            override fun canRead(actorId: UUID, organizationId: UUID) =
                organizationId == orgOne && actorId in setOf(actor, secondPreparer)
            override fun canPrepare(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
            override fun canReview(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
        }
        val otherService = Payroll9aService(jdbc, dualAuthority, AuditEventWriter.NOOP, clock)
        assertThrows<ProductApiException> {
            transaction.execute { otherService.create(secondPreparer, command, "payroll-peer-replay") }
        }
        assertThrows<ProductApiException> {
            transaction.execute {
                service.create(actor, command.copy(endDate = LocalDate.parse("2026-11-29")), "payroll-test")
            }
        }
        assertThrows<ProductApiException> {
            service.list(actor, orgTwo)
        }
        val id = UUID.fromString(created.first.period.periodId)
        val captured = transaction.execute { service.captureInputs(actor, id, "payroll-test") }!!
        assertFalse(captured.second)
        assertEquals(2, captured.first.employees.size)
        assertTrue(captured.first.employees.any { it.employeeId == active.toString() })
        assertTrue(captured.first.employees.any { it.employeeId == ended.toString() })
        assertFalse(captured.first.employees.any { it.employeeId == preboarding.toString() })
        assertFalse(captured.first.employees.any { it.employeeId == outsider.toString() })
        assertTrue(captured.first.employees.all { it.exceptions.isNotEmpty() })
        assertTrue(captured.first.employees.all { it.exceptions.all { flag -> flag.blocksCalculation } })
        assertTrue(captured.first.employees.first { it.employeeId == ended.toString() }
            .exceptions.any { it.code == "OFFBOARDING_REQUIRES_REVIEW" })
        val duplicateSnapshot = transaction.execute { service.captureInputs(actor, id, "payroll-test") }!!
        assertTrue(duplicateSnapshot.second)
        assertEquals(2, duplicateSnapshot.first.employees.size)

        val flagId = UUID.fromString(captured.first.employees.first().exceptions.first().exceptionId)
        val reviewOperation = UUID.randomUUID()
        val firstReview = transaction.execute {
            service.review(actor, id, flagId, reviewOperation, "Needs verified source", "payroll-test")
        }!!
        assertFalse(firstReview.second)
        val duplicateReview = transaction.execute {
            service.review(actor, id, flagId, reviewOperation, "Needs verified source", "payroll-test")
        }!!
        assertTrue(duplicateReview.second)
        assertThrows<ProductApiException> {
            transaction.execute {
                service.review(actor, id, flagId, reviewOperation, "Different review", "payroll-test")
            }
        }
        // Reviewing an exception is never a settlement or a permission to calculate.
        assertFalse(service.detail(actor, id).calculationAllowed)
        assertTrue(service.detail(actor, id).employees.all {
            it.exceptions.all { flag -> flag.blocksCalculation }
        })

        jdbc.update("UPDATE employee SET version = version + 1 WHERE id = ?", active)
        assertTrue(service.detail(actor, id).employees.first { it.employeeId == active.toString() }.sourceChanged)
        assertThrows<Exception> {
            jdbc.update(
                "UPDATE payroll_employee_input_snapshot SET employee_code = 'FORGED' WHERE employee_id = ?",
                active,
            )
        }
        assertThrows<Exception> {
            jdbc.update(
                "DELETE FROM payroll_exception_review WHERE operation_id = ?",
                reviewOperation,
            )
        }


        // Payroll read/prepare/review permissions are source-scoped, explicit,
        // and immediately revoked by current membership/grant changes.
        val fgaAllowsOrgMembership = AuthorizationCheckPort { true }
        val authorizedBeans = StaticListableBeanFactory(
            mapOf("authorizationTest" to fgaAllowsOrgMembership),
        )
        val payrollPermission = JdbcPayroll9aAuthority(
            jdbc = jdbc,
            source = JdbcRoleTeamSourceAuthority(jdbc),
            authorizationProvider = authorizedBeans.getBeanProvider(AuthorizationCheckPort::class.java),
            clock = clock,
        )
        assertFalse(payrollPermission.canRead(actor, orgOne))
        val membershipId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO organization_membership (
                id, organization_id, user_identity_id, membership_type,
                state, valid_from
            ) VALUES (?, ?, ?, 'EMPLOYEE', 'ACTIVE', ?)
            """.trimIndent(),
            membershipId, orgOne, actor, at.minusSeconds(60).atOffset(ZoneOffset.UTC),
        )
        assertFalse(payrollPermission.canRead(actor, orgOne))
        val grantId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO payroll_authority_binding (
                id, organization_id, identity_id, capability,
                valid_from, active, source_approval_ref, created_at
            ) VALUES (?, ?, ?, 'PREPARE', ?, true, 'APPROVED_TEST_POLICY', ?)
            """.trimIndent(),
            grantId, orgOne, actor, at.minusSeconds(60).atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
        assertTrue(payrollPermission.canRead(actor, orgOne))
        assertTrue(payrollPermission.canPrepare(actor, orgOne))
        assertFalse(payrollPermission.canReview(actor, orgOne))
        assertFalse(payrollPermission.canRead(actor, orgTwo))
        jdbc.update(
            "UPDATE organization_membership SET state = 'ENDED' WHERE id = ?",
            membershipId,
        )
        assertFalse(payrollPermission.canRead(actor, orgOne))
        jdbc.update(
            "UPDATE organization_membership SET state = 'ACTIVE' WHERE id = ?",
            membershipId,
        )
        jdbc.update(
            "UPDATE payroll_authority_binding SET active = false, version = version + 1 WHERE id = ?",
            grantId,
        )
        assertFalse(payrollPermission.canRead(actor, orgOne))
    }

    private fun seedOrganization(jdbc: JdbcTemplate, id: UUID, at: Instant) {
        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name, display_name,
                organization_type, status, created_at
            ) VALUES (?, ?, 'Pay Test Legal', 'Pay Test', 'HILTECH', 'ACTIVE', ?)
            """.trimIndent(),
            id, "PAY9A_" + id.toString().take(12).uppercase().replace("-", ""),
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun seedEmployee(
        jdbc: JdbcTemplate,
        org: UUID,
        state: String,
        end: LocalDate?,
        at: Instant,
    ): UUID {
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val employment = UUID.randomUUID()
        val time = at.atOffset(ZoneOffset.UTC)
        val code = "PAY_" + employee.toString().take(12).uppercase().replace("-", "")
        jdbc.update(
            """
            INSERT INTO person (id, display_name, created_at, updated_at)
            VALUES (?, 'Synthetic Payroll Employee', ?, ?)
            """.trimIndent(),
            person, time, time,
        )
        jdbc.update(
            """
            INSERT INTO employee (
                id, organization_id, person_id, employee_code, state,
                hire_date, end_date, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            employee, org, person, code, state,
            LocalDate.parse("2026-10-01"), end, time, time,
        )
        jdbc.update(
            """
            INSERT INTO employment (
                id, employee_id, employment_type_code, start_date, end_date,
                state, created_at, updated_at
            ) VALUES (?, ?, 'FULL_TIME', ?, ?, ?, ?, ?)
            """.trimIndent(),
            employment, employee, LocalDate.parse("2026-10-01"), end,
            if (end == null) "ACTIVE" else "ENDED", time, time,
        )
        return employee
    }
}
