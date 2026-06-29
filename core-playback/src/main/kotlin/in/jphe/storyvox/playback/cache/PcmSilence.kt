package `in`.jphe.storyvox.playback.cache

import java.io.File

/**
 * Issue #1281 — "is this PCM all silence?" helpers backing the read-side content
 * gate in `CacheFileSource.open`.
 *
 * The structural belt added in #1128 (sentence count / byte length / offsets)
 * never inspects the samples, so a render that emitted non-empty but
 * zero-amplitude PCM (transient engine glitch, model decline that still emits a
 * silent buffer, low-memory partial synth) finalizes as a structurally-valid
 * "complete" entry and plays back as a silent, never-re-rendered chapter — a
 * silent skip. The read gate rejects such an entry (→ delete + re-render),
 * making it self-healing.
 *
 * A symmetric write-side discard in `PcmAppender.complete` was considered but
 * deliberately not added: the read gate already heals a silent entry on next
 * play, and an all-zero buffer is a common content-agnostic fixture across the
 * cache test suite, so a finalize-time content guard carried outsized blast
 * radius for marginal benefit (see #1281 / the PR).
 *
 * Signed PCM16 / float32 silence is all-zero bytes, so "silence" is defined
 * conservatively as *at most [PCM_SILENCE_TOLERANCE_BYTES] non-zero bytes* — 0
 * by default, i.e. strictly all-zero. Real neural-TTS audio always carries
 * non-zero samples (even a leading pause is followed by speech), so this only
 * ever rejects a provably all-zero render and never a quiet-but-real chapter.
 */

/**
 * Non-zero byte budget below which a `.pcm` region counts as silence. **0** —
 * strictly all-zero is silence, any non-zero sample is audio. Kept as a named,
 * tunable knob: raise it only if field data shows "near-silent garbage" glitch
 * buffers (a stray DC offset / click) that should also be rejected. Deliberately
 * conservative at 0 so a genuine (even very short) render is never discarded.
 */
internal const val PCM_SILENCE_TOLERANCE_BYTES: Int = 0

/**
 * True if [bytes] over `[0, len)` is PCM silence: at most [tolerance] non-zero
 * bytes. Early-exits once the tolerance is exceeded, so an audible buffer
 * returns false near-instantly. Pure (no I/O) — the unit-tested encoding of the
 * silence definition; [pcmIsAllSilence] applies the same accounting across a
 * whole file (accumulating across read buffers, so it stays correct if the
 * tolerance is ever raised above 0).
 */
internal fun isPcmBufferSilent(
    bytes: ByteArray,
    len: Int = bytes.size,
    tolerance: Int = PCM_SILENCE_TOLERANCE_BYTES,
): Boolean {
    var nonZero = 0
    var i = 0
    while (i < len) {
        if (bytes[i].toInt() != 0) {
            nonZero++
            if (nonZero > tolerance) return false
        }
        i++
    }
    return true
}

/**
 * Stream up to [scanBytes] of [pcmFile] and report whether the declared content
 * is all silence (≤ [PCM_SILENCE_TOLERANCE_BYTES] non-zero bytes across the
 * whole scanned region). Reads in 64 KB buffers and early-exits on the first
 * audible run — so a valid entry reads only its first buffer, while a genuinely
 * silent entry (rare, and about to be discarded) is read in full. The non-zero
 * count accumulates across buffers, so scanning a finalized file is correct even
 * on the resume path (where the file carries bytes from a prior session).
 *
 * A missing / empty file reads as silent — callers gate that case separately
 * (the read belt's `isFile`/length checks, the write belt's empty-sentences
 * check run first).
 */
internal fun pcmIsAllSilence(pcmFile: File, scanBytes: Long): Boolean {
    if (scanBytes <= 0L) return true
    var nonZero = 0
    pcmFile.inputStream().buffered().use { input ->
        val buf = ByteArray(64 * 1024)
        var remaining = scanBytes
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            for (i in 0 until n) {
                if (buf[i].toInt() != 0) {
                    nonZero++
                    if (nonZero > PCM_SILENCE_TOLERANCE_BYTES) return false
                }
            }
            remaining -= n.toLong()
        }
    }
    return true
}
