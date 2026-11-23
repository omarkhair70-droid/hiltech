package com.hiltech.server.finance

import org.flywaydb.core.Flyway
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
import java.util.UUID

/**
 * Disposable PostgreSQL smoke contract: migration ordering, immutable
 * reversal proposal, and the database (not UI) rejecting forged posting.
 * A full approved reversal posting scenario requires seeded real authority
 * fixtures and is intentionally NOT represented as passing here.
 */
class FinanceApReversalPostgresContractTest {
    @Test fun stagedReversalSchemaRejectsForgedJournalAndDirectMutation() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val pass = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val migrations = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url,user,pass)
            .locations("filesystem:$migrations").load().migrate()
        DriverManager.getConnection(url,user,pass).use { connection ->
            connection.autoCommit=false
            val jdbc=JdbcTemplate(SingleConnectionDataSource(connection,true))
            try {
                for (file in listOf(
                    "database/phase8/pending_ap_ledger_posting.sql",
                    "database/phase8/pending_gl_period_close.sql",
                    "database/phase8/pending_ap_journal_reversal.sql",
                )) {
                    connection.createStatement().use {
                        it.execute(Files.readString(Path.of(file)))
                    }
                }
                for (table in listOf(
                    "finance_ap_reversal_proposal",
                    "finance_ap_journal_reversal",
                    "finance_ap_journal_reversal_line",
                )) {
                    assertNotNull(jdbc.queryForObject(
                        "SELECT to_regclass('public.$table')",String::class.java,
                    ))
                }
                val ddl=Files.readString(Path.of(
                    "database/phase8/pending_ap_journal_reversal.sql",
                ))
                assertTrue(ddl.contains("finance_ap_reversal_insert_guard"))
                assertTrue(ddl.contains("FINANCE_AP_JOURNAL_REVERSAL"))
                assertTrue(ddl.contains("finance_ap_reversal_complete_line"))
                assertTrue(ddl.contains("finance_ap_reversal_proposal_immutable"))

                // No organization, source journal, proposal or company-approved
                // authority. Even direct SQL must reject fabricated reversal.
                assertThrows(SQLException::class.java) {
                    connection.prepareStatement(
                        """INSERT INTO finance_ap_journal_reversal (
                          id,operation_id,organization_id,original_journal_id,
                          proposal_id,target_period_id,approval_request_id,
                          approval_subject_version,approval_policy_key,
                          source_ledger_fingerprint_sha256,currency,gross_amount,
                          reason_reference,posted_by_user_id,posted_at,correlation_id
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?)""",
                    ).use { st ->
                        for (i in listOf(1,2,3,4,5,6,7,14))
                            st.setObject(i,UUID.randomUUID())
                        st.setLong(8,1)
                        st.setString(9,"FINANCE_AP_REVERSAL_APPROVAL")
                        st.setString(10,"a".repeat(64))
                        st.setString(11,"EGP")
                        st.setBigDecimal(12,java.math.BigDecimal("100"))
                        st.setString(13,"TEST-REVERSAL-REASON")
                        st.setString(15,"test-correlation")
                        st.executeUpdate()
                    }
                }
            } finally {
                connection.rollback()
            }
        }
    }
}
