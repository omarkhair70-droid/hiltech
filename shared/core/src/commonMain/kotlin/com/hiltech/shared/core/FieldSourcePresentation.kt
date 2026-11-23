package com.hiltech.shared.core

/**
 * UI source alignment, not an authorization decision.
 *
 * A cached JobBundle must never be presented as the currently selected job
 * unless the current Today projection still lists that exact WorkOrder.
 * The backend continues to decide access and whether actions are permitted.
 */
fun fieldSourceAllowsSelectedBundle(
    selectedWorkOrderId: String?,
    bundleWorkOrderId: String,
    todayWorkOrderIds: List<String>,
): Boolean =
    !selectedWorkOrderId.isNullOrBlank() &&
        selectedWorkOrderId == bundleWorkOrderId &&
        todayWorkOrderIds.any { it == selectedWorkOrderId }

/**
 * Focus is presentation-only, scoped by the caller's signed-in identity/org.
 * A removed assignment immediately invalidates local focus; a later regrant
 * requires a new user tap rather than silently reopening the old WorkOrder.
 * Do not use this as a server authorization check.
 */
fun fieldFocusStillAssigned(
    focusedWorkOrderId: String?,
    todayWorkOrderIds: List<String>,
): Boolean =
    !focusedWorkOrderId.isNullOrBlank() &&
        todayWorkOrderIds.any { it == focusedWorkOrderId }
