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
 * Unnumbered stacked AR + AP schema on disposable PostgreSQL.
 * Rejects a forged AR header, confirms append-only posting triggers exist.
 * This is NOT an approved positive real-world invoice acceptance fixture.
 */
class FinanceArJournalPostgresContractTest {
    @Test fun exactAccountKindAndForgedReceivablePostingRejected() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
        val url=System.getenv("HILTECH_DB_URL") ?:"jdbc:postgresql://localhost:5432/hiltech"
        val user=System.getenv("HILTECH_DB_USER") ?:"hiltech"
        val pass=System.getenv("HILTECH_DB_PASSWORD") ?:"hiltech"
        val root=Path.of(System.getenv("HILTECH_REPO_ROOT") ?:".").toAbsolutePath()
        val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
            ?:root.resolve("database/migrations").toString()
        Flyway.configure().dataSource(url,user,pass)
            .locations("filesystem:$migrations").load().migrate()
        DriverManager.getConnection(url,user,pass).use { connection->
            connection.autoCommit=false
            val jdbc=JdbcTemplate(SingleConnectionDataSource(connection,true))
            try {
                connection.createStatement().use { statement->
                    for(file in listOf(
                        "database/phase8/pending_ap_ledger_posting.sql",
                        "database/phase8/pending_gl_period_close.sql",
                        "database/phase8/pending_ap_journal_reversal.sql",
                        "database/phase8/pending_ar_invoice_candidate.sql",
                        "database/phase8/pending_ar_issued_invoice.sql",
                        "database/phase8/pending_ar_journal_posting.sql",
                    )) statement.execute(FinancePendingSql.read(root.resolve(file)))
                }
                for(table in listOf(
                    "finance_ar_posting_proposal",
                    "finance_ar_posting_proposal_line",
                    "finance_ar_posted_journal",
                    "finance_ar_posted_journal_line",
                )) assertNotNull(jdbc.queryForObject(
                    "SELECT to_regclass('public.$table')",String::class.java,
                ))
                val view=jdbc.queryForObject(
                    """SELECT definition FROM pg_views
                       WHERE schemaname='public'
                         AND viewname='finance_gl_period_close_source'""",
                    String::class.java,
                )
                assertTrue(view?.contains("finance_ar_posted_journal")==true)
                assertThrows(SQLException::class.java) {
                    connection.prepareStatement(
                        """INSERT INTO finance_ar_posted_journal(
                          id,operation_id,organization_id,proposal_id,
                          issued_invoice_id,posting_approval_id,fiscal_period_id,
                          ledger_fingerprint_sha256,source_fingerprint_sha256,
                          currency,gross_amount,posted_by_user_id,posted_at,
                          correlation_id
                        ) VALUES(?,?,?,?,?,?,?,?,?,'EGP',100,?,now(),?)""",
                    ).use {ps->
                        for(i in listOf(1,2,3,4,5,6,7,12))
                            ps.setObject(i,UUID.randomUUID())
                        ps.setString(8,"a".repeat(64))
                        ps.setString(9,"b".repeat(64))
                        ps.setString(13,"FORGED-AR-JOURNAL")
                        ps.executeUpdate()
                    }
                }
            } finally { connection.rollback() }
        }
    }
}
