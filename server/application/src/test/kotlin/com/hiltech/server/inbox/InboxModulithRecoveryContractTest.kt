package com.hiltech.server.inbox

import com.hiltech.server.approval.ApprovalRequested
import com.hiltech.server.approval.JdbcApprovalPersistence
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

class InboxModulithRecoveryContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_INBOX_CONTRACT_TEST",
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
    fun failedProductionInboxProjectionIsDurableRecoverableAndDeduplicated() {
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
                InboxRecoveryTestApplication::class.java,
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

            val organizationId =
                UUID.randomUUID()
            val requesterId =
                UUID.randomUUID()
            val approverId =
                UUID.randomUUID()
            val requestId =
                UUID.randomUUID()
            val subjectId =
                UUID.randomUUID()
            val event =
                ApprovalRequested(
                    eventId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        requestId,
                    organizationId =
                        organizationId,
                    subjectType =
                        "CONTRACT_EXCEPTION",
                    subjectId =
                        subjectId,
                    subjectVersion = 1,
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    policyVersion = 1,
                    authorityKey =
                        "OWNER_FINAL",
                    requesterIdentityId =
                        requesterId,
                    occurredAt =
                        Instant.parse(
                            "2026-09-19T20:45:00.123456789Z",
                        ),
                    correlationId =
                        "corr-inbox-recovery",
                )

            transaction.executeWithoutResult {
                context.publishEvent(event)
            }

            await(
                "failed production Inbox publication",
            ) {
                publicationStatus(
                    jdbc = jdbc,
                    eventId =
                        event.eventId,
                ) == "FAILED"
            }

            assertEquals(
                0,
                inboxCount(
                    jdbc = jdbc,
                    requestId =
                        requestId,
                ),
            )

            seedApprovalSource(
                jdbc = jdbc,
                organizationId =
                    organizationId,
                requesterId =
                    requesterId,
                approverId =
                    approverId,
                requestId =
                    requestId,
                subjectId =
                    subjectId,
                at =
                    event.occurredAt
                        .minusSeconds(60),
            )

            failed.resubmit(
                ResubmissionOptions.defaults(),
            )

            await(
                "recovered production Inbox projection",
            ) {
                publicationStatus(
                    jdbc = jdbc,
                    eventId =
                        event.eventId,
                ) == "COMPLETED" &&
                    inboxCount(
                        jdbc = jdbc,
                        requestId =
                            requestId,
                    ) == 1
            }

            val versionAfterRecovery =
                inboxVersion(
                    jdbc = jdbc,
                    requestId =
                        requestId,
                )

            failed.resubmit(
                ResubmissionOptions.defaults(),
            )
            Thread.sleep(150)

            assertEquals(
                1,
                inboxCount(
                    jdbc = jdbc,
                    requestId =
                        requestId,
                ),
            )
            assertEquals(
                versionAfterRecovery,
                inboxVersion(
                    jdbc = jdbc,
                    requestId =
                        requestId,
                ),
                "Completed publication resubmission must not duplicate the production Inbox projection effect.",
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
                "Recovery must prove one failed production-listener attempt and one successful resubmission.",
            )
        } finally {
            context.close()
        }
    }

    private fun seedApprovalSource(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        requesterId: UUID,
        approverId: UUID,
        requestId: UUID,
        subjectId: UUID,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(
                ZoneOffset.UTC,
            )

        jdbc.update(
            """
            INSERT INTO organization (
                id,
                organization_code,
                legal_name,
                display_name,
                organization_type,
                status,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'Inbox Recovery',
                'Inbox Recovery',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            organizationId,
            "INBOX-REC-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            timestamp,
        )

        listOf(
            requesterId,
            approverId,
        ).forEach { userId ->
            jdbc.update(
                """
                INSERT INTO user_identity (
                    id,
                    auth_provider,
                    auth_subject,
                    status,
                    primary_organization_id,
                    created_at,
                    version
                )
                VALUES (
                    ?, 'contract',
                    ?, 'ACTIVE',
                    ?, ?, 1
                )
                """.trimIndent(),
                userId,
                "inbox-recovery-" +
                    userId,
                organizationId,
                timestamp,
            )
            jdbc.update(
                """
                INSERT INTO organization_membership (
                    id,
                    organization_id,
                    user_identity_id,
                    membership_type,
                    role_label,
                    state,
                    valid_from,
                    valid_until,
                    invited_by,
                    version
                )
                VALUES (
                    ?, ?, ?,
                    'EMPLOYEE',
                    'contract-only',
                    'ACTIVE',
                    ?, NULL,
                    NULL, 1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                organizationId,
                userId,
                timestamp.minusMinutes(1),
            )
        }

        jdbc.update(
            """
            INSERT INTO approval_authority_binding (
                id,
                organization_id,
                authority_key,
                principal_type,
                principal_user_id,
                principal_team_id,
                effective_from,
                effective_to,
                active,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'OWNER_FINAL',
                'USER',
                ?, NULL,
                ?, NULL,
                true,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            approverId,
            timestamp.minusMinutes(1),
            approverId,
            timestamp,
        )

        jdbc.update(
            """
            INSERT INTO approval_policy_version (
                id,
                organization_id,
                policy_key,
                version_number,
                lifecycle_state,
                approval_required,
                step_mode,
                authority_key,
                reason_required,
                effective_from,
                effective_to,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'EXCEPTION_OWNER_FINAL',
                1,
                'ACTIVE',
                true,
                'SINGLE',
                'OWNER_FINAL',
                false,
                ?, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            timestamp.minusMinutes(1),
            approverId,
            timestamp,
        )

        val stepId =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO approval_request (
                id,
                organization_id,
                subject_type,
                subject_id,
                subject_version,
                policy_key,
                policy_version,
                requester_user_id,
                state,
                reason_code,
                safe_reason_summary,
                created_at,
                completed_at,
                correlation_id,
                version
            )
            VALUES (
                ?, ?,
                'CONTRACT_EXCEPTION',
                ?, 1,
                'EXCEPTION_OWNER_FINAL',
                1, ?,
                'PENDING',
                'RECOVERY_EXCEPTION',
                'Recovered projection source.',
                ?, NULL,
                'corr-inbox-recovery',
                1
            )
            """.trimIndent(),
            requestId,
            organizationId,
            subjectId,
            requesterId,
            timestamp,
        )
        jdbc.update(
            """
            INSERT INTO approval_step (
                id,
                approval_request_id,
                sequence_number,
                mode,
                authority_key,
                state,
                required_count
            )
            VALUES (
                ?, ?, 1,
                'SINGLE',
                'OWNER_FINAL',
                'PENDING', 1
            )
            """.trimIndent(),
            stepId,
            requestId,
        )
        jdbc.update(
            """
            INSERT INTO approval_assignment (
                id,
                approval_request_id,
                approval_step_id,
                principal_type,
                principal_user_id,
                principal_team_id,
                state,
                assigned_at,
                acted_at
            )
            VALUES (
                ?, ?, ?,
                'USER',
                ?, NULL,
                'ASSIGNED',
                ?, NULL
            )
            """.trimIndent(),
            UUID.randomUUID(),
            requestId,
            stepId,
            approverId,
            timestamp,
        )
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

    private fun inboxCount(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM inbox_item
            WHERE source_type =
                  'APPROVAL_REQUEST'
              AND source_id = ?
            """.trimIndent(),
            Int::class.java,
            requestId,
        ) ?: 0

    private fun inboxVersion(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT version
                FROM inbox_item
                WHERE source_type =
                      'APPROVAL_REQUEST'
                  AND source_id = ?
                """.trimIndent(),
                Long::class.java,
                requestId,
            ),
        )

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

@Configuration(proxyBeanMethods = false)
class InboxRecoveryClockConfiguration {
    @Bean
    fun clock(): Clock =
        Clock.systemUTC()
}

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    JdbcApprovalPersistence::class,
    JdbcInboxProjection::class,
    ApprovalInboxProjectionListener::class,
    InboxRecoveryClockConfiguration::class,
)
class InboxRecoveryTestApplication
