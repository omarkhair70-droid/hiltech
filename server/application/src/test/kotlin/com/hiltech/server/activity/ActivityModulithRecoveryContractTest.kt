package com.hiltech.server.activity

import com.hiltech.server.documents.EvidenceTerminalStateChanged
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ActivityModulithRecoveryContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_ACTIVITY_CONTRACT_TEST",
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
    fun failedActivityPublicationIsDurableAndRecoverable() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(
                dbUrl,
                dbUser,
                dbPassword,
            )
            .locations(
                "filesystem:" + migrationPath,
            )
            .load()
            .migrate()

        val fgaApi =
            requireEnv(
                "HILTECH_FGA_API_URL",
            )
        val fgaStore =
            requireEnv(
                "HILTECH_FGA_STORE_ID",
            )
        val fgaModel =
            requireEnv(
                "HILTECH_FGA_MODEL_ID",
            )

        val context =
            SpringApplicationBuilder(
                ActivityRecoveryTestApplication::class.java,
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
                            ("filesystem:" + migrationPath),
                        "spring.modulith.events.jdbc.schema-initialization.enabled" to
                            "false",
                        "hiltech.activity.cursor-signing-key" to
                            "activity-recovery-contract-key-20260919-strong",
                        "hiltech.authorization.openfga.enabled" to
                            "true",
                        "hiltech.authorization.openfga.api-url" to
                            fgaApi,
                        "hiltech.authorization.openfga.store-id" to
                            fgaStore,
                        "hiltech.authorization.openfga.authorization-model-id" to
                            fgaModel,
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
            val transactionManager =
                context.getBean(
                    PlatformTransactionManager::class.java,
                )
            val failed =
                context.getBean(
                    FailedEventPublications::class.java,
                )
            val transaction =
                TransactionTemplate(
                    transactionManager,
                )

            val ids =
                seedContext(
                    jdbc,
                    Instant.parse(
                        "2026-09-19T12:00:00Z",
                    ),
                )
            val event =
                EvidenceTerminalStateChanged(
                    eventId =
                        UUID.randomUUID(),
                    evidenceId =
                        ids.evidenceId,
                    workOrderId =
                        ids.workOrderId,
                    sourceVersion = 2,
                    storageState =
                        "READY",
                    evidenceTypeCode =
                        "PHOTO",
                    evidenceRequirementKey =
                        "after-photo",
                    classificationCode =
                        "INTERNAL",
                    actorIdentityId =
                        ids.actorId,
                    occurredAt =
                        Instant.parse(
                            "2026-09-19T12:01:00Z",
                        ),
                    correlationId =
                        "corr-recovery",
                )

            transaction.executeWithoutResult {
                context.publishEvent(
                    event,
                )
            }

            await(
                "publication to fail",
            ) {
                publicationStatus(
                    jdbc = jdbc,
                    eventId = event.eventId,
                ) == "FAILED"
            }

            assertEquals(
                0,
                activityCount(
                    jdbc,
                    event.eventId,
                ),
                "The forced FK failure must not create a partial Activity row.",
            )

            insertEvidence(
                jdbc = jdbc,
                ids = ids,
                now =
                    Instant.parse(
                        "2026-09-19T12:02:00Z",
                    ),
            )

            failed.resubmit(
                ResubmissionOptions
                    .defaults(),
            )

            await(
                "failed publication recovery",
            ) {
                activityCount(
                    jdbc,
                    event.eventId,
                ) == 1 &&
                    publicationStatus(
                        jdbc = jdbc,
                        eventId =
                            event.eventId,
                    ) == "COMPLETED"
            }

            failed.resubmit(
                ResubmissionOptions
                    .defaults(),
            )

            Thread.sleep(150)

            assertEquals(
                1,
                activityCount(
                    jdbc,
                    event.eventId,
                ),
                "Completed/resubmitted publication must not duplicate Activity.",
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
                    "%" + event.eventId + "%",
                ) ?: 0
            assertTrue(
                attempts >= 2,
                "Recovery must prove more than the original failed listener attempt.",
            )
        } finally {
            context.close()
        }
    }

    private fun publicationStatus(
        jdbc: JdbcTemplate,
        eventId: String,
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

    private fun activityCount(
        jdbc: JdbcTemplate,
        eventId: String,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM activity_event
            WHERE source_event_id = ?
            """.trimIndent(),
            Int::class.java,
            UUID.fromString(eventId),
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

    private fun seedContext(
        jdbc: JdbcTemplate,
        now: Instant,
    ): RecoveryIds {
        val organizationId =
            UUID.randomUUID()
        val actorId =
            UUID.randomUUID()
        val projectId =
            UUID.randomUUID()
        val siteId =
            UUID.randomUUID()
        val workOrderId =
            UUID.randomUUID()
        val evidenceId =
            UUID.randomUUID()
        val at =
            now.atOffset(
                ZoneOffset.UTC,
            )

        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code,
                legal_name, display_name,
                organization_type, status,
                created_at, version
            )
            VALUES (
                ?, ?,
                'Activity Recovery',
                'Activity Recovery',
                'HILTECH', 'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            organizationId,
            "REC-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at,
        )
        jdbc.update(
            """
            INSERT INTO user_identity (
                id, auth_provider,
                auth_subject, status,
                primary_organization_id,
                created_at, version
            )
            VALUES (
                ?, 'contract',
                ?, 'ACTIVE',
                ?, ?, 1
            )
            """.trimIndent(),
            actorId,
            "recovery-" +
                actorId,
            organizationId,
            at,
        )
        jdbc.update(
            """
            INSERT INTO project (
                id, organization_id,
                project_code, name,
                client_organization_id,
                lifecycle_state,
                project_manager_id,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?,
                'Recovery Project',
                ?, 'ACTIVE',
                NULL,
                ?, ?, ?, 1
            )
            """.trimIndent(),
            projectId,
            organizationId,
            "RP-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            organizationId,
            at,
            actorId,
            at,
        )
        jdbc.update(
            """
            INSERT INTO site (
                id,
                client_organization_id,
                site_code, name,
                status,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?,
                'Recovery Site',
                'ACTIVE',
                ?, ?, ?, 1
            )
            """.trimIndent(),
            siteId,
            organizationId,
            "RS-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at,
            actorId,
            at,
        )
        jdbc.update(
            """
            INSERT INTO work_order (
                id, work_order_code,
                project_id, site_id,
                title,
                lifecycle_state,
                readiness_state,
                priority_code,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?, ?,
                'Recovery WorkOrder',
                'DRAFT',
                'READY',
                'NORMAL',
                ?, ?, ?, 1
            )
            """.trimIndent(),
            workOrderId,
            "RW-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            projectId,
            siteId,
            at,
            actorId,
            at,
        )

        return RecoveryIds(
            organizationId =
                organizationId,
            actorId = actorId,
            workOrderId =
                workOrderId,
            evidenceId =
                evidenceId,
        )
    }

    private fun insertEvidence(
        jdbc: JdbcTemplate,
        ids: RecoveryIds,
        now: Instant,
    ) {
        val at =
            now.atOffset(
                ZoneOffset.UTC,
            )
        jdbc.update(
            """
            INSERT INTO evidence (
                id,
                organization_id,
                target_type,
                target_id,
                work_order_id,
                evidence_requirement_key,
                evidence_type_code,
                content_type,
                size_bytes,
                sha256,
                captured_at,
                captured_by_user_id,
                storage_state,
                finalized_at,
                classification_code,
                client_visibility_mode,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'WORK_ORDER', ?,
                ?,
                'after-photo',
                'PHOTO',
                'image/jpeg',
                4,
                ?,
                ?, ?,
                'READY',
                ?,
                'INTERNAL',
                'INTERNAL_ONLY',
                ?, 2
            )
            """.trimIndent(),
            ids.evidenceId,
            ids.organizationId,
            ids.workOrderId,
            ids.workOrderId,
            "b".repeat(64),
            at,
            ids.actorId,
            at,
            at,
        )
    }

    private fun requireEnv(
        name: String,
    ): String =
        requireNotNull(
            System.getenv(name)
                ?.takeIf {
                    it.isNotBlank()
                },
        ) {
            name +
                " is required for the Activity recovery contract."
        }

    private data class RecoveryIds(
        val organizationId: UUID,
        val actorId: UUID,
        val workOrderId: UUID,
        val evidenceId: UUID,
    )
}


@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    JdbcActivityProjection::class,
    ActivityProjectionListener::class,
    ActivityRecoveryClockConfiguration::class,
)
class ActivityRecoveryTestApplication

@Configuration(proxyBeanMethods = false)
class ActivityRecoveryClockConfiguration {
    @Bean
    fun clock(): Clock =
        Clock.systemUTC()
}
