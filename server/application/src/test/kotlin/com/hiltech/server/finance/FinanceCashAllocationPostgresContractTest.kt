package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class FinanceCashAllocationPostgresContractTest {
    @Test fun forgedCashPostingRejectedAndSourceForeignKeysAvailable() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
        val url=System.getenv("HILTECH_DB_URL")?:"jdbc:postgresql://localhost:5432/hiltech"
        val user=System.getenv("HILTECH_DB_USER")?:"hiltech"
        val pass=System.getenv("HILTECH_DB_PASSWORD")?:"hiltech"
        val root=Path.of(System.getenv("HILTECH_REPO_ROOT")?:".").toAbsolutePath()
        val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
            ?:root.resolve("database/migrations").toString()
        Flyway.configure().dataSource(url,user,pass)
            .locations("filesystem:$migrations").load().migrate()
        DriverManager.getConnection(url,user,pass).use {c->
            c.autoCommit=false
            try {
                c.createStatement().use {stmt->
                    listOf(
                        "database/phase8/pending_ap_ledger_posting.sql",
                        "database/phase8/pending_gl_period_close.sql",
                        "database/phase8/pending_ap_journal_reversal.sql",
                        "database/phase8/pending_ar_invoice_candidate.sql",
                        "database/phase8/pending_ar_issued_invoice.sql",
                        "database/phase8/pending_ar_journal_posting.sql",
                        "database/phase8/pending_bank_statement_intake.sql",
                        "database/phase8/pending_ap_ar_cash_allocation.sql",
                    ).forEach {stmt.execute(FinancePendingSql.read(root.resolve(it)))}
                }
                val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
                for(table in listOf(
                    "finance_bank_statement_intake_line",
                    "finance_cash_allocation_proposal",
                    "finance_cash_allocation_record",
                ))assertNotNull(jdbc.queryForObject(
                    "SELECT to_regclass('public.$table')",String::class.java))
                assertThrows(SQLException::class.java) {
                    c.prepareStatement(
                        """INSERT INTO finance_cash_allocation_record(
                          id,operation_id,organization_id,proposal_id,
                          source_kind,source_journal_id,bank_statement_line_id,
                          approval_request_id,approval_subject_version,policy_key,
                          currency,allocated_amount,recorded_by_user_id,
                          recorded_at,correlation_id
                        ) VALUES(?,?,?,?,'AR',?,?,?,1,
                            'COMPANY_CASH_ALLOCATION','EGP',100,?,now(),?)"""
                    ).use {st->
                        for(i in listOf(1,2,3,4,5,6,7,8))
                            st.setObject(i,UUID.randomUUID())
                        st.setString(9,"FORGED-CASH-ALLOCATION")
                        st.executeUpdate()
                    }
                }
            } finally {c.rollback()}
        }
    }
}
