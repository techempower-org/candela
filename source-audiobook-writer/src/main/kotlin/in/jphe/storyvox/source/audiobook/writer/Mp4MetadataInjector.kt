package `in`.jphe.storyvox.source.audiobook.writer

import java.io.RandomAccessFile

/**
 * Injects a `udta` metadata box (chapters + iTunes tags) into the `moov` atom
 * of an MP4/M4A produced by Android's `MediaMuxer` (issue #1003).
 *
 * Why this is safe and cheap: `MediaMuxer` writes the `moov` atom **last**
 * (after `mdat`). Because `moov` is the final top-level box and `udta` becomes
 * its final child, we can:
 *   1. append the `udta` bytes at the current end-of-file (i.e. immediately
 *      after `moov`'s existing last child), and
 *   2. grow `moov`'s 4-byte size field by the `udta` length.
 *
 * Nothing in `mdat` moves, so the `stco`/`co64` sample-chunk offsets inside
 * `moov` stay valid — no offset rewriting needed. This is the one ordering
 * that makes in-place metadata injection trivial; if a future Android release
 * starts writing faststart (`moov` before `mdat`) files we'd need the full
 * offset-patching path instead, so [findTopLevelMoov] verifies the layout and
 * the caller treats a non-trailing `moov` as "skip metadata" rather than
 * corrupting the file.
 */
object Mp4MetadataInjector {

    /** Result of [findTopLevelMoov]: byte offset of the `moov` box header and
     *  its declared size. */
    data class MoovBox(val offset: Long, val size: Long)

    /**
     * Inject chapters + tags into [file] in place. No-op (returns false)
     * when there's nothing to write or the `moov` isn't the trailing box.
     * Returns true when metadata was injected.
     */
    fun inject(
        file: RandomAccessFile,
        markers: List<ChapterMarker>,
        title: String,
        author: String,
        cover: ByteArray?,
    ): Boolean {
        val udta = Mp4Boxes.udta(markers, title, author, cover)
        // udta header alone (8 bytes) with no children means nothing to add.
        if (udta.size <= 8) return false

        val moov = findTopLevelMoov(file) ?: return false

        // moov must be the last top-level box for the append-and-grow trick.
        val fileLen = file.length()
        if (moov.offset + moov.size != fileLen) return false
        // A 64-bit-size moov (size field == 1) would need largesize handling;
        // audiobook moovs are far under 4 GiB, so a 32-bit size is expected.
        if (moov.size > 0xFFFFFFFFL) return false

        // 1. Append udta at EOF (becomes moov's final child).
        file.seek(fileLen)
        file.write(udta)

        // 2. Grow moov's 32-bit size field in place.
        val newMoovSize = moov.size + udta.size
        require(newMoovSize <= 0xFFFFFFFFL) { "moov would exceed 32-bit size" }
        file.seek(moov.offset)
        file.write(Mp4Boxes.intBE(newMoovSize.toInt()))
        return true
    }

    /**
     * Walk the top-level box list and return the trailing `moov` box, or null
     * if there's no `moov` or it isn't last. Handles the 64-bit `largesize`
     * extension (size field == 1) and the "rest of file" sentinel (size == 0).
     */
    fun findTopLevelMoov(file: RandomAccessFile): MoovBox? {
        val fileLen = file.length()
        var pos = 0L
        var found: MoovBox? = null
        val header = ByteArray(8)
        while (pos + 8 <= fileLen) {
            file.seek(pos)
            file.readFully(header)
            val size32 = readUIntBE(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            val boxSize: Long = when (size32) {
                1L -> {
                    // 64-bit largesize follows the type.
                    val ext = ByteArray(8)
                    file.readFully(ext)
                    readULongBE(ext, 0)
                }
                0L -> fileLen - pos // extends to EOF
                else -> size32
            }
            if (boxSize <= 0 || pos + boxSize > fileLen) break
            if (type == "moov") found = MoovBox(offset = pos, size = boxSize)
            pos += boxSize
        }
        // Only return moov if it's the last box (offset + size == EOF).
        return found?.takeIf { it.offset + it.size == fileLen }
    }

    private fun readUIntBE(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

    private fun readULongBE(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
