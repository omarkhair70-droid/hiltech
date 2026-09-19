package com.hiltech.server.approval

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ApprovalModulithRecoveryContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_APPROVAL_CONTRACT_TEST",
        ) == "1"

    private val dbUrl =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val dbUser =
        System.getenv("HILTECH_DB_USER")
            ?: "hiltech"
    private val dbPassword =
        System.getenv("HILTECH_DB_PASSWORD")
            ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"

    @Test
    fun failedApprovalPublicationIsDurableRecoverableAndDeduplicated() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(
                dbUrl,
                dbUser,
                dbPassword,
            )
            .locations(
                "filesystem:" +
                    migrationPath,
            )
            .load()
            .migrate()

        val context =
            SpringApplicationBuilder(
                ApprovalRecoveryTestApplication::class.java,
            )
                .web(
                    WebApplicationType.NONE,
                )
                .properties(
                    mapOf(
                        "spring.datasource.url" to
                            dbUrl,
                        "spring.datasource.username" to
                            dbUser,
                        "spring.datasource.password" to
                            dbPassword,
                        "spring.flyway.locations" to
                            (
                                "filesystem:" +
                                    migrationPath
                            ),
                        "spring.modulith.events.jdbc.schema-initialization.enabled" to
                            "false",
                        "spring.main.banner-mode" to
                            "off",
                    ),
                )
                .run()

        try {
            val jdbc =
                context.getBean(
                    JdbcTemplate::class.java,
                )
            val transaction =
                TransactionTemplate(
                    context.getBean(
                        PlatformTransactionManager::class.java,
                    ),
                )
            val failed =
                context.getBean(
                    FailedEventPublications::class.java,
                )
            val probe =
                context.getBean(
                    ApprovalRecoveryProbe::class.java,
                )

            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS
                    approval_recovery_probe (
                        event_id uuid PRIMARY KEY,
                        approval_request_id uuid NOT NULL,
                        received_at timestamptz NOT NULL
                    )
                """.trimIndent(),
            )
            jdbc.update(
                "DELETE FROM approval_recovery_probe",
            )

            val event =
                ApprovalApproved(
                    eventId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        UUID.randomUUID(),
                    organizationId =
                        UUID.randomUUID(),
                    subjectType =
                        "CONTRACT_EXCEPTION",
                    subjectId =
                        UUID.randomUUID(),
                    subjectVersion = 1,
                    actorIdentityId =
                        UUID.randomUUID(),
                    occurredAt =
                        Instant.parse(
                            "2026-09-19T19:00:00Z",
                        ),
                    correlationId =
                        "corr-approval-recovery",
                )

            transaction.executeWithoutResult {
                context.publishEvent(event)
            }

            await(
                "Approval publication failure",
            ) {
                publicationStatus(
                    jdbc = jdbc,
                    eventId = event.eventId,
                ) == "FAILED"
            }

            assertEquals(
                0,
                probeCount(
                    jdbc = jdbc,
                    eventId = event.eventId,
                ),
            )

            probe.fail.set(false)
            failed.resubmit(
                ResubmissionOptions.defaults(),
            )

            await(
                "Approval publication recovery",
            ) {
                probeCount(
                    jdbc = jdbc,
                    eventId = event.eventId,
                ) == 1 &&
                    publicationStatus(
                        jdbc = jdbc,
                        eventId =
                            event.eventId,
                    ) == "COMPLETED"
            }

            failed.resubmit(
                ResubmissionOptions.defaults(),
            )
            Thread.sleep(150)

            assertEquals(
                1,
                probeCount(
                    jdbc = jdbc,
                    eventId = event.eventId,
                ),
                "Repeated resubmission must not duplicate a completed Approval consumer effect.",
            )

            val attempts =
                jdbc.queryForObject(
                    """
                    SELECT completion_attempts
                    FROM event_publication
                    WHERE serialized_event
                              LIKE ?
                    ORDER BY publication_date DESC
                    LIMIT 1
                    """.trimIndent(),
                    Int::class.java,
                    "%" +
                        event.eventId +
                        "%",
                ) ?: 0

            assertTrue(
                attempts >= 2,
                "Approval recovery must prove an original failed listener attempt and a later resubmission.",
            )
        } finally {
            context.close()
        }
    }

    private fun publicationStatus(
        jdbc: JdbcTemplate,
        eventId: UUID,
    ): String? =
        jdbc.query(
            """
            SELECT status
            FROM event_publication
            WHERE serialized_event
                      LIKE ?
            ORDER BY publication_date DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                rs.getString(
                    "status",
                )
            },
            "%" + eventId + "%",
        ).singleOrNull()

    private fun probeCount(
        jdbc: JdbcTemplate,
        eventId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM approval_recovery_probe
            WHERE event_id = ?
            """.trimIndent(),
            Int::class.java,
            eventId,
        ) ?: 0

    private fun await(
        label: String,
        condition: () -> Boolean,
    ) {
        repeat(100) {
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }

        error(
            "Timed out waiting for " +
                label +
                ".",
        )
    }
}

@Component
class ApprovalRecoveryProbe(
    private val jdbc: JdbcTemplate,
) {
    val fail =
        AtomicBoolean(true)

    @ApplicationModuleListener
    fun on(
        event: ApprovalApproved,
    ) {
        if (fail.get()) {
            error(
                "Forced Approval recovery probe failure.",
            )
        }

        jdbc.update(
            """
            INSERT INTO approval_recovery_probe (
                event_id,
                approval_request_id,
                received_at
            )
            VALUES (?, ?, ?)
            ON CONFLICT (event_id)
            DO NOTHING
            """.trimIndent(),
            event.eventId,
            event.approvalRequestId,
            event.occurredAt.atOffset(
                ZoneOffset.UTC,
            ),
        )
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    ApprovalRecoveryProbe::class,
)
class ApprovalRecoveryTestApplication
