package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalSubjectRef
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class FinanceMoneyDraftPolicySourcePostgresContractTest {
    @Test fun advanceAndCompanyImprestStayIndependentAndPolicyRevisionInvalidatesPriorApproval() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val pass = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val migrationPath = System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"
        Flyway.configure().dataSource(url, user, pass)
            .locations("filesystem:$migrationPath").load().migrate()

        DriverManager.getConnection(url, user, pass).use { connection ->
            connection.autoCommit = false
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            val now = Instant.parse("2026-11-23T15:00:00Z")
            val at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
            val org = UUID.randomUUID()
            val person = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val employee = UUID.randomUUID()
            val advanceId = UUID.randomUUID()
            val imprestId = UUID.randomUUID()
            val approver = UUID.randomUUID()
            val policyAdvance = "FINANCE_ADVANCE_TEST_REVIEW"
            val policyImprest = "FINANCE_IMPREST_TEST_REVIEW"
            val authorityAdvance = "FINANCE_ADVANCE_REVIEWER"
            val authorityImprest = "FINANCE_IMPREST_REVIEWER"
            val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
            val environment = StandardEnvironment()
            environment.propertySources.addFirst(MapPropertySource("finance-pg",
                mapOf(
                    "hiltech.finance.employee-requests.enabled" to true,
                    "hiltech.finance.advances.policy-review.enabled" to true,
                    "hiltech.finance.imprests.policy-review.enabled" to true,
                    "hiltech.finance.advances.policy-review.policy-key" to policyAdvance,
                    "hiltech.finance.imprests.policy-review.policy-key" to policyImprest,
                )))
            val authorityA = ApprovalAuthorityBindingSnapshot(
                UUID.randomUUID(), org, authorityAdvance,
                ApprovalPrincipalType.USER, approver, now.minusSeconds(3600),
                null, 1L,
            )
            val authorityI = ApprovalAuthorityBindingSnapshot(
                UUID.randomUUID(), org, authorityImprest,
                ApprovalPrincipalType.USER, approver, now.minusSeconds(3600),
                null, 1L,
            )
            fun policy(key: String, reviewer: String, revision: Int) =
                ApprovalPolicySnapshot(
                    organizationId = org, policyKey = key,
                    versionNumber = revision, approvalRequired = true,
                    mode = ApprovalPolicyMode.SINGLE,
                    authorityKey = reviewer, reasonRequired = false,
                )
            Mockito.`when`(approvals.loadActivePolicy(org, policyAdvance, now))
                .thenReturn(policy(policyAdvance, authorityAdvance, 1))
            Mockito.`when`(approvals.loadActivePolicy(org, policyImprest, now))
                .thenReturn(policy(policyImprest, authorityImprest, 2))
            Mockito.`when`(approvals.resolveCurrentAuthority(
                org, authorityAdvance, now)).thenReturn(authorityA)
            Mockito.`when`(approvals.resolveCurrentAuthority(
                org, authorityImprest, now)).thenReturn(authorityI)
            Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, now))
                .thenReturn(true)

            val subject = FinanceMoneyDraftPolicyApprovalSubject(
                jdbc, approvals, environment, Clock.fixed(now, ZoneOffset.UTC),
            )
            val date = "'$at'"
            fun sql(statement: String) = jdbc.execute(statement.trimIndent())
            try {
                sql("""
                    INSERT INTO organization(
                        id,organization_code,legal_name,display_name,
                        organization_type,status,created_at)
                    VALUES ('$org','T_${org.toString().take(12)}',
                        'Finance Review Test','Finance Review Test','HILTECH','ACTIVE',$date)
                """)
                sql("""
                    INSERT INTO person(id,display_name,created_at,updated_at)
                    VALUES ('$person','Finance employee',$date,$date)
                """)
                sql("""
                    INSERT INTO user_identity(
                        id,auth_provider,auth_subject,person_id,status,created_at)
                    VALUES ('$actor','contract-test','${UUID.randomUUID()}',
                        '$person','ACTIVE',$date)
                """)
                sql("""
                    INSERT INTO employee(
                        id,organization_id,person_id,employee_code,state,
                        created_at,updated_at)
                    VALUES ('$employee','$org','$person',
                        'EMP_${employee.toString().take(8)}',
                        'ACTIVE',$date,$date)
                """)
                sql("""
                    INSERT INTO employment(
                        id,employee_id,start_date,state,created_at,updated_at)
                    VALUES ('${UUID.randomUUID()}','$employee',
                        '${at.minusDays(2).toLocalDate()}','ACTIVE',$date,$date)
                """)
                sql("""
                    INSERT INTO organization_membership(
                        id,organization_id,user_identity_id,membership_type,
                        state,valid_from)
                    VALUES ('${UUID.randomUUID()}','$org','$actor','EMPLOYEE',
                        'ACTIVE','${at.minusDays(2)}')
                """)
                sql("""
                    INSERT INTO finance_employee_advance_draft(
                        id,operation_id,organization_id,employee_id,
                        requested_amount,currency,purpose,created_by_user_id,
                        created_at,correlation_id)
                    VALUES ('$advanceId','${UUID.randomUUID()}','$org','$employee',
                        800,'EGP','Employee advance for work','$actor',$date,
                        'test-company-advance')
                """)
                sql("""
                    INSERT INTO finance_financial_imprest_draft(
                        id,operation_id,organization_id,custodian_employee_id,
                        requested_amount,currency,purpose,created_by_user_id,
                        created_at,correlation_id)
                    VALUES ('$imprestId','${UUID.randomUUID()}','$org','$employee',
                        2500,'EGP','Company custody for project','$actor',$date,
                        'test-company-imprest')
                """)

                val advanceKind = FinanceEmployeeMoneyDraftKind.ADVANCE
                val imprestKind = FinanceEmployeeMoneyDraftKind.IMPREST
                val advance = requireNotNull(
                    subject.current(advanceKind, org, advanceId),
                )
                val imprest = requireNotNull(
                    subject.current(imprestKind, org, imprestId),
                )
                assertEquals(advanceKind, advance.kind)
                assertEquals(imprestKind, imprest.kind)
                assertEquals(policyAdvance, advance.policyKey)
                assertEquals(policyImprest, imprest.policyKey)
                assertEquals("800", advance.amount.toPlainString())
                assertEquals("2500", imprest.amount.toPlainString())
                assertNotEquals(advance.sourceVersion, imprest.sourceVersion)
                assertNull(subject.current(advanceKind, org, imprestId))
                assertNull(subject.current(imprestKind, org, advanceId))
                val original = ApprovalSubjectRef(
                    org, advanceKind.subjectType, advanceId, advance.sourceVersion,
                )
                assertTrue(subject.isCurrent(original))
                Mockito.`when`(approvals.loadActivePolicy(org, policyAdvance, now))
                    .thenReturn(policy(policyAdvance, authorityAdvance, 3))
                assertFalse(subject.isCurrent(original))
                val changed = requireNotNull(
                    subject.current(advanceKind, org, advanceId),
                )
                assertNotEquals(advance.sourceVersion, changed.sourceVersion)
                assertNotNull(changed)
                Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, now))
                    .thenReturn(false)
                assertNull(subject.current(imprestKind, org, imprestId))
            } finally {
                connection.rollback()
            }
        }
    }
}
