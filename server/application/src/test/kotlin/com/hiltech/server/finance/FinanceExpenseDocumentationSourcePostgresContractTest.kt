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

class FinanceExpenseDocumentationSourcePostgresContractTest {
    @Test fun realReadyReceiptChangesInvalidateOldPendingDocumentationReview() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val migrations = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url, user, password)
            .locations("filesystem:$migrations").load().migrate()

        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            val jdbc = JdbcTemplate(SingleConnectionDataSource(conn, true))
            val now = Instant.parse("2026-11-23T15:00:00Z")
            val at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
            val org = UUID.randomUUID()
            val person = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val employee = UUID.randomUUID()
            val expense = UUID.randomUUID()
            val policyKey = "FINANCE_EXPENSE_DOCUMENT_REVIEW"
            val approverId = UUID.randomUUID()
            val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
            val env = StandardEnvironment()
            env.propertySources.addFirst(MapPropertySource("integration", mapOf(
                "hiltech.finance.expenses.documentation-review.enabled" to true,
                "hiltech.finance.expenses.documentation-review.policy-key" to policyKey,
                "hiltech.finance.expense-receipts.canonical-upload-ready" to true,
                "hiltech.finance.expense-receipts.readiness-enabled" to true,
            )))
            val policy = ApprovalPolicySnapshot(
                organizationId = org, policyKey = policyKey,
                versionNumber = 4, approvalRequired = true,
                mode = ApprovalPolicyMode.SINGLE, authorityKey = "FINANCE_REVIEWER",
                reasonRequired = false,
            )
            val authority = ApprovalAuthorityBindingSnapshot(
                id = UUID.randomUUID(), organizationId = org,
                authorityKey = "FINANCE_REVIEWER", principalType = ApprovalPrincipalType.USER,
                principalId = approverId, effectiveFrom = now.minusSeconds(7200),
                effectiveTo = null, version = 1,
            )
            Mockito.`when`(approvals.loadActivePolicy(org, policyKey, now))
                .thenReturn(policy)
            Mockito.`when`(approvals.resolveCurrentAuthority(org, "FINANCE_REVIEWER", now))
                .thenReturn(authority)
            Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, now))
                .thenReturn(true)

            val subject = FinanceExpenseDocumentationApprovalSubject(
                jdbc, approvals, env, Clock.fixed(now, ZoneOffset.UTC),
            )
            val hash = "a".repeat(64)
            val date = "'$at'"
            fun exec(sql: String) = jdbc.execute(sql.trimIndent())

            try {
                exec("""
                    INSERT INTO organization(
                        id, organization_code, legal_name, display_name,
                        organization_type, status, created_at)
                    VALUES ('$org','T_${org.toString().take(12)}',
                        'Test finance documentation','Test finance documentation',
                        'HILTECH','ACTIVE',$date)
                """)
                exec("""
                    INSERT INTO person(id,display_name,created_at,updated_at)
                    VALUES ('$person','Finance employee',$date,$date)
                """)
                exec("""
                    INSERT INTO user_identity(
                        id,auth_provider,auth_subject,person_id,status,created_at)
                    VALUES ('$actor','contract-test','${UUID.randomUUID()}',
                        '$person','ACTIVE',$date)
                """)
                exec("""
                    INSERT INTO employee(
                        id,organization_id,person_id,employee_code,state,created_at,updated_at)
                    VALUES ('$employee','$org','$person','EMP_${employee.toString().take(8)}',
                        'ACTIVE',$date,$date)
                """)
                exec("""
                    INSERT INTO employment(
                        id,employee_id,start_date,state,created_at,updated_at)
                    VALUES ('${UUID.randomUUID()}','$employee',
                        '${at.minusDays(2).toLocalDate()}','ACTIVE',$date,$date)
                """)
                exec("""
                    INSERT INTO organization_membership(
                        id,organization_id,user_identity_id,membership_type,
                        state,valid_from)
                    VALUES ('${UUID.randomUUID()}','$org','$actor','EMPLOYEE',
                        'ACTIVE','${at.minusDays(2)}')
                """)
                exec("""
                    INSERT INTO finance_expense_claim_draft(
                        id,operation_id,organization_id,employee_id,amount,
                        currency,purpose,merchant,spent_on,created_by_user_id,
                        created_at,correlation_id)
                    VALUES ('$expense','${UUID.randomUUID()}','$org','$employee',
                        150,'EGP','Consumables for project','Merchant',
                        '${at.toLocalDate()}','$actor',$date,'review-evidence-test')
                """)

                fun addReceipt(value: String): UUID {
                    val e = UUID.randomUUID()
                    val s = UUID.randomUUID()
                    exec("""
                        INSERT INTO evidence(
                            id,organization_id,target_type,target_id,
                            evidence_requirement_key,evidence_type_code,
                            content_type,original_file_name,size_bytes,sha256,
                            captured_at,captured_by_user_id,storage_state,
                            object_key_ref,finalized_at,classification_code,
                            client_visibility_mode,security_scan_class,created_at)
                        VALUES ('$e','$org','FINANCE_EXPENSE_RECEIPT','$expense',
                            'FINANCE_EXPENSE_RECEIPT_BINARY','FINANCE_EXPENSE_RECEIPT',
                            'application/pdf','receipt.pdf',60,'$value',$date,'$actor',
                            'READY','evidence/internal/receipt',$date,
                            'HIGHLY_RESTRICTED','INTERNAL_ONLY','SIGNED_DOCUMENT',$date)
                    """)
                    exec("""
                        INSERT INTO evidence_upload_session(
                            id,evidence_id,expected_sha256,expected_size_bytes,expires_at,
                            state,created_at,finalized_at,operation_id)
                        VALUES ('$s','$e','$value',60,'${at.plusHours(2)}',
                            'READY',$date,$date,'${UUID.randomUUID()}')
                    """)
                    return e
                }

                assertEquals(null, subject.current(org, expense))
                val receiptOne = addReceipt(hash)
                val initial = requireNotNull(subject.current(org, expense))
                assertNotNull(initial)
                assertEquals(listOf(receiptOne), initial.verifiedEvidenceIds)
                assertEquals("150", initial.amount)
                val original = ApprovalSubjectRef(
                    org, FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT,
                    expense, initial.sourceVersion,
                )
                assertTrue(subject.isCurrent(original))

                val receiptTwo = addReceipt("b".repeat(64))
                val latest = requireNotNull(subject.current(org, expense))
                assertNotNull(latest)
                assertEquals(2, latest.verifiedEvidenceIds.size)
                assertTrue(latest.verifiedEvidenceIds.contains(receiptTwo))
                assertNotEquals(initial.sourceVersion, latest.sourceVersion)
                assertFalse(subject.isCurrent(original))
                assertTrue(subject.isCurrent(ApprovalSubjectRef(
                    org, FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT,
                    expense, latest.sourceVersion,
                )))
                Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, now))
                    .thenReturn(false)
                assertEquals(null, subject.current(org, expense))
            } finally {
                conn.rollback()
            }
        }
    }
}
