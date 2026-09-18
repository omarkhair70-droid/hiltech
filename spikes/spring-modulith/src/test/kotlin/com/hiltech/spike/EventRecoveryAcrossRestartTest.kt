package com.hiltech.spike

import com.hiltech.spike.work.WorkService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.time.Duration

class EventRecoveryAcrossRestartTest {
    @Test
    fun failed_module_event_survives_restart_and_is_republished() {
        val directory = Files.createTempDirectory("hiltech-modulith-spike-")
        val databasePath = directory.resolve("events").toAbsolutePath().toString()
        val databaseUrl = "jdbc:h2:file:$databasePath;DB_CLOSE_ON_EXIT=FALSE"

        val first = startContext(
            databaseUrl = databaseUrl,
            listenerFails = true,
            republishOnRestart = false,
        )

        try {
            first.getBean(WorkService::class.java)
                .completeWork(workOrderId = "wo-42", version = 8)

            waitUntil(Duration.ofSeconds(15)) {
                val publication = publication(first)
                println("SPIKE-10 first-context publication=" + publication)
                publication?.status == "FAILED"
            }

            val failed = requireNotNull(publication(first))
            assertEquals("FAILED", failed.status)
            assertEquals(0, auditCount(first))
            assertTrue(failed.completionAttempts >= 1)
            assertEquals(false, failed.hasCompletionDate)
        } finally {
            first.close()
        }

        val second = startContext(
            databaseUrl = databaseUrl,
            listenerFails = false,
            republishOnRestart = true,
        )

        try {
            waitUntil(Duration.ofSeconds(25)) {
                val audit = auditCount(second)
                val publication = publication(second)
                println(
                    "SPIKE-10 restarted-context audit=" + audit +
                        " publication=" + publication,
                )

                audit == 1 &&
                    publication?.status == "COMPLETED" &&
                    publication.hasCompletionDate
            }

            val completed = requireNotNull(publication(second))
            assertEquals(1, auditCount(second))
            assertEquals("COMPLETED", completed.status)
            assertNotNull(completed.completionAttempts)
            assertTrue(completed.completionAttempts >= 2)
            assertTrue(completed.hasCompletionDate)
        } finally {
            second.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun startContext(
        databaseUrl: String,
        listenerFails: Boolean,
        republishOnRestart: Boolean,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(HiltechBackendSpikeApplication::class.java)
            .properties(
                mapOf(
                    "spring.datasource.url" to databaseUrl,
                    "spring.datasource.username" to "sa",
                    "spring.datasource.password" to "",
                    "spring.sql.init.mode" to "always",
                    "spring.modulith.events.jdbc.schema-initialization.enabled" to "true",
                    "spring.modulith.events.republish-outstanding-events-on-restart" to republishOnRestart.toString(),
                    "hiltech.spike.audit.fail" to listenerFails.toString(),
                ),
            )
            .run()

    private fun auditCount(context: ConfigurableApplicationContext): Int {
        val jdbc = context.getBean(JdbcTemplate::class.java)

        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log",
            Int::class.java,
        ) ?: 0
    }

    private fun publication(
        context: ConfigurableApplicationContext,
    ): PublicationSnapshot? {
        val jdbc = context.getBean(JdbcTemplate::class.java)

        val rows = jdbc.query(
            """
            SELECT STATUS, COMPLETION_DATE, COMPLETION_ATTEMPTS
            FROM EVENT_PUBLICATION
            ORDER BY PUBLICATION_DATE
            """.trimIndent(),
        ) { rs, _ ->
            PublicationSnapshot(
                status = rs.getString("STATUS"),
                completionAttempts = rs.getInt("COMPLETION_ATTEMPTS"),
                hasCompletionDate = rs.getTimestamp("COMPLETION_DATE") != null,
            )
        }

        return rows.lastOrNull()
    }

    private fun waitUntil(
        timeout: Duration,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()

        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }

            Thread.sleep(100)
        }

        error("Condition did not become true within $timeout")
    }

    private data class PublicationSnapshot(
        val status: String?,
        val completionAttempts: Int,
        val hasCompletionDate: Boolean,
    )
}
