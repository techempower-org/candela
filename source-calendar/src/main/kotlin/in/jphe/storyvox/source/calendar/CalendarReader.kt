package `in`.jphe.storyvox.source.calendar

/**
 * Read-only seam over the device's calendar (#1495).
 *
 * Split out from [CalendarSource] so the source's chaptering logic is testable
 * with a hand-built fake — the real impl ([DeviceCalendarReader]) touches
 * `CalendarContract` and needs a `Context`, which a JVM unit test can't supply.
 * The interface is intentionally tiny: permission state + a windowed instance
 * query. Everything else (bucketing, narration) is pure [CalendarAgenda].
 */
interface CalendarReader {

    /** True when `READ_CALENDAR` is currently granted. Cheap; call before every
     *  read so a mid-session revoke degrades gracefully to "empty". */
    fun hasPermission(): Boolean

    /**
     * All event instances overlapping `[beginUtcMillis, endUtcMillis)`, across
     * every calendar synced to the device, sorted by start. Returns an empty
     * list — never throws — when the permission is absent or the provider is
     * unavailable, so callers treat "no access" and "no events" identically.
     */
    suspend fun events(beginUtcMillis: Long, endUtcMillis: Long): List<CalendarEvent>
}
