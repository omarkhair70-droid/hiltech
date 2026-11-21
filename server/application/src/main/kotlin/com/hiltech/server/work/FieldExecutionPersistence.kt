package com.hiltech.server.work

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class OpenFieldExecutionBlocker(val id: UUID, val version: Long)

interface FieldExecutionPersistencePort {
    fun openFieldExecutionBlockers(workOrderId: UUID): List<OpenFieldExecutionBlocker>
    fun transition(workOrderId: UUID, expectedVersion: Long, from: WorkOrderLifecycle,
                   to: WorkOrderLifecycle, at: Instant, setActualStart: Boolean): Boolean
    fun recordBlocker(id: UUID, workOrderId: UUID, actor: UUID, reason: String)
    fun resolveBlocker(workOrderId: UUID, id: UUID, version: Long, reason: String, at: Instant): Boolean
}

@Component
class JdbcFieldExecutionPersistence(private val jdbc: JdbcTemplate) : FieldExecutionPersistencePort {
    override fun openFieldExecutionBlockers(workOrderId: UUID): List<OpenFieldExecutionBlocker> =
        jdbc.query(
            """SELECT id, version FROM work_blocker
               WHERE work_order_id = ? AND blocker_type_code = 'FIELD_EXECUTION'
                 AND source_requirement_id IS NULL AND state = 'OPEN'
               ORDER BY id""",
            { rs, _ -> OpenFieldExecutionBlocker(rs.getObject("id", UUID::class.java), rs.getLong("version")) },
            workOrderId,
        )

    override fun transition(workOrderId: UUID, expectedVersion: Long, from: WorkOrderLifecycle,
                            to: WorkOrderLifecycle, at: Instant, setActualStart: Boolean): Boolean =
        jdbc.update(
            """UPDATE work_order
               SET lifecycle_state = ?, version = version + 1, updated_at = ?,
                   actual_start = CASE WHEN ? THEN COALESCE(actual_start, ?) ELSE actual_start END
               WHERE id = ? AND version = ? AND lifecycle_state = ?""",
            to.name, at.atOffset(ZoneOffset.UTC), setActualStart, at.atOffset(ZoneOffset.UTC),
            workOrderId, expectedVersion, from.name,
        ) == 1

    override fun recordBlocker(id: UUID, workOrderId: UUID, actor: UUID, reason: String) {
        jdbc.update(
            """INSERT INTO work_blocker(
                 id, work_order_id, blocker_type_code, severity_code, description,
                 created_by, owner_target_type, owner_target_id, state,
                 resolved_at, resolution, client_visible, version, source_requirement_id, explanation_code
               ) VALUES (?, ?, 'FIELD_EXECUTION', 'BLOCKING', ?, ?, NULL, NULL,
                         'OPEN', NULL, NULL, false, 1, NULL, 'FIELD_EXECUTION_INTERRUPTED')""",
            id, workOrderId, reason, actor,
        )
    }

    override fun resolveBlocker(workOrderId: UUID, id: UUID, version: Long, reason: String, at: Instant): Boolean =
        jdbc.update(
            """UPDATE work_blocker
               SET state = 'RESOLVED', resolved_at = ?, resolution = ?, version = version + 1
               WHERE id = ? AND work_order_id = ? AND version = ?
                 AND blocker_type_code = 'FIELD_EXECUTION'
                 AND source_requirement_id IS NULL AND state = 'OPEN'""",
            at.atOffset(ZoneOffset.UTC), reason, id, workOrderId, version,
        ) == 1
}
