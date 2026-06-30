package `in`.jphe.storyvox.feature.reader.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.data.script.LineSegment
import `in`.jphe.storyvox.data.script.ScriptBlock
import `in`.jphe.storyvox.data.script.parseTeleprompterScript
import `in`.jphe.storyvox.feature.reader.teleprompterScrollDeltaPx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

/**
 * Issue #1367 — the teleprompter text layer composited over the camera, with
 * the production cues ported from the TechEmpower Show web prompter:
 *  - **Speaker colour-coding** — a left accent bar + label per speaker (coral,
 *    teal, … by first appearance).
 *  - **Section banners** — centered uppercase chips.
 *  - **Production cues** (`[POST: ...]`) — dashed, italic, dimmed; not spoken.
 *  - **Eye-line markers** — fixed chevrons at 38% height (look here ≈ the lens).
 *  - **Edge fades** — the top/bottom 14% of the text fades to transparent so it
 *    doesn't hard-cut over the camera (the camera stays fully visible — only the
 *    text's alpha is masked).
 *  - **Mirror mode** — horizontal flip for beam-splitter glass rigs.
 *  - **Font size** — caller-controlled, for camera distance.
 *
 * The script is parsed once ([parseTeleprompterScript]); the spoken-word count
 * (cues/sections excluded) drives the auto-scroll pace via the reader's shared
 * [teleprompterScrollDeltaPx] math, so the pace matches the in-reader
 * teleprompter exactly. Scrolls only while [scrolling] (recording); before that
 * it rests at the top so the user can frame themselves.
 */
@Composable
fun TeleprompterOverlay(
    script: String,
    wpm: Int,
    fontSize: Int,
    opacity: Float,
    mirror: Boolean,
    scrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(script) { parseTeleprompterScript(script) }
    val scroll = rememberScrollState()

    LaunchedEffect(scrolling, wpm, parsed.spokenWordCount) {
        if (!scrolling || parsed.spokenWordCount <= 0) return@LaunchedEffect
        scroll.scrollTo(0)
        var lastFrame = 0L
        var carry = 0f
        while (isActive) {
            val frame = withFrameNanos { it }
            if (lastFrame != 0L && scroll.maxValue > 0) {
                val px = teleprompterScrollDeltaPx(
                    wpm = wpm,
                    totalWords = parsed.spokenWordCount,
                    scrollableHeightPx = scroll.maxValue,
                    elapsedNanos = frame - lastFrame,
                ) + carry
                val whole = px.toInt()
                carry = px - whole
                if (whole > 0) {
                    try {
                        scroll.scrollTo(scroll.value + whole)
                    } catch (e: CancellationException) {
                        if (!isActive) throw e
                        carry = 0f
                    }
                }
                if (scroll.value >= scroll.maxValue) break
            }
            lastFrame = frame
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (parsed.blocks.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (mirror) scaleX = -1f
                        // Render to an offscreen layer so the edge-fade DstIn
                        // mask blends against the layer's own transparency
                        // (revealing the camera) rather than the camera pixels.
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val fade = size.height * EDGE_FADE_FRACTION
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                (fade / size.height) to Color.Black,
                                1f - (fade / size.height) to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .verticalScroll(scroll)
                    .padding(horizontal = 24.dp, vertical = 150.dp),
            ) {
                parsed.blocks.forEach { block ->
                    when (block) {
                        is ScriptBlock.Section -> SectionChip(block.title, fontSize, opacity)
                        is ScriptBlock.Cue -> CueCard(block.text, fontSize, opacity)
                        is ScriptBlock.Line -> ScriptLineRow(block, fontSize, opacity)
                    }
                }
            }
        }

        // Eye-line chevrons — fixed at 38% height, NOT inside the scroll/mirror
        // layer (they stay put and unmirrored).
        EyeLineMarkers(opacity)
    }
}

/** Fixed inward-pointing chevrons at 38% viewport height — the speaker looks
 *  here, near the lens, instead of down at the text. */
@Composable
private fun EyeLineMarkers(opacity: Float) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.weight(EYELINE_FRACTION))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val tint = AccentColor.copy(alpha = 0.85f * opacity)
            Text(text = "▶", color = tint, fontSize = 22.sp) // ▶
            Text(text = "◀", color = tint, fontSize = 22.sp) // ◀
        }
        Spacer(modifier = Modifier.weight(1f - EYELINE_FRACTION))
    }
}

@Composable
private fun SectionChip(title: String, fontSize: Int, opacity: Float) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.uppercase(),
            color = AccentColor.copy(alpha = opacity),
            fontSize = (fontSize * 0.5f).sp,
            lineHeight = (fontSize * 0.7f).sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CueCard(text: String, fontSize: Int, opacity: Float) {
    val dim = Color.White.copy(alpha = 0.62f * opacity)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .drawBehind {
                drawRoundRect(
                    color = dim,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    ),
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = dim,
            fontStyle = FontStyle.Italic,
            fontSize = (fontSize * 0.6f).sp,
            lineHeight = (fontSize * 0.85f).sp,
        )
    }
}

@Composable
private fun ScriptLineRow(line: ScriptBlock.Line, fontSize: Int, opacity: Float) {
    val speakerColor = line.speaker?.let {
        SpeakerPalette[it.colorIndex % SpeakerPalette.size].copy(alpha = opacity)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (line.showLabel && line.speaker != null && speakerColor != null) {
            Text(
                text = line.speaker.name,
                color = speakerColor,
                fontSize = (fontSize * 0.5f).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
        }
        if (line.segments.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                if (speakerColor != null) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(speakerColor),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = buildLine(line.segments, opacity),
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.4f).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Spoken runs in white@opacity, inline cues dimmed + italic. */
private fun buildLine(segments: List<LineSegment>, opacity: Float): AnnotatedString {
    val spoken = SpanStyle(color = Color.White.copy(alpha = opacity))
    val cue = SpanStyle(color = Color.White.copy(alpha = 0.62f * opacity), fontStyle = FontStyle.Italic)
    return buildAnnotatedString {
        segments.forEach { segment ->
            when (segment) {
                is LineSegment.Spoken -> withStyle(spoken) { append(segment.text) }
                is LineSegment.InlineCue -> withStyle(cue) { append(segment.text) }
            }
        }
    }
}

/** TechEmpower Show accent (amber) used for section chips + eye-line markers. */
private val AccentColor = Color(0xFFE8A13C)

/** Speaker colours assigned by first-appearance order. Coral + teal match the
 *  web prompter's Shawna/Jeff; the rest cover scripts with more voices. */
private val SpeakerPalette = listOf(
    Color(0xFFE87A5D), // coral
    Color(0xFF5DA89B), // teal
    Color(0xFFE8A13C), // amber
    Color(0xFFB98AE0), // violet
    Color(0xFF7FB069), // sage
)

private const val EDGE_FADE_FRACTION = 0.14f
private const val EYELINE_FRACTION = 0.38f
