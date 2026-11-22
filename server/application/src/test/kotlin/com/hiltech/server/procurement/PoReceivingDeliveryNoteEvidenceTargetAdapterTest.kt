package com.hiltech.server.procurement

import com.hiltech.server.documents.PoReceivingDeliveryNoteEvidenceTarget
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PoReceivingDeliveryNoteEvidenceTargetAdapterTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val warehouse = Mockito.mock(WarehousePersistencePort::class.java)
    private val authority = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val actor = UUID.randomUUID()
    private val target = PoReceivingDeliveryNoteEvidenceTarget(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), "IN_PROGRESS",
    )

    @Test fun noLiveWarehouseProcurementFlagMeansNoUploadOrRead() {
        val adapter = PoReceivingDeliveryNoteEvidenceTargetAdapter(
            jdbc, warehouse, authority,
            Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"), ZoneOffset.UTC),
            false,
        )
        assertFalse(adapter.canReserve(actor, target))
        assertFalse(adapter.canAccess(actor, target))
        Mockito.verifyNoInteractions(jdbc, warehouse, authority)
    }

    @Test fun anUnboundReceivingDocumentCannotBypassWarehouseAuthority() {
        val adapter = PoReceivingDeliveryNoteEvidenceTargetAdapter(
            jdbc, warehouse, authority, Clock.systemUTC(), true,
        )
        assertFalse(adapter.canReserve(actor, target))
        assertFalse(adapter.canAccess(actor, target))
        Mockito.verifyNoInteractions(warehouse, authority)
    }

    @Test fun closedReceivingCannotAcquireNewDeliveryNote() {
        val adapter = PoReceivingDeliveryNoteEvidenceTargetAdapter(
            jdbc, warehouse, authority, Clock.systemUTC(), true,
        )
        val closed = target.copy(receivingState = "RECEIVED")
        assertFalse(adapter.canReserve(actor, closed))
        Mockito.verifyNoInteractions(jdbc, warehouse, authority)
    }
}
