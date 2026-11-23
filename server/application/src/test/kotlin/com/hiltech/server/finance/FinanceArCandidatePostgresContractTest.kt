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

/** Disposable PostgreSQL DDL smoke only; never certifies customer invoice issuance. */
class FinanceArCandidatePostgresContractTest {
    @Test fun unnumberedDraftDdlExistsButDirectForgedBillingIsRejected() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
        val url=System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user=System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val pass=System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val root=Path.of(System.getenv("HILTECH_REPO_ROOT") ?: ".").toAbsolutePath()
        val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: root.resolve("database/migrations").toString()
        Flyway.configure().dataSource(url,user,pass)
            .locations("filesystem:$migrations").load().migrate()
        DriverManager.getConnection(url,user,pass).use { c ->
            c.autoCommit=false
            try {
                c.createStatement().use {
                    it.execute(Files.readString(root.resolve(
                        "database/phase8/pending_ap_ledger_posting.sql",
                    )))
                    it.execute(Files.readString(root.resolve(
                        "database/phase8/pending_ar_invoice_candidate.sql",
                    )))
                }
                val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
                assertNotNull(jdbc.queryForObject(
                    "SELECT to_regclass('public.finance_ar_invoice_candidate')",
                    String::class.java,
                ))
                assertThrows(SQLException::class.java) {
                    c.prepareStatement(
                        """INSERT INTO finance_ar_invoice_candidate(
                          id,operation_id,organization_id,opportunity_id,project_id,
                          customer_organization_id,signed_contract_id,
                          verified_client_po_id,commercial_source_sha256,
                          billing_milestone_evidence_id,
                          contractual_billable_reference,legal_tax_review_reference,
                          invoice_issue_date,invoice_due_date,currency,requested_amount,
                          created_by_user_id,created_at,correlation_id
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                          ?,now(),?)""",
                    ).use { ps ->
                        for(i in listOf(1,2,3,4,5,6,7,8,10,17))
                            ps.setObject(i,UUID.randomUUID())
                        ps.setString(9,"a".repeat(64))
                        ps.setString(11,"SIGNED-MILESTONE-2026")
                        ps.setString(12,"APPROVED-TAX-REVIEW")
                        ps.setDate(13,java.sql.Date.valueOf("2026-11-23"))
                        ps.setDate(14,java.sql.Date.valueOf("2026-12-23"))
                        ps.setString(15,"EGP")
                        ps.setBigDecimal(16,java.math.BigDecimal("100"))
                        ps.setString(18,"forged-untrusted-candidate")
                        ps.executeUpdate()
                    }
                }
            } finally {
                c.rollback()
            }
        }
    }
}
