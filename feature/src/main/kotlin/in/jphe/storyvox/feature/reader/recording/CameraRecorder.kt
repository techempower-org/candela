package `in`.jphe.storyvox.feature.reader.recording

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.view.video.AudioConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner

/**
 * Issue #1367 — camera + video-recording lifecycle wrapper for Recording mode.
 *
 * Wraps a [LifecycleCameraController] (the same camera-view controller the OCR
 * capture surface uses for preview) and adds the [CameraController.VIDEO_CAPTURE]
 * use case so one object drives both the live [androidx.camera.view.PreviewView]
 * feed and the MP4 (H.264 video + AAC audio) recording. CameraX finalizes the
 * clip straight into [MediaStore], so it lands in the device gallery's Movies
 * folder immediately — no manual file plumbing.
 *
 * Owned by [RecordingScreen] (created with `remember`, bound in a
 * `DisposableEffect`) rather than the ViewModel, because the controller must
 * bind to the composition's [LifecycleOwner] and outliving that lifecycle would
 * leak the camera. The ViewModel stays camera-free and merely sends
 * [RecordingCommand]s here.
 *
 * Recording resolution is left at the CameraController default
 * (`QualitySelector.from(Quality.FHD, …)`), so a portrait capture yields a
 * 1080×1920 (9:16) clip — the right shape for Shorts / Reels / TikTok — without
 * pinning a quality the device may not offer.
 */
class CameraRecorder(context: Context) {

    private val appContext = context.applicationContext

    /** The shared preview+video controller. Exposed so the composable can hand
     *  it to its `PreviewView`. */
    val controller: LifecycleCameraController = LifecycleCameraController(appContext).apply {
        setEnabledUseCases(CameraController.VIDEO_CAPTURE)
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private var recording: Recording? = null

    /** Bind the camera to [owner]'s lifecycle (starts the live preview). */
    fun bind(owner: LifecycleOwner) {
        controller.bindToLifecycle(owner)
    }

    /** Switch between the selfie (front) and rear camera. A no-op rebind while
     *  already on the requested lens; CameraX rebinds the use cases internally
     *  when the selector changes. */
    fun setFrontCamera(front: Boolean) {
        val target = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (controller.cameraSelector != target) {
            controller.cameraSelector = target
        }
    }

    /**
     * Start recording video+audio into a new MediaStore entry named
     * [displayName] (CameraX appends the container suffix). [onFinalized] fires
     * with the saved clip's content Uri on success; [onError] with a
     * user-facing message on failure. Both are invoked on the main thread.
     *
     * The caller MUST have been granted RECORD_AUDIO (audio is enabled) — the
     * [RecordingScreen] permission gate guarantees this before issuing
     * [RecordingCommand.Start].
     */
    @SuppressLint("MissingPermission")
    fun start(displayName: String, onFinalized: (Uri) -> Unit, onError: (String) -> Unit) {
        if (recording != null) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // Scoped storage (API 29+) routes the clip into the gallery's
            // Movies/Candela album. On API ≤28 MediaStore ignores RELATIVE_PATH
            // and drops it in the default Movies bucket via the legacy
            // (maxSdk-28-scoped) WRITE_EXTERNAL_STORAGE grant.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Candela")
            }
        }
        val output = MediaStoreOutputOptions
            .Builder(appContext.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        recording = controller.startRecording(
            output,
            AudioConfig.create(true),
            ContextCompat.getMainExecutor(appContext),
            Consumer { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (event.hasError()) {
                        onError("Couldn't save the recording (error ${event.error}).")
                    } else {
                        onFinalized(event.outputResults.outputUri)
                    }
                }
            },
        )
    }

    /** Stop the active recording; finalization arrives asynchronously via the
     *  listener passed to [start]. */
    fun stop() {
        recording?.stop()
        recording = null
    }

    /** Tear down: stop any active recording and unbind the camera. Called from
     *  the composable's `DisposableEffect.onDispose`. */
    fun release() {
        recording?.stop()
        recording = null
        controller.unbind()
    }
}
