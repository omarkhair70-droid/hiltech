package com.hiltech.spike15

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class ProjectionListener(
    private val store: WorkStore,
) {
    @ApplicationModuleListener(id = "spike15-work-submitted")
    fun onSubmitted(event: WorkSubmittedEvent) {
        store.recordProjection(
            eventKey = "submitted:${event.workOrderId}:${event.version}",
            eventType = "WORK_SUBMITTED",
            workOrderId = event.workOrderId,
            version = event.version,
            correlationId = event.correlationId,
        )
    }

    @ApplicationModuleListener(id = "spike15-work-accepted")
    fun onAccepted(event: WorkAcceptedEvent) {
        store.recordProjection(
            eventKey = "accepted:${event.workOrderId}:${event.version}",
            eventType = "WORK_ACCEPTED",
            workOrderId = event.workOrderId,
            version = event.version,
            correlationId = event.correlationId,
        )
    }
}
