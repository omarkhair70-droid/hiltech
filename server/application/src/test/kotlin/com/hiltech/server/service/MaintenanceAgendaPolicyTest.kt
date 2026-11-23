package com.hiltech.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MaintenanceAgendaPolicyTest {
    @Test
    fun visitWindowIsAnObservationNotAnOverdueServiceSla() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        assertEquals("FUTURE_DRAFT_VISIT",
            MaintenanceAgendaPolicy.visitWindowState(now.plusSeconds(1), now))
        assertEquals("DRAFT_VISIT_WINDOW_REACHED",
            MaintenanceAgendaPolicy.visitWindowState(now, now))
        assertEquals("DRAFT_VISIT_WINDOW_REACHED",
            MaintenanceAgendaPolicy.visitWindowState(now.minusSeconds(1), now))
    }

    @Test
    fun explicitUtcWindowMustIncreaseWithoutInventedCadence() {
        val from = Instant.parse("2026-11-23T00:00:00Z")
        val to = from.plusSeconds(3600)
        assertTrue(MaintenanceAgendaPolicy.validWindow(from, to))
        assertFalse(MaintenanceAgendaPolicy.validWindow(from, from))
        assertFalse(MaintenanceAgendaPolicy.validWindow(to, from))
    }
}
