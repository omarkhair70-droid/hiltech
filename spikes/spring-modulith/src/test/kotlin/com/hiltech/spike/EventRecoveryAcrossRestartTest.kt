package com.hiltech.spike

import com.hiltech.spike.work.WorkService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRegistry
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
                val incomplete = incomplete(first)
                println("SPIKE-10 first-context incomplete=" + incomplete)
                incomplete.size == 1 &&
                    incomplete.single().status == EventPublication.Status.FAILED
            }

            val failed = incomplete(first).single()
            assertEquals(EventPublication.Status.FAILED, failed.status)
            assertEquals(0, auditCount(first))
            assertTrue(failed.completionAttempts >= 1)
            assertTrue(failed.completionDate.isEmpty)
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
                val incomplete = incomplete(second)

                println(
                    "SPIKE-10 restarted-context audit=" + audit +
                        " incomplete=" + incomplete,
                )

                audit == 1 && incomplete.isEmpty()
            }

            assertEquals(1, auditCount(second))
            assertTrue(incomplete(second).isEmpty())
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

    private fun incomplete(context: ConfigurableApplicationContext) =
        context.getBean(EventPublicationRegistry::class.java)
            .findIncompletePublications()

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
}
