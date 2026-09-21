package com.hiltech.server.activity

import com.hiltech.server.documents.EvidenceTerminalStateChanged
import com.hiltech.server.work.ProjectHealthChanged
import com.hiltech.server.work.ProjectHoldChanged
import com.hiltech.server.work.WorkAccepted
import com.hiltech.server.work.WorkReworkRequested
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class ActivityRecord(
    val id: UUID,
    val sourceEventId: UUID,
    val activityType: String,
    val contextType: String,
    val contextId: UUID,
    val sourceType: String,
    val sourceId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID?,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val safeSummary: Map<String, String>,
    val correlationId: String?,
    val classificationCode: String,
)

interface ActivityProjectionPort {
    fun appendEvidenceTerminal(
        event: EvidenceTerminalStateChanged,
    ): Boolean

    fun appendWorkAccepted(
        event: WorkAccepted,
    ): Boolean

    fun appendWorkReworkRequested(
        event: WorkReworkRequested,
    ): Boolean

    fun appendProjectHealthChanged(
        event: ProjectHealthChanged,
    ): Boolean

    fun appendProjectHoldChanged(
        event: ProjectHoldChanged,
    ): Boolean

    fun findWorkOrderActivity(
        workOrderId: UUID,
        asOf: Instant,
        afterOccurredAt: Instant?,
        afterActivityId: UUID?,
        limit: Int,
    ): List<ActivityRecord>
}

@Component
class JdbcActivityProjection(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : ActivityProjectionPort {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    override fun appendEvidenceTerminal(
        event: EvidenceTerminalStateChanged,
    ): Boolean {
        require(
            event.storageState in
                setOf(
                    "READY",
                    "QUARANTINED",
                    "REJECTED",
                ),
        )
        require(event.sourceVersion >= 1)

        val sourceEventId =
            UUID.fromString(event.eventId)
        val evidenceId =
            UUID.fromString(event.evidenceId)
        val workOrderId =
            UUID.fromString(event.workOrderId)
        val actorUserId =
            event.actorIdentityId
                .takeIf { it.isNotBlank() }
                ?.let(UUID::fromString)
        val occurredAt =
            Instant.parse(event.occurredAt)

        val safeSummary =
            buildJsonObject {
                put(
                    "storageState",
                    event.storageState,
                )
                if (
                    !event.classificationCode
                        .equals(
                            "HIGHLY_RESTRICTED",
                            ignoreCase = true,
                        )
                ) {
                    put(
                        "evidenceTypeCode",
                        event.evidenceTypeCode,
                    )
                    put(
                        "evidenceRequirementKey",
                        event.evidenceRequirementKey,
                    )
                }
            }.toString()

        val activityId =
            UUID.nameUUIDFromBytes(
                (
                    "activity:" +
                        sourceEventId
                ).toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )

        return jdbc.update(
            """
            INSERT INTO activity_event (
                id,
                source_event_id,
                activity_type,
                context_type,
                context_id,
                source_type,
                source_id,
                source_version,
                actor_user_id,
                occurred_at,
                recorded_at,
                safe_summary,
                correlation_id,
                classification_code,
                schema_version
            )
            VALUES (
                ?, ?,
                ?, 'WORK_ORDER', ?,
                'EVIDENCE', ?, ?,
                ?, ?, ?,
                CAST(? AS jsonb),
                ?, ?, 1
            )
            ON CONFLICT (source_event_id)
            DO NOTHING
            """.trimIndent(),
            activityId,
            sourceEventId,
            "EVIDENCE_" +
                event.storageState,
            workOrderId,
            evidenceId,
            event.sourceVersion,
            actorUserId,
            occurredAt.atOffset(
                ZoneOffset.UTC,
            ),
            clock.instant()
                .atOffset(ZoneOffset.UTC),
            safeSummary,
            event.correlationId
                .takeIf { it.isNotBlank() }
                ?.take(128),
            event.classificationCode,
        ) == 1
    }

    override fun appendWorkAccepted(
        event: WorkAccepted,
    ): Boolean =
        appendDomainActivity(
            sourceEventId = event.eventId,
            activityType = "WORK_ACCEPTED",
            contextType = "WORK_ORDER",
            contextId = event.workOrderId,
            sourceType = "WORK_ORDER",
            sourceId = event.workOrderId,
            sourceVersion = event.sourceVersion,
            actorUserId = event.actorUserId,
            occurredAt = event.occurredAt,
            correlationId = event.correlationId,
            safeSummary =
                buildJsonObject {
                    put(
                        "projectId",
                        event.projectId.toString(),
                    )
                    put(
                        "decisionId",
                        event.decisionId.toString(),
                    )
                }.toString(),
        )

    override fun appendWorkReworkRequested(
        event: WorkReworkRequested,
    ): Boolean =
        appendDomainActivity(
            sourceEventId = event.eventId,
            activityType =
                "WORK_REWORK_REQUESTED",
            contextType = "WORK_ORDER",
            contextId = event.workOrderId,
            sourceType = "WORK_ORDER",
            sourceId = event.workOrderId,
            sourceVersion = event.sourceVersion,
            actorUserId = event.actorUserId,
            occurredAt = event.occurredAt,
            correlationId = event.correlationId,
            safeSummary =
                buildJsonObject {
                    put(
                        "projectId",
                        event.projectId.toString(),
                    )
                    put(
                        "decisionId",
                        event.decisionId.toString(),
                    )
                }.toString(),
        )

    override fun appendProjectHealthChanged(
        event: ProjectHealthChanged,
    ): Boolean =
        appendDomainActivity(
            sourceEventId = event.eventId,
            activityType =
                "PROJECT_HEALTH_CHANGED",
            contextType = "PROJECT",
            contextId = event.projectId,
            sourceType = "PROJECT",
            sourceId = event.projectId,
            sourceVersion = event.sourceVersion,
            actorUserId = event.actorUserId,
            occurredAt = event.occurredAt,
            correlationId = event.correlationId,
            safeSummary =
                buildJsonObject {
                    put(
                        "previousState",
                        event.previousState.name,
                    )
                    put(
                        "newState",
                        event.newState.name,
                    )
                }.toString(),
        )

    override fun appendProjectHoldChanged(
        event: ProjectHoldChanged,
    ): Boolean =
        appendDomainActivity(
            sourceEventId = event.eventId,
            activityType =
                if (
                    event.toState.name ==
                    "ON_HOLD"
                ) {
                    "PROJECT_PUT_ON_HOLD"
                } else {
                    "PROJECT_RESUMED"
                },
            contextType = "PROJECT",
            contextId = event.projectId,
            sourceType = "PROJECT",
            sourceId = event.projectId,
            sourceVersion = event.sourceVersion,
            actorUserId = event.actorUserId,
            occurredAt = event.occurredAt,
            correlationId = event.correlationId,
            safeSummary =
                buildJsonObject {
                    put(
                        "fromState",
                        event.fromState.name,
                    )
                    put(
                        "toState",
                        event.toState.name,
                    )
                }.toString(),
        )

    private fun appendDomainActivity(
        sourceEventId: UUID,
        activityType: String,
        contextType: String,
        contextId: UUID,
        sourceType: String,
        sourceId: UUID,
        sourceVersion: Long,
        actorUserId: UUID?,
        occurredAt: Instant,
        correlationId: String?,
        safeSummary: String,
    ): Boolean {
        require(sourceVersion >= 1)
        val activityId =
            UUID.nameUUIDFromBytes(
                (
                    "activity:" +
                        sourceEventId
                ).toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )
        return jdbc.update(
            """
            INSERT INTO activity_event (
                id,
                source_event_id,
                activity_type,
                context_type,
                context_id,
                source_type,
                source_id,
                source_version,
                actor_user_id,
                occurred_at,
                recorded_at,
                safe_summary,
                correlation_id,
                classification_code,
                schema_version
            )
            VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                CAST(? AS jsonb),
                ?, 'INTERNAL', 1
            )
            ON CONFLICT (source_event_id)
            DO NOTHING
            """.trimIndent(),
            activityId,
            sourceEventId,
            activityType,
            contextType,
            contextId,
            sourceType,
            sourceId,
            sourceVersion,
            actorUserId,
            occurredAt.atOffset(
                ZoneOffset.UTC,
            ),
            clock.instant()
                .atOffset(ZoneOffset.UTC),
            safeSummary,
            correlationId
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.take(128),
        ) == 1
    }

    override fun findWorkOrderActivity(
        workOrderId: UUID,
        asOf: Instant,
        afterOccurredAt: Instant?,
        afterActivityId: UUID?,
        limit: Int,
    ): List<ActivityRecord> {
        require(limit in 1..101)
        require(
            (afterOccurredAt == null) ==
                (afterActivityId == null),
        )

        val baseSelect =
            """
            SELECT
                id,
                source_event_id,
                activity_type,
                context_type,
                context_id,
                source_type,
                source_id,
                source_version,
                actor_user_id,
                occurred_at,
                recorded_at,
                safe_summary::text
                    AS safe_summary,
                correlation_id,
                classification_code
            FROM activity_event
            WHERE context_type = 'WORK_ORDER'
              AND context_id = ?
              AND recorded_at <= ?
            """.trimIndent()

        val rows =
            if (
                afterOccurredAt == null ||
                afterActivityId == null
            ) {
                jdbc.query(
                    baseSelect +
                        """
                        
                        ORDER BY
                            occurred_at DESC,
                            id DESC
                        LIMIT ?
                        """.trimIndent(),
                    mapper,
                    workOrderId,
                    asOf.atOffset(
                        ZoneOffset.UTC,
                    ),
                    limit,
                )
            } else {
                jdbc.query(
                    baseSelect +
                        """
                        
                          AND (
                              occurred_at < ?
                              OR (
                                  occurred_at = ?
                                  AND id < ?
                              )
                          )
                        ORDER BY
                            occurred_at DESC,
                            id DESC
                        LIMIT ?
                        """.trimIndent(),
                    mapper,
                    workOrderId,
                    asOf.atOffset(
                        ZoneOffset.UTC,
                    ),
                    afterOccurredAt
                        .atOffset(ZoneOffset.UTC),
                    afterOccurredAt
                        .atOffset(ZoneOffset.UTC),
                    afterActivityId,
                    limit,
                )
            }

        return rows
    }

    private val mapper =
        { rs: java.sql.ResultSet, _: Int ->
            ActivityRecord(
                id =
                    rs.getObject(
                        "id",
                        UUID::class.java,
                    ),
                sourceEventId =
                    rs.getObject(
                        "source_event_id",
                        UUID::class.java,
                    ),
                activityType =
                    rs.getString(
                        "activity_type",
                    ),
                contextType =
                    rs.getString(
                        "context_type",
                    ),
                contextId =
                    rs.getObject(
                        "context_id",
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
                actorUserId =
                    rs.getObject(
                        "actor_user_id",
                        UUID::class.java,
                    ),
                occurredAt =
                    rs.getObject(
                        "occurred_at",
                        java.time.OffsetDateTime::class.java,
                    ).toInstant(),
                recordedAt =
                    rs.getObject(
                        "recorded_at",
                        java.time.OffsetDateTime::class.java,
                    ).toInstant(),
                safeSummary =
                    json.parseToJsonElement(
                        rs.getString(
                            "safe_summary",
                        ),
                    ).jsonObject
                        .mapValues {
                            it.value
                                .jsonPrimitive
                                .content
                        },
                correlationId =
                    rs.getString(
                        "correlation_id",
                    ),
                classificationCode =
                    rs.getString(
                        "classification_code",
                    ),
            )
        }
}

@Component
class ActivityProjectionListener(
    private val projection:
        ActivityProjectionPort,
) {
    @ApplicationModuleListener
    fun on(
        event: EvidenceTerminalStateChanged,
    ) {
        projection.appendEvidenceTerminal(
            event,
        )
    }

    @ApplicationModuleListener
    fun onWorkAccepted(
        event: WorkAccepted,
    ) {
        projection.appendWorkAccepted(
            event,
        )
    }

    @ApplicationModuleListener
    fun onWorkReworkRequested(
        event: WorkReworkRequested,
    ) {
        projection.appendWorkReworkRequested(
            event,
        )
    }

    @ApplicationModuleListener
    fun onProjectHealthChanged(
        event: ProjectHealthChanged,
    ) {
        projection.appendProjectHealthChanged(
            event,
        )
    }

    @ApplicationModuleListener
    fun onProjectHoldChanged(
        event: ProjectHoldChanged,
    ) {
        projection.appendProjectHoldChanged(
            event,
        )
    }
}
