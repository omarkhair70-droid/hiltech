package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Runs only on disposable PostgreSQL; installs UNNUMBERED schema in a
 * rollback transaction, never writes production Flyway history.
 */
class FinanceApLedgerSchemaPostgresContractTest {
    @Test fun exactLedgerDdlAndImmutableCompanyAccountsCannotBeSqlOverwritten() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val pass = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val migrations = System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"
        Flyway.configure().dataSource(url, user, pass)
            .locations("filesystem:$migrations").load().migrate()
        val schema = System.getenv("HILTECH_AP_LEDGER_SQL")
            ?: "database/phase8/pending_ap_ledger_posting.sql"
        val ddl = Files.readString(Path.of(schema))
        assertTrue(ddl.contains("finance_ap_posted_journal"))
        assertTrue(ddl.contains("approval_decision"))
        DriverManager.getConnection(url, user, pass).use { connection ->
            connection.autoCommit = false
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            try {
                connection.createStatement().use { it.execute(ddl) }
                for (table in listOf(
                    "finance_gl_account", "finance_gl_period",
                    "finance_ap_posting_proposal", "finance_ap_posting_proposal_line",
                    "finance_ap_posted_journal", "finance_ap_posted_journal_line",
                )) {
                    assertNotNull(jdbc.queryForObject(
                        "SELECT to_regclass('public.$table')", String::class.java,
                    ), "Missing required unnumbered accounting table $table")
                }
                val org = UUID.randomUUID()
                val account = UUID.randomUUID()
                val period = UUID.randomUUID()
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                jdbc.update(
                    """
                    INSERT INTO organization (
                       id, organization_code,legal_name,display_name,
                       organization_type,status,created_at
                    ) VALUES (?, ?, ?, ?, 'HILTECH','ACTIVE', ?)
                    """.trimIndent(),
                    org,"T_${org.toString().take(12)}",
                    "Finance ledger contract","Finance ledger contract",now,
                )
                jdbc.update(
                    """
                    INSERT INTO finance_gl_account (
                      id,organization_id,account_code,account_name,account_kind,
                      currency,state,approved_company_chart_reference,created_at
                    ) VALUES (?,?,?,?,?,'EGP','APPROVED',?,?)
                    """.trimIndent(),
                    account,org,"AP-CONTRACT-1","Test payable",
                    "SUPPLIER_PAYABLE","CHART-APPROVED-CONTRACT",now,
                )
                jdbc.update(
                    """
                    INSERT INTO finance_gl_period (
                       id, organization_id,date_start,date_end,state,
                       approved_company_period_reference,created_at
                    ) VALUES (?,?,'2026-01-01','2026-12-31',
                        'OPEN','PERIOD-APPROVED-CONTRACT',?)
                    """.trimIndent(),
                    period,org,now,
                )
                assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM finance_gl_account WHERE id=? AND state='APPROVED'",
                    Int::class.java, account,
                ))
                assertThrows(SQLException::class.java) {
                    connection.prepareStatement(
                        "UPDATE finance_gl_account SET account_name='Forged' WHERE id=?",
                    ).use { s -> s.setObject(1,account); s.executeUpdate() }
                }
                // Failed SQL aborts this disposable transaction; never commit.
            } finally {
                connection.rollback()
            }
        }
    }
}
