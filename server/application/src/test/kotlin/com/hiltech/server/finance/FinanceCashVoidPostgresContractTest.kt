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

class FinanceCashVoidPostgresContractTest {
 @Test fun correctionIsAppendOnlyAndCompanyApprovedRatherThanDeletingPaymentEvidence() {
  assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
  val url=System.getenv("HILTECH_DB_URL")?:"jdbc:postgresql://localhost:5432/hiltech"
  val user=System.getenv("HILTECH_DB_USER")?:"hiltech"
  val pass=System.getenv("HILTECH_DB_PASSWORD")?:"hiltech"
  val root=Path.of(System.getenv("HILTECH_REPO_ROOT")?:".").toAbsolutePath()
  val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
     ?:root.resolve("database/migrations").toString()
  Flyway.configure().dataSource(url,user,pass)
    .locations("filesystem:$migrations").load().migrate()
  DriverManager.getConnection(url,user,pass).use{c->
   c.autoCommit=false
   try {
    c.createStatement().use{stmt->
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
        "database/phase8/pending_ap_ar_source_balance.sql",
        "database/phase8/pending_bank_reconciliation_exceptions.sql",
      ).forEach{stmt.execute(Files.readString(root.resolve(it)))}
    }
    val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
    for(table in listOf(
      "finance_cash_allocation_record",
      "finance_cash_allocation_void_proposal",
      "finance_cash_allocation_void_record",
      "finance_cash_allocation_effective",
      "finance_ap_ar_source_balance",
      "finance_bank_allocation_exception",
    )) assertNotNull(jdbc.queryForObject(
      "SELECT to_regclass('public.$table')",String::class.java))
    val originalCount=jdbc.queryForObject(
      "SELECT count(*) FROM finance_cash_allocation_record",Long::class.java)
    val effectiveCount=jdbc.queryForObject(
      "SELECT count(*) FROM finance_cash_allocation_effective",Long::class.java)
    assertTrue(originalCount==0L && effectiveCount==0L)
    val definition=jdbc.queryForObject(
      """SELECT definition FROM pg_views WHERE schemaname='public'
         AND viewname='finance_cash_allocation_effective'""",
      String::class.java) ?: error("Effective match ledger missing")
    assertTrue(definition.contains("finance_cash_allocation_void_record"))
    val balances=jdbc.queryForObject(
      """SELECT definition FROM pg_views WHERE schemaname='public'
         AND viewname='finance_ap_ar_source_balance'""",
      String::class.java) ?: error("Balance view missing")
    assertTrue(balances.contains("finance_cash_allocation_effective"))
    val bankVariances=jdbc.queryForObject(
      """SELECT definition FROM pg_views WHERE schemaname='public'
         AND viewname='finance_bank_allocation_exception'""",
      String::class.java) ?: error("Bank variance view missing")
    assertTrue(bankVariances.contains("finance_cash_allocation_effective"))
    val source=jdbc.queryForObject(
      """SELECT pg_get_functiondef('finance_cash_allocation_void_guard()'::regprocedure)""",
      String::class.java) ?: error("Independent void guard missing")
    assertTrue(source.contains("finance_gl_period_close"))
    assertTrue(source.contains("FINANCE_CASH_ALLOCATION_VOID"))
    assertTrue(source.contains("approval_decision"))
    // Direct, approval-less correction is never accepted by PostgreSQL.
    assertThrows(SQLException::class.java){
      c.prepareStatement(
        """INSERT INTO finance_cash_allocation_void_record(
          id,operation_id,organization_id,proposal_id,original_record_id,
          source_kind,source_journal_id,bank_statement_line_id,amount,
          currency,approval_request_id,recorded_by_user_id,
          recorded_at,correlation_id)
        VALUES(?,?,?,?,?,'AR',?,?,30,'EGP',?,?,now(),?)""",
      ).use{ps->
        for(i in 1..10)ps.setObject(i,UUID.randomUUID())
        ps.setString(11,"FAKE-CASH-CORRECTION")
        ps.executeUpdate()
      }
    }
   } finally {c.rollback()}
  }
 }
}
