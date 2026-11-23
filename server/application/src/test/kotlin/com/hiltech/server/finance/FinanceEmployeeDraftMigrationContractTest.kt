package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class FinanceEmployeeDraftMigrationContractTest {
    @Test fun threeTypesStaySeparateCurrentEmployeeBoundAndImmutable() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val pass = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val dir = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url, user, pass)
            .locations("filesystem:$dir").load().migrate()

        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.autoCommit = false
            val org = UUID.randomUUID()
            val foreignOrg = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val person = UUID.randomUUID()
            val employee = UUID.randomUUID()
            val current = OffsetDateTime.now(ZoneOffset.UTC)
            val prev = current.minusDays(30)
            val started = LocalDate.now().minusDays(25)
            conn.createStatement().use { stmt ->
                fun exec(sql: String) { stmt.executeUpdate(sql) }
                val d = "'$current'"
                val p = "'$prev'"
                exec("""
                    INSERT INTO organization
                      (id,organization_code,legal_name,display_name,organization_type,status,created_at)
                    VALUES ('$org','TEST_${org.toString().take(12)}','Test company','Test company','HILTECH','ACTIVE',$d),
                      ('$foreignOrg','TEST_${foreignOrg.toString().take(12)}',
                        'Other test company','Other test company','OTHER','ACTIVE',$d)
                """.trimIndent())
                exec("""
                    INSERT INTO person(id,display_name,created_at,updated_at)
                    VALUES ('$person','Test employee',$d,$d)
                """.trimIndent())
                exec("""
                    INSERT INTO user_identity
                      (id,auth_provider,auth_subject,person_id,status,created_at)
                    VALUES ('$actor','contract-test','${UUID.randomUUID()}',
                        '$person','ACTIVE',$d)
                """.trimIndent())
                exec("""
                    INSERT INTO employee
                      (id,organization_id,person_id,employee_code,state,created_at,updated_at)
                    VALUES ('$employee','$org','$person','EMP_TEST','ACTIVE',$d,$d)
                """.trimIndent())
                exec("""
                    INSERT INTO employment
                      (id,employee_id,start_date,state,created_at,updated_at)
                    VALUES ('${UUID.randomUUID()}','$employee','$started','ACTIVE',$d,$d)
                """.trimIndent())
                exec("""
                    INSERT INTO organization_membership
                      (id,organization_id,user_identity_id,membership_type,state,valid_from)
                    VALUES ('${UUID.randomUUID()}','$org','$actor','EMPLOYEE','ACTIVE',$p)
                """.trimIndent())

                val expense = UUID.randomUUID()
                val invalid = """
                    INSERT INTO finance_expense_claim_draft
                      (id,operation_id,organization_id,employee_id,amount,currency,
                        purpose,merchant,spent_on,created_by_user_id,created_at,correlation_id)
                    VALUES ('${UUID.randomUUID()}','${UUID.randomUUID()}','$foreignOrg',
                      '$employee',125,'EGP','Foreign claim','Vendor X',
                      '$started','$actor',$d,'cross-org-contract')
                """.trimIndent()
                val point = conn.setSavepoint()
                val denied = assertThrows<SQLException> { exec(invalid) }
                assertEquals("23514", denied.sqlState)
                conn.rollback(point)

                exec("""
                    INSERT INTO finance_expense_claim_draft
                      (id,operation_id,organization_id,employee_id,amount,currency,
                        purpose,merchant,spent_on,created_by_user_id,created_at,correlation_id)
                    VALUES ('$expense','${UUID.randomUUID()}','$org',
                      '$employee',125,'EGP','Personal supplies','Vendor X',
                      '$started','$actor',$d,'valid-expense-contract')
                """.trimIndent())
                exec("""
                    INSERT INTO finance_employee_advance_draft
                      (id,operation_id,organization_id,employee_id,requested_amount,
                       currency,purpose,created_by_user_id,created_at,correlation_id)
                    VALUES ('${UUID.randomUUID()}','${UUID.randomUUID()}','$org',
                      '$employee',450,'EGP','Employee advance request',
                      '$actor',$d,'valid-advance-contract')
                """.trimIndent())
                exec("""
                    INSERT INTO finance_financial_imprest_draft
                      (id,operation_id,organization_id,custodian_employee_id,
                       requested_amount,currency,purpose,created_by_user_id,created_at,correlation_id)
                    VALUES ('${UUID.randomUUID()}','${UUID.randomUUID()}','$org',
                      '$employee',700,'EGP','Company site spending',
                      '$actor',$d,'valid-imprest-contract')
                """.trimIndent())

                val names = listOf(
                    "finance_expense_claim_draft",
                    "finance_employee_advance_draft",
                    "finance_financial_imprest_draft",
                )
                names.forEach { table ->
                    stmt.executeQuery("SELECT count(*) FROM $table WHERE organization_id='$org'").use {
                        assertTrue(it.next())
                        assertEquals(1, it.getInt(1))
                    }
                }
                stmt.executeQuery(
                    "SELECT state FROM finance_financial_imprest_draft WHERE organization_id='$org'",
                ).use {
                    assertTrue(it.next())
                    assertEquals("DRAFT_CUSTODY_POLICY_PENDING", it.getString(1))
                }

                val immutablePoint = conn.setSavepoint()
                val editDenied = assertThrows<SQLException> {
                    exec("UPDATE finance_expense_claim_draft SET amount=999 WHERE id='$expense'")
                }
                assertEquals("23514", editDenied.sqlState)
                conn.rollback(immutablePoint)
            }
            conn.rollback() // no test person/company/cash persists
        }
    }
}
