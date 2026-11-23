package com.hiltech.shared.core.procurement

/**
 * UI source/currentness guard only. A selected order must still be in the
 * currently authorized list; the server independently verifies all actions.
 */
fun servicePurchaseOrderStillAvailable(
    selectedOrderId: String?,
    currentOrderIds: List<String>,
): Boolean =
    !selectedOrderId.isNullOrBlank() && currentOrderIds.any { it == selectedOrderId }

/**
 * Calendar-aware invoice date preflight, independent of platform date APIs.
 * Formatted but impossible dates must not trigger the native evidence picker.
 */
fun servicePurchaseDateIsReal(date: String): Boolean {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) return false
    val year = date.substring(0, 4).toInt()
    val month = date.substring(5, 7).toInt()
    val day = date.substring(8, 10).toInt()
    if (year == 0 || month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..days
}

/**
 * Presentation-only preflight. Never use floating-point values for currency;
 * the real purchasing API preserves decimal strings and enforces authority.
 */
fun serviceInvoiceInputIsWellFormed(
    reference: String,
    date: String,
    currency: String,
    amount: String,
    tax: String,
): Boolean {
    val money = Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?$")
    return reference.trim().length in 2..96 &&
        servicePurchaseDateIsReal(date) &&
        Regex("[A-Z]{3}").matches(currency) &&
        money.matches(amount) &&
        money.matches(tax)
}

/**
 * A cached source action must never remain clickable after its originating
 * team / service need becomes stale, even if the old DTO retained a can* flag.
 * This is UI fail-closed behavior; the Phase 7 server is still authoritative.
 */
fun serviceNeedActionEnabled(
    sourceCurrent: Boolean,
    permittedBySource: Boolean,
    busy: Boolean,
): Boolean = sourceCurrent && permittedBySource && !busy
