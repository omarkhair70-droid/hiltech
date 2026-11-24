package com.hiltech.server.commercial

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Real disposable PostgreSQL proof of the already-numbered Phase10 view
 * consumed independently by Finance8 and Service11. This is a DB projection
 * contract, NOT server permissions, an issued invoice, or client SLA signoff.
 */
class CommercialFinanceServiceSourcePostgresContractTest {
    @Test fun sameReleasedSalesAwardPowersFinanceAndServiceSourceWithoutPayments() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val path = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url, user, password)
            .locations("filesystem:$path").load().migrate()
        val source = listOf(System.getenv("HILTECH_REPO_ROOT") ?: ".", "../..", "../../..")
            .map {
                Path.of(it).toAbsolutePath().normalize()
                    .resolve("database/phase10/commercial_positive_contract.sql")
            }
            .firstOrNull { Files.isRegularFile(it) }
            ?: error("Phase10 project->Finance/Service SQL proof was not found")

        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                "SELECT to_regclass('public.commercial_finance_award_source') IS NOT NULL " +
                    "AND to_regclass('public.commercial_support_contract_source') IS NOT NULL",
            ).use { query ->
                query.executeQuery().use { rs ->
                    assertTrue(rs.next() && rs.getBoolean(1))
                }
            }
            // Keep the synthetic fixture in this transaction long enough to exercise
            // the real signed webhook handler, then roll back every fixture row.
            connection.autoCommit = false
            try {
                val fixture = Files.readString(source).replaceFirst("BEGIN;","").replace("ROLLBACK;","")
                connection.createStatement().use { it.execute(fixture) }
                val jdbc = org.springframework.jdbc.core.JdbcTemplate(
                    org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true))
                val message = "wamid.synthetic"
                val phone = "123456"
                val recipient = "201000000000"
                org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.update(
                    "UPDATE commercial_quote_whatsapp_attempt SET state='API_ACCEPTED',provider_status_at=NULL WHERE provider_message_id=?",message))
                val secret = "synthetic-only-webhook-key"
                val handler = CommercialWhatsappWebhook(jdbc,secret,"synthetic-verify-token")
                fun state() = jdbc.queryForObject(
                    "SELECT state FROM commercial_quote_whatsapp_attempt WHERE provider_message_id=?",String::class.java,message)
                fun receipt(status: String, timestamp: Long, sender: String=phone, to: String=recipient, signed: Boolean=true): Boolean {
                    val raw = """{"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{"metadata":{"phone_number_id":"$sender"},"statuses":[{"id":"$message","status":"$status","timestamp":"$timestamp","recipient_id":"$to"}]}}]}]}""".toByteArray()
                    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                    mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(),"HmacSHA256"))
                    val signature = "sha256="+java.util.HexFormat.of().formatHex(mac.doFinal(raw))
                    return handler.receive(raw,if(signed) signature else "sha256="+ "0".repeat(64))
                }
                org.junit.jupiter.api.Assertions.assertFalse(receipt("delivered",10,signed=false))
                org.junit.jupiter.api.Assertions.assertEquals("API_ACCEPTED",state())
                assertTrue(receipt("delivered",11,sender="654321"))
                assertTrue(receipt("delivered",12,to="201111111111"))
                org.junit.jupiter.api.Assertions.assertEquals("API_ACCEPTED",state())
                assertTrue(receipt("sent",13))
                org.junit.jupiter.api.Assertions.assertEquals("SENT",state())
                assertTrue(receipt("delivered",14))
                assertTrue(receipt("read",15))
                assertTrue(receipt("failed",16))
                assertTrue(receipt("sent",12))
                assertTrue(receipt("delivered",14))
                org.junit.jupiter.api.Assertions.assertEquals("READ",state())
            } finally {
                connection.rollback()
            }
        }
    }
}
