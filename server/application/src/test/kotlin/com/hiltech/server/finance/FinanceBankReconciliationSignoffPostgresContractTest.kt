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
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * Full Flyway installation + immutable reconciliation gates on PostgreSQL.
 * The positive *signed* fixture still requires original-approved AP/AR
 * source and finalized company Evidence; forged inserts must not pass.
 */
class FinanceBankReconciliationSignoffPostgresContractTest {
 @Test fun numberedReconciliationNeedsExactRealAllocationAndSignedApproval() {
   assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
   val url=System.getenv("HILTECH_DB_URL")?:"jdbc:postgresql://localhost:5432/hiltech"
   val user=System.getenv("HILTECH_DB_USER")?:"hiltech"
   val pass=System.getenv("HILTECH_DB_PASSWORD")?:"hiltech"
   val root=Path.of(System.getenv("HILTECH_REPO_ROOT")?:".").toAbsolutePath()
   val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
     ?:root.resolve("database/migrations").toString()
   Flyway.configure().dataSource(url,user,pass)
     .locations("filesystem:$migrations").load().migrate()
   DriverManager.getConnection(url,user,pass).use{c ->
     c.autoCommit=false
     try {
       val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
       for(name in listOf(
         "finance_bank_reconciliation_snapshot",
         "finance_bank_reconciliation_proposal",
         "finance_bank_reconciliation_record",
         "finance_bank_reconciliation_status",
       )) {
         assertNotNull(jdbc.queryForObject(
           "SELECT to_regclass('public.$name')",String::class.java))
       }
       val guard=jdbc.queryForObject(
         """SELECT pg_get_functiondef(
           'finance_bank_reconciliation_record_guard()'::regprocedure)""",
         String::class.java)?:error("Missing signed reconciliation guard")
       assertTrue(guard.contains("FINANCE_BANK_RECONCILIATION"))
       assertTrue(guard.contains("approval_decision"))
       assertTrue(guard.contains("signed_evidence_id"))
       val snapshotSql=jdbc.queryForObject(
         """SELECT definition FROM pg_views
            WHERE schemaname='public'
              AND viewname='finance_bank_reconciliation_snapshot'""",
         String::class.java) ?: error("Missing unified bank source")
       for(source in listOf(
         "finance_cash_allocation_effective",
         "finance_employee_funding_record",
         "finance_employee_settlement_record",
         "finance_expense_reimbursement_record",
       )) assertTrue(snapshotSql.contains(source),
         "Missing accepted bank event in signoff snapshot: $source")
       for(table in listOf(
         "finance_employee_funding_record",
         "finance_employee_settlement_record",
         "finance_expense_reimbursement_record",
       )) assertTrue(jdbc.queryForObject(
         """SELECT EXISTS(SELECT 1 FROM pg_trigger
            WHERE tgrelid=to_regclass(?) AND
              tgname LIKE 'finance_bank_reconciliation_no_late_%')""",
         Boolean::class.java,table)==true,
         "Signed statement must block late $table")
       val counters=jdbc.queryForObject(
         "SELECT count(*) FROM finance_bank_reconciliation_status",Long::class.java)
       assertEquals(0L,counters)
       val forgedSavepoint=c.setSavepoint()
       try {
       assertThrows(SQLException::class.java){
         c.prepareStatement(
           """INSERT INTO finance_bank_reconciliation_proposal(
             id,operation_id,organization_id,intake_id,account_id,
             statement_sha256,match_sha256,expected_line_count,
             total_credit,total_debit,total_matched,reconciliation_reference,
             subject_version,policy_key,prepared_by_user_id,created_at)
             VALUES(?,?,?,?,? ,?,?,1,100,0,100,
             'FORGED-COMPANY-BANK-CLOSE',1,'FINANCE_BANK_RECONCILIATION',?,now())"""
         ).use {st ->
           for(i in listOf(1,2,3,4,5,8)) st.setObject(i,UUID.randomUUID())
           st.setString(6,"a".repeat(64))
           st.setString(7,"b".repeat(64))
           st.executeUpdate()
         }
       }
       } finally { c.rollback(forgedSavepoint) }
       assertEquals(0L,jdbc.queryForObject(
         "SELECT count(*) FROM finance_bank_reconciliation_record",Long::class.java))
       assertTrue(jdbc.queryForObject(
         """SELECT to_regprocedure('public.finance_bank_reconciliation_prevent_late_match()')
             IS NOT NULL""",Boolean::class.java)==true)
     } finally {c.rollback()}
   }
 }
}
