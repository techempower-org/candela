package `in`.jphe.storyvox.source.epub.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Issue #235 — EPUB 2/3 parser using only Android's bundled
 * XmlPullParser + java.util.zip. Walk:
 *
 * 1. Read `META-INF/container.xml` to find the rootfile path (the OPF).
 * 2. Read the OPF: extract `<dc:title>`, `<dc:creator>`, build the
 *    manifest map (id → href + media-type), walk the spine to get
 *    the ordered list of idrefs.
 * 3. For each spine idref, look up the manifest href, read the
 *    corresponding zip entry, and capture its HTML body.
 *
 * The parser takes a ZipInputStream factory rather than a path so
 * callers can feed it a SAF DocumentFile (see [parseFromBytes]) or
 * a regular File without coupling to either path.
 *
 * Why hand-rolled instead of pulling epublib-android: epublib drags
 * in JSoup + a chain of XML deps, ~3MB AAR, and the storyvox use
 * case is read-only spine extraction. ~250 LOC keeps it minimal.
 */
object EpubParser {

    /** Parse an EPUB from raw bytes (the entire .epub file). The
     *  full file gets read into memory once — fine for typical
     *  audiobook-length EPUBs (5-50 MB) and avoids the streaming
     *  zip-walk problem (we need to read the OPF before we know
     *  which entries to extract for the spine). */
    fun parseFromBytes(bytes: ByteArray): EpubBook {
        // Pass 1 — find rootfile path from META-INF/container.xml.
        val rootfilePath = readFirstEntry(bytes, "META-INF/container.xml") { stream ->
            findRootfilePath(stream)
        } ?: throw EpubParseException("META-INF/container.xml missing or has no rootfile")

        // Pass 2 — read the OPF + collect every other entry into a
        // map so we can resolve hrefs by matching the zip path to
        // the OPF-relative href.
        val entries = readAllEntries(bytes)
        val opfBytes = entries[rootfilePath]
            ?: throw EpubParseException("OPF rootfile missing: $rootfilePath")
        val opfDir = rootfilePath.substringBeforeLast('/', "")
        val opf = parseOpf(String(opfBytes, Charsets.UTF_8))

        // Resolve each spine idref → href via the manifest, then
        // pull the chapter HTML by resolving opfDir + href against the
        // zip entry names.
        val chapters = opf.spineIdrefs.mapIndexedNotNull { index, idref ->
            val manifestItem = opf.manifest[idref] ?: return@mapIndexedNotNull null
            // #1035 / #1021 — OPF hrefs are URI references: they may be
            // percent-encoded ("Chapter%201.xhtml") and relative
            // ("../text/ch1.xhtml"), while ZipEntry.name is the literal
            // decoded path. [resolveHref] percent-decodes, resolves
            // ./ and ../ segments, and strips a leading slash so the
            // result matches the zip entry. The raw join is kept as a
            // fallback for the (rare) EPUB whose zip names are
            // themselves percent-encoded.
            val resolved = resolveHref(opfDir, manifestItem.href)
            val bodyBytes = entries[resolved]
                ?: entries[joinPath(opfDir, manifestItem.href)]
                ?: return@mapIndexedNotNull null
            EpubChapter(
                id = idref,
                title = manifestItem.title ?: "Chapter ${index + 1}",
                index = index,
                htmlBody = String(bodyBytes, Charsets.UTF_8),
            )
        }

        return EpubBook(
            title = opf.title.ifBlank { "Untitled" },
            author = opf.creator.orEmpty(),
            chapters = chapters,
            coverHref = opf.coverHref,
        )
    }

    // ── container.xml ────────────────────────────────────────────

    private fun findRootfilePath(stream: InputStream): String? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
        }
        return null
    }

    // ── OPF ──────────────────────────────────────────────────────

    private data class OpfData(
        val title: String,
        val creator: String?,
        val manifest: Map<String, ManifestItem>,
        val spineIdrefs: List<String>,
        val coverHref: String?,
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String?,
        val title: String? = null,
    )

    private fun parseOpf(xml: String): OpfData {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var title = ""
        var creator: String? = null
        val manifest = mutableMapOf<String, ManifestItem>()
        val spineIdrefs = mutableListOf<String>()
        var coverIdRef: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "dc:title" -> if (title.isBlank()) title = readText(parser).trim()
                "dc:creator" -> if (creator == null) creator = readText(parser).trim().takeIf { it.isNotBlank() }
                "meta" -> {
                    // EPUB 2 cover hint: <meta name="cover" content="<id>"/>
                    val name = parser.getAttributeValue(null, "name")
                    if (name == "cover") {
                        coverIdRef = parser.getAttributeValue(null, "content")
                    }
                    skip(parser)
                }
                "item" -> {
                    val id = parser.getAttributeValue(null, "id") ?: ""
                    val href = parser.getAttributeValue(null, "href") ?: ""
                    val mediaType = parser.getAttributeValue(null, "media-type")
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        manifest[id] = ManifestItem(id, href, mediaType)
                    }
                    skip(parser)
                }
                "itemref" -> {
                    val idref = parser.getAttributeValue(null, "idref")
                    if (!idref.isNullOrEmpty()) spineIdrefs += idref
                    skip(parser)
                }
                else -> {} // continue walking
            }
        }

        val coverHref = coverIdRef?.let { manifest[it]?.href }
        return OpfData(title, creator, manifest, spineIdrefs, coverHref)
    }

    // ── zip helpers ──────────────────────────────────────────────

    /** Single-pass zip walk to find one named entry; returns null if
     *  not found. Streams the matching entry to [reader] and returns
     *  its result. Used for container.xml only — for the body
     *  extraction we read everything into a map (faster than walking
     *  the zip once per chapter). */
    private fun <T> readFirstEntry(
        bytes: ByteArray,
        name: String,
        reader: (InputStream) -> T,
    ): T? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) {
                    return reader(zip)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** Read every non-directory entry into a Map<path, bytes>. EPUBs
     *  are typically <50 MB so the memory hit is acceptable; the
     *  alternative (re-walking the zip per chapter) is O(N²) on
     *  spine size and noticeably slow on long fictions. */
    private fun readAllEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun joinPath(dir: String, href: String): String {
        if (dir.isEmpty()) return href
        // EPUB hrefs use forward slashes regardless of host OS.
        return "$dir/$href".replace("//", "/")
    }

    /**
     * Resolve an OPF manifest [href] against the OPF directory [dir] into
     * a path that matches a decoded [ZipEntry.name].
     *
     * OPF hrefs are URI references (EPUB 3 §3.4.1 / OPF 2.0.1 §1.4), so
     * they may be:
     *  - **percent-encoded** — `Chapter%201.xhtml`, `Caf%C3%A9.xhtml`
     *    (#1035). `java.util.zip` exposes the literal decoded name, so we
     *    decode the href before matching.
     *  - **relative with `./` / `../` segments** — `../text/ch1.xhtml`
     *    when the OPF lives in a subdir like `OEBPS/` (#1021).
     *  - **leading-slash absolute** — `/OEBPS/ch1.xhtml`.
     *
     * Steps: percent-decode → join with [dir] → collapse `.`/`..`
     * segments → strip a leading `/`. Pure Kotlin (no `android.util.Xml`)
     * so it is unit-testable without the Android XML coupling that has
     * kept this module untested.
     */
    internal fun resolveHref(dir: String, href: String): String {
        val decoded = percentDecode(href)
        // A leading-slash href is root-absolute: resolve it from the zip
        // root, ignoring the OPF dir (joining would double-prefix).
        val joined = when {
            decoded.startsWith('/') -> decoded
            dir.isEmpty() -> decoded
            else -> "$dir/$decoded"
        }
        return normalizeSegments(joined)
    }

    /** Collapse `.`/`..` path segments and strip a leading slash, on a
     *  forward-slash path. A `..` that would escape the root is dropped
     *  (clamped at root) rather than producing a leading `..`, since zip
     *  entry names are always root-relative. */
    private fun normalizeSegments(path: String): String {
        val stack = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {} // skip empty (handles `//` and leading `/`) and `.`
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }

    /** Path-aware percent-decoding: turns `%XX` escapes into their bytes
     *  and UTF-8-decodes the result. Unlike [java.net.URLDecoder], a
     *  literal `+` stays a `+` (it is a valid filename char and only
     *  means "space" in `application/x-www-form-urlencoded`, not in path
     *  components). Malformed escapes are left verbatim. */
    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            // Not a valid escape — emit the char's UTF-8 bytes verbatim.
            for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    // ── XmlPullParser helpers (mirrored from RssParser) ──────────

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }
}

class EpubParseException(message: String) : RuntimeException(message)
