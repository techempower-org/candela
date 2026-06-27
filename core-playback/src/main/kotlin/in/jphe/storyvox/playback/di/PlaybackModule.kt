package `in`.jphe.storyvox.playback.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.playback.AndroidDndController
import `in`.jphe.storyvox.playback.DefaultPlaybackController
import `in`.jphe.storyvox.playback.DndController
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.SleepTimer
import `in`.jphe.storyvox.playback.TtsVolumeRamp
import `in`.jphe.storyvox.playback.VolumeRamp
import `in`.jphe.storyvox.playback.cache.PcmRenderScheduler
import `in`.jphe.storyvox.playback.cache.WorkManagerPcmRenderScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackController(impl: DefaultPlaybackController): PlaybackController

    @Binds
    @Singleton
    abstract fun bindVolumeRamp(impl: TtsVolumeRamp): VolumeRamp

    /** Issue #1190 — auto Do Not Disturb around the sleep timer. */
    @Binds
    @Singleton
    abstract fun bindDndController(impl: AndroidDndController): DndController

    /** PR-F (#86) — background PCM cache pre-render scheduler. */
    @Binds
    @Singleton
    abstract fun bindPcmRenderScheduler(
        impl: WorkManagerPcmRenderScheduler,
    ): PcmRenderScheduler

    companion object {
        @Provides
        @Singleton
        fun providePauseAction(controller: dagger.Lazy<PlaybackController>): SleepTimer.PauseAction =
            SleepTimer.PauseAction { controller.get().pause() }
    }
}
