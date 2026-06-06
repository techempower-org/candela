package `in`.jphe.storyvox.source.audiobook.writer

import java.io.ByteArrayOutputStream

/**
 * Minimal MP4 (ISO-BMFF) box builders for the metadata atoms that turn a
 * MediaMuxer-produced `.m4a`/`.mp4` into a chaptered, tagged audiobook
 * (issue #1003). Pure byte manipulation — no Android — so the layout is
 * unit-testable against the well-known formats.
 *
 * We write two things into a `udta` box that gets injected into the `moov`
 * atom by [Mp4MetadataInjector]:
 *
 *  - **`chpl`** — the Nero chapter list (`moov.udta.chpl`). The de-facto
 *    chapter format read by VLC, audiobookshelf, Smart AudioBook Player and
 *    most Android audiobook apps. 100-nanosecond timestamps, 1-byte count.
 *  - **`meta`/`ilst`** — iTunes-style metadata: `©nam` (title), `©ART`
 *    (author), and `covr` (cover art). Read by Apple Books / iTunes / many
 *    players for the title + artwork.
 *
 * Box wire format (ISO-BMFF): `[uint32 size][4-byte type][payload]`, sizes
 * big-endian and inclusive of the 8-byte header.
 */
internal object Mp4Boxes {

    /** Every ISO-BMFF box type is a 4-character (FourCC) code. */
    private const val FOURCC_LEN = 4

    /** Build a full `udta` box carrying the chapter list and iTunes metadata.
     *  Either part may be empty (no chapters / no metadata); a fully empty
     *  result still returns a valid (header-only) udta the injector can skip. */
    fun udta(
        markers: List<ChapterMarker>,
        title: String,
        author: String,
        cover: ByteArray?,
    ): ByteArray {
        val children = ByteArrayOutputStream()
        if (markers.isNotEmpty()) children.write(chpl(markers))
        val meta = meta(title, author, cover)
        if (meta != null) children.write(meta)
        return box("udta", children.toByteArray())
    }

    /**
     * Nero `chpl` chapter box. Version 1, with the 4-byte reserved field and
     * a 1-byte chapter count, then per-chapter an 8-byte 100ns start time and
     * a 1-byte-length-prefixed UTF-8 title.
     *
     * Chapter count is an 8-bit field; we clamp at 255 (the Nero ceiling).
     */
    fun chpl(markers: List<ChapterMarker>): ByteArray {
        val count = markers.size.coerceAtMost(255)
        val payload = ByteArrayOutputStream()
        // version(1) + flags(3)
        payload.write(0x01)
        payload.write(byteArrayOf(0, 0, 0))
        // reserved(4) — FFmpeg writes a 4-byte reserved field in the v1 box
        payload.write(byteArrayOf(0, 0, 0, 0))
        // chapter count (1 byte)
        payload.write(count and 0xFF)
        for (i in 0 until count) {
            val m = markers[i]
            // start time in 100-nanosecond units
            val start100ns = m.startMs * 10_000L
            payload.write(longBE(start100ns))
            val titleBytes = m.title.toByteArray(Charsets.UTF_8)
            val tlen = titleBytes.size.coerceAtMost(255)
            payload.write(tlen and 0xFF)
            payload.write(titleBytes, 0, tlen)
        }
        return box("chpl", payload.toByteArray())
    }

    /**
     * iTunes `meta` box: `[fullbox header][hdlr 'mdir'][ilst ...]`. Returns
     * null when there's nothing to write (no title, author or cover).
     */
    fun meta(title: String, author: String, cover: ByteArray?): ByteArray? {
        val items = ByteArrayOutputStream()
        if (title.isNotBlank()) items.write(ituneTextItem("©nam", title))
        if (author.isNotBlank()) items.write(ituneTextItem("©ART", author))
        if (cover != null && cover.isNotEmpty()) items.write(ituneCoverItem(cover))
        val ilstPayload = items.toByteArray()
        if (ilstPayload.isEmpty()) return null

        val metaPayload = ByteArrayOutputStream()
        // meta is a FullBox: version(1) + flags(3) before its children.
        metaPayload.write(byteArrayOf(0, 0, 0, 0))
        metaPayload.write(hdlrMdir())
        metaPayload.write(box("ilst", ilstPayload))
        return box("meta", metaPayload.toByteArray())
    }

    /** `hdlr` declaring the metadata handler type `mdir`/`appl` that iTunes
     *  metadata readers expect inside a `meta` box. */
    private fun hdlrMdir(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(byteArrayOf(0, 0, 0, 0)) // version + flags
        p.write(byteArrayOf(0, 0, 0, 0)) // pre_defined
        p.write("mdir".toByteArray(Charsets.US_ASCII)) // handler_type
        p.write("appl".toByteArray(Charsets.US_ASCII)) // reserved[0] = 'appl'
        p.write(byteArrayOf(0, 0, 0, 0)) // reserved[1]
        p.write(byteArrayOf(0, 0, 0, 0)) // reserved[2]
        p.write(0x00) // name (empty, null-terminated)
        return box("hdlr", p.toByteArray())
    }

    /** A `©nam`/`©ART`-style text metadata item: an outer box named by the
     *  4-byte key, containing a `data` box with type-flag 1 (UTF-8 text). */
    private fun ituneTextItem(key: String, value: String): ByteArray {
        val data = dataBox(typeFlag = 1, payload = value.toByteArray(Charsets.UTF_8))
        return box(key, data)
    }

    /** A `covr` cover-art item: `data` box with the image type flag (13 = JPEG,
     *  14 = PNG). We sniff the PNG magic; default to JPEG. */
    private fun ituneCoverItem(cover: ByteArray): ByteArray {
        val isPng = cover.size >= 8 &&
            cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte() &&
            cover[2] == 'N'.code.toByte() && cover[3] == 'G'.code.toByte()
        val typeFlag = if (isPng) 14 else 13
        return box("covr", dataBox(typeFlag = typeFlag, payload = cover))
    }

    /** iTunes `data` box: `[uint32 typeFlag][uint32 locale=0][payload]`. */
    private fun dataBox(typeFlag: Int, payload: ByteArray): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(intBE(typeFlag))
        p.write(intBE(0)) // locale
        p.write(payload)
        return box("data", p.toByteArray())
    }

    /** Wrap [payload] in a box of [type] (4 ASCII chars). */
    fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == FOURCC_LEN) { "box type must be 4 chars: $type" }
        val size = 8 + payload.size
        val out = ByteArray(size)
        writeIntBE(out, 0, size)
        // Type chars are Latin-1 (©nam uses 0xA9) — encode per-char.
        for (i in 0 until FOURCC_LEN) out[4 + i] = type[i].code.toByte()
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    fun intBE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    fun longBE(v: Long): ByteArray = ByteArray(8) { i ->
        ((v ushr (56 - i * 8)) and 0xFF).toByte()
    }

    fun writeIntBE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = ((v ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (v and 0xFF).toByte()
    }
}
