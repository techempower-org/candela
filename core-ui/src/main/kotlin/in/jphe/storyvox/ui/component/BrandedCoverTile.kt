package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.ui.theme.BrassRamp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Source-family hint for [BrandedCoverTile]. Different families render
 * their watermark glyph slightly differently — TechEmpower gets a brass
 * sun-disk, generic Notion / others get a neutral brass medallion.
 *
 * New families can be added here when storyvox grows another branded
 * synthetic-cover surface (Outline collections, GitHub repo READMEs,
 * Royal Road author imprints, …); the `when`s in this file are
 * exhaustive so the compiler will tell you what to wire next.
 */
enum class CoverSourceFamily {
    /** TechEmpower-flavored — brass sun-disk watermark (the sun-disc
     *  is the org's mark; #511 wired it into the Notion source default). */
    TechEmpower,

    /** Generic Notion or other source — neutral brass medallion
     *  watermark instead of the TechEmpower sun-disk. */
    Generic,
}

/**
 * Map a [FictionSummary.sourceId] (or any [`SourceIds`][`in`.jphe.storyvox.data.source.SourceIds]
 * constant) to the right [CoverSourceFamily] for the branded fallback.
 *
 * Notion currently always means TechEmpower in practice (the Notion
 * source defaults to TechEmpower's root page; non-TechEmpower public
 * Notion pages still use TechEmpower's parsing). When/if we add other
 * Notion roots and want a different watermark we can branch on the
 * configured rootPageId at the source-mapper layer and pass the
 * family explicitly.
 *
 * Everything else maps to [CoverSourceFamily.Generic] so we don't
 * claim a specific brand on third-party content.
 */
fun coverSourceFamilyFor(sourceId: String?): CoverSourceFamily = when (sourceId) {
    "notion" -> CoverSourceFamily.TechEmpower
    else -> CoverSourceFamily.Generic
}

/**
 * Branded synthetic book cover, rendered entirely in Compose — used as
 * the second-tier fallback in [FictionCoverThumb] when no remote cover
 * URL is available. Looks like a hand-designed jacket rather than a
 * monogram tile: warm gradient ground, family watermark in the upper
 * corner at low alpha, title centered in serif, author/source in a
 * smaller sans line, brass border framing the whole tile.
 *
 * Aspect ratio is **inherited from the parent slot** — [FictionCoverThumb]
 * already enforces 2:3 on its `Modifier`, so this composable just fills
 * that space. Reusable: any future source family that wants a branded
 * synthetic cover (Outline, GitHub repo READMEs, …) can drop this in.
 *
 * Pure-composable / network-free: renders instantly on cache miss, makes
 * no HTTP calls, and survives Coil's S3-signed-URL-expiry failures by
 * being the very thing the error slot falls back to.
 *
 * Library Nocturne palette only — uses [MaterialTheme.colorScheme]
 * surfaces + the [BrassRamp] for accents, so both default and
 * high-contrast variants come out right.
 */
@Composable
fun BrandedCoverTile(
    title: String,
    author: String?,
    sourceFamily: CoverSourceFamily,
    modifier: Modifier = Modifier,
) {
    val brass = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHigh

    // Brass border + warm vertical gradient ground. The gradient runs
    // top→bottom from the higher surface-container down to the base
    // surface, so the title (centered vertically) sits on the warmer
    // band of the gradient.
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        surfaceContainer,
                        surface,
                    ),
                ),
            )
            .border(width = 1.5.dp, color = BrassRamp.Brass400.copy(alpha = 0.55f)),
    ) {
        // Family watermark — drawn at low alpha in the upper-left so it
        // reads as a quiet maker's mark, not the focal element.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val smallerDim = min(maxWidth.value, maxHeight.value)
            val watermarkSize = smallerDim * 0.28f
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, top = 10.dp),
            ) {
                val w = watermarkSize.dp.toPx()
                val center = Offset(w / 2f, w / 2f)
                when (sourceFamily) {
                    CoverSourceFamily.TechEmpower -> drawTechEmpowerSunDisk(center, w / 2f, brass)
                    CoverSourceFamily.Generic -> drawGenericMedallion(center, w / 2f, brass)
                }
            }

            // Title + author block — vertically centered in the tile.
            // Padding keeps text away from the brass border on all sides;
            // bottom pad larger so the author line isn't crowded.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val titleSizeF = (smallerDim * 0.11f).coerceIn(13f, 26f)
                val authorSizeF = (smallerDim * 0.06f).coerceIn(9f, 14f)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = titleSizeF.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = (titleSizeF * 1.2f).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!author.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = authorSizeF.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.2.sp,
                        ),
                        color = brass,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * TechEmpower sun-disk — concentric brass circles with eight radiating
 * rays. Drawn at low alpha so it reads as a quiet watermark in the
 * corner of the tile, not the focal element. Geometry approximates the
 * project's sun-disk illustration without needing a bundled PNG.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTechEmpowerSunDisk(
    center: Offset,
    radius: Float,
    brass: Color,
) {
    val alpha = 0.32f
    val ringStroke = (radius * 0.10f).coerceAtLeast(1.2f)
    val rayStroke = (radius * 0.08f).coerceAtLeast(1.0f)

    // Inner solid disc — small filled circle at ~35% radius.
    drawCircle(
        color = brass.copy(alpha = alpha * 0.85f),
        radius = radius * 0.35f,
        center = center,
    )
    // Outer ring — open circle at ~75% radius.
    drawCircle(
        color = brass.copy(alpha = alpha),
        radius = radius * 0.75f,
        center = center,
        style = Stroke(width = ringStroke),
    )
    // Eight rays — short brass lines radiating from just outside the
    // inner disc to just inside the outer ring.
    val rayInner = radius * 0.45f
    val rayOuter = radius * 0.68f
    for (i in 0 until 8) {
        val angleDeg = i * 45f
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val from = Offset(center.x + rayInner * cosA, center.y + rayInner * sinA)
        val to = Offset(center.x + rayOuter * cosA, center.y + rayOuter * sinA)
        drawLine(
            color = brass.copy(alpha = alpha),
            start = from,
            end = to,
            strokeWidth = rayStroke,
        )
    }
}

/**
 * Generic medallion — two concentric brass rings with a small inner
 * dot. Used for non-TechEmpower sources so the watermark stays
 * neutral and on-brand without claiming a specific organization.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGenericMedallion(
    center: Offset,
    radius: Float,
    brass: Color,
) {
    val alpha = 0.30f
    val ringStroke = (radius * 0.08f).coerceAtLeast(1.0f)

    drawCircle(
        color = brass.copy(alpha = alpha),
        radius = radius * 0.85f,
        center = center,
        style = Stroke(width = ringStroke),
    )
    drawCircle(
        color = brass.copy(alpha = alpha * 0.7f),
        radius = radius * 0.55f,
        center = center,
        style = Stroke(width = ringStroke * 0.8f),
    )
    drawCircle(
        color = brass.copy(alpha = alpha),
        radius = radius * 0.18f,
        center = center,
    )
}
