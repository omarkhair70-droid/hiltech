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
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/** Proves real independently approved, zero-money opening in PostgreSQL. */
class FinancePhysicalCashPostgresContractTest {
 @Test fun zeroCashboxOpeningIsApprovedWhileForgedPaymentsAreRejected() {
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
        "database/phase8/pending_cashbox_vouchers.sql",
      ).forEach{stmt.execute(Files.readString(root.resolve(it)))}
    }
    val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
    val org=UUID.randomUUID()
    val box=UUID.randomUUID()
    val preparer=UUID.randomUUID()
    val reviewer=UUID.randomUUID()
    val custodian=UUID.randomUUID()
    val approval=UUID.randomUUID()
    val policy="FINANCE_CASHBOX_OPENING_REVIEW"
    val authority="FINANCE_CASHBOX_MANAGER"
    val code="CASH_${box.toString().take(8)}"
    val reference="COMPANY-CASH-ACCOUNT-2026"
    val version=FinanceCashboxOpeningVersion.derive(
      org,code,"EGP",custodian,reference,1,UUID.randomUUID(),1)
    val t0=OffsetDateTime.parse("2026-11-22T09:00:00Z")
    val accepted=t0.plusHours(1)
    val opened=t0.plusHours(2)
    jdbc.update(
      """INSERT INTO organization(id,organization_code,legal_name,
        display_name,organization_type,status,created_at)
        VALUES(?,?,?,?,'HILTECH','ACTIVE',?)""",
      org,"CASH_${org.toString().take(10)}","Fixture Cash Co","Fixture Cash Co",t0)
    for((name,identity) in listOf(
      "Preparer" to preparer,"Independent reviewer" to reviewer,
      "Physical custodian" to custodian)) {
      val person=UUID.randomUUID()
      jdbc.update(
        """INSERT INTO person(id,display_name,created_at,updated_at)
           VALUES(?,?,?,?)""",person,name,t0,t0)
      jdbc.update(
        """INSERT INTO user_identity(id,auth_provider,auth_subject,
          person_id,status,created_at)
          VALUES(?,'contract-fixture',?,?,'ACTIVE',?)""",
        identity,UUID.randomUUID().toString(),person,t0)
      jdbc.update(
        """INSERT INTO organization_membership(
          id,organization_id,user_identity_id,membership_type,state,valid_from)
          VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""",
        UUID.randomUUID(),org,identity,t0)
    }
    jdbc.update(
      """INSERT INTO finance_cashbox_opening_proposal(
          id,operation_id,organization_id,cashbox_code,currency,
          custodian_user_id,approved_company_reference,opening_balance,
          subject_version,policy_key,prepared_by_user_id,created_at)
        VALUES(?,?,?,?,?,?,?,0,?,?,?,?)""",
      box,UUID.randomUUID(),org,code,"EGP",custodian,
      reference,version,policy,preparer,t0)
    jdbc.update(
      """INSERT INTO approval_authority_binding(
           id,organization_id,authority_key,principal_type,
           principal_user_id,effective_from,active,created_by_user_id,created_at)
         VALUES(?,?,?,'USER',?,?,true,?,?)""",
      UUID.randomUUID(),org,authority,reviewer,t0,preparer,t0)
    jdbc.update(
      """INSERT INTO approval_policy_version(
          id,organization_id,policy_key,version_number,lifecycle_state,
          approval_required,step_mode,authority_key,reason_required,
          effective_from,created_by_user_id,created_at)
        VALUES(?,?,?,1,'ACTIVE',true,'SINGLE',?,false,?,?,?)""",
      UUID.randomUUID(),org,policy,authority,t0,preparer,t0)
    jdbc.update(
      """INSERT INTO approval_request(
           id,organization_id,subject_type,subject_id,subject_version,
           policy_key,policy_version,requester_user_id,state,reason_code,
           created_at,completed_at)
         VALUES(?,?,'FINANCE_CASHBOX_OPENING',?,?,?,1,?,
          'APPROVED','FINANCE_CASHBOX_OPENING_REVIEW',?,?)""",
      approval,org,box,version,policy,preparer,t0,accepted)
    val step=UUID.randomUUID()
    val assignment=UUID.randomUUID()
    jdbc.update(
      """INSERT INTO approval_step(
          id,approval_request_id,sequence_number,mode,authority_key,state)
         VALUES(?,?,1,'SINGLE',?,'COMPLETED')""",
      step,approval,authority)
    jdbc.update(
      """INSERT INTO approval_assignment(
           id,approval_request_id,approval_step_id,principal_type,
           principal_user_id,state,assigned_at,acted_at)
         VALUES(?,?,?,'USER',?,'ACTED',?,?)""",
      assignment,approval,step,reviewer,t0,accepted)
    jdbc.update(
      """INSERT INTO approval_decision(
          id,approval_request_id,approval_assignment_id,actor_user_id,
          decision,decided_at,subject_version,operation_id)
         VALUES(?,?,?,?,'APPROVE',?,?,?)""",
      UUID.randomUUID(),approval,assignment,reviewer,accepted,
      version,UUID.randomUUID())
    // This is an actual positive source-backed bank-independent cashbox open.
    assertEquals(1,jdbc.update(
      """INSERT INTO finance_cashbox_register(
          id,organization_id,cashbox_code,currency,custodian_user_id,
          approved_company_reference,opening_balance,status,
          opening_approval_request_id,opening_proposal_id,
          created_by_user_id,created_at)
         VALUES(?,?,?,?,?,?,0,'ACTIVE',?,?,?,?)""",
      box,org,code,"EGP",custodian,reference,
      approval,box,preparer,opened))
    val cash=jdbc.queryForObject(
       """SELECT cash_on_hand FROM finance_cashbox_balance
          WHERE organization_id=? AND cashbox_id=?""",
        BigDecimal::class.java,org,box)
    assertEquals(0,cash?.compareTo(BigDecimal.ZERO))
    assertNotNull(jdbc.queryForObject(
      "SELECT to_regclass('public.finance_cash_voucher_record')",
      String::class.java))
    val alter=c.setSavepoint()
    try {
      val error=assertThrows(SQLException::class.java) {
        c.prepareStatement(
          "UPDATE finance_cashbox_register SET opening_balance=500 WHERE id=?"
        ).use{ps->ps.setObject(1,box);ps.executeUpdate()}
      }
      assertEquals("23514",error.sqlState)
    } finally {c.rollback(alter)}
    val forged=c.setSavepoint()
    try {
      assertThrows(SQLException::class.java) {
        c.prepareStatement(
          """INSERT INTO finance_cash_voucher_record(
            id,operation_id,organization_id,proposal_id,cashbox_id,
            source_kind,source_journal_id,direction,amount,currency,
            approval_request_id,custody_evidence_id,recorded_by_user_id,
            recorded_at,correlation_id)
          VALUES(?,?,?,?,?,'AP',?,'PAYMENT',100,'EGP',?,?,?,now(),?)"""
        ).use{ps->
          for(i in 1..6)ps.setObject(i,UUID.randomUUID())
          ps.setObject(7,approval)
          ps.setObject(8,UUID.randomUUID())
          ps.setObject(9,custodian)
          ps.setString(10,"FORGED-CASH-EXPENSE")
          ps.executeUpdate()
        }
      }
    } finally {c.rollback(forged)}
    assertTrue(jdbc.queryForObject(
      """SELECT cash_on_hand=0 FROM finance_cashbox_balance
         WHERE organization_id=? AND cashbox_id=?""",
      Boolean::class.java,org,box)==true)
   } finally {c.rollback()}
  }
 }
}
