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
import java.time.ZoneOffset
import java.util.UUID

class FinanceGlPeriodClosePostgresContractTest {
    @Test fun actualPostgresBlocksPrematureOrUnapprovedPeriodClose() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url=System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user=System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password=System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"
        val apSql=System.getenv("HILTECH_AP_LEDGER_SQL")
            ?: Path.of(
                System.getenv("HILTECH_REPO_ROOT") ?: ".",
                "database/phase8/pending_ap_ledger_posting.sql",
            ).toString()
        val periodSql=System.getenv("HILTECH_GL_CLOSE_SQL")
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
                connection.createStatement().use { statement ->
                    statement.execute(Files.readString(Path.of(apSql)))
                    statement.execute(Files.readString(Path.of(periodSql)))
                }
                assertNotNull(jdbc.queryForObject(
                    "SELECT to_regclass('public.finance_gl_period_close')",
                    String::class.java,
                ))
                val org=UUID.randomUUID()
                val person=UUID.randomUUID()
                val identity=UUID.randomUUID()
                val past=UUID.randomUUID()
                val future=UUID.randomUUID()
                val now=OffsetDateTime.parse("2026-11-23T02:00:00Z")
                jdbc.update(
                    """
                    INSERT INTO organization(id,organization_code,legal_name,
                        display_name,organization_type,status,created_at)
                    VALUES (?,?,?,?,'HILTECH','ACTIVE',?)
                    """.trimIndent(),
                    org,"T_${org.toString().take(12)}",
                    "Period test","Period test",now,
                )
                jdbc.update(
                    """
                    INSERT INTO person(id,display_name,created_at,updated_at)
                    VALUES(?,'Finance Test',?,?)
                    """.trimIndent(),person,now,now,
                )
                jdbc.update(
                    """
                    INSERT INTO user_identity(id,auth_provider,auth_subject,
                        person_id,status,created_at)
                    VALUES(?,'contract-test',? ,?,'ACTIVE',?)
                    """.trimIndent(),identity,UUID.randomUUID().toString(),person,now,
                )
                for ((id,start,end) in listOf(
                    Triple(past,"2026-01-01","2026-01-31"),
                    Triple(future,"2026-01-01","2027-12-31"),
                )) {
                    jdbc.update(
                        """
                        INSERT INTO finance_gl_period(
                            id,organization_id,date_start,date_end,state,
                            approved_company_period_reference,created_at)
                        VALUES(?,?,?::date,?::date,'OPEN',?,?)
                        """.trimIndent(),
                        id,org,start,end,"PERIOD-CONTRACT-APPROVED",now,
                    )
                }
                val source=jdbc.query(
                    """
                    SELECT period_id,journal_count,snapshot_sha256
                    FROM finance_gl_period_close_source
                    WHERE organization_id=? ORDER BY period_id
                    """.trimIndent(),
                    { rs,_ ->
                        Triple(rs.getObject("period_id",UUID::class.java),
                            rs.getLong("journal_count"),
                            rs.getString("snapshot_sha256"))
                    },org,
                )
                assertEquals(2,source.size)
                source.forEach { (id,count,sha) ->
                    assertTrue(id==past || id==future)
                    assertEquals(0L,count)
                    assertEquals(64,sha.length)
                }
                fun forbidden(period:UUID,closed:OffsetDateTime) {
                    val save=connection.setSavepoint()
                    val sha=source.single { it.first==period }.third
                    try {
                        val failure=assertThrows(SQLException::class.java) {
                            connection.prepareStatement(
                                """
                                INSERT INTO finance_gl_period_close(
                                    id,operation_id,organization_id,period_id,
                                    approval_request_id,policy_key,subject_version,
                                    snapshot_sha256,journal_count,closed_by_user_id,
                                    closed_at,correlation_id)
                                VALUES(?,?,?,?,?,'FINANCE_PERIOD_CLOSE',1,?,0,?,?,'test')
                                """.trimIndent(),
                            ).use { st ->
                                st.setObject(1,UUID.randomUUID())
                                st.setObject(2,UUID.randomUUID())
                                st.setObject(3,org)
                                st.setObject(4,period)
                                st.setObject(5,UUID.randomUUID())
                                st.setString(6,sha)
                                st.setObject(7,identity)
                                st.setObject(8,closed)
                                st.executeUpdate()
                            }
                        }
                        assertEquals("23514",failure.sqlState)
                    } finally {
                        connection.rollback(save)
                    }
                }
                forbidden(future,now) // no early company close
                forbidden(past,now) // no forged independent approval
                assertEquals(0,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM finance_gl_period_close",
                    Int::class.java,
                ))
            } finally {
                connection.rollback()
            }
        }
    }
}
