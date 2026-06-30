package `in`.jphe.storyvox.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.scrubProgress
import `in`.jphe.storyvox.wear.WearMainActivity

/**
 * Watch-face complication for what's playing on the phone. Reads the
 * phone-published `/playback/state` DataItem and renders the chapter/book title
 * (SHORT_TEXT) or the chapter progress ring (RANGED_VALUE). Tapping opens
 * [WearMainActivity] (the Now Playing screen).
 *
 * No polling — `UPDATE_PERIOD_SECONDS=0` in the manifest. Refreshed reactively
 * by [ComplicationUpdateListenerService] when the phone republishes state.
 */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        render(request.complicationType, readPlaybackState())

    override fun getPreviewData(type: ComplicationType): ComplicationData? = render(type, PREVIEW)

    private fun render(type: ComplicationType, state: PlaybackState): ComplicationData? {
        val title = state.chapterTitle?.takeIf { it.isNotBlank() }
            ?: state.bookTitle?.takeIf { it.isNotBlank() }
            ?: NOT_PLAYING
        val short = if (title.length <= MAX_CHARS) title else title.take(MAX_CHARS - 1) + "…"
        val description = PlainComplicationText.Builder("Candela — $title").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(short).build(),
                contentDescription = description,
            ).setTapAction(openReader()).build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = state.scrubProgress(),
                min = 0f,
                max = 1f,
                contentDescription = description,
            ).setText(PlainComplicationText.Builder(short).build())
                .setTapAction(openReader())
                .build()

            else -> null
        }
    }

    private fun openReader(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_NOW_PLAYING,
        Intent(this, WearMainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val NOT_PLAYING = "Not playing"
        const val MAX_CHARS = 18
        const val REQUEST_NOW_PLAYING = 0

        /** Preview shown in the complication picker. */
        val PREVIEW = PlaybackState(
            bookTitle = "Moby Dick",
            chapterTitle = "Chapter 3",
            charOffset = 1_200,
            durationEstimateMs = 600_000L,
        )
    }
}
