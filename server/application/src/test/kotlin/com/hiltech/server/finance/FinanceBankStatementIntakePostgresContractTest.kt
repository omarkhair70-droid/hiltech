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

class FinanceBankStatementIntakePostgresContractTest {
 @Test fun forgedStatementCannotBeImportedWithoutCompanySourceApproval() {
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
    c.createStatement().use {s->
      s.execute(FinancePendingSql.read(root.resolve(
          "database/phase8/pending_ap_ledger_posting.sql")))
      s.execute(FinancePendingSql.read(root.resolve(
          "database/phase8/pending_bank_statement_intake.sql")))
    }
    val jdbc=JdbcTemplate(SingleConnectionDataSource(c,true))
    for(table in listOf(
        "finance_bank_account_register","finance_bank_statement_intake_proposal",
        "finance_bank_statement_intake","finance_bank_statement_intake_line"
    )) assertNotNull(jdbc.queryForObject(
        "SELECT to_regclass('public.$table')",String::class.java))
    assertThrows(SQLException::class.java) {
      c.prepareStatement(
          """INSERT INTO finance_bank_statement_intake(
            id,operation_id,organization_id,proposal_id,account_id,
            approval_request_id,statement_sha256,statement_evidence_id,
            currency,date_start,date_end,opening_balance,closing_balance,
            expected_line_count,imported_by_user_id,imported_at,correlation_id
          ) VALUES(?,?,?,?,?,?,?,?,'EGP',
            DATE '2026-11-01',DATE '2026-11-30',100,150,1,?,now(),?)"""
      ).use {st->
        for(i in listOf(1,2,3,4,5,6,8,9))
          st.setObject(i,UUID.randomUUID())
        st.setString(7,"a".repeat(64))
        st.setString(10,"FAKE-BANK-STATEMENT")
        st.executeUpdate()
      }
    }
   } finally {c.rollback()}
  }
 }
}
