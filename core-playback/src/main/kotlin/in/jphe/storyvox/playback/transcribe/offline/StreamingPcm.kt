package `in`.jphe.storyvox.playback.transcribe.offline

import `in`.jphe.storyvox.playback.transcribe.PcmDownsampler

/**
 * Voice Notes (#1669) — the pure, device-free halves of the **streaming** file
 * decode. [AudioFileDecoder] feeds each `MediaCodec` output buffer through a
 * [StreamingMono16kResampler] into a [PcmWindowAccumulator], so peak memory is
 * one recognizer window (~30 s ≈ 1.9 MB of float) plus one codec buffer —
 * independent of the recording's length. The old path buffered the whole file
 * as PCM16 and then again as float (a 2 h stereo 48 kHz meeting ≈ 1.4 GB + 460 MB).
 */

/**
 * Incremental twin of [PcmDownsampler.toMono16k]: accepts interleaved signed
 * 16-bit little-endian PCM in arbitrary-sized chunks (chunks may split a frame)
 * and produces mono float at 16 kHz.
 *
 * The output is **sample-identical** to running [PcmDownsampler.toMono16k] over
 * the concatenation of every chunk: the resampler keeps a global output index
 * and interpolates at `i * srcRate / 16000` over the global input timeline,
 * carrying the one or two input frames an interpolation straddles across a chunk
 * boundary. That is what the old "resample once, whole-file" design protected
 * (no per-buffer drift or boundary clicks); streaming keeps it.
 *
 * Call [flush] once at end-of-stream to emit the tail (the last output samples
 * interpolate against the final frame, exactly like the whole-file clamp).
 */
class StreamingMono16kResampler(
    private val srcRateHz: Int,
    private val channels: Int,
) {
    private val valid = srcRateHz >= 1 && channels >= 1
    private val bytesPerFrame = 2 * channels.coerceAtLeast(1)
    private val passthrough = srcRateHz == TARGET
    private val step = srcRateHz.toDouble() / TARGET

    /** Bytes of a frame split across a chunk boundary. */
    private val partial = ByteArray(bytesPerFrame)
    private var partialLen = 0

    /** Mono input frames seen so far (global timeline). */
    private var framesSeen = 0L

    /** Next output sample index (global timeline). */
    private var outIndex = 0L

    /** Carried mono frames `[carryStart, carryStart + carry.size)` still needed for interpolation. */
    private var carry = FloatArray(0)
    private var carryStart = 0L

    private var flushed = false

    /** Feed `pcm[offset, offset + length)`; returns the 16 kHz mono samples now computable. */
    fun feed(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): FloatArray {
        check(!flushed) { "feed after flush" }
        if (!valid || length <= 0) return EMPTY
        val mono = decodeFrames(pcm, offset, length)
        if (mono.isEmpty()) return EMPTY
        if (passthrough) {
            framesSeen += mono.size
            return mono
        }
        // Working buffer = carried frames + new frames, starting at carryStart.
        val work = if (carry.isEmpty()) mono else carry + mono
        val workStart = if (carry.isEmpty()) framesSeen else carryStart
        framesSeen += mono.size

        // Emit every output whose right-hand neighbour (i0 + 1) has arrived,
        // capped at the whole-file length for the frames seen so far
        // (floor(N * 16000 / src) — monotonic in N, so nothing emitted here can
        // exceed the final length).
        val out = FloatArrayBuilder()
        val lenSoFar = framesSeen * TARGET / srcRateHz
        while (outIndex < lenSoFar) {
            val srcPos = outIndex * step
            val i0 = srcPos.toLong()
            if (i0 + 1 >= framesSeen) break
            val frac = (srcPos - i0).toFloat()
            val a = work[(i0 - workStart).toInt()]
            val b = work[(i0 + 1 - workStart).toInt()]
            out.add(a * (1f - frac) + b * frac)
            outIndex++
        }
        // Keep only frames from the next interpolation's left sample onward.
        val keepFrom = (outIndex * step).toLong().coerceAtMost(framesSeen - 1)
        val drop = (keepFrom - workStart).toInt().coerceIn(0, work.size)
        carry = work.copyOfRange(drop, work.size)
        carryStart = workStart + drop
        return out.toArray()
    }

    /** End of stream: emit the tail samples. Idempotent after the first call. */
    fun flush(): FloatArray {
        if (flushed) return EMPTY
        flushed = true
        if (!valid || passthrough || framesSeen == 0L) return EMPTY
        val outLen = framesSeen * TARGET / srcRateHz
        if (outIndex >= outLen) return EMPTY
        val last = framesSeen - 1
        val out = FloatArrayBuilder()
        while (outIndex < outLen) {
            val srcPos = outIndex * step
            val i0 = srcPos.toLong()
            val frac = (srcPos - i0).toFloat()
            val i1 = if (i0 + 1 < framesSeen) i0 + 1 else last
            val a = carry[(i0 - carryStart).toInt()]
            val b = carry[(i1 - carryStart).toInt()]
            out.add(a * (1f - frac) + b * frac)
            outIndex++
        }
        return out.toArray()
    }

    /** Decode whole frames (joining any split frame) to mono float, same maths as [PcmDownsampler]. */
    private fun decodeFrames(pcm: ByteArray, offset: Int, length: Int): FloatArray {
        var src = offset
        val end = offset + length
        var head: Float? = null
        if (partialLen > 0) {
            // Complete the split frame first (or stash and wait for more bytes).
            val need = bytesPerFrame - partialLen
            if (length < need) {
                System.arraycopy(pcm, src, partial, partialLen, length)
                partialLen += length
                return EMPTY
            }
            System.arraycopy(pcm, src, partial, partialLen, need)
            src += need
            head = frameToMono(partial, 0)
            partialLen = 0
        }
        val frameCount = (end - src) / bytesPerFrame
        val lead = if (head != null) 1 else 0
        val mono = FloatArray(lead + frameCount)
        if (head != null) mono[0] = head
        for (f in 0 until frameCount) {
            mono[lead + f] = frameToMono(pcm, src)
            src += bytesPerFrame
        }
        val rem = end - src
        if (rem > 0) System.arraycopy(pcm, src, partial, 0, rem)
        partialLen = rem
        return mono
    }

    private fun frameToMono(bytes: ByteArray, at: Int): Float {
        var acc = 0
        var b = at
        for (c in 0 until channels) {
            val lo = bytes[b].toInt() and 0xFF
            val hi = bytes[b + 1].toInt() // signed — sign-extends the sample
            acc += (hi shl 8) or lo
            b += 2
        }
        return (acc.toFloat() / channels) / INT16_FULL_SCALE
    }

    private companion object {
        const val TARGET = PcmDownsampler.TARGET_RATE_HZ
        const val INT16_FULL_SCALE = 32_768f
        val EMPTY = FloatArray(0)
    }
}

/**
 * Collects a stream of 16 kHz mono samples into fixed windows of
 * [windowSamples] (the recognizer's decode unit — [TranscriptionChunker.DEFAULT_WINDOW_SEC]).
 * [push] returns every window completed by the new samples; [flush] returns the
 * final partial window (if any). Window boundaries and timestamps are identical
 * to [TranscriptionChunker.windows] over the full sample count, so switching to
 * streaming does not move a single segment boundary.
 *
 * Memory: one reusable window buffer; each emitted [PcmWindow] owns a copy that
 * the caller releases after decoding it.
 */
class PcmWindowAccumulator(
    private val windowSamples: Int,
    private val sampleRate: Int = PcmDownsampler.TARGET_RATE_HZ,
) {
    init {
        require(windowSamples > 0) { "windowSamples must be > 0" }
        require(sampleRate > 0) { "sampleRate must be > 0" }
    }

    private val buf = FloatArray(windowSamples)
    private var fill = 0
    private var emitted = 0L

    /** Total samples accepted so far. */
    val totalSamples: Long get() = emitted + fill

    fun push(samples: FloatArray): List<PcmWindow> {
        if (samples.isEmpty()) return emptyList()
        var out: MutableList<PcmWindow>? = null
        var i = 0
        while (i < samples.size) {
            val n = minOf(windowSamples - fill, samples.size - i)
            System.arraycopy(samples, i, buf, fill, n)
            fill += n
            i += n
            if (fill == windowSamples) {
                (out ?: ArrayList<PcmWindow>(1).also { out = it }).add(take())
            }
        }
        return out ?: emptyList()
    }

    /** The trailing partial window, or null if nothing is buffered. */
    fun flush(): PcmWindow? = if (fill == 0) null else take()

    private fun take(): PcmWindow {
        val w = PcmWindow(startSample = emitted, samples = buf.copyOf(fill), sampleRate = sampleRate)
        emitted += fill
        fill = 0
        return w
    }
}

/** One recognizer window: samples starting at global [startSample]. */
class PcmWindow(
    val startSample: Long,
    val samples: FloatArray,
    val sampleRate: Int = PcmDownsampler.TARGET_RATE_HZ,
) {
    val endSample: Long get() = startSample + samples.size
    val startMs: Long get() = startSample * 1000 / sampleRate
    val endMs: Long get() = endSample * 1000 / sampleRate
}

/** Minimal growable float buffer (avoids boxing through List<Float>). */
private class FloatArrayBuilder {
    private var a = FloatArray(256)
    private var n = 0
    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    fun toArray(): FloatArray = if (n == a.size) a else a.copyOf(n)
}
