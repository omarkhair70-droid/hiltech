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
