package com.hiltech.server.service

import com.hiltech.server.work.WorkOrderLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MaintenanceReviewPolicyTest {
    @Test
    fun reviewMustStartThenConfirmAndMayRequestAnAuditedRecheck() {
        assertEquals("REVIEWING", MaintenanceReviewPolicy.next(null, "REVIEW_STARTED"))
        assertEquals("COMPLETED_INTERNAL",
            MaintenanceReviewPolicy.next("REVIEWING", "INTERNAL_TECHNICAL_COMPLETION"))
        assertEquals("RECHECK_REQUIRED",
            MaintenanceReviewPolicy.next("COMPLETED_INTERNAL", "RECHECK_REQUESTED"))
        assertEquals("REVIEWING",
            MaintenanceReviewPolicy.next("RECHECK_REQUIRED", "REVIEW_STARTED"))
    }

    @Test
    fun neverTreatClientAcceptanceOrSkippedReviewAsTechnicalProof() {
        assertNull(MaintenanceReviewPolicy.next(null, "INTERNAL_TECHNICAL_COMPLETION"))
        assertNull(MaintenanceReviewPolicy.next(null, "CLIENT_ACCEPTED"))
        assertNull(MaintenanceReviewPolicy.next("REVIEWING", "CLIENT_ACCEPTED"))
        assertNull(MaintenanceReviewPolicy.next("COMPLETED_INTERNAL", "INTERNAL_TECHNICAL_COMPLETION"))
        assertNull(MaintenanceReviewPolicy.next("REVIEWING", "REVIEW_STARTED"))
        assertNull(MaintenanceReviewPolicy.next("RECHECK_REQUIRED", "INTERNAL_TECHNICAL_COMPLETION"))
        assertNull(MaintenanceReviewPolicy.next("COMPLETED_INTERNAL", "REVIEW_STARTED"))
    }

    private fun visit(plannedFor: Instant) = MaintenanceVisitRow(
        id = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        projectId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
        workOrderId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
        siteId = UUID.fromString("44444444-4444-4444-8444-444444444444"),
        plannedFor = plannedFor,
        state = "DRAFT", coverageState = "UNVERIFIED",
        workOrderVersionAtPlan = 1,
        createdAt = plannedFor.minusSeconds(3600),
    )

    @Test
    fun sourceReadinessRejectsFutureAndUnacceptedWorkEvenWithEvidence() {
        val now = Instant.parse("2026-11-23T10:00:00Z")
        val blockers = MaintenanceReviewPolicy.missing(
            visit(now.plusSeconds(3600)), WorkOrderLifecycle.IN_PROGRESS,
            requiredChecklistTotal = 2, requiredChecklistPending = 1,
            readyEvidenceCount = 1, at = now,
        )
        assertTrue("PLANNED_VISIT_WINDOW_NOT_REACHED" in blockers)
        assertTrue("CANONICAL_WORK_NOT_TECHNICALLY_ACCEPTED" in blockers)
        assertTrue("REQUIRED_WORK_CHECKLIST_INCOMPLETE" in blockers)
        assertFalse("CURRENT_VISIT_READY_EVIDENCE_MISSING" in blockers)
    }

    @Test
    fun sourceReadinessRequiresConfiguredChecklistAndReadyVisitProof() {
        val now = Instant.parse("2026-11-23T10:00:00Z")
        val blockers = MaintenanceReviewPolicy.missing(
            visit(now.minusSeconds(1)), WorkOrderLifecycle.ACCEPTED,
            requiredChecklistTotal = 0, requiredChecklistPending = 0,
            readyEvidenceCount = 0, at = now,
        )
        assertTrue("REQUIRED_WORK_CHECKLIST_NOT_DEFINED" in blockers)
        assertTrue("CURRENT_VISIT_READY_EVIDENCE_MISSING" in blockers)
    }

    @Test
    fun acceptedWorkWithRealChecklistAndCurrentEvidenceCanBeInternallyConfirmed() {
        val now = Instant.parse("2026-11-23T10:00:00Z")
        assertTrue(MaintenanceReviewPolicy.missing(
            visit(now.minusSeconds(1)), WorkOrderLifecycle.CLOSED,
            requiredChecklistTotal = 3, requiredChecklistPending = 0,
            readyEvidenceCount = 1, at = now,
        ).isEmpty())
    }
}
