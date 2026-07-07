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
import `in`.jphe.storyvox.playback.transcribe.MicCaptureProcessor
import `in`.jphe.storyvox.playback.transcribe.RecognizedWordSource
import `in`.jphe.storyvox.playback.transcribe.offline.OfflineTranscriber
import `in`.jphe.storyvox.playback.transcribe.offline.SherpaOfflineTranscriber
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

    /**
     * Issue #1368 — the live mic→ASR word source for the voice-paced
     * teleprompter. Replaces the [RecognizedWordSource.NoOp] seam default with
     * the real [MicCaptureProcessor]; it self-gates on RECORD_AUDIO + the
     * downloaded model, so binding it is safe even before either is present.
     */
    @Binds
    @Singleton
    abstract fun bindRecognizedWordSource(
        impl: MicCaptureProcessor,
    ): RecognizedWordSource

    /**
     * Issue #1657 (Voice Notes, Phase 2b) — the offline batch transcriber for
     * recorded notes (Whisper base int8). Self-gates on the downloaded model,
     * so binding it is safe even before the model is present.
     */
    @Binds
    @Singleton
    abstract fun bindOfflineTranscriber(
        impl: SherpaOfflineTranscriber,
    ): OfflineTranscriber

    companion object {
        @Provides
        @Singleton
        fun providePauseAction(controller: dagger.Lazy<PlaybackController>): SleepTimer.PauseAction =
            SleepTimer.PauseAction { controller.get().pause() }
    }
}
