package com.hiltech.server.warehouse

import com.hiltech.server.work.WorkResourceChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class ReservationReadinessEvents(
    private val reservations: ReservationPersistence,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @EventListener
    fun assetService(event: AssetServiceChanged) =
        publish(ResourceKind.ASSET, event.assetId, "service:${event.version}", event.correlationId)

    @EventListener
    fun assetCustody(event: AssetCustodyMovementPosted) =
        publish(ResourceKind.ASSET, event.assetId, "custody:${event.movementId}", event.correlationId)

    private fun publish(kind: ResourceKind, resource: UUID, source: String, correlation: String) {
        reservations.affectedWork(kind, resource, clock.instant()).distinctBy { it.first }.forEach { (work, actor) ->
            val id = UUID.nameUUIDFromBytes("resource-changed:$resource:$source:$work".toByteArray())
            events.publishEvent(WorkResourceChanged(id, work, actor, correlation))
        }
    }
}
