package `in`.jphe.storyvox.source.handbook

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the handbook out of this module's bundled `assets/handbook/`, which
 * `scripts/build-handbook-assets.py` compiles from `docs/`. There is no network
 * and no device-private data — just a tab-separated `manifest.tsv` (a `version`
 * line + one `id<TAB>title` line per chapter, in order) and one `<id>.txt`
 * plain-text body per chapter.
 *
 * Every read is pinned to Dispatchers.IO here — a local source gets NO #585
 * IO-pin check from the HTTP contract kit, so main-safety is this seam's job.
 */
@Singleton
internal class AssetCandelaHandbookReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : CandelaHandbookReader {

    override suspend fun manifest(): HandbookManifest = withContext(Dispatchers.IO) {
        val raw = try {
            context.assets.open("$ASSET_DIR/$MANIFEST").use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            // Missing OR unreadable/corrupt manifest → degrade to an empty
            // handbook, never crash the help surface (FileNotFoundException is an
            // IOException, so this one arm covers both).
            return@withContext HandbookManifest(version = "", chapters = emptyList())
        }
        parseManifest(raw)
    }

    override suspend fun chapterText(id: String): String? = withContext(Dispatchers.IO) {
        // Chapter ids are slugs, never paths — guard against traversal out of the bundle.
        if (!id.matches(SLUG)) return@withContext null
        try {
            context.assets.open("$ASSET_DIR/$id.txt").use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            // Missing OR unreadable body → NotFound upstream, not a crash.
            null
        }
    }

    private fun parseManifest(raw: String): HandbookManifest {
        var version = ""
        val chapters = mutableListOf<HandbookChapter>()
        var index = 0
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val key = parts[0]
            val value = parts[1]
            if (key == "version") {
                version = value
            } else {
                chapters += HandbookChapter(id = key, title = value, index = index++)
            }
        }
        return HandbookManifest(version = version, chapters = chapters)
    }

    private companion object {
        const val ASSET_DIR = "handbook"
        const val MANIFEST = "manifest.tsv"
        val SLUG = Regex("[a-z0-9-]+")
    }
}
