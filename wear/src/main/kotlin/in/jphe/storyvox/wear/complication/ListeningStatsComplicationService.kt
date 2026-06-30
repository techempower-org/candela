package `in`.jphe.storyvox.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import `in`.jphe.storyvox.wear.WearMainActivity

/**
 * Watch-face complication for today's listening — the audiobook analog to an
 * activity ring. Reads the phone-published `/stats/today` DataItem and renders
 * time-listened-today as the main SHORT_TEXT ("37m" / "1h05") with the current
 * streak as the title ("🔥7"). Tapping opens [WearMainActivity].
 *
 * No polling — refreshed by [ComplicationUpdateListenerService] when the phone
 * republishes stats (at chapter boundaries).
 */
class ListeningStatsComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        render(request.complicationType, readTodayStats())

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        render(type, TodayStats(todayMs = 37 * 60_000L, streakDays = 7))

    private fun render(type: ComplicationType, stats: TodayStats): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val today = formatToday(stats.todayMs)
        val builder = ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(today).build(),
            contentDescription = PlainComplicationText.Builder(
                "Candela — $today listened today" +
                    if (stats.streakDays > 0) ", ${stats.streakDays}-day streak" else "",
            ).build(),
        ).setTapAction(openApp())
        if (stats.streakDays > 0) {
            builder.setTitle(PlainComplicationText.Builder("🔥${stats.streakDays}").build())
        }
        return builder.build()
    }

    /** ms → glanceable duration: "0m", "37m", or "1h05". */
    private fun formatToday(ms: Long): String {
        val minutes = (ms / 60_000L).toInt()
        return when {
            minutes <= 0 -> "0m"
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_STATS,
        Intent(this, WearMainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_STATS = 1
    }
}
