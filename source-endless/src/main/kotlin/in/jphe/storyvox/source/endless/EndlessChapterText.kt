package `in`.jphe.storyvox.source.endless

/**
 * Turns a chapter's canonical markdown into the two bodies
 * [`in`.jphe.storyvox.data.source.model.ChapterContent] wants: a
 * sanitized HTML body for the reader and a plaintext rendering for TTS.
 *
 * ## The input shape
 *
 * The daemon's `text_md` is an `# Chapter N: Title` heading followed by
 * blank-line-separated blocks, each opening with a speaker tag:
 *
 * ```
 * # Chapter 5: The Mill at Blackwater
 *
 * [narrator] The rain in Oakhaven did not wash things clean. …
 *
 * [SYSTEM] Quest updated: …
 *
 * [Sera] "It's always giving way."
 * ```
 *
 * Speakers seen live: `narrator`, `SYSTEM`, and character names
 * (`Kaelen`, `Sera`, `Shadow`). The parser treats any `[label]` at the
 * start of a block as a speaker and does not enumerate them — a new
 * character must not need a client release.
 *
 * ## Why the tags must come off
 *
 * `plainBody` feeds TTS on any chapter whose audio hasn't been rendered
 * yet. Left in place, the narrator voice reads the word "narrator" aloud
 * at the top of every block. So the tag is stripped from the plaintext,
 * and carried into the HTML as structure instead.
 *
 * ## Why this parses `text_md` and not the render manifest
 *
 * Both carry the prose, but they are split differently and only one is
 * canonical. `text_md` is permanent and never rewritten; the manifest is
 * a *render-time* artifact whose granularity changes with the TTS engine
 * — measured against the live daemon, chapter 5 has 5 markdown narrator
 * blocks but 58 narrator manifest segments, and older chapters have
 * per-speaker-turn segments spanning several minutes each. Deriving the
 * reader's text from the manifest would make the prose the user sees
 * depend on which engine version last rendered the audio, and would show
 * nothing at all for a chapter awaiting its first render. The manifest is
 * for timings; the markdown is for text.
 */
internal object EndlessChapterText {

    /** One `[speaker] text` block from the markdown. */
    internal data class Block(val speaker: String, val text: String) {
        /** True for the LitRPG status readouts (`[SYSTEM] …`). */
        val isSystem: Boolean get() = speaker.equals("SYSTEM", ignoreCase = true)
    }

    /**
     * Split [textMd] into speaker blocks, dropping the leading `#`
     * heading (the reader renders the chapter title from
     * [`in`.jphe.storyvox.data.source.model.ChapterInfo.title]; repeating
     * it in the body would double it on screen and make TTS read it
     * twice).
     *
     * Untagged prose is kept with an empty speaker rather than discarded.
     * The daemon tags every block today, but silently dropping text
     * because it lacked a tag would lose story — the one thing this
     * pipeline is built never to do.
     */
    internal fun blocks(textMd: String): List<Block> =
        BLOCK_SPLIT.split(textMd)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("#") }
            .map { block ->
                val match = SPEAKER_TAG.find(block)
                if (match != null) {
                    Block(
                        speaker = match.groupValues[1].trim(),
                        text = block.substring(match.range.last + 1).trim(),
                    )
                } else {
                    Block(speaker = "", text = block)
                }
            }
            .filter { it.text.isNotEmpty() }
            .toList()

    /**
     * Plaintext for TTS: block texts joined by a blank line, speaker tags
     * removed. Blank-line separation is what `:core-playback`'s paragraph
     * chunker reads as a paragraph boundary, so status blocks and prose
     * get their own cadence.
     */
    internal fun plainBody(textMd: String): String =
        blocks(textMd).joinToString(separator = "\n\n") { it.text }

    /**
     * Sanitized HTML for the reader. Narration and dialogue become `<p>`;
     * `[SYSTEM]` blocks become `<blockquote><p>`, because a LitRPG status
     * readout is a quoted interface panel rather than prose and reads
     * wrongly run together with the narration.
     *
     * Character dialogue keeps a `<strong>` speaker label — with a cast
     * of voices in the audio, the reader should show who is speaking.
     * `narrator` gets no label (it is the default voice, and labelling
     * every narration block would be noise).
     *
     * Escaping happens here and only here: the daemon's text is
     * model-generated prose that routinely contains `&`, `<` and `>`, and
     * this is the one place it becomes markup.
     */
    internal fun htmlBody(textMd: String): String =
        blocks(textMd).joinToString(separator = "") { block ->
            val body = escape(block.text).replace("\n", "<br/>")
            when {
                block.isSystem -> "<blockquote><p>$body</p></blockquote>"
                block.speaker.isBlank() ||
                    block.speaker.equals("narrator", ignoreCase = true) -> "<p>$body</p>"
                else -> "<p><strong>${escape(block.speaker)}:</strong> $body</p>"
            }
        }

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** Blank line — the block delimiter in the daemon's markdown. */
    private val BLOCK_SPLIT = Regex("\\n\\s*\\n")

    /**
     * A leading `[speaker]` tag. Deliberately permissive about the label
     * (any run of non-`]` characters) so a newly-introduced character
     * needs no client change.
     */
    private val SPEAKER_TAG = Regex("^\\[([^\\]]{1,64})\\]")
}
