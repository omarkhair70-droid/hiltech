package com.hiltech.server.notifications

import com.hiltech.server.approval.ApprovalAttentionSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class NotificationPolicyState {
    PENDING,
    SUPPRESSED,
    DISPATCHED,
}

enum class NotificationChannel {
    PUSH,
    EMAIL,
    SMS,
    DESKTOP,
}

enum class NotificationDeliveryOutcome {
    ACCEPTED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
}

data class NotificationIntentRecord(
    val id: UUID,
    val organizationId: UUID,
    val producerKey: String,
    val sourceEventId: UUID,
    val sourceType: String,
    val sourceId: UUID,
    val sourceVersion: Long,
    val recipientUserIdentityId: UUID,
    val notificationClass: String,
    val templateCode: String,
    val safePreview: String?,
    val deepLinkType: String,
    val deepLinkId: UUID,
    val policyState: NotificationPolicyState,
    val suppressionReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val dispatchedAt: Instant?,
    val correlationId: String?,
    val version: Long,
)

data class NotificationDeliveryAttemptRecord(
    val id: UUID,
    val notificationIntentId: UUID,
    val channel: NotificationChannel,
    val providerKey: String,
    val attemptNumber: Int,
    val attemptedAt: Instant,
    val outcome: NotificationDeliveryOutcome,
    val providerMessageId: String?,
    val failureCode: String?,
    val deliveredAt: Instant?,
    val correlationId: String?,
)

interface NotificationProjectionPort {
    fun projectApprovalRequested(
        snapshot: ApprovalAttentionSnapshot,
        sourceEventId: UUID,
        sourceOccurredAt: Instant,
        correlationId: String?,
    ): Boolean
}

interface NotificationPersistencePort {
    fun loadIntent(
        notificationIntentId: UUID,
    ): NotificationIntentRecord?

    fun suppress(
        notificationIntentId: UUID,
        reasonCode: String,
        at: Instant,
    ): Boolean

    fun recordAttempt(
        notificationIntentId: UUID,
        channel: NotificationChannel,
        result: NotificationDeliveryResult,
        attemptedAt: Instant,
        correlationId: String?,
    ): NotificationDeliveryAttemptRecord
}

@Component
class JdbcNotificationPersistence(
    private val jdbc: JdbcTemplate,
) : NotificationProjectionPort,
    NotificationPersistencePort {
    override fun projectApprovalRequested(
        snapshot: ApprovalAttentionSnapshot,
        sourceEventId: UUID,
        sourceOccurredAt: Instant,
        correlationId: String?,
    ): Boolean {
        require(snapshot.requestVersion >= 1)
        require(
            snapshot.assignmentPrincipalType.name ==
                "USER",
        )

        val producerKey =
            "approval:" +
                snapshot.requestId +
                ":requested"
        val intentId =
            UUID.nameUUIDFromBytes(
                (
                    "notification:" +
                        producerKey
                ).toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )

        return jdbc.update(
            """
            INSERT INTO notification_intent (
                id,
                organization_id,
                producer_key,
                source_event_id,
                source_type,
                source_id,
                source_version,
                recipient_user_identity_id,
                notification_class,
                template_code,
                safe_preview,
                deep_link_type,
                deep_link_id,
                policy_state,
                suppression_reason,
                created_at,
                updated_at,
                dispatched_at,
                correlation_id,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                'APPROVAL_REQUEST',
                ?, ?, ?,
                'ACTION_REQUIRED',
                'APPROVAL_DECISION_REQUIRED',
                NULL,
                'APPROVAL_REQUEST',
                ?,
                'PENDING',
                NULL,
                ?, ?, NULL,
                ?, 1
            )
            ON CONFLICT (producer_key)
            DO NOTHING
            """.trimIndent(),
            intentId,
            snapshot.organizationId,
            producerKey,
            sourceEventId,
            snapshot.requestId,
            snapshot.requestVersion,
            snapshot.assignmentPrincipalId,
            snapshot.requestId,
            sourceOccurredAt
                .atOffset(ZoneOffset.UTC),
            sourceOccurredAt
                .atOffset(ZoneOffset.UTC),
            correlationId
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.take(128),
        ) > 0
    }

    override fun loadIntent(
        notificationIntentId: UUID,
    ): NotificationIntentRecord? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                producer_key,
                source_event_id,
                source_type,
                source_id,
                source_version,
                recipient_user_identity_id,
                notification_class,
                template_code,
                safe_preview,
                deep_link_type,
                deep_link_id,
                policy_state,
                suppression_reason,
                created_at,
                updated_at,
                dispatched_at,
                correlation_id,
                version
            FROM notification_intent
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                NotificationIntentRecord(
                    id =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    producerKey =
                        rs.getString(
                            "producer_key",
                        ),
                    sourceEventId =
                        rs.getObject(
                            "source_event_id",
                            UUID::class.java,
                        ),
                    sourceType =
                        rs.getString(
                            "source_type",
                        ),
                    sourceId =
                        rs.getObject(
                            "source_id",
                            UUID::class.java,
                        ),
                    sourceVersion =
                        rs.getLong(
                            "source_version",
                        ),
                    recipientUserIdentityId =
                        rs.getObject(
                            "recipient_user_identity_id",
                            UUID::class.java,
                        ),
                    notificationClass =
                        rs.getString(
                            "notification_class",
                        ),
                    templateCode =
                        rs.getString(
                            "template_code",
                        ),
                    safePreview =
                        rs.getString(
                            "safe_preview",
                        ),
                    deepLinkType =
                        rs.getString(
                            "deep_link_type",
                        ),
                    deepLinkId =
                        rs.getObject(
                            "deep_link_id",
                            UUID::class.java,
                        ),
                    policyState =
                        NotificationPolicyState
                            .valueOf(
                                rs.getString(
                                    "policy_state",
                                ),
                            ),
                    suppressionReason =
                        rs.getString(
                            "suppression_reason",
                        ),
                    createdAt =
                        rs.getObject(
                            "created_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    updatedAt =
                        rs.getObject(
                            "updated_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    dispatchedAt =
                        rs.getObject(
                            "dispatched_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    correlationId =
                        rs.getString(
                            "correlation_id",
                        ),
                    version =
                        rs.getLong(
                            "version",
                        ),
                )
            },
            notificationIntentId,
        ).singleOrNull()

    override fun suppress(
        notificationIntentId: UUID,
        reasonCode: String,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE notification_intent
            SET
                policy_state = 'SUPPRESSED',
                suppression_reason = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND policy_state = 'PENDING'
            """.trimIndent(),
            reasonCode
                .trim()
                .uppercase()
                .take(80),
            at.atOffset(ZoneOffset.UTC),
            notificationIntentId,
        ) > 0

    @Transactional
    override fun recordAttempt(
        notificationIntentId: UUID,
        channel: NotificationChannel,
        result: NotificationDeliveryResult,
        attemptedAt: Instant,
        correlationId: String?,
    ): NotificationDeliveryAttemptRecord {
        val currentState =
            jdbc.query(
                """
                SELECT policy_state
                FROM notification_intent
                WHERE id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    NotificationPolicyState
                        .valueOf(
                            rs.getString(
                                "policy_state",
                            ),
                        )
                },
                notificationIntentId,
            ).singleOrNull()
                ?: error(
                    "Notification intent not found.",
                )

        require(
            currentState ==
                NotificationPolicyState.PENDING,
        ) {
            "Notification intent is not pending."
        }

        val attemptNumber =
            (
                jdbc.queryForObject(
                    """
                    SELECT
                        COALESCE(
                            MAX(attempt_number),
                            0
                        ) + 1
                    FROM notification_delivery_attempt
                    WHERE notification_intent_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    notificationIntentId,
                ) ?: 1
            )

        val attemptId =
            UUID.randomUUID()
        val providerKey =
            result.providerKey
                .trim()
                .uppercase()
                .take(80)
        val failureCode =
            result.failureCode
                ?.trim()
                ?.uppercase()
                ?.take(80)

        jdbc.update(
            """
            INSERT INTO notification_delivery_attempt (
                id,
                notification_intent_id,
                channel,
                provider_key,
                attempt_number,
                attempted_at,
                outcome,
                provider_message_id,
                failure_code,
                delivered_at,
                correlation_id
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            attemptId,
            notificationIntentId,
            channel.name,
            providerKey,
            attemptNumber,
            attemptedAt
                .atOffset(ZoneOffset.UTC),
            result.outcome.name,
            result.providerMessageId
                ?.take(220),
            failureCode,
            result.deliveredAt
                ?.atOffset(ZoneOffset.UTC),
            correlationId
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.take(128),
        )

        if (
            result.outcome ==
            NotificationDeliveryOutcome.ACCEPTED
        ) {
            jdbc.update(
                """
                UPDATE notification_intent
                SET
                    policy_state = 'DISPATCHED',
                    suppression_reason = NULL,
                    updated_at = ?,
                    dispatched_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND policy_state = 'PENDING'
                """.trimIndent(),
                attemptedAt
                    .atOffset(ZoneOffset.UTC),
                attemptedAt
                    .atOffset(ZoneOffset.UTC),
                notificationIntentId,
            )
        }

        return NotificationDeliveryAttemptRecord(
            id = attemptId,
            notificationIntentId =
                notificationIntentId,
            channel = channel,
            providerKey = providerKey,
            attemptNumber = attemptNumber,
            attemptedAt = attemptedAt,
            outcome = result.outcome,
            providerMessageId =
                result.providerMessageId,
            failureCode = failureCode,
            deliveredAt =
                result.deliveredAt,
            correlationId =
                correlationId,
        )
    }
}
