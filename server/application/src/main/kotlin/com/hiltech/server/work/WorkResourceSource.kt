package com.hiltech.server.work

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class WorkResourceFact(
    val satisfied: Boolean, val reason: String, val sourceId: UUID?, val sourceVersion: Long?, val asOf: Instant,
)

fun interface WorkResourceSource {
    fun evaluate(order: WorkOrderSnapshot, requirement: ReadinessRequirementExecutionSnapshot, at: Instant): WorkResourceFact?
}

data class WorkResourceChanged(val id: UUID, val workOrderId: UUID, val actor: UUID, val correlationId: String)

@Component
class WorkResourceReadinessListener(private val readiness: ReadinessAssignmentService) {
    @ApplicationModuleListener
    fun resourceChanged(change: WorkResourceChanged) = readiness.resourceChanged(change)
}
