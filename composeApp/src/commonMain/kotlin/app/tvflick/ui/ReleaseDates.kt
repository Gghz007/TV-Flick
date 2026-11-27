package app.tvflick.ui

/** A calendar date in the device's current time zone. */
expect fun localToday(): String

fun daysUntil(date: String, today: String = localToday()): Long? {
    val target = calendarEpochDay(date) ?: return null
    val current = calendarEpochDay(today) ?: return null
    return (target - current).coerceAtLeast(0L)
}

fun releaseSection(date: String, today: String = localToday()): String = when (daysUntil(date, today)) {
    0L -> "TODAY"
    1L -> "TOMORROW"
    in 2L..6L -> {
        listOf("THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY")[
            ((calendarEpochDay(date)!! % 7 + 7) % 7).toInt()]
    }
    else -> "LATER"
}

internal fun calendarEpochDay(date: String): Long? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) return null
    val (year, month, day) = date.split('-').map(String::toInt)
    if (year < 1 || month !in 1..12) return null
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val monthDays = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    if (day !in 1..monthDays[month - 1]) return null
    val y = year - if (month <= 2) 1 else 0
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400
    val m = month + if (month > 2) -3 else 9
    val doy = (153 * m + 2) / 5 + day - 1
    return era.toLong() * 146097L + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468L
}
