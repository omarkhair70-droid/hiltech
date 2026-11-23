package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class FinanceProjectSourceReportPostgresContractTest {
 @Test fun projectCashAndPostedArApHaveOnlyVerifiedSourceJoinPaths() {
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
        "database/phase8/pending_employee_funding.sql",
        "database/phase8/pending_employee_settlement.sql",
        "database/phase8/pending_expense_reimbursement.sql",
        "database/phase8/pending_project_finance_sources.sql",
      ).forEach{stmt.execute(Files.readString(root.resolve(it)))}
    }
    val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
    assertNotNull(jdbc.queryForObject(
      "SELECT to_regclass('public.finance_project_source_event')",
      String::class.java))
    assertTrue(jdbc.queryForObject(
      "SELECT COUNT(*) FROM finance_project_source_event",
      Long::class.java)==0L)
    val source=jdbc.queryForObject(
      """SELECT definition FROM pg_views WHERE schemaname='public'
         AND viewname='finance_project_source_event'""",
      String::class.java)
    assertTrue(source?.contains("finance_ar_posted_journal")==true)
    assertTrue(source?.contains("finance_ap_posted_journal")==true)
    assertTrue(source?.contains("finance_expense_reimbursement_record")==true)
    assertTrue(source?.contains("procurement_purchase_requirement")==true)
   } finally {c.rollback()}
  }
 }
}
