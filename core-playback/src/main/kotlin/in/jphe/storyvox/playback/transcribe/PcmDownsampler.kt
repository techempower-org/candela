package `in`.jphe.storyvox.playback.transcribe

/**
 * Issue #1223 — convert decoded radio PCM into the format sherpa-onnx's
 * streaming ASR wants: **mono, 16 kHz, float samples in [-1, 1]**.
 *
 * The radio [`in`.jphe.storyvox.playback.tts.EnginePlayer] plays through a
 * Media3 `ExoPlayer`, whose decoded audio is typically 44.1/48 kHz stereo
 * 16-bit PCM. The Phase-2 `AudioProcessor` tap hands those raw frames here
 * before pushing the result into `OnlineStream.acceptWaveform`.
 *
 * Pure and allocation-simple so it can run on the audio thread and be
 * unit-tested without a device — this is the one signal-processing step
 * worth locking down with tests before the (device-validated) recognizer
 * wiring lands.
 *
 * Resampling is linear interpolation: more than adequate for ASR feature
 * extraction (the recognizer's own front-end is the quality bottleneck,
 * not the resampler) and cheap enough for the hot path. Channel downmix is
 * a straight average.
 */
object PcmDownsampler {

    /** Sample rate every sherpa-onnx streaming model expects. */
    const val TARGET_RATE_HZ: Int = 16_000

    /** Full-scale divisor for signed 16-bit → float normalisation. */
    private const val INT16_FULL_SCALE: Float = 32_768f

    /**
     * Convert interleaved signed-16-bit-little-endian [pcm] at [srcRateHz]
     * with [channels] channels into mono float at [TARGET_RATE_HZ],
     * normalised to [-1, 1].
     *
     * Returns an empty array for empty/degenerate input ([channels] < 1,
     * [srcRateHz] < 1, or fewer than one whole frame). Trailing bytes that
     * don't complete a frame are ignored.
     */
    fun toMono16k(pcm: ByteArray, srcRateHz: Int, channels: Int): FloatArray {
        if (channels < 1 || srcRateHz < 1) return FloatArray(0)
        val bytesPerFrame = 2 * channels
        val frameCount = pcm.size / bytesPerFrame
        if (frameCount == 0) return FloatArray(0)

        // 1. Decode + downmix to mono float in one pass.
        val mono = FloatArray(frameCount)
        var b = 0
        for (f in 0 until frameCount) {
            var acc = 0
            for (c in 0 until channels) {
                val lo = pcm[b].toInt() and 0xFF
                val hi = pcm[b + 1].toInt() // signed — sign-extends the sample
                acc += (hi shl 8) or lo
                b += 2
            }
            mono[f] = (acc.toFloat() / channels) / INT16_FULL_SCALE
        }

        // 2. Resample to 16 kHz (skip when already there).
        if (srcRateHz == TARGET_RATE_HZ) return mono
        val outLen = (frameCount.toLong() * TARGET_RATE_HZ / srcRateHz).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        val step = srcRateHz.toDouble() / TARGET_RATE_HZ
        for (i in 0 until outLen) {
            val srcPos = i * step
            val i0 = srcPos.toInt()
            val frac = (srcPos - i0).toFloat()
            val i1 = if (i0 + 1 < frameCount) i0 + 1 else frameCount - 1
            out[i] = mono[i0] * (1f - frac) + mono[i1] * frac
        }
        return out
    }
}
