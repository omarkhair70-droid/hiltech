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

class FinanceArInvoiceIssuancePostgresContractTest {
    @Test fun databaseEnforcesReviewedInvoiceAndRejectsUnapprovedInsert() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
        val url=System.getenv("HILTECH_DB_URL") ?:"jdbc:postgresql://localhost:5432/hiltech"
        val user=System.getenv("HILTECH_DB_USER") ?:"hiltech"
        val pass=System.getenv("HILTECH_DB_PASSWORD") ?:"hiltech"
        val root=Path.of(System.getenv("HILTECH_REPO_ROOT") ?: ".").toAbsolutePath()
        val path=System.getenv("HILTECH_MIGRATIONS_PATH")
            ?:root.resolve("database/migrations").toString()
        Flyway.configure().dataSource(url,user,pass)
            .locations("filesystem:$path").load().migrate()
        DriverManager.getConnection(url,user,pass).use { connection ->
            connection.autoCommit=false
            try {
                val ddl=listOf(
                    "database/phase8/pending_ap_ledger_posting.sql",
                    "database/phase8/pending_ar_invoice_candidate.sql",
                    "database/phase8/pending_ar_issued_invoice.sql",
                )
                connection.createStatement().use { statement ->
                    ddl.forEach { statement.execute(Files.readString(root.resolve(it))) }
                }
                val jdbc=JdbcTemplate(SingleConnectionDataSource(connection,true))
                for (table in listOf(
                    "finance_ar_invoice_issuance_proposal",
                    "finance_ar_issued_invoice",
                )) {
                    assertNotNull(jdbc.queryForObject(
                        "SELECT to_regclass('public.$table')",String::class.java,
                    ))
                }
                val content=Files.readString(root.resolve(ddl.last()))
                assertTrue(content.contains("finance_ar_issued_invoice_guard"))
                assertTrue(content.contains("FINANCE_AR_INVOICE_ISSUANCE"))
                assertTrue(content.contains("finance_ar_issued_invoice_immutable"))
                assertThrows(SQLException::class.java) {
                    connection.prepareStatement(
                        """INSERT INTO finance_ar_issued_invoice(
                           id,operation_id,organization_id,candidate_id,proposal_id,
                           invoice_number,invoice_document_evidence_id,
                           invoice_approval_id,approval_policy_key,approval_subject_version,
                           source_fingerprint_sha256,customer_organization_id,
                           currency,gross_amount,issue_date,due_date,
                           issued_by_user_id,issued_at,correlation_id
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?)""",
                    ).use { st ->
                        for(i in listOf(1,2,3,4,5,7,8,12,17)) st.setObject(i,UUID.randomUUID())
                        st.setString(6,"EG-TEST-0001")
                        st.setString(9,"FINANCE_AR_INVOICE_APPROVAL")
                        st.setLong(10,1)
                        st.setString(11,"a".repeat(64))
                        st.setString(13,"EGP")
                        st.setBigDecimal(14,java.math.BigDecimal("100"))
                        st.setDate(15,java.sql.Date.valueOf("2026-11-23"))
                        st.setDate(16,java.sql.Date.valueOf("2026-12-23"))
                        st.setString(18,"unapproved-invoice-record")
                        st.executeUpdate()
                    }
                }
            } finally { connection.rollback() }
        }
    }
}
