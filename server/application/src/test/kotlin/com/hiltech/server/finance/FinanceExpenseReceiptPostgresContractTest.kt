package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

/**
 * Actual disposable PostgreSQL and canonical Evidence metadata test.
 * Does NOT simulate real accepted upload bytes, scanned content or bank payment.
 */
class FinanceExpenseReceiptPostgresContractTest {
    @Test fun onlyExactCurrentOrgCreatorAndReadyCanonicalReceiptCanBeCounted() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val pass = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val dir = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url, user, pass)
            .locations("filesystem:$dir").load().migrate()

        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.autoCommit = false
            val jdbc = JdbcTemplate(SingleConnectionDataSource(conn, true))
            val clock = Clock.fixed(Instant.parse("2026-11-23T14:00:00Z"), ZoneOffset.UTC)
            val environment = StandardEnvironment()
            environment.propertySources.addFirst(MapPropertySource("receipt-integration-test",
                mapOf(
                    "hiltech.finance.expense-receipts.readiness-enabled" to true,
                    "hiltech.finance.expense-receipts.canonical-upload-ready" to true,
                )))
            val service = FinanceExpenseReceiptReadinessService(
                jdbc,
                Mockito.mock(FinanceEmployeeDraftService::class.java),
                Mockito.mock(FinanceEmployeeReviewService::class.java),
                clock, environment,
            )
            val org = UUID.randomUUID()
            val other = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val someoneElse = UUID.randomUUID()
            val expense = UUID.randomUUID()
            val hash = "a".repeat(64)
            val at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            val before = at.minusDays(30)
            val d = "'$at'"
            val prev = "'$before'"
            fun sql(query: String) { jdbc.execute(query) }
            try {
                sql("""
                    INSERT INTO organization(
                        id,organization_code,legal_name,display_name,
                        organization_type,status,created_at)
                    VALUES ('$org','T_${org.toString().take(12)}',
                        'Expense test company','Expense test company','HILTECH','ACTIVE',$d),
                        ('$other','T_${other.toString().take(12)}',
                        'Different company','Different company','OTHER','ACTIVE',$d)
                """.trimIndent())
                val person = UUID.randomUUID()
                sql("""
                    INSERT INTO person(id,display_name,created_at,updated_at)
                    VALUES ('$person','Expense test employee',$d,$d)
                """.trimIndent())
                sql("""
                    INSERT INTO user_identity(
                        id,auth_provider,auth_subject,person_id,status,created_at)
                    VALUES ('$actor','contract-test','${UUID.randomUUID()}',
                        '$person','ACTIVE',$d),
                        ('$someoneElse','contract-test','${UUID.randomUUID()}',
                        '$person','ACTIVE',$d)
                """.trimIndent())
                val employee = UUID.randomUUID()
                sql("""
                    INSERT INTO employee(
                        id,organization_id,person_id,employee_code,state,
                        created_at,updated_at)
                    VALUES ('$employee','$org','$person','EMP_${employee.toString().take(8)}',
                        'ACTIVE',$d,$d)
                """.trimIndent())
                sql("""
                    INSERT INTO employment(id,employee_id,start_date,state,
                        created_at,updated_at)
                    VALUES ('${UUID.randomUUID()}','$employee',
                        '${before.toLocalDate()}','ACTIVE',$d,$d)
                """.trimIndent())
                sql("""
                    INSERT INTO organization_membership(id,organization_id,
                        user_identity_id,membership_type,state,valid_from)
                    VALUES ('${UUID.randomUUID()}','$org','$actor','EMPLOYEE','ACTIVE',$prev)
                """.trimIndent())
                sql("""
                    INSERT INTO finance_expense_claim_draft(id,operation_id,
                        organization_id,employee_id,amount,currency,purpose,merchant,
                        spent_on,created_by_user_id,created_at,correlation_id)
                    VALUES ('$expense','${UUID.randomUUID()}','$org','$employee',
                        125.50,'EGP','Personal consumables','Merchant A',
                        '${at.toLocalDate()}','$actor',$d,'expense-test')
                """.trimIndent())

                fun insertEvidence(
                    targetOrg: UUID = org,
                    targetDraft: UUID = expense,
                    creator: UUID = actor,
                    state: String = "READY",
                    storedHash: String = hash,
                    scanClass: String = "SIGNED_DOCUMENT",
                ): UUID {
                    val evidence = UUID.randomUUID()
                    val upload = UUID.randomUUID()
                    sql("""
                        INSERT INTO evidence(
                            id,organization_id,target_type,target_id,
                            evidence_requirement_key,evidence_type_code,
                            content_type,original_file_name,size_bytes,sha256,
                            captured_at,captured_by_user_id,storage_state,
                            object_key_ref,finalized_at,classification_code,
                            client_visibility_mode,security_scan_class,created_at)
                        VALUES ('$evidence','$targetOrg','FINANCE_EXPENSE_RECEIPT',
                            '$targetDraft','FINANCE_EXPENSE_RECEIPT_BINARY',
                            'FINANCE_EXPENSE_RECEIPT','application/pdf',
                            'expense-receipt.pdf',80,'$storedHash',$d,
                            '$creator','$state','evidence/internal/receipt',$d,
                            'HIGHLY_RESTRICTED','INTERNAL_ONLY','$scanClass',$d)
                    """.trimIndent())
                    sql("""
                        INSERT INTO evidence_upload_session(
                            id,evidence_id,expected_sha256,expected_size_bytes,
                            expires_at,state,created_at,finalized_at,operation_id)
                        VALUES ('$upload','$evidence','$hash',80,
                            '${at.plusHours(1)}','$state',$d,$d,'${UUID.randomUUID()}')
                    """.trimIndent())
                    return evidence
                }

                // No binary documents invented: this is only a relational
                // source query contract against transaction-local fixtures.
                assertEquals(0, service.checkExpenseEvidence(
                    expense, org, "125.50", "EGP", actor).verifiedReceiptCount)
                val good = insertEvidence()
                insertEvidence(targetOrg = other)
                insertEvidence(targetDraft = UUID.randomUUID())
                insertEvidence(creator = someoneElse)
                insertEvidence(state = "QUARANTINED")
                insertEvidence(storedHash = "b".repeat(64))
                insertEvidence(scanClass = "ARBITRARY_FILE")
                val result = service.checkExpenseEvidence(
                    expense, org, "125.50", "EGP", actor,
                )
                assertTrue(result.uploadedReceiptReady)
                assertEquals(1, result.verifiedReceiptCount)
                assertEquals(listOf(good), result.verifiedReceiptEvidenceIds)
                assertFalse(result.reviewerActionAllowed)
                assertFalse(result.paymentActionAllowed)
                assertTrue(result.blockingReasons.contains(
                    "EXPENSE_REVIEW_AND_APPROVAL_POLICY_NOT_BOUND",
                ))
            } finally {
                conn.rollback()
            }
        }
    }
}
