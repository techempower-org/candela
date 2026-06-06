package `in`.jphe.storyvox.playback.audiobook

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import `in`.jphe.storyvox.source.audiobook.writer.ChapterMarker
import `in`.jphe.storyvox.source.audiobook.writer.Mp4ChapterMarkers
import `in`.jphe.storyvox.source.audiobook.writer.Mp4MetadataInjector
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Encodes a sequence of per-chapter PCM buffers into a single chaptered
 * `.m4b` audiobook (issue #1003).
 *
 * Pipeline:
 *  1. **AAC encode + mux** — one [MediaCodec] AAC-LC encoder feeds one
 *     [MediaMuxer] (MPEG-4 container). All chapters share one continuous audio
 *     track; we record each chapter's byte length so we can compute its
 *     duration for the chapter markers. PCM is 16-bit mono at [sampleRate].
 *  2. **Chapter + metadata injection** — once the muxer has written the file
 *     (with its `moov` atom trailing `mdat`), [Mp4MetadataInjector] appends a
 *     `udta` box carrying the Nero `chpl` chapter list and iTunes title /
 *     author / cover tags, then grows the `moov` size in place. See that
 *     class for why this is safe without rewriting sample-chunk offsets.
 *
 * The encode loop is a textbook MediaCodec sync-mode drain: queue input
 * buffers from the PCM, dequeue output buffers, write encoded samples to the
 * muxer, signal end-of-stream, drain the tail. No threads — synthesis already
 * happened; this is CPU-bound transcode the WorkManager job runs off the main
 * thread.
 */
class AacM4bEncoder {

    /** One chapter's already-synthesized audio. */
    data class ChapterAudio(val title: String, val pcm: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChapterAudio) return false
            return title == other.title && pcm.contentEquals(other.pcm)
        }
        override fun hashCode(): Int = 31 * title.hashCode() + pcm.contentHashCode()
    }

    /**
     * Encode [chapters] to [outFile] as a chaptered M4B. [sampleRate] is the
     * PCM sample rate; [title]/[author]/[cover] become container metadata.
     *
     * Returns the chapter markers actually written (with their computed start
     * offsets) for logging / UI. Throws on encoder configuration failure.
     */
    fun encode(
        chapters: List<ChapterAudio>,
        sampleRate: Int,
        title: String,
        author: String,
        cover: ByteArray?,
        outFile: File,
    ): List<ChapterMarker> {
        require(chapters.isNotEmpty()) { "no chapters to encode" }

        val format = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

        val codec = MediaCodec.createEncoderByType(MIME_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        // Per-chapter durations, derived from the PCM byte length so chapter
        // boundaries are exact regardless of encoder framing.
        val durationsMs = chapters.map { pcmDurationMs(it.pcm.size, sampleRate) }
        val titles = chapters.map { it.title }

        val bufferInfo = MediaCodec.BufferInfo()
        try {
            // Concatenate the chapter PCM into one stream; the chapter
            // boundaries are tracked purely by byte length above.
            val pcmStream = ConcatPcm(chapters.map { it.pcm })
            var presentationUs = 0L
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val chunk = pcmStream.read(inBuf.remaining())
                        if (chunk == null) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            inBuf.put(chunk)
                            codec.queueInputBuffer(inIndex, 0, chunk.size, presentationUs, 0)
                            // Advance the presentation clock by this chunk's duration.
                            presentationUs += pcmDurationUs(chunk.size, sampleRate)
                        }
                    }
                }

                // Drain available output. The encoder emits exactly one
                // INFO_OUTPUT_FORMAT_CHANGED (-2) before the first real sample
                // — that's when we learn the output format (incl. the AAC
                // ESDS) and can addTrack + start the muxer. All status codes
                // are negative, so they fall out of the `>= 0` loop and are
                // handled explicitly below.
                var outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec config (ESDS) — already captured by addTrack
                        // from outputFormat; don't write it as a sample.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted) { "format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        // Inject chapter markers + metadata into the finished file.
        val markers = Mp4ChapterMarkers.markers(titles, durationsMs)
        runCatching {
            RandomAccessFile(outFile, "rw").use { raf ->
                val injected = Mp4MetadataInjector.inject(raf, markers, title, author, cover)
                if (!injected) {
                    Log.w(LOG_TAG, "chapter/metadata injection skipped (moov layout)")
                }
            }
        }.onFailure { Log.w(LOG_TAG, "metadata injection failed", it) }

        return markers
    }

    /** Sequential reader over a list of PCM buffers, exposing them as one
     *  continuous byte stream the encoder pulls input chunks from. */
    private class ConcatPcm(private val buffers: List<ByteArray>) {
        private var bufIdx = 0
        private var pos = 0

        /** Read up to [max] bytes; null when fully drained. */
        fun read(max: Int): ByteArray? {
            // Skip exhausted/empty buffers.
            while (bufIdx < buffers.size && pos >= buffers[bufIdx].size) {
                bufIdx++
                pos = 0
            }
            if (bufIdx >= buffers.size) return null
            val cur = buffers[bufIdx]
            val n = minOf(max, cur.size - pos)
            val out = cur.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    private fun pcmDurationMs(byteLen: Int, sampleRate: Int): Long =
        byteLen.toLong() * 1000L / (sampleRate.toLong() * BYTES_PER_SAMPLE)

    private fun pcmDurationUs(byteLen: Int, sampleRate: Int): Long =
        byteLen.toLong() * 1_000_000L / (sampleRate.toLong() * BYTES_PER_SAMPLE)

    companion object {
        private const val LOG_TAG = "AacM4bEncoder"
        private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val CHANNELS = 1 // mono — engines synthesize mono PCM
        private const val BYTES_PER_SAMPLE = 2 // 16-bit
        private const val BIT_RATE = 64_000 // 64 kbps mono AAC — speech-grade
        private const val MAX_INPUT_SIZE = 16 * 1024
        private const val TIMEOUT_US = 10_000L
    }
}
