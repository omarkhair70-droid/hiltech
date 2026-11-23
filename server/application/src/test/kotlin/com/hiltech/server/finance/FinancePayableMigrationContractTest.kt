package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class FinancePayableMigrationContractTest {
    @Test fun provisionalV0061MigratesAndFinanceRecordsRemainImmutable() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val dir = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url, user, password)
            .locations("filesystem:$dir").load().migrate()
        DriverManager.getConnection(url, user, password).use { con ->
            con.createStatement().use { s ->
                s.executeQuery(
                    "SELECT state FROM finance_payable_candidate LIMIT 1",
                ).use { assertTrue(it.next().not()) }
                s.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger t
                    JOIN pg_class c ON c.oid=t.tgrelid
                    WHERE c.relname='finance_payable_candidate'
                      AND NOT t.tgisinternal
                    """.trimIndent(),
                ).use {
                    assertTrue(it.next())
                    assertEquals(2, it.getInt(1))
                }
                s.executeQuery(
                    """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_name='finance_payable_candidate'
                      AND column_name IN ('source_sha256','finance_handoff_id',
                        'gross_amount','state','match_revision','invoice_evidence_id')
                    """.trimIndent(),
                ).use {
                    assertTrue(it.next())
                    assertEquals(6, it.getInt(1))
                }
            }
        }
    }
}
