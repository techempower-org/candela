package `in`.jphe.storyvox.di

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.StoryvoxPlaybackService
import `in`.jphe.storyvox.playback.briefing.BriefingItemPlayer
import `in`.jphe.storyvox.playback.briefing.PreparingBriefingItemPlayer
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #1810 — gives the Morning Briefing / For you queue the same start path as
 * the Play buttons (`RealPlaybackControllerUi.startListening`): start the
 * playback service, download the chapter, wait for it, then play. Without it
 * the queue's first item never played on a cold launch.
 */
@Module
@InstallIn(SingletonComponent::class)
object BriefingPlaybackModule {

    private const val TAG = "BriefingPlayback"
    private const val BODY_TIMEOUT_MS = 30_000L
    private const val ENGINE_TIMEOUT_MS = 10_000L

    @Provides
    @Singleton
    fun provideBriefingItemPlayer(
        @ApplicationContext context: Context,
        controller: PlaybackController,
        chapters: ChapterRepository,
    ): BriefingItemPlayer = PreparingBriefingItemPlayer(
        isEngineBound = { controller.isEngineBound },
        startService = {
            ContextCompat.startForegroundService(context, Intent(context, StoryvoxPlaybackService::class.java))
        },
        queueDownload = { fictionId, chapterId ->
            chapters.queueChapterDownload(fictionId, chapterId, requireUnmetered = false)
        },
        awaitBody = { chapterId ->
            withTimeoutOrNull(BODY_TIMEOUT_MS) { chapters.observeChapter(chapterId).filterNotNull().first() } != null
        },
        awaitEngine = {
            withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
                while (!controller.isEngineBound) delay(100)
                true
            } == true
        },
        play = { fictionId, chapterId -> controller.play(fictionId, chapterId) },
        // Log.w, not Log.i: release builds strip i/d (#1276).
        onProblem = { Log.w(TAG, "#1810 $it") },
        // Media3's Player is Main-confined, and the queue auto-advances from
        // Dispatchers.Default (the "wrong thread" crash on the tablet, #1810).
        playContext = Dispatchers.Main.immediate,
    )
}
