package com.hiltech.spike.work

import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkService(
    private val jdbcTemplate: JdbcTemplate,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    fun completeWork(
        workOrderId: String,
        version: Long,
    ) {
        jdbcTemplate.update(
            """
            MERGE INTO work_order (work_order_id, state, version)
            KEY (work_order_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            workOrderId,
            "COMPLETED",
            version,
        )

        eventPublisher.publishEvent(
            WorkCompleted(
                workOrderId = workOrderId,
                version = version,
            ),
        )
    }
}
