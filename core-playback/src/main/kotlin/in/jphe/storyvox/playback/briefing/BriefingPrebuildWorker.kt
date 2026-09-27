package `in`.jphe.storyvox.playback.briefing

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.briefing.BriefingBuilder
import `in`.jphe.storyvox.data.briefing.BriefingPlanner
import `in`.jphe.storyvox.data.briefing.BriefingSchedule
import `in`.jphe.storyvox.data.briefing.PrebuiltBriefing
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.playback.cache.PcmRenderScheduler
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daily morning-briefing prebuild (#1467 slice B) — the "each morning" half.
 *
 * At the user's chosen time it assembles the queue with the same
 * [BriefingBuilder] the on-demand button uses, saves it as a
 * [PrebuiltBriefing] (so tapping play is instant and offline-tolerant), then
 * queues each item's chapter text download and a background PCM pre-render
 * through the existing [ChapterRepository] / [PcmRenderScheduler] paths —
 * the render worker already applies its own battery/storage constraints and
 * skips engines that can't pre-render. No new fetchers.
 */
@HiltWorker
class BriefingPrebuildWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: BriefingSettingsStore,
    private val builder: BriefingBuilder,
    private val chapters: ChapterRepository,
    private val renders: PcmRenderScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = store.current()
        if (!settings.schedule.enabled) return Result.success()
        val items = runCatching { builder.build(settings.config) }.getOrElse { e ->
            Log.w(TAG, "prebuild failed", e)
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        if (items.isEmpty()) {
            // Offline at the scheduled minute is the common cause; one retry
            // under WorkManager's backoff, then give up until tomorrow.
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
        val now = System.currentTimeMillis()
        store.savePrebuilt(
            PrebuiltBriefing(
                epochDay = BriefingPlanner.epochDay(now, ZoneId.systemDefault()),
                configKey = BriefingPlanner.configKey(settings.config),
                builtAtMillis = now,
                items = items,
            ),
        )
        for (item in BriefingPlanner.prerenderTargets(items)) {
            runCatching {
                chapters.queueChapterDownload(item.fictionId, item.chapterId, requireUnmetered = false)
                renders.scheduleRender(item.fictionId, item.chapterId)
            }.onFailure { Log.w(TAG, "prefetch failed for ${item.chapterId}", it) }
        }
        Log.w(TAG, "briefing prebuilt: ${items.size} items")
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "morning-briefing-prebuild"
        const val TAG = "BriefingPrebuild"
        private const val MAX_ATTEMPTS = 2
    }
}

/**
 * Applies a [BriefingSchedule] to WorkManager: a unique 24h periodic
 * [BriefingPrebuildWorker] whose first run is aligned to the chosen local time,
 * or a cancel when the schedule is off. WorkManager persists the request across
 * reboots, so this only needs calling when the schedule changes.
 */
@Singleton
class BriefingPrebuildScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(schedule: BriefingSchedule) {
        val wm = WorkManager.getInstance(context)
        if (!schedule.enabled) {
            wm.cancelUniqueWork(BriefingPrebuildWorker.UNIQUE_NAME)
            return
        }
        val delay = BriefingPlanner.nextRunDelayMillis(
            nowMillis = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            hour = schedule.hour,
            minute = schedule.minute,
        )
        val request = PeriodicWorkRequestBuilder<BriefingPrebuildWorker>(Duration.ofHours(24))
            .setInitialDelay(Duration.ofMillis(delay))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(BriefingPrebuildWorker.TAG)
            .build()
        // CANCEL_AND_REENQUEUE (not KEEP/UPDATE): a changed time must re-anchor
        // the first run; UPDATE keeps the old period anchor.
        wm.enqueueUniquePeriodicWork(
            BriefingPrebuildWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }
}
