package `in`.jphe.storyvox.playback.transcribe.offline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Voice Notes (#1657, Phase 2b) — decodes a recorded audio file (AAC `.m4a`
 * from Phase 2a) to **16 kHz mono float PCM** for the offline recognizer,
 * via `MediaExtractor` + `MediaCodec` (no extra dependency).
 *
 * **Streaming (#1669):** [streamMono16kWindows] decodes incrementally — each
 * codec output buffer goes through a [StreamingMono16kResampler] (sample-identical
 * to a whole-file [`in`.jphe.storyvox.playback.transcribe.PcmDownsampler.toMono16k]
 * pass, so no per-buffer drift) into a [PcmWindowAccumulator], and every completed
 * ~30 s window is handed to the caller *before* decoding continues. Peak memory is
 * one window + one codec buffer regardless of recording length; the previous
 * design held the whole file as PCM16 and float at once.
 *
 * Device-validated wiring (same posture as `MicCaptureProcessor`): the codec
 * loop is standard but its behaviour must be confirmed on-device. Any failure
 * throws → the caller keeps the audio + sets status FAILED, never crashes.
 */
object AudioFileDecoder {

    private const val TAG = "AudioFileDecoder"
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Decode [path] as a stream of 16 kHz mono float windows of [windowSamples],
     * invoking [onWindow] for each one as soon as it is complete (the final
     * window may be shorter). Suspends inside [onWindow] — the codec is paused
     * while the recognizer works, so at most one window is resident.
     *
     * Throws on any failure (no audio track, codec error, zero samples decoded);
     * windows already delivered stay delivered. Honours coroutine cancellation
     * between codec buffers. Returns the total number of 16 kHz samples.
     */
    suspend fun streamMono16kWindows(
        path: String,
        windowSamples: Int,
        onWindow: suspend (PcmWindow) -> Unit,
    ): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in $path")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("no mime")

            val codec = MediaCodec.createDecoderByType(mime)
            var srcRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val windows = PcmWindowAccumulator(windowSamples)
            // Created lazily at the first output buffer so an early
            // INFO_OUTPUT_FORMAT_CHANGED (the usual AAC case) sets the true rate.
            var resampler: StreamingMono16kResampler? = null
            var scratch = ByteArray(0)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            suspend fun deliver(samples: FloatArray) {
                for (w in windows.push(samples)) onWindow(w)
            }

            try {
                while (!sawOutputEos) {
                    currentCoroutineContext().ensureActive()
                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex) ?: ByteBuffer.allocate(0)
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            var samples: FloatArray? = null
                            if (outBuf != null && bufferInfo.size > 0) {
                                if (scratch.size < bufferInfo.size) scratch = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.get(scratch, 0, bufferInfo.size)
                                var r = resampler
                                if (r == null) {
                                    r = StreamingMono16kResampler(srcRate, channels)
                                    resampler = r
                                }
                                samples = r.feed(scratch, 0, bufferInfo.size)
                            }
                            // Release before the (possibly long) recognizer call.
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                            if (samples != null) deliver(samples)
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val r = resampler
                            if (r != null && (newRate != srcRate || newChannels != channels)) {
                                // Mid-stream format change: close out the old timeline
                                // contiguously, then resample the rest at the new format.
                                deliver(r.flush())
                                resampler = null
                            }
                            srcRate = newRate
                            channels = newChannels
                        }
                    }
                }
                resampler?.let { deliver(it.flush()) }
                windows.flush()?.let { onWindow(it) }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            val total = windows.totalSamples
            if (total == 0L) error("decoded 0 samples from $path")
            return total
        } catch (t: Throwable) {
            if (t !is CancellationException) Log.w(TAG, "decode failed for $path: ${t.message}")
            throw t
        } finally {
            runCatching { extractor.release() }
        }
    }
}
