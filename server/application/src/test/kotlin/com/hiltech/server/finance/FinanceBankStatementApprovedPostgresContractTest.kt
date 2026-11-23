package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** A positive transaction-local approved bank source, not an authenticated bank feed. */
class FinanceBankStatementApprovedPostgresContractTest {
 @Test fun approvedSourceImportsExactBankLineAndRejectsTampering() {
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
   val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
   try {
    c.createStatement().use {stmt->
      stmt.execute(Files.readString(root.resolve(
        "database/phase8/pending_ap_ledger_posting.sql")))
      stmt.execute(Files.readString(root.resolve(
        "database/phase8/pending_bank_statement_intake.sql")))
    }
    val org=UUID.randomUUID()
    val account=UUID.randomUUID()
    val proposal=UUID.randomUUID()
    val evidence=UUID.randomUUID()
    val bankLine=UUID.randomUUID()
    val review=UUID.randomUUID()
    val importer=UUID.randomUUID()
    val prep=UUID.randomUUID()
    val reviewer=UUID.randomUUID()
    val approval=UUID.randomUUID()
    val t0=OffsetDateTime.parse("2026-11-22T09:00:00Z")
    val imported=t0.plusHours(2)
    val sha="a".repeat(64)
    val line=FinanceBankIntakeSourceLine(
      externalTransactionId="EG-BANK-TXN-000881",
      bookedOn=LocalDate.parse("2026-11-22"),
      flow=FinanceBankFlow.CREDIT,
      currency="EGP",amount=BigDecimal("250.000000"),
      originalLineSha256="b".repeat(64),
    )
    val source=FinanceBankIntakeSource(
      organizationId=org,bankAccountId=account,evidenceId=evidence,
      evidenceSha256=sha,currency="EGP",
      statementStart=LocalDate.parse("2026-11-01"),
      statementEnd=LocalDate.parse("2026-11-30"),
      openingBalance=BigDecimal("1000.000000"),
      closingBalance=BigDecimal("1250.000000"),
      lines=listOf(line),companyBankAccountApproved=true,
      finalizedEvidenceReviewed=true,
    )
    val digest=FinanceBankIntakeRules.validate(source).sourceLinesSha256
    val policy="FINANCE_BANK_STATEMENT_INTAKE"
    val authority="FINANCE_BANK_STATEMENT_INTAKE_REVIEWER"
    jdbc.update(
      """INSERT INTO organization(id,organization_code,legal_name,
           display_name,organization_type,status,created_at)
         VALUES(?,?,?,?,'HILTECH','ACTIVE',?)""",
      org,"BK_${org.toString().take(12)}","Test bank org","Test bank org",t0)
    for((name,identity) in listOf(
      "Finance preparer" to prep,"Bank reviewer" to reviewer,
      "Finance operator" to importer)) {
      val person=UUID.randomUUID()
      jdbc.update("""INSERT INTO person(id,display_name,created_at,updated_at)
        VALUES(?,?,?,?)""",person,name,t0,t0)
      jdbc.update(
        """INSERT INTO user_identity(id,auth_provider,auth_subject,
            person_id,status,created_at)
           VALUES(?,'contract-test',?,?,'ACTIVE',?)""",
        identity,UUID.randomUUID().toString(),person,t0)
      jdbc.update(
        """INSERT INTO organization_membership(
            id,organization_id,user_identity_id,membership_type,state,valid_from)
           VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""",
        UUID.randomUUID(),org,identity,t0)
    }
    jdbc.update(
      """INSERT INTO finance_bank_account_register(
           id,organization_id,currency,bank_name,masked_account_reference,
           company_approval_reference,state,created_at)
         VALUES(?,?,'EGP','Fixture Bank','EG****0881',
           'BOARD-APPROVED-ACCOUNT','APPROVED',?)""",account,org,t0)
    jdbc.update(
      """INSERT INTO evidence(
           id,organization_id,target_type,target_id,evidence_requirement_key,
           evidence_type_code,content_type,original_file_name,size_bytes,sha256,
           captured_at,captured_by_user_id,storage_state,object_key_ref,
           finalized_at,classification_code,client_visibility_mode,created_at)
         VALUES(?,?,'FINANCE_BANK_STATEMENT_INTAKE',?,
           'FINANCE_BANK_STATEMENT_BINARY','FINANCE_BANK_STATEMENT',
           'application/pdf','statement.pdf',128,?,?,
           ?,'READY','finance/bank/private/statement',?,
           'HIGHLY_RESTRICTED','INTERNAL_ONLY',?)""",
      evidence,org,proposal,sha,t0,prep,t0,t0)
    jdbc.update(
      """INSERT INTO evidence_upload_session(
           id,evidence_id,expected_sha256,expected_size_bytes,
           expires_at,state,created_at,finalized_at,operation_id)
         VALUES(?,?,?,128,?,'READY',?,?,?)""",
      UUID.randomUUID(),evidence,sha,t0.plusHours(3),t0,t0,
      UUID.randomUUID())
    jdbc.update(
      """INSERT INTO finance_bank_statement_intake_proposal(
           id,operation_id,organization_id,account_id,statement_evidence_id,
           statement_sha256,statement_lines_sha256,date_start,date_end,
           opening_balance,closing_balance,expected_line_count,source_version,
           policy_key,prepared_by_user_id,created_at)
         VALUES(?,?,?,?,?,?,?,?,?,?,?,1,101,?,?,?)""",
      proposal,UUID.randomUUID(),org,account,evidence,sha,digest,
      source.statementStart,source.statementEnd,source.openingBalance,
      source.closingBalance,policy,prep,t0)
    jdbc.update(
      """INSERT INTO finance_bank_statement_intake_proposal_line(
           id,organization_id,proposal_id,line_number,external_transaction_id,
           booked_on,flow,currency,amount,original_line_sha256)
         VALUES(?,?,?,1,?,?,'CREDIT','EGP',?,?)""",
      UUID.randomUUID(),org,proposal,line.externalTransactionId,
      line.bookedOn,line.amount,line.originalLineSha256)
    c.createStatement().use {it.execute("SET CONSTRAINTS ALL IMMEDIATE")}
    jdbc.update(
      """INSERT INTO approval_authority_binding(
           id,organization_id,authority_key,principal_type,
           principal_user_id,effective_from,active,
           created_by_user_id,created_at)
         VALUES(?,?,?,'USER',?,?,true,?,?)""",
      UUID.randomUUID(),org,authority,reviewer,t0,prep,t0)
    jdbc.update(
      """INSERT INTO approval_policy_version(
           id,organization_id,policy_key,version_number,lifecycle_state,
           approval_required,step_mode,authority_key,reason_required,
           effective_from,created_by_user_id,created_at)
         VALUES(?,?,?,1,'ACTIVE',true,'SINGLE',?,false,?,?,?)""",
      UUID.randomUUID(),org,policy,authority,t0,prep,t0)
    jdbc.update(
      """INSERT INTO approval_request(
           id,organization_id,subject_type,subject_id,subject_version,
           policy_key,policy_version,requester_user_id,state,reason_code,
           created_at,completed_at)
         VALUES(? ,?,'FINANCE_BANK_STATEMENT_INTAKE',?,101,?,1,?,
           'APPROVED','FINANCE_BANK_STATEMENT_INTAKE_REVIEW',?,?)""",
      approval,org,proposal,policy,prep,t0,imported.minusMinutes(30))
    val step=UUID.randomUUID()
    val assignment=UUID.randomUUID()
    jdbc.update(
      """INSERT INTO approval_step(
           id,approval_request_id,sequence_number,mode,authority_key,state)
         VALUES(?,?,1,'SINGLE',?,'COMPLETED')""",step,approval,authority)
    jdbc.update(
      """INSERT INTO approval_assignment(
           id,approval_request_id,approval_step_id,principal_type,
           principal_user_id,state,assigned_at,acted_at)
         VALUES(?,?,?,'USER',?,'ACTED',?,?)""",
      assignment,approval,step,reviewer,t0,imported.minusMinutes(30))
    jdbc.update(
      """INSERT INTO approval_decision(
           id,approval_request_id,approval_assignment_id,actor_user_id,
           decision,decided_at,subject_version,operation_id)
         VALUES(?,?,?,?,'APPROVE',?,101,?)""",
      UUID.randomUUID(),approval,assignment,reviewer,
      imported.minusMinutes(30),UUID.randomUUID())
    val headerChecks=jdbc.queryForMap(
      """
      SELECT p.account_id=? AS account_matches,
             p.statement_sha256=? AS statement_matches,
             p.statement_lines_sha256=? AS lines_match,
             p.statement_evidence_id=? AS evidence_matches,
             p.date_start=? AS start_matches,
             p.date_end=? AS end_matches,
             p.opening_balance=? AS opening_matches,
             p.closing_balance=? AS closing_matches,
             p.expected_line_count=1 AS count_matches,
             p.prepared_by_user_id<>? AS actor_distinct,
             a.state='APPROVED' AS account_approved,
             a.currency='EGP' AS currency_matches
      FROM finance_bank_statement_intake_proposal p
      JOIN finance_bank_account_register a
        ON a.id=p.account_id AND a.organization_id=p.organization_id
      WHERE p.id=? AND p.organization_id=?
      """.trimIndent(),
      account,sha,digest,evidence,
      source.statementStart,source.statementEnd,
      source.openingBalance,source.closingBalance,
      importer,proposal,org,
    )
    assertTrue(headerChecks.values.all{it==true},
       "BANK_HEADER_SOURCE_DRIFT: $headerChecks")
    val intake=UUID.randomUUID()
    assertEquals(1,jdbc.update(
      """INSERT INTO finance_bank_statement_intake(
           id,operation_id,organization_id,proposal_id,account_id,
           approval_request_id,statement_sha256,statement_lines_sha256,
           statement_evidence_id,currency,date_start,date_end,opening_balance,
           closing_balance,expected_line_count,imported_by_user_id,
           imported_at,correlation_id)
         VALUES(?,?,?,?,?,?,?,?,?,'EGP',?,?,?,?,1,?,?,'bank-fixture')""",
      intake,UUID.randomUUID(),org,proposal,account,approval,sha,digest,evidence,
      source.statementStart,source.statementEnd,source.openingBalance,
      source.closingBalance,importer,imported))
    assertEquals(1,jdbc.update(
      """INSERT INTO finance_bank_statement_intake_line(
           id,organization_id,intake_id,account_id,external_transaction_id,
           booked_on,flow,currency,amount,original_line_sha256)
         VALUES(?,?,?,?,?,?,'CREDIT','EGP',?,?)""",
      bankLine,org,intake,account,line.externalTransactionId,
      line.bookedOn,line.amount,line.originalLineSha256))
    c.createStatement().use {it.execute("SET CONSTRAINTS ALL IMMEDIATE")}
    assertEquals(1,jdbc.queryForObject(
      """SELECT count(*) FROM finance_bank_statement_intake_line
         WHERE organization_id=? AND intake_id=?""",Int::class.java,org,intake))
    val save=c.setSavepoint()
    try {
      val invalid=assertThrows(SQLException::class.java) {
        c.prepareStatement(
          """INSERT INTO finance_bank_statement_intake_line(
               id,organization_id,intake_id,account_id,external_transaction_id,
               booked_on,flow,currency,amount,original_line_sha256)
             VALUES(?,?,?,?,?,?,'CREDIT','EGP',?,?)"""
        ).use {ps->
          ps.setObject(1,UUID.randomUUID())
          ps.setObject(2,org)
          ps.setObject(3,intake)
          ps.setObject(4,account)
          ps.setString(5,"BANK-INVENTED-TRANSFER")
          ps.setObject(6,line.bookedOn)
          ps.setBigDecimal(7,BigDecimal("5.000000"))
          ps.setString(8,"c".repeat(64))
          ps.executeUpdate()
        }
      }
      assertEquals("23514",invalid.sqlState)
    } finally {c.rollback(save)}
    val mutation=c.setSavepoint()
    try {
      val invalid=assertThrows(SQLException::class.java) {
        c.prepareStatement(
          "UPDATE finance_bank_statement_intake_line SET amount=9 WHERE id=?"
        ).use {it.setObject(1,bankLine);it.executeUpdate()}
      }
      assertEquals("23514",invalid.sqlState)
    } finally {c.rollback(mutation)}
   } finally {c.rollback()}
  }
 }
}
