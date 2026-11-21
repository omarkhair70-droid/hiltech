package com.hiltech.server.work

/** Server-owned transitions only; a stale Android snapshot can never change authoritative work. */
enum class FieldExecutionAction { START, BLOCK, RESUME }

object FieldExecutionTransitionPolicy {
    fun next(
        action: FieldExecutionAction,
        current: WorkOrderLifecycle,
        readiness: WorkReadiness,
        projectActive: Boolean,
        openExecutionBlockerCount: Int,
    ): WorkOrderLifecycle? =
        when (action) {
            FieldExecutionAction.START ->
                WorkOrderLifecycle.IN_PROGRESS.takeIf {
                    current == WorkOrderLifecycle.ASSIGNED &&
                        readiness == WorkReadiness.READY &&
                        projectActive && openExecutionBlockerCount == 0
                }
            FieldExecutionAction.BLOCK ->
                WorkOrderLifecycle.BLOCKED.takeIf {
                    current == WorkOrderLifecycle.IN_PROGRESS && openExecutionBlockerCount == 0
                }
            FieldExecutionAction.RESUME ->
                WorkOrderLifecycle.IN_PROGRESS.takeIf {
                    current == WorkOrderLifecycle.BLOCKED &&
                        readiness == WorkReadiness.READY &&
                        projectActive && openExecutionBlockerCount == 1
                }
        }
}
