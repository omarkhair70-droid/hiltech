package com.hiltech.server.work

import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.security.OpenFgaTuple
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

object WorkAuthorizationRelations {
    fun project(workOrderId: UUID, projectId: UUID) = OpenFgaTuple(
        subjectType = "project", subjectId = projectId.toString(), relation = "project",
        objectType = "work_order", objectId = workOrderId.toString(),
    )
    fun site(workOrderId: UUID, siteId: UUID) = OpenFgaTuple(
        subjectType = "site", subjectId = siteId.toString(), relation = "site",
        objectType = "work_order", objectId = workOrderId.toString(),
    )
}

@Component
class WorkAuthorizationProjectionBridge(private val writer: AuthorizationProjectionIntentWriter) {
    fun workOrderCreated(workOrder: WorkOrderSnapshot, eventId: UUID, at: Instant) {
        listOf(
            WorkAuthorizationRelations.project(workOrder.workOrderId, workOrder.projectId),
            WorkAuthorizationRelations.site(workOrder.workOrderId, workOrder.siteId),
        ).forEachIndexed { index, tuple ->
            writer.write(
                AuthorizationProjectionIntent(
                    eventId = if (index == 0) eventId else UUID.nameUUIDFromBytes("$eventId:site".toByteArray()),
                    tuple = tuple,
                    desiredState = AuthorizationDesiredState.PRESENT,
                    sourceType = "WorkOrder",
                    sourceId = workOrder.workOrderId.toString(),
                    sourceVersion = workOrder.version,
                    eventType = if (index == 0) "WORK_ORDER_PROJECT_LINKED" else "WORK_ORDER_SITE_LINKED",
                    occurredAt = at,
                ),
            )
        }
    }
}
