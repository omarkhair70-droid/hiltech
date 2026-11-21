package com.hiltech.server.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FieldExecutionTransitionPolicyTest {
    @Test fun `start requires assigned ready active and no prior field blocker`() {
        assertEquals(WorkOrderLifecycle.IN_PROGRESS,
            FieldExecutionTransitionPolicy.next(FieldExecutionAction.START, WorkOrderLifecycle.ASSIGNED, WorkReadiness.READY, true, 0))
        assertNull(FieldExecutionTransitionPolicy.next(FieldExecutionAction.START, WorkOrderLifecycle.PLANNED, WorkReadiness.READY, true, 0))
        assertNull(FieldExecutionTransitionPolicy.next(FieldExecutionAction.START, WorkOrderLifecycle.ASSIGNED, WorkReadiness.BLOCKED, true, 0))
        assertNull(FieldExecutionTransitionPolicy.next(FieldExecutionAction.START, WorkOrderLifecycle.ASSIGNED, WorkReadiness.READY, false, 0))
    }

    @Test fun `block and resume require exact field execution states`() {
        assertEquals(WorkOrderLifecycle.BLOCKED,
            FieldExecutionTransitionPolicy.next(FieldExecutionAction.BLOCK, WorkOrderLifecycle.IN_PROGRESS, WorkReadiness.READY, true, 0))
        assertNull(FieldExecutionTransitionPolicy.next(FieldExecutionAction.BLOCK, WorkOrderLifecycle.BLOCKED, WorkReadiness.READY, true, 1))
        assertEquals(WorkOrderLifecycle.IN_PROGRESS,
            FieldExecutionTransitionPolicy.next(FieldExecutionAction.RESUME, WorkOrderLifecycle.BLOCKED, WorkReadiness.READY, true, 1))
        assertNull(FieldExecutionTransitionPolicy.next(FieldExecutionAction.RESUME, WorkOrderLifecycle.BLOCKED, WorkReadiness.READY, true, 0))
        assertNull(FieldExecutionTransitionPolicy.next(FieldExecutionAction.RESUME, WorkOrderLifecycle.BLOCKED, WorkReadiness.BLOCKED, true, 1))
        assertNull(FieldExecutionTransitionPolicy.next(FieldExecutionAction.RESUME, WorkOrderLifecycle.REWORK_REQUIRED, WorkReadiness.READY, true, 1))
    }
}
