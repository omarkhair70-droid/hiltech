package com.hiltech.server.payroll

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class Payroll9bDraftPostgresContractTest {
    @Test
    fun draftsRequireApprovedPolicySourcePreserveRevisionsAndNeverApproveOrPay() {
        assumeTrue(System.getenv("HILTECH_PAYROLL_9A_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val username = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val path = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url, username, password)
            .locations("filesystem:" + path).load().migrate()

        val datasource = DriverManagerDataSource(url, username, password)
        val jdbc = JdbcTemplate(datasource)
        val tx = TransactionTemplate(DataSourceTransactionManager(datasource))
        val at = Instant.parse("2026-11-23T12:00:00Z")
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val actor = UUID.randomUUID()
        val org = UUID.randomUUID()
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val employment = UUID.randomUUID()
        val time = at.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name, display_name,
                organization_type, status, created_at
            ) VALUES (?, ?, 'Synthetic Legal', 'Synthetic Company', 'HILTECH', 'ACTIVE', ?)
            """.trimIndent(),
            org, "P9B_" + org.toString().substring(0, 12).uppercase(), time,
        )
        jdbc.update(
            """
            INSERT INTO user_identity (
                id, auth_provider, auth_subject, status, primary_organization_id, created_at
            ) VALUES (?, 'payroll-test', ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            actor, actor.toString(), org, time,
        )
        jdbc.update(
            "INSERT INTO person (id, display_name, created_at, updated_at) VALUES (?, 'Synthetic Staff', ?, ?)",
            person, time, time,
        )
        jdbc.update(
            """
            INSERT INTO employee (
                id, organization_id, person_id, employee_code, state,
                hire_date, created_at, updated_at
            ) VALUES (?, ?, ?, 'P9B_TEST', 'ACTIVE', ?, ?, ?)
            """.trimIndent(),
            employee, org, person, LocalDate.parse("2026-10-01"), time, time,
        )
        jdbc.update(
            """
            INSERT INTO employment (
                id, employee_id, employment_type_code, start_date, state,
                created_at, updated_at
            ) VALUES (?, ?, 'FULL_TIME', ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            employment, employee, LocalDate.parse("2026-10-01"), time, time,
        )
        val permission = object : Payroll9aAuthorityPort {
            override fun canRead(actorId: UUID, organizationId: UUID) =
                actorId == actor && organizationId == org
            override fun canPrepare(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
            override fun canReview(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
        }
        val a = Payroll9aService(jdbc, permission, AuditEventWriter.NOOP, clock)
        val periodId = tx.execute {
            UUID.fromString(a.create(
                actor, Payroll9aPeriodCommand(
                    UUID.randomUUID(), org, "P9B_2026_11",
                    LocalDate.parse("2026-11-01"),
                    LocalDate.parse("2026-11-30"),
                    LocalDate.parse("2026-11-22"), null,
                ), "p9b-test",
            ).first.period.periodId)
        }!!
        tx.execute { a.captureInputs(actor, periodId, "p9b-test") }

        val noSource = Payroll9bDraftService(
            jdbc, a, permission,
            StaticListableBeanFactory().getBeanProvider(PayrollApprovedInputSourcePort::class.java),
            AuditEventWriter.NOOP, clock,
        )
        assertThrows<ProductApiException> {
            tx.execute { noSource.create(periodId, UUID.randomUUID(), actor, "p9b-test") }
        }
        val policy = PayrollValidatedPolicy(
            organizationId = org, policyVersionId = UUID.randomUUID(),
            policyVersion = 1, currency = "EGP", fractionalDigits = 2,
            roundingMode = RoundingMode.HALF_EVEN,
            companyAuthorizationReference = "synthetic-p9b-policy",
            statutoryPolicyReference = "synthetic-statutory-reviewed",
            statutoryVerified = true,
            deductionLimitPolicyReference = null, deductionLimitsVerified = false,
        )
        val component = PayrollApprovedComponent(
            organizationId = org, employeeId = employee, componentCode = "SYNTHETIC_BASE",
            kind = PayrollComponentKind.EARNING,
            sourceDomain = "SYNTHETIC_FIXTURE", sourceId = UUID.randomUUID(),
            sourceVersion = 1, sourceApprovalReference = "SYNTHETIC_APPROVAL",
            approval = PayrollInputApproval.APPROVED,
            currency = "EGP", amount = "100.005",
        )
        val sources = object : PayrollApprovedInputSourcePort {
            override fun policyFor(organizationId: UUID, payrollPeriodId: UUID) = policy
            override fun approvedFor(
                organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID,
            ) = listOf(component)
        }
        val factory = StaticListableBeanFactory(mapOf("payrollSources" to sources))
        val service = Payroll9bDraftService(
            jdbc, a, permission,
            factory.getBeanProvider(PayrollApprovedInputSourcePort::class.java),
            AuditEventWriter.NOOP, clock,
        )
        val operation = UUID.randomUUID()
        val first = tx.execute { service.create(periodId, operation, actor, "p9b-test") }!!
        assertFalse(first.second)
        assertEquals("DRAFT", first.first.state)
        assertEquals(1, first.first.generation)
        assertEquals(1, first.first.lines.size)
        assertEquals("100.000000", first.first.net)
        assertFalse(first.first.approvalAllowed)
        assertFalse(first.first.paymentAllowed)
        val again = tx.execute { service.create(periodId, operation, actor, "p9b-test") }!!
        assertTrue(again.second)
        assertEquals(first.first.runVersionId, again.first.runVersionId)
        assertThrows<ProductApiException> {
            service.read(UUID.randomUUID(), UUID.fromString(first.first.runVersionId))
        }

        val newer = tx.execute {
            service.create(periodId, UUID.randomUUID(), actor, "p9b-test")
        }!!
        assertEquals(2, newer.first.generation)
        assertNotNull(newer.first.sourceFingerprint)
        assertThrows<Exception> {
            jdbc.update("UPDATE payroll_run_version SET gross = 999 WHERE id = ?",
                UUID.fromString(first.first.runVersionId))
        }
        assertThrows<Exception> {
            jdbc.update("DELETE FROM payroll_run_employee_line WHERE run_version_id = ?",
                UUID.fromString(first.first.runVersionId))
        }
        jdbc.update("UPDATE employee SET version = version + 1 WHERE id = ?", employee)
        assertThrows<ProductApiException> {
            tx.execute {
                service.create(periodId, UUID.randomUUID(), actor, "p9b-test")
            }
        }
    }
}
