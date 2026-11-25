package com.hiltech.shared.core.design

private const val MILLIS_PER_DAY = 86_400_000L
private const val DAYS_BEFORE_UNIX_EPOCH = 719_162L

/** A civil date, never an instant in the device's time zone. Wire values remain ISO dates. */
internal data class HiltechDate(val year: Int, val month: Int, val day: Int) {
    fun toIso(): String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    fun toUtcMillis(): Long {
        val previousYear = (year - 1).toLong()
        var days = previousYear * 365 + previousYear / 4 - previousYear / 100 + previousYear / 400
        for (monthBefore in 1 until month) days += daysInMonth(year, monthBefore)
        return (days + day - 1 - DAYS_BEFORE_UNIX_EPOCH) * MILLIS_PER_DAY
    }
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

internal fun parseHiltechDate(value: String?): HiltechDate? {
    if (value == null || value.length != 10 || value[4] != '-' || value[7] != '-') return null
    if (value.withIndex().any { (index, character) -> index != 4 && index != 7 && character !in '0'..'9' }) return null
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    if (year !in 1..9999 || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    return HiltechDate(year, month, day)
}

/** Material date pickers represent the selected civil date as midnight UTC. */
internal fun hiltechDateToUtcMillis(value: String?): Long? = parseHiltechDate(value)?.toUtcMillis()

internal fun hiltechDateFromUtcMillis(value: Long): String? {
    // Floor division matters for dates before 1970, including -1 ms.
    val epochDay = value / MILLIS_PER_DAY - if (value < 0 && value % MILLIS_PER_DAY != 0L) 1 else 0
    val firstDay = HiltechDate(1, 1, 1).toUtcMillis() / MILLIS_PER_DAY
    val lastDay = HiltechDate(9999, 12, 31).toUtcMillis() / MILLIS_PER_DAY
    if (epochDay !in firstDay..lastDay) return null
    var lowerYear = 1
    var upperYear = 9999
    while (lowerYear < upperYear) {
        val middleYear = (lowerYear + upperYear + 1) / 2
        if (HiltechDate(middleYear, 1, 1).toUtcMillis() / MILLIS_PER_DAY <= epochDay) lowerYear = middleYear
        else upperYear = middleYear - 1
    }
    var dayInYear = (epochDay - HiltechDate(lowerYear, 1, 1).toUtcMillis() / MILLIS_PER_DAY).toInt()
    var month = 1
    while (dayInYear >= daysInMonth(lowerYear, month)) {
        dayInYear -= daysInMonth(lowerYear, month)
        month += 1
    }
    return HiltechDate(lowerYear, month, dayInYear + 1).toIso()
}

internal fun isValidOptionalHiltechDate(value: String): Boolean = value.isBlank() || parseHiltechDate(value) != null

internal enum class HiltechDateRangeProblem { INVALID_START, INVALID_END, END_BEFORE_START }

internal fun hiltechDateRangeProblem(start: String, end: String): HiltechDateRangeProblem? {
    if (!isValidOptionalHiltechDate(start)) return HiltechDateRangeProblem.INVALID_START
    if (!isValidOptionalHiltechDate(end)) return HiltechDateRangeProblem.INVALID_END
    val startMillis = hiltechDateToUtcMillis(start)
    val endMillis = hiltechDateToUtcMillis(end)
    return if (startMillis != null && endMillis != null && endMillis < startMillis) HiltechDateRangeProblem.END_BEFORE_START else null
}
