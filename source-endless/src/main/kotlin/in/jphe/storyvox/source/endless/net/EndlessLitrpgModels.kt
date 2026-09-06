package `in`.jphe.storyvox.source.endless.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the endless-litrpg daemon's JSON surface (spec §9.1).
 *
 * Every field that the daemon can legitimately omit or null is modelled
 * as nullable with a default, and the shared [EndlessLitrpgApi.JSON]
 * instance sets `ignoreUnknownKeys` — the daemon and this module ship
 * on separate cycles, so a field added server-side must never break an
 * installed client.
 */

/** `GET /api/story` — the serial's metadata. */
@Serializable
internal data class EndlessStory(
    val title: String = "",
    val description: String = "",
    val protagonist: String = "",
    val language: String = "",
    @SerialName("chapter_count") val chapterCount: Int = 0,
    @SerialName("latest_chapter") val latestChapter: Int = 0,
    /**
     * Chapters whose ledger extraction failed but whose prose shipped
     * anyway (spec §10: "a bookkeeping failure must never cost a
     * chapter"). Informational for a client — the text is fine.
     */
    @SerialName("dirty_chapters") val dirtyChapters: List<Int> = emptyList(),
    @SerialName("sample_rate") val sampleRate: Int = 0,
    /**
     * Bytes of PCM per millisecond — 32 for 16 kHz mono s16le. Served
     * so clients don't hardcode it (spec §8.1). Unused on the MP3 path
     * this module takes today; the follow-up highlighting work needs it.
     */
    @SerialName("bytes_per_ms") val bytesPerMs: Int = 0,
    @SerialName("target_words") val targetWords: Int = 0,
    /**
     * Hash of the story prompt. Changes when JP edits the premise, so
     * it belongs in the cheap-revision token alongside the chapter
     * count — see [`in`.jphe.storyvox.source.endless.EndlessLitrpgSource.latestRevisionToken].
     */
    @SerialName("prompt_hash") val promptHash: String = "",
    val initialised: Boolean = false,
)

/**
 * `GET /api/chapters?since=N` — one table-of-contents entry.
 *
 * [mp3Url] / [pcmUrl] / [totalBytes] are **null whenever [hasAudio] is
 * false** — verified against the live daemon, which nulls all three
 * together when a chapter's audio hasn't been rendered (or has been
 * invalidated for a re-render). Never synthesize a media URL from a
 * template to fill the gap: a fabricated URL turns "audio not rendered
 * yet" into a 404 inside ExoPlayer, which surfaces to the user as a
 * broken chapter instead of a text chapter that reads fine.
 */
@Serializable
internal data class EndlessChapterEntry(
    val number: Int,
    val title: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0L,
    @SerialName("has_audio") val hasAudio: Boolean = false,
    val words: Int = 0,
    @SerialName("state_dirty") val stateDirty: Boolean = false,
    @SerialName("pcm_url") val pcmUrl: String? = null,
    @SerialName("mp3_url") val mp3Url: String? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
)

/** `GET /api/chapters/{n}` — prose plus the render manifest. */
@Serializable
internal data class EndlessChapter(
    val number: Int,
    val title: String = "",
    /** Canonical markdown: an `# Chapter N: Title` heading then
     *  `[speaker] …` blocks. Permanent; never rewritten by a re-render. */
    @SerialName("text_md") val textMd: String = "",
    @SerialName("prompt_hash") val promptHash: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0L,
    @SerialName("has_audio") val hasAudio: Boolean = false,
    @SerialName("state_dirty") val stateDirty: Boolean = false,
    @SerialName("pcm_url") val pcmUrl: String? = null,
    @SerialName("mp3_url") val mp3Url: String? = null,
    /**
     * Per-segment render manifest with `start_ms`/`end_ms` timings.
     *
     * Parsed but **not consumed** by this module: sentence highlighting
     * over pre-rendered audio needs a `:core-playback` change that this
     * source module cannot reach (see the class kdoc on
     * [`in`.jphe.storyvox.source.endless.EndlessLitrpgSource]). Kept on
     * the model because it costs nothing, documents the daemon's real
     * shape, and is the input the follow-up work needs.
     *
     * Null-safe on purpose: the daemon serves a *stale* manifest for a
     * chapter whose audio was invalidated for re-render, so manifest
     * presence is NOT evidence that audio exists — only [hasAudio] and
     * a non-null [mp3Url] are.
     */
    val manifest: EndlessManifest? = null,
    @SerialName("manifest_contiguous") val manifestContiguous: Boolean = false,
)

@Serializable
internal data class EndlessManifest(
    val chapter: Int = 0,
    @SerialName("sample_rate") val sampleRate: Int = 0,
    @SerialName("bytes_per_ms") val bytesPerMs: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val segments: List<EndlessSegment> = emptyList(),
)

@Serializable
internal data class EndlessSegment(
    val idx: Int = 0,
    val speaker: String = "",
    /** `narrator` | `character` | `system`. */
    val kind: String = "",
    @SerialName("voice_ref") val voiceRef: String = "",
    val text: String = "",
    @SerialName("start_ms") val startMs: Long = 0L,
    @SerialName("end_ms") val endMs: Long = 0L,
)
