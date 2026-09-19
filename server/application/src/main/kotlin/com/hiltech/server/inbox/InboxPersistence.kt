package com.hiltech.server.inbox

import com.hiltech.server.approval.ApprovalAttentionSnapshot
import com.hiltech.server.approval.ApprovalRequestState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class InboxItemState {
    OPEN,
    RESOLVED,
}

enum class InboxTargetPrincipalType {
    USER,
    TEAM,
}

data class InboxItemRecord(
    val id: UUID,
    val organizationId: UUID,
    val producerKey: String,
    val sourceType: String,
    val sourceId: UUID,
    val sourceVersion: Long,
    val actionKey: String,
    val targetPrincipalType:
        InboxTargetPrincipalType,
    val targetPrincipalId: UUID,
    val state: InboxItemState,
    val safeTitleCode: String,
    val safeSummary: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val resolvedAt: Instant?,
    val correlationId: String?,
)

interface InboxProjectionPort {
    fun projectApproval(
        snapshot: ApprovalAttentionSnapshot,
        sourceEventId: UUID,
        sourceOccurredAt: Instant,
        correlationId: String?,
    ): Boolean
}

@Component
class JdbcInboxProjection(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : InboxProjectionPort {
    override fun projectApproval(
        snapshot: ApprovalAttentionSnapshot,
        sourceEventId: UUID,
        sourceOccurredAt: Instant,
        correlationId: String?,
    ): Boolean {
        require(snapshot.requestVersion >= 1)

        val producerKey =
            "approval:" +
                snapshot.requestId +
                ":decide"
        val itemId =
            UUID.nameUUIDFromBytes(
                (
                    "inbox:" +
                        producerKey
                ).toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )
        val state =
            if (
                snapshot.requestState ==
                    ApprovalRequestState.PENDING &&
                snapshot.assignmentState ==
                    "ASSIGNED"
            ) {
                InboxItemState.OPEN
            } else {
                InboxItemState.RESOLVED
            }
        val resolvedAt =
            if (
                state ==
                InboxItemState.RESOLVED
            ) {
                snapshot.completedAt
                    ?: sourceOccurredAt
            } else {
                null
            }
        val updatedAt =
            resolvedAt
                ?: sourceOccurredAt
                    .takeIf {
                        it.isAfter(
                            snapshot.createdAt,
                        )
                    }
                ?: snapshot.createdAt

        val principalType =
            InboxTargetPrincipalType.valueOf(
                snapshot
                    .assignmentPrincipalType
                    .name,
            )

        return jdbc.update(
            """
            INSERT INTO inbox_item (
                id,
                organization_id,
                producer_key,
                source_type,
                source_id,
                source_version,
                action_key,
                attention_class,
                target_principal_type,
                target_user_id,
                target_team_id,
                state,
                safe_title_code,
                safe_summary,
                source_event_id,
                created_at,
                updated_at,
                resolved_at,
                correlation_id,
                version
            )
            VALUES (
                ?, ?, ?,
                'APPROVAL_REQUEST',
                ?, ?,
                'DECIDE_APPROVAL',
                'ACTION_REQUIRED',
                ?,
                ?, ?,
                ?,
                'APPROVAL_DECISION_REQUIRED',
                ?,
                ?, ?, ?, ?,
                ?, 1
            )
            ON CONFLICT (producer_key)
            DO UPDATE SET
                source_version =
                    GREATEST(
                        inbox_item.source_version,
                        EXCLUDED.source_version
                    ),
                target_principal_type =
                    CASE
                        WHEN EXCLUDED.source_version >=
                             inbox_item.source_version
                        THEN EXCLUDED.target_principal_type
                        ELSE inbox_item.target_principal_type
                    END,
                target_user_id =
                    CASE
                        WHEN EXCLUDED.source_version >=
                             inbox_item.source_version
                        THEN EXCLUDED.target_user_id
                        ELSE inbox_item.target_user_id
                    END,
                target_team_id =
                    CASE
                        WHEN EXCLUDED.source_version >=
                             inbox_item.source_version
                        THEN EXCLUDED.target_team_id
                        ELSE inbox_item.target_team_id
                    END,
                state =
                    CASE
                        WHEN inbox_item.state = 'RESOLVED'
                        THEN 'RESOLVED'
                        WHEN EXCLUDED.source_version >=
                             inbox_item.source_version
                        THEN EXCLUDED.state
                        ELSE inbox_item.state
                    END,
                safe_summary =
                    CASE
                        WHEN EXCLUDED.source_version >=
                             inbox_item.source_version
                        THEN EXCLUDED.safe_summary
                        ELSE inbox_item.safe_summary
                    END,
                updated_at =
                    GREATEST(
                        inbox_item.updated_at,
                        EXCLUDED.updated_at
                    ),
                resolved_at =
                    COALESCE(
                        inbox_item.resolved_at,
                        EXCLUDED.resolved_at
                    ),
                correlation_id =
                    COALESCE(
                        EXCLUDED.correlation_id,
                        inbox_item.correlation_id
                    ),
                version =
                    inbox_item.version + 1
            WHERE
                EXCLUDED.source_version >=
                    inbox_item.source_version
                OR (
                    inbox_item.state = 'OPEN'
                    AND EXCLUDED.state =
                        'RESOLVED'
                )
            """.trimIndent(),
            itemId,
            snapshot.organizationId,
            producerKey,
            snapshot.requestId,
            snapshot.requestVersion,
            principalType.name,
            snapshot.assignmentPrincipalId
                .takeIf {
                    principalType ==
                        InboxTargetPrincipalType.USER
                },
            snapshot.assignmentPrincipalId
                .takeIf {
                    principalType ==
                        InboxTargetPrincipalType.TEAM
                },
            state.name,
            snapshot.safeReasonSummary
                ?.trim()
                ?.take(500)
                ?.takeIf {
                    it.isNotEmpty()
                },
            sourceEventId,
            snapshot.createdAt
                .atOffset(ZoneOffset.UTC),
            updatedAt
                .atOffset(ZoneOffset.UTC),
            resolvedAt
                ?.atOffset(ZoneOffset.UTC),
            correlationId
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.take(128),
        ) > 0
    }
}
