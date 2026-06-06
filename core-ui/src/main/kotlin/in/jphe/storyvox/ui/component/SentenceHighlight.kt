package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReaderColors
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion

/**
 * Renders chapter text with a brass underline that animates over the currently-spoken sentence.
 *
 * @param text full chapter text
 * @param highlightStart UTF-16 char index where the current sentence begins
 * @param highlightEnd UTF-16 char index where the current sentence ends (exclusive)
 * @param onTapWord optional — invoked with char index of the word the user tapped (for "start TTS from here")
 * @param onLongPressWord optional — invoked with the long-pressed word (extracted via `TextLayoutResult.getWordBoundary`).
 *                        Used by the reader for the "Ask AI: who is X?" character-lookup affordance (#188); the long-press
 *                        does NOT fire `onTapWord`, so a deliberate long-press never accidentally seeks playback.
 * @param onLayout optional — emits the text layout each time it changes; reader uses it to auto-scroll
 *                the highlighted sentence into view.
 */
@Composable
fun SentenceHighlight(
    text: String,
    highlightStart: Int,
    highlightEnd: Int,
    modifier: Modifier = Modifier,
    onTapWord: ((Int) -> Unit)? = null,
    onLongPressWord: ((String) -> Unit)? = null,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    // #993 — when a reading theme is active, the chapter text uses the
    // theme's foreground and the sentence underline uses its accent; otherwise
    // we fall back to MaterialTheme so the default reader is pixel-identical.
    val readerColors = LocalReaderColors.current.resolved
    val brass = readerColors?.accent ?: MaterialTheme.colorScheme.primary
    val onSurface = readerColors?.foreground ?: MaterialTheme.colorScheme.onSurface
    val motion = LocalMotion.current

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Build the highlighted AnnotatedString without copying the chapter body.
    // The previous shape was three `text.substring(...)` calls per sentence
    // change (pre-highlight prefix, highlighted span, post-highlight tail) —
    // that's two ~10-50KB allocations every ~3 s while playback runs, just
    // to throw the substrings into a freshly built AnnotatedString.
    //
    // Instead, hold a single shared `AnnotatedString` over the full text
    // (memoized by `text` alone — the chapter body changes only on chapter
    // switch) and overlay the highlight span via the public AnnotatedString
    // constructor's `spanStyles` list. That constructor takes the SpanStyle
    // ranges by reference; no character copying happens on a sentence change.
    val baseAnnotated: AnnotatedString = remember(text) { AnnotatedString(text) }
    val annotated: AnnotatedString = remember(baseAnnotated, highlightStart, highlightEnd, onSurface) {
        if (highlightEnd > highlightStart &&
            highlightStart in 0..text.length &&
            highlightEnd in highlightStart..text.length
        ) {
            AnnotatedString(
                text = text,
                spanStyles = listOf(
                    AnnotatedString.Range(
                        item = SpanStyle(color = onSurface, fontWeight = FontWeight.Medium),
                        start = highlightStart,
                        end = highlightEnd,
                    ),
                ),
            )
        } else {
            baseAnnotated
        }
    }

    val reducedMotion = LocalReducedMotion.current

    // The underline used to snap from one sentence's bounds to the next.
    // Now we animate the bounds themselves: `animatedStart` and
    // `animatedEnd` glide between consecutive sentence ranges over
    // `sentenceDurationMs` (180ms via `sentenceEasing`). The drawBehind
    // resolves these animated offsets to line positions, so within a line
    // the underline literally slides its edges; crossing a line boundary
    // reads as a continuous traversal of the offset space rather than a
    // jump cut.
    //
    // Reduced motion: skip the int animations entirely, fall back to the
    // raw values. Same contract as cascadeReveal / NavHost / spinner /
    // sleep timer — absent motion, not shorter motion.
    val animatedStart by animateIntAsState(
        targetValue = highlightStart,
        animationSpec = androidx.compose.animation.core.tween(motion.sentenceDurationMs, easing = motion.sentenceEasing),
        label = "sentenceUnderlineStart",
    )
    val animatedEnd by animateIntAsState(
        targetValue = highlightEnd,
        animationSpec = androidx.compose.animation.core.tween(motion.sentenceDurationMs, easing = motion.sentenceEasing),
        label = "sentenceUnderlineEnd",
    )

    // Fade the underline in on first appearance / out on disappearance.
    // Held at 1f throughout sentence-to-sentence transitions because the
    // underline is *moving*, not appearing — fading the brass would obscure
    // the glide.
    val target = if (highlightEnd > highlightStart) 1f else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = androidx.compose.animation.core.tween(motion.sentenceDurationMs, easing = motion.sentenceEasing),
        label = "sentenceUnderline",
    )

    val drawStart = if (reducedMotion) highlightStart else animatedStart
    val drawEnd = if (reducedMotion) highlightEnd else animatedEnd

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        color = onSurface,
        onTextLayout = {
            layout = it
            onLayout?.invoke(it)
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            // Tap-to-seek + long-press-to-look-up. Convert pointer position
            // into a UTF-16 offset via the captured TextLayoutResult and
            // forward to the appropriate callback. The pointerInput is keyed
            // on `text` AND both callbacks so a chapter switch (which changes
            // both text content and length) re-installs the gesture handler
            // with fresh closures — otherwise tap/press offsets would be
            // clamped against the previous chapter's length.
            //
            // Long-press resolves the word boundary at the press position via
            // `TextLayoutResult.getWordBoundary` (a `TextRange`) and passes
            // the substring to onLongPressWord. detectTapGestures dispatches
            // either onTap OR onLongPress for a given gesture, never both, so
            // a long-press never accidentally seeks.
            .then(
                if (onTapWord != null || onLongPressWord != null) {
                    Modifier.pointerInput(onTapWord, onLongPressWord, text) {
                        detectTapGestures(
                            onTap = if (onTapWord != null) {
                                { tap ->
                                    val l = layout
                                    if (l != null) {
                                        val charIndex = l.getOffsetForPosition(tap)
                                            .coerceIn(0, text.length)
                                        onTapWord(charIndex)
                                    }
                                }
                            } else null,
                            onLongPress = if (onLongPressWord != null) {
                                { press ->
                                    val l = layout
                                    if (l != null && text.isNotEmpty()) {
                                        val charIndex = l.getOffsetForPosition(press)
                                            .coerceIn(0, (text.length - 1).coerceAtLeast(0))
                                        val range = l.getWordBoundary(charIndex)
                                        val safeStart = range.start.coerceIn(0, text.length)
                                        val safeEnd = range.end.coerceIn(safeStart, text.length)
                                        val word = text.substring(safeStart, safeEnd).trim()
                                        if (word.isNotEmpty()) onLongPressWord(word)
                                    }
                                }
                            } else null,
                        )
                    }
                } else {
                    Modifier
                }
            )
            .drawBehind {
                val l = layout ?: return@drawBehind
                if (drawEnd <= drawStart) return@drawBehind
                val safeStart = drawStart.coerceIn(0, text.length)
                val safeEnd = drawEnd.coerceIn(safeStart, text.length)
                val firstLine = l.getLineForOffset(safeStart)
                val lastLine = l.getLineForOffset(safeEnd.coerceAtLeast(safeStart + 1) - 1)
                for (line in firstLine..lastLine) {
                    val lineStart = l.getLineStart(line)
                    val lineEnd = l.getLineEnd(line, visibleEnd = true)
                    val segStart = maxOf(safeStart, lineStart)
                    val segEnd = minOf(safeEnd, lineEnd)
                    if (segEnd <= segStart) continue
                    val xStart = l.getHorizontalPosition(segStart, usePrimaryDirection = true)
                    val xEnd = l.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                    val y = l.getLineBottom(line) - 2.dp.toPx()
                    val width = (xEnd - xStart) * animated
                    drawRect(
                        color = brass,
                        topLeft = Offset(xStart, y),
                        size = Size(width, 2.dp.toPx()),
                    )
                }
            },
    )

}
