package com.hiltech.server.service

import com.hiltech.server.work.WorkOrderLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import com.hiltech.server.platform.ProductApiException
import java.time.Instant

class MaintenanceDraftPolicyTest {
    @Test
    fun draftVisitsCannotBeCreatedFromTerminalWork() {
        assertTrue(MaintenanceDraftPolicy.allowedWorkState(WorkOrderLifecycle.PLANNED))
        assertTrue(MaintenanceDraftPolicy.allowedWorkState(WorkOrderLifecycle.IN_PROGRESS))
        assertFalse(MaintenanceDraftPolicy.allowedWorkState(WorkOrderLifecycle.CANCELLED))
        assertFalse(MaintenanceDraftPolicy.allowedWorkState(WorkOrderLifecycle.CLOSED))
        assertFalse(MaintenanceDraftPolicy.allowedWorkState(WorkOrderLifecycle.ACCEPTED))
        assertFalse(MaintenanceDraftPolicy.allowedWorkState(WorkOrderLifecycle.SUBMITTED_FOR_REVIEW))
    }

    @Test
    fun findingsCanBeCapturedDuringRealFieldExecutionWithReadyEvidence() {
        assertFalse(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.DRAFT))
        assertFalse(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.PLANNED))
        assertFalse(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.CANCELLED))
        assertTrue(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.IN_PROGRESS))
        assertTrue(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.BLOCKED))
        assertTrue(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.SUBMITTED_FOR_REVIEW))
        assertTrue(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.REWORK_REQUIRED))
        assertTrue(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.ACCEPTED))
        assertTrue(MaintenanceDraftPolicy.canRecordEvidenceFinding(WorkOrderLifecycle.CLOSED))
    }

    @Test
    fun assignedFieldFindingRequiresExplicitFlagLiveSourceAndOpenFgaAuthority() {
        assertFalse(MaintenanceDraftPolicy.assignedFindingAllowed(
            featureEnabled = false, assignedNow = true, workPermissionGranted = true,
        ))
        assertFalse(MaintenanceDraftPolicy.assignedFindingAllowed(
            featureEnabled = true, assignedNow = false, workPermissionGranted = true,
        ))
        assertFalse(MaintenanceDraftPolicy.assignedFindingAllowed(
            featureEnabled = true, assignedNow = true, workPermissionGranted = false,
        ))
        assertTrue(MaintenanceDraftPolicy.assignedFindingAllowed(
            featureEnabled = true, assignedNow = true, workPermissionGranted = true,
        ))
    }

    @Test
    fun plannedDateMustBeExplicitlyInFuture() {
        val now = Instant.parse("2026-11-23T10:00:00Z")
        assertFalse(MaintenanceDraftPolicy.plannedForIsFuture(now, now))
        assertFalse(MaintenanceDraftPolicy.plannedForIsFuture(now.minusSeconds(1), now))
        assertTrue(MaintenanceDraftPolicy.plannedForIsFuture(now.plusSeconds(1), now))
    }

    @Test
    fun configurableFindingCodeIsNotAnSLAAssumption() {
        assertEquals("FIBER_TEST", MaintenanceDraftPolicy.code("FIBER_TEST"))
        assertThrows(ProductApiException::class.java) {
            MaintenanceDraftPolicy.code("not a configured code")
        }
    }
}
