package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class FinanceApArBalancePostgresContractTest {
 @Test fun balancesIncludeOnlyPostedAndRecognizedSources() {
  assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
  val url=System.getenv("HILTECH_DB_URL") ?:"jdbc:postgresql://localhost:5432/hiltech"
  val user=System.getenv("HILTECH_DB_USER") ?:"hiltech"
  val pass=System.getenv("HILTECH_DB_PASSWORD") ?:"hiltech"
  val root=Path.of(System.getenv("HILTECH_REPO_ROOT")?:".").toAbsolutePath()
  val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
      ?:root.resolve("database/migrations").toString()
  Flyway.configure().dataSource(url,user,pass)
    .locations("filesystem:$migrations").load().migrate()
  DriverManager.getConnection(url,user,pass).use { c->
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
        "database/phase8/pending_ap_ar_source_balance.sql",
        "database/phase8/pending_bank_reconciliation_exceptions.sql",
      ).forEach {stmt.execute(Files.readString(root.resolve(it)))}
    }
    val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
    assertNotNull(jdbc.queryForObject(
       "SELECT to_regclass('public.finance_ap_ar_source_balance')",
       String::class.java))
    val result=jdbc.queryForObject(
      "SELECT count(*) FROM finance_ap_ar_source_balance",Long::class.java)
    assertTrue(result==0L)
    val src=jdbc.queryForObject(
      """SELECT definition FROM pg_views WHERE schemaname='public'
         AND viewname='finance_ap_ar_source_balance'""",String::class.java)
    // Only effective allocations count after an independently approved
    // immutable whole-allocation correction; raw records overstate cash used.
    assertTrue(src?.contains("finance_cash_allocation_effective")==true)
    assertFalse(src?.contains("FROM finance_cash_allocation_record")==true)
    assertTrue(src?.contains("finance_ap_journal_reversal")==true)
    assertTrue(src?.contains("finance_ar_posted_journal")==true)
    assertNotNull(jdbc.queryForObject(
      "SELECT to_regclass('public.finance_bank_allocation_exception')",
      String::class.java))
    assertTrue(jdbc.queryForObject(
      "SELECT count(*) FROM finance_bank_allocation_exception",
      Long::class.java)==0L)
   } finally { c.rollback() }
  }
 }
}
