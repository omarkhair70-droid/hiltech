package com.hiltech.spike.audit

import com.hiltech.spike.work.WorkCompleted
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class WorkAuditListener(
    private val jdbcTemplate: JdbcTemplate,
    private val failureSwitch: AuditFailureSwitch,
) {
    @ApplicationModuleListener(id = "audit-work-completed")
    fun on(event: WorkCompleted) {
        if (failureSwitch.shouldFail()) {
            error("Intentional SPIKE-10 listener failure")
        }

        jdbcTemplate.update(
            """
            MERGE INTO audit_log (event_key, event_type, object_id, object_version)
            KEY (event_key)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "work-completed:" + event.workOrderId + ":" + event.version,
            "WORK_COMPLETED",
            event.workOrderId,
            event.version,
        )
    }
}
