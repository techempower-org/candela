package `in`.jphe.storyvox.playback.transcribe.offline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import `in`.jphe.storyvox.playback.transcribe.PcmDownsampler
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Voice Notes (#1657, Phase 2b) — decodes a recorded audio file (AAC `.m4a`
 * from Phase 2a) to **16 kHz mono float PCM** for the offline recognizer,
 * via `MediaExtractor` + `MediaCodec` (no extra dependency).
 *
 * The whole file is decoded to interleaved PCM16 then resampled in **one**
 * [PcmDownsampler.toMono16k] pass (resampling per output buffer would drift +
 * add boundary artifacts). Transcription memory is then bounded by windowing
 * the resulting buffer ([TranscriptionChunker]); a fully-streaming decode for
 * multi-hour recordings is a tracked follow-up (typical notes are minutes).
 *
 * Device-validated wiring (same posture as `MicCaptureProcessor`): the codec
 * loop is standard but its behaviour must be confirmed on-device. Any failure
 * returns null → the caller keeps the audio + sets status FAILED, never crashes.
 */
object AudioFileDecoder {

    private const val TAG = "AudioFileDecoder"
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** Decode [path] to 16 kHz mono float PCM in `[-1, 1]`, or null on any failure. */
    fun decodeToMono16k(path: String): FloatArray? = runCatching {
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

            val pcm = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            try {
                while (!sawOutputEos) {
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
                            if (outBuf != null && bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.get(chunk, 0, bufferInfo.size)
                                pcm.write(chunk)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            val bytes = pcm.toByteArray()
            if (bytes.isEmpty()) error("decoded 0 bytes from $path")
            PcmDownsampler.toMono16k(bytes, srcRate, channels)
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrElse {
        Log.w(TAG, "decode failed for $path: ${it.message}")
        null
    }
}
