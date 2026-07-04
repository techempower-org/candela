package `in`.jphe.storyvox.source.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CalendarReader] backed by the on-device [CalendarContract] provider (#1495).
 *
 * Zero network: this reads whatever the *phone* already syncs (Google, Samsung,
 * Outlook, local accounts). The data is narrated locally and never leaves the
 * device — see privacy.md §2.9 / the Data-Safety checklist. Every read is pinned
 * to [Dispatchers.IO] (a ContentResolver query is blocking IO) and every failure
 * — missing permission, revoked mid-flight, dead provider — collapses to an
 * empty list, so the source degrades to "nothing to read" rather than crashing.
 */
@Singleton
internal class DeviceCalendarReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarReader {

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun events(
        beginUtcMillis: Long,
        endUtcMillis: Long,
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()

        // Instances.CONTENT_URI wants the [begin, end] window appended to the
        // path; the provider expands recurrences into concrete instances for us.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { b ->
            ContentUris.appendId(b, beginUtcMillis)
            ContentUris.appendId(b, endUtcMillis)
            b.build()
        }

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val locIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val calIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

                buildList {
                    while (c.moveToNext()) {
                        add(
                            CalendarEvent(
                                id = c.getLong(idIdx),
                                title = c.getString(titleIdx).orEmpty(),
                                startUtcMillis = c.getLong(beginIdx),
                                endUtcMillis = c.getLong(endIdx),
                                allDay = c.getInt(allDayIdx) == 1,
                                location = c.getString(locIdx),
                                calendarName = c.getString(calIdx),
                            ),
                        )
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            // Permission revoked between the check above and the query. Treat as
            // "no access" — the Browse gate re-prompts on next open.
            emptyList()
        }
    }
}
