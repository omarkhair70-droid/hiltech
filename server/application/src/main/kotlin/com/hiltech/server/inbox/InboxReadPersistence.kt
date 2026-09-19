package com.hiltech.server.inbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class InboxReadFilter {
    READ,
    UNREAD,
}

data class InboxCandidateRecord(
    val item: InboxItemRecord,
    val readAt: Instant?,
)

interface InboxReadPort {
    fun findWorkQueueCandidates(
        actorUserId: UUID,
        currentAt: Instant,
        asOf: Instant,
        afterCreatedAt: Instant?,
        afterItemId: UUID?,
        limit: Int,
    ): List<InboxCandidateRecord>

    fun findInboxCandidates(
        actorUserId: UUID,
        currentAt: Instant,
        asOf: Instant,
        stateFilter: InboxItemState?,
        readFilter: InboxReadFilter?,
        afterUpdatedAt: Instant?,
        afterItemId: UUID?,
        limit: Int,
    ): List<InboxCandidateRecord>

    fun loadCandidate(
        actorUserId: UUID,
        currentAt: Instant,
        inboxItemId: UUID,
    ): InboxCandidateRecord?

    fun markRead(
        inboxItemId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): Boolean

    fun markUnread(
        inboxItemId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): Boolean
}

@Component
class JdbcInboxReadStore(
    private val jdbc: JdbcTemplate,
) : InboxReadPort {
    override fun findWorkQueueCandidates(
        actorUserId: UUID,
        currentAt: Instant,
        asOf: Instant,
        afterCreatedAt: Instant?,
        afterItemId: UUID?,
        limit: Int,
    ): List<InboxCandidateRecord> {
        require(limit in 1..101)
        require(
            (afterCreatedAt == null) ==
                (afterItemId == null),
        )

        val args =
            mutableListOf<Any>(
                actorUserId,
                actorUserId,
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
                actorUserId,
                actorUserId,
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
                asOf.atOffset(
                    ZoneOffset.UTC,
                ),
            )

        val boundary =
            if (
                afterCreatedAt != null &&
                afterItemId != null
            ) {
                args +=
                    afterCreatedAt.atOffset(
                        ZoneOffset.UTC,
                    )
                args +=
                    afterCreatedAt.atOffset(
                        ZoneOffset.UTC,
                    )
                args += afterItemId
                """
                  AND (
                      i.created_at > ?
                      OR (
                          i.created_at = ?
                          AND i.id > ?
                      )
                  )
                """.trimIndent()
            } else {
                ""
            }

        args += limit

        return jdbc.query(
            baseVisibleSelect +
                """
                
                WHERE i.state = 'OPEN'
                  AND i.attention_class =
                      'ACTION_REQUIRED'
                """ +
                visibleTargetPredicate +
                """
                  AND i.created_at <= ?
                """ +
                "
" +
                boundary +
                """
                
                ORDER BY
                    i.created_at ASC,
                    i.id ASC
                LIMIT ?
                """.trimIndent(),
            mapper,
            *args.toTypedArray(),
        )
    }

    override fun findInboxCandidates(
        actorUserId: UUID,
        currentAt: Instant,
        asOf: Instant,
        stateFilter: InboxItemState?,
        readFilter: InboxReadFilter?,
        afterUpdatedAt: Instant?,
        afterItemId: UUID?,
        limit: Int,
    ): List<InboxCandidateRecord> {
        require(limit in 1..101)
        require(
            (afterUpdatedAt == null) ==
                (afterItemId == null),
        )

        val args =
            mutableListOf<Any>(
                actorUserId,
                actorUserId,
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
                actorUserId,
                actorUserId,
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
                currentAt.atOffset(
                    ZoneOffset.UTC,
                ),
            )

        val filters =
            StringBuilder(
                """
                
                WHERE 1 = 1
                """.trimIndent(),
            )
        filters.append(
            "
",
        )
        filters.append(
            visibleTargetPredicate,
        )

        stateFilter?.let {
            filters.append(
                "
  AND i.state = ?",
            )
            args += it.name
        }

        when (readFilter) {
            InboxReadFilter.READ -> {
                filters.append(
                    "
  AND us.read_at IS NOT NULL",
                )
            }

            InboxReadFilter.UNREAD -> {
                filters.append(
                    "
  AND us.read_at IS NULL",
                )
            }

            null -> Unit
        }

        filters.append(
            "
  AND i.updated_at <= ?",
        )
        args +=
            asOf.atOffset(
                ZoneOffset.UTC,
            )

        if (
            afterUpdatedAt != null &&
            afterItemId != null
        ) {
            filters.append(
                """
                
                  AND (
                      i.updated_at < ?
                      OR (
                          i.updated_at = ?
                          AND i.id < ?
                      )
                  )
                """.trimIndent(),
            )
            args +=
                afterUpdatedAt.atOffset(
                    ZoneOffset.UTC,
                )
            args +=
                afterUpdatedAt.atOffset(
                    ZoneOffset.UTC,
                )
            args += afterItemId
        }

        args += limit

        return jdbc.query(
            baseVisibleSelect +
                filters.toString() +
                """
                
                ORDER BY
                    i.updated_at DESC,
                    i.id DESC
                LIMIT ?
                """.trimIndent(),
            mapper,
            *args.toTypedArray(),
        )
    }

    override fun loadCandidate(
        actorUserId: UUID,
        currentAt: Instant,
        inboxItemId: UUID,
    ): InboxCandidateRecord? =
        jdbc.query(
            baseVisibleSelect +
                """
                
                WHERE i.id = ?
                """ +
                visibleTargetPredicate,
            mapper,
            actorUserId,
            inboxItemId,
            actorUserId,
            currentAt.atOffset(
                ZoneOffset.UTC,
            ),
            currentAt.atOffset(
                ZoneOffset.UTC,
            ),
            actorUserId,
            actorUserId,
            currentAt.atOffset(
                ZoneOffset.UTC,
            ),
            currentAt.atOffset(
                ZoneOffset.UTC,
            ),
        ).singleOrNull()

    override fun markRead(
        inboxItemId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): Boolean {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.update(
            """
            INSERT INTO inbox_user_state (
                inbox_item_id,
                user_identity_id,
                read_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, 1
            )
            ON CONFLICT (
                inbox_item_id,
                user_identity_id
            )
            DO UPDATE SET
                read_at =
                    EXCLUDED.read_at,
                updated_at =
                    EXCLUDED.updated_at,
                version =
                    inbox_user_state.version + 1
            WHERE
                inbox_user_state.read_at
                    IS NULL
            """.trimIndent(),
            inboxItemId,
            actorUserId,
            timestamp,
            timestamp,
        ) > 0
    }

    override fun markUnread(
        inboxItemId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE inbox_user_state
            SET read_at = NULL,
                updated_at = ?,
                version = version + 1
            WHERE inbox_item_id = ?
              AND user_identity_id = ?
              AND read_at IS NOT NULL
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            inboxItemId,
            actorUserId,
        ) > 0

    private val baseVisibleSelect =
        """
        SELECT
            i.id,
            i.organization_id,
            i.producer_key,
            i.source_type,
            i.source_id,
            i.source_version,
            i.action_key,
            i.target_principal_type,
            i.target_user_id,
            i.target_team_id,
            i.state,
            i.safe_title_code,
            i.safe_summary,
            i.created_at,
            i.updated_at,
            i.resolved_at,
            i.correlation_id,
            us.read_at
        FROM inbox_item i
        LEFT JOIN inbox_user_state us
          ON us.inbox_item_id = i.id
         AND us.user_identity_id = ?
        """.trimIndent()

    private val visibleTargetPredicate =
        """
          AND EXISTS (
              SELECT 1
              FROM organization_membership om
              JOIN user_identity ui
                ON ui.id = om.user_identity_id
              WHERE om.organization_id =
                    i.organization_id
                AND om.user_identity_id = ?
                AND om.state = 'ACTIVE'
                AND ui.status = 'ACTIVE'
                AND om.valid_from <= ?
                AND (
                    om.valid_until IS NULL
                    OR om.valid_until > ?
                )
          )
          AND (
              (
                  i.target_principal_type =
                      'USER'
                  AND i.target_user_id = ?
              )
              OR
              (
                  i.target_principal_type =
                      'TEAM'
                  AND EXISTS (
                      SELECT 1
                      FROM team t
                      JOIN team_membership tm
                        ON tm.team_id = t.id
                      WHERE t.id =
                            i.target_team_id
                        AND t.organization_id =
                            i.organization_id
                        AND t.active = true
                        AND tm.user_identity_id = ?
                        AND tm.valid_from <= ?
                        AND (
                            tm.valid_until IS NULL
                            OR tm.valid_until > ?
                        )
                  )
              )
          )
        """.trimIndent()

    private val mapper =
        { rs: java.sql.ResultSet, _: Int ->
            val principalType =
                InboxTargetPrincipalType.valueOf(
                    rs.getString(
                        "target_principal_type",
                    ),
                )
            InboxCandidateRecord(
                item =
                    InboxItemRecord(
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
                        actionKey =
                            rs.getString(
                                "action_key",
                            ),
                        targetPrincipalType =
                            principalType,
                        targetPrincipalId =
                            rs.getObject(
                                if (
                                    principalType ==
                                    InboxTargetPrincipalType.USER
                                ) {
                                    "target_user_id"
                                } else {
                                    "target_team_id"
                                },
                                UUID::class.java,
                            ),
                        state =
                            InboxItemState.valueOf(
                                rs.getString(
                                    "state",
                                ),
                            ),
                        safeTitleCode =
                            rs.getString(
                                "safe_title_code",
                            ),
                        safeSummary =
                            rs.getString(
                                "safe_summary",
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
                        resolvedAt =
                            rs.getObject(
                                "resolved_at",
                                OffsetDateTime::class.java,
                            )?.toInstant(),
                        correlationId =
                            rs.getString(
                                "correlation_id",
                            ),
                    ),
                readAt =
                    rs.getObject(
                        "read_at",
                        OffsetDateTime::class.java,
                    )?.toInstant(),
            )
        }
}
