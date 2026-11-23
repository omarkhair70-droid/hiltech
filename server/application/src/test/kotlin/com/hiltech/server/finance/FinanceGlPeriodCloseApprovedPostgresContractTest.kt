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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Real disposable PG positive/negative constraint test. Approval rows are
 * explicitly synthetic fixtures; this does not replace a full ApprovalEngine
 * and multi-subledger staging end-to-end test.
 */
class FinanceGlPeriodCloseApprovedPostgresContractTest {
    @Test fun approvedClosurePersistsAndRejectsLaterRawApPostingOrMutation() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
        val url=System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user=System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password=System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"
        val ap=System.getenv("HILTECH_AP_LEDGER_SQL")
            ?: Path.of(
                System.getenv("HILTECH_REPO_ROOT") ?: ".",
                "database/phase8/pending_ap_ledger_posting.sql",
            ).toString()
        val close=System.getenv("HILTECH_GL_CLOSE_SQL")
            ?: Path.of(
                System.getenv("HILTECH_REPO_ROOT") ?: ".",
                "database/phase8/pending_gl_period_close.sql",
            ).toString()
        Flyway.configure().dataSource(url,user,password)
            .locations("filesystem:$migrations").load().migrate()
        DriverManager.getConnection(url,user,password).use { connection ->
            connection.autoCommit=false
            val jdbc=JdbcTemplate(SingleConnectionDataSource(connection,true))
            try {
                connection.createStatement().use { st ->
                    st.execute(Files.readString(Path.of(ap)))
                    st.execute(Files.readString(Path.of(close)))
                }
                val org=UUID.randomUUID()
                val period=UUID.randomUUID()
                val prep=UUID.randomUUID()
                val reviewer=UUID.randomUUID()
                val operator=UUID.randomUUID()
                val approval=UUID.randomUUID()
                val step=UUID.randomUUID()
                val assignment=UUID.randomUUID()
                val closure=UUID.randomUUID()
                val t0=OffsetDateTime.parse("2027-01-01T10:00:00Z")
                val completed=OffsetDateTime.parse("2027-01-03T10:00:00Z")
                val closedAt=OffsetDateTime.parse("2027-01-04T10:00:00Z")
                val policy="FINANCE_GL_PERIOD_CLOSE_POLICY"
                val authority="FINANCE_GL_PERIOD_CLOSE_REVIEWER"
                jdbc.update(
                    """
                    INSERT INTO organization(id,organization_code,legal_name,
                        display_name,organization_type,status,created_at)
                    VALUES(?,?,?,?,'HILTECH','ACTIVE',?)
                    """.trimIndent(),
                    org,"T_${org.toString().take(12)}",
                    "Close contract","Close contract",t0,
                )
                for ((name,identity) in listOf(
                    "Preparer" to prep,"Reviewer" to reviewer,"Operator" to operator,
                )) {
                    val person=UUID.randomUUID()
                    jdbc.update(
                        """
                        INSERT INTO person(id,display_name,created_at,updated_at)
                        VALUES(?,?,?,?)
                        """.trimIndent(),
                        person,name,t0,t0,
                    )
                    jdbc.update(
                        """
                        INSERT INTO user_identity(
                            id,auth_provider,auth_subject,person_id,status,created_at)
                        VALUES(?,'contract-test',?,?,'ACTIVE',?)
                        """.trimIndent(),
                        identity,UUID.randomUUID().toString(),person,t0,
                    )
                    jdbc.update(
                        """
                        INSERT INTO organization_membership(
                            id,organization_id,user_identity_id,membership_type,
                            state,valid_from)
                        VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)
                        """.trimIndent(),
                        UUID.randomUUID(),org,identity,t0,
                    )
                }
                jdbc.update(
                    """
                    INSERT INTO finance_gl_period(
                      id,organization_id,date_start,date_end,state,
                      approved_company_period_reference,created_at)
                    VALUES(?,?,'2026-01-01','2026-12-31','OPEN',?,?)
                    """.trimIndent(),
                    period,org,"APPROVED-FINANCE-PERIOD",t0,
                )
                jdbc.update(
                    """
                    INSERT INTO approval_authority_binding(
                      id,organization_id,authority_key,principal_type,
                      principal_user_id,effective_from,active,
                      created_by_user_id,created_at)
                    VALUES(?,?,?,'USER',?,? ,true,?,?)
                    """.trimIndent(),
                    UUID.randomUUID(),org,authority,reviewer,t0,prep,t0,
                )
                jdbc.update(
                    """
                    INSERT INTO approval_policy_version(
                      id,organization_id,policy_key,version_number,
                      lifecycle_state,approval_required,step_mode,
                      authority_key,reason_required,effective_from,
                      created_by_user_id,created_at)
                    VALUES(?,?,?,1,'ACTIVE',true,'SINGLE',?,false,?,?,?)
                    """.trimIndent(),
                    UUID.randomUUID(),org,policy,authority,t0,prep,t0,
                )
                jdbc.update(
                    """
                    INSERT INTO approval_request(
                      id,organization_id,subject_type,subject_id,subject_version,
                      policy_key,policy_version,requester_user_id,
                      state,reason_code,created_at,completed_at)
                    VALUES(? ,?,'FINANCE_GL_PERIOD_CLOSE',?,31337,
                      ?,1,?,'APPROVED','FINANCE_GL_PERIOD_CLOSE_REVIEW',?,?)
                    """.trimIndent(),
                    approval,org,period,policy,prep,t0,completed,
                )
                jdbc.update(
                    """
                    INSERT INTO approval_step(
                      id,approval_request_id,sequence_number,mode,
                      authority_key,state)
                    VALUES(?,?,1,'SINGLE',?,'COMPLETED')
                    """.trimIndent(),
                    step,approval,authority,
                )
                jdbc.update(
                    """
                    INSERT INTO approval_assignment(
                      id,approval_request_id,approval_step_id,principal_type,
                      principal_user_id,state,assigned_at,acted_at)
                    VALUES(?,?,?,'USER',?,'ACTED',?,?)
                    """.trimIndent(),
                    assignment,approval,step,reviewer,t0,completed,
                )
                jdbc.update(
                    """
                    INSERT INTO approval_decision(
                      id,approval_request_id,approval_assignment_id,
                      actor_user_id,decision,decided_at,subject_version,
                      operation_id)
                    VALUES(?,?,?,?,'APPROVE',?,31337,?)
                    """.trimIndent(),
                    UUID.randomUUID(),approval,assignment,reviewer,
                    completed,UUID.randomUUID(),
                )
                val snapshot=jdbc.query(
                    """
                    SELECT journal_count,snapshot_sha256
                    FROM finance_gl_period_close_source
                    WHERE organization_id=? AND period_id=?
                    """.trimIndent(),
                    { rs,_ ->rs.getLong("journal_count") to
                        rs.getString("snapshot_sha256") },org,period,
                ).single()
                assertEquals(0L,snapshot.first)
                assertEquals(64,snapshot.second.length)
                assertEquals(1,jdbc.update(
                    """
                    INSERT INTO finance_gl_period_close(
                        id,operation_id,organization_id,period_id,
                        approval_request_id,policy_key,subject_version,
                        snapshot_sha256,journal_count,closed_by_user_id,
                        closed_at,correlation_id)
                    VALUES(?,?,?,?,?,?,31337,?,0,?,?,'PG-period-close-test')
                    """.trimIndent(),
                    closure,UUID.randomUUID(),org,period,approval,
                    policy,snapshot.second,operator,closedAt,
                ))
                assertEquals(1,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM finance_gl_period_close WHERE id=?",
                    Int::class.java,closure,
                ))
                fun fails23514(block:()->Unit) {
                    val sp=connection.setSavepoint()
                    try {
                        val failure=assertThrows(SQLException::class.java) { block() }
                        assertEquals("23514",failure.sqlState)
                    } finally {
                        connection.rollback(sp)
                    }
                }
                fails23514 {
                    connection.prepareStatement(
                        "UPDATE finance_gl_period_close SET journal_count=7 WHERE id=?",
                    ).use { ps ->
                        ps.setObject(1,closure)
                        ps.executeUpdate()
                    }
                }
                // The very first BEFORE-INSERT trigger checks closed period,
                // before the old payable proposal/source approval guard runs.
                fails23514 {
                    connection.prepareStatement(
                        """
                        INSERT INTO finance_ap_posted_journal(
                           id,operation_id,organization_id,proposal_id,
                           payable_candidate_id,journal_posting_approval_id,
                           fiscal_period_id,invoice_source_sha256,
                           ledger_fingerprint_sha256,currency,gross_amount,
                           posted_by_user_id,posted_at,correlation_id)
                        VALUES(?,?,?,?,?,?,?,? ,?,'EGP',100,?,?,?)
                        """.trimIndent(),
                    ).use { ps ->
                        listOf(
                            UUID.randomUUID(),UUID.randomUUID(),org,
                            UUID.randomUUID(),UUID.randomUUID(),
                            approval,period,
                        ).forEachIndexed { index,id ->
                            ps.setObject(index+1,id)
                        }
                        ps.setString(8,snapshot.second)
                        ps.setString(9,snapshot.second)
                        ps.setObject(10,operator)
                        ps.setObject(11,closedAt.plusDays(1))
                        ps.setString(12,"late-payable-forbidden")
                        ps.executeUpdate()
                    }
                }
            } finally {
                connection.rollback()
            }
        }
    }
}
