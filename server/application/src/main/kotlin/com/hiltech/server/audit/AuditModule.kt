package com.hiltech.server.audit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class AuditEventRecord(
    val id: UUID = UUID.randomUUID(),
    val actorUserId: UUID?,
    val action: String,
    val targetType: String,
    val targetId: UUID?,
    val previousStateRef: String? = null,
    val newStateRef: String? = null,
    val safeDiffJson: String? = null,
    val occurredAt: Instant,
    val correlationId: String,
    val traceId: String? = null,
    val reason: String? = null,
    val delegationId: UUID? = null,
    val configRevisionRefsJson: String? = null,
)

fun interface AuditEventWriter {
    fun append(event: AuditEventRecord)

    companion object {
        val NOOP =
            AuditEventWriter { _ -> Unit }
    }
}

@Component
class JdbcAuditEventWriter(
    private val jdbc: JdbcTemplate,
) : AuditEventWriter {
    override fun append(
        event: AuditEventRecord,
    ) {
        require(event.action.isNotBlank())
        require(event.targetType.isNotBlank())
        require(event.correlationId.isNotBlank())

        jdbc.update(
            """
            INSERT INTO audit_event (
                id,
                actor_user_id,
                action,
                target_type,
                target_id,
                previous_state_ref,
                new_state_ref,
                safe_diff,
                occurred_at,
                correlation_id,
                trace_id,
                reason,
                delegation_id,
                config_revision_refs
            )
            VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                CAST(? AS jsonb),
                ?, ?, ?, ?, ?,
                CAST(? AS jsonb)
            )
            """.trimIndent(),
            event.id,
            event.actorUserId,
            event.action,
            event.targetType,
            event.targetId,
            event.previousStateRef,
            event.newStateRef,
            event.safeDiffJson,
            event.occurredAt.atOffset(
                ZoneOffset.UTC,
            ),
            event.correlationId.take(128),
            event.traceId?.take(64),
            event.reason,
            event.delegationId,
            event.configRevisionRefsJson,
        )
    }
}
