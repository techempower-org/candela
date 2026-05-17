package `in`.jphe.storyvox.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = BrassRamp.Brass500,
    onPrimary = SurfaceTokens.SurfaceDark,
    primaryContainer = BrassRamp.Brass800,
    onPrimaryContainer = BrassRamp.Brass200,
    inversePrimary = BrassRamp.Brass400,

    secondary = PlumRamp.Plum500,
    onSecondary = SurfaceTokens.SurfaceDark,
    secondaryContainer = PlumRamp.Plum700,
    onSecondaryContainer = PlumRamp.Plum300,

    tertiary = BrassRamp.Brass400,
    onTertiary = SurfaceTokens.SurfaceDark,
    tertiaryContainer = BrassRamp.Brass700,
    onTertiaryContainer = BrassRamp.Brass200,

    background = SurfaceTokens.SurfaceDark,
    onBackground = SurfaceTokens.OnSurfaceDark,

    surface = SurfaceTokens.SurfaceDark,
    onSurface = SurfaceTokens.OnSurfaceDark,
    surfaceVariant = SurfaceTokens.SurfaceContainerHighDark,
    onSurfaceVariant = SurfaceTokens.OnSurfaceVariantDark,
    surfaceTint = BrassRamp.Brass500,
    inverseSurface = SurfaceTokens.SurfaceLight,
    inverseOnSurface = SurfaceTokens.OnSurfaceLight,

    surfaceContainerLowest = SurfaceTokens.SurfaceDark,
    surfaceContainerLow = SurfaceTokens.SurfaceContainerLowDark,
    surfaceContainer = SurfaceTokens.SurfaceContainerDark,
    surfaceContainerHigh = SurfaceTokens.SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceTokens.SurfaceContainerHighestDark,

    outline = SurfaceTokens.OutlineDark,
    outlineVariant = SurfaceTokens.OutlineVariantDark,

    error = StatusTokens.ErrorDark,
    onError = StatusTokens.OnErrorDark,
    errorContainer = StatusTokens.ErrorContainerDark,
    onErrorContainer = StatusTokens.OnErrorContainerDark,

    scrim = SurfaceTokens.SurfaceDark,
)

private val LightColors = lightColorScheme(
    primary = BrassRamp.Brass550,
    onPrimary = SurfaceTokens.SurfaceLight,
    primaryContainer = BrassRamp.Brass200,
    onPrimaryContainer = BrassRamp.Brass900,
    inversePrimary = BrassRamp.Brass500,

    secondary = PlumRamp.Plum500,
    onSecondary = SurfaceTokens.SurfaceLight,
    secondaryContainer = PlumRamp.Plum100,
    onSecondaryContainer = PlumRamp.Plum700,

    tertiary = BrassRamp.Brass550,
    onTertiary = SurfaceTokens.SurfaceLight,
    tertiaryContainer = BrassRamp.Brass200,
    onTertiaryContainer = BrassRamp.Brass900,

    background = SurfaceTokens.SurfaceLight,
    onBackground = SurfaceTokens.OnSurfaceLight,

    surface = SurfaceTokens.SurfaceLight,
    onSurface = SurfaceTokens.OnSurfaceLight,
    surfaceVariant = SurfaceTokens.SurfaceContainerHighLight,
    onSurfaceVariant = SurfaceTokens.OnSurfaceVariantLight,
    surfaceTint = BrassRamp.Brass550,
    inverseSurface = SurfaceTokens.SurfaceDark,
    inverseOnSurface = SurfaceTokens.OnSurfaceDark,

    surfaceContainerLowest = SurfaceTokens.SurfaceLight,
    surfaceContainerLow = SurfaceTokens.SurfaceContainerLowLight,
    surfaceContainer = SurfaceTokens.SurfaceContainerLight,
    surfaceContainerHigh = SurfaceTokens.SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceTokens.SurfaceContainerHighestLight,

    outline = SurfaceTokens.OutlineLight,
    outlineVariant = SurfaceTokens.OutlineVariantLight,

    error = StatusTokens.ErrorLight,
    onError = StatusTokens.OnErrorLight,
    errorContainer = StatusTokens.ErrorContainerLight,
    onErrorContainer = StatusTokens.OnErrorContainerLight,

    // Scrim is the dim layer behind modals (ModalBottomSheet, dialogs).
    // It needs to be DARK in light mode so the modal can attenuate the
    // background; a near-cream value renders as "no perceptible dim".
    scrim = Color.Black,
)

/**
 * High-contrast dark color scheme (#486 Phase 2). Brass-on-near-black.
 *
 * Public so tests can resolve it and snapshot the palette without
 * spinning up a composition. The brass primary is sat-pumped
 * (`#FFC14A`) to keep accent affordances readable against the deeper
 * background ladder.
 */
val HighContrastDarkColors: ColorScheme = darkColorScheme(
    primary = HighContrastTokens.BrassHc500,
    onPrimary = HighContrastTokens.SurfaceHcDark,
    primaryContainer = HighContrastTokens.BrassHcContainer,
    onPrimaryContainer = HighContrastTokens.BrassHc300,
    inversePrimary = HighContrastTokens.BrassHc400,

    // Secondary uses the same brass family in HC mode — plum doesn't
    // saturate well at the AAA contrast budget on near-black, so we
    // collapse secondary onto the brass-warm ramp. Visually distinct
    // affordances should rely on shape/typography in HC, not hue.
    secondary = HighContrastTokens.BrassHc400,
    onSecondary = HighContrastTokens.SurfaceHcDark,
    secondaryContainer = HighContrastTokens.BrassHcContainer,
    onSecondaryContainer = HighContrastTokens.BrassHc300,

    tertiary = HighContrastTokens.BrassHc400,
    onTertiary = HighContrastTokens.SurfaceHcDark,
    tertiaryContainer = HighContrastTokens.BrassHcContainer,
    onTertiaryContainer = HighContrastTokens.BrassHc300,

    background = HighContrastTokens.SurfaceHcDark,
    onBackground = HighContrastTokens.OnSurfaceHcDark,

    surface = HighContrastTokens.SurfaceHcDark,
    onSurface = HighContrastTokens.OnSurfaceHcDark,
    surfaceVariant = HighContrastTokens.SurfaceContainerHighHcDark,
    onSurfaceVariant = HighContrastTokens.OnSurfaceVariantHcDark,
    surfaceTint = HighContrastTokens.BrassHc500,
    inverseSurface = HighContrastTokens.SurfaceHcLight,
    inverseOnSurface = HighContrastTokens.OnSurfaceHcLight,

    surfaceContainerLowest = HighContrastTokens.SurfaceHcDark,
    surfaceContainerLow = HighContrastTokens.SurfaceContainerLowHcDark,
    surfaceContainer = HighContrastTokens.SurfaceContainerHcDark,
    surfaceContainerHigh = HighContrastTokens.SurfaceContainerHighHcDark,
    surfaceContainerHighest = HighContrastTokens.SurfaceContainerHighestHcDark,

    outline = HighContrastTokens.OutlineHcDark,
    outlineVariant = HighContrastTokens.OutlineVariantHcDark,

    error = HighContrastTokens.ErrorHcDark,
    onError = HighContrastTokens.SurfaceHcDark,
    errorContainer = HighContrastTokens.ErrorContainerHcDark,
    onErrorContainer = HighContrastTokens.ErrorHcDark,

    scrim = Color.Black,
)

/** High-contrast light color scheme (#486 Phase 2). Inverse of [HighContrastDarkColors]. */
val HighContrastLightColors: ColorScheme = lightColorScheme(
    primary = HighContrastTokens.BrassHcLight,
    onPrimary = HighContrastTokens.SurfaceHcLight,
    primaryContainer = HighContrastTokens.BrassHcContainerLight,
    onPrimaryContainer = HighContrastTokens.OnSurfaceHcLight,
    inversePrimary = HighContrastTokens.BrassHc500,

    secondary = HighContrastTokens.BrassHcLight,
    onSecondary = HighContrastTokens.SurfaceHcLight,
    secondaryContainer = HighContrastTokens.BrassHcContainerLight,
    onSecondaryContainer = HighContrastTokens.OnSurfaceHcLight,

    tertiary = HighContrastTokens.BrassHcLight,
    onTertiary = HighContrastTokens.SurfaceHcLight,
    tertiaryContainer = HighContrastTokens.BrassHcContainerLight,
    onTertiaryContainer = HighContrastTokens.OnSurfaceHcLight,

    background = HighContrastTokens.SurfaceHcLight,
    onBackground = HighContrastTokens.OnSurfaceHcLight,

    surface = HighContrastTokens.SurfaceHcLight,
    onSurface = HighContrastTokens.OnSurfaceHcLight,
    surfaceVariant = HighContrastTokens.SurfaceContainerHighHcLight,
    onSurfaceVariant = HighContrastTokens.OnSurfaceVariantHcLight,
    surfaceTint = HighContrastTokens.BrassHcLight,
    inverseSurface = HighContrastTokens.SurfaceHcDark,
    inverseOnSurface = HighContrastTokens.OnSurfaceHcDark,

    surfaceContainerLowest = HighContrastTokens.SurfaceHcLight,
    surfaceContainerLow = HighContrastTokens.SurfaceContainerLowHcLight,
    surfaceContainer = HighContrastTokens.SurfaceContainerHcLight,
    surfaceContainerHigh = HighContrastTokens.SurfaceContainerHighHcLight,
    surfaceContainerHighest = HighContrastTokens.SurfaceContainerHighestHcLight,

    outline = HighContrastTokens.OutlineHcLight,
    outlineVariant = HighContrastTokens.OutlineVariantHcLight,

    error = HighContrastTokens.ErrorHcLight,
    onError = HighContrastTokens.SurfaceHcLight,
    errorContainer = HighContrastTokens.ErrorContainerHcLight,
    onErrorContainer = HighContrastTokens.ErrorHcLight,

    scrim = Color.Black,
)

/**
 * Scale every fontSize/lineHeight in [LibraryNocturneTypography] by
 * [scale]. The system's font-scale (Android Settings → Display → Font
 * size) already multiplies the rendered px size; this multiplies the
 * sp value we hand to Material on top of that. So users on a 1.15×
 * system font scale AND `pref_a11y_font_scale_override = 1.20` see
 * 1.38× effective text — the override stacks, it doesn't replace.
 *
 * No-op fast-path when [scale] is ~1.0 — we hand back the singleton
 * so callers that didn't enable the override see the same Typography
 * instance composition-after-composition (Material3's equality check
 * matters for stable-snapshot semantics).
 *
 * Letter spacing and font family are preserved bit-identical; only
 * fontSize and lineHeight scale.
 */
fun scaledTypography(scale: Float): Typography {
    if (kotlin.math.abs(scale - 1.0f) < 0.001f) return LibraryNocturneTypography
    val s = scale.coerceIn(0.5f, 2.5f)
    fun TextStyle.scaled(): TextStyle = copy(
        fontSize = fontSize.scaled(s),
        lineHeight = lineHeight.scaled(s),
    )
    val base = LibraryNocturneTypography
    return base.copy(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}

private fun TextUnit.scaled(s: Float): TextUnit =
    if (isUnspecified) this else (value * s).sp

/**
 * Library Nocturne — the bookish, brass-on-warm-dark theme.
 *
 * Wraps [MaterialTheme] and provides Library Nocturne spacing/motion
 * CompositionLocals.
 *
 * @param darkTheme — explicit dark/light selection. Default reads
 *  [isSystemInDarkTheme]; MainActivity overrides per the user's
 *  Settings → Reading → Theme picker.
 * @param useHighContrast — when true (#486 Phase 2), swap the
 *  Library Nocturne palette for [HighContrastDarkColors] /
 *  [HighContrastLightColors] depending on [darkTheme]. The brass-on-
 *  near-black variant clears AAA on body text and ships saturated
 *  brass accents.
 * @param reducedMotion — when true (#486 Phase 2 / #480), supplies
 *  [LocalReducedMotion] = true so motion-consuming sites snap to
 *  instant transitions. Defaults to reading
 *  `Settings.Global.ANIMATOR_DURATION_SCALE` so a theme wrapper used
 *  outside MainActivity (previews, tests) still respects the OS-level
 *  signal. MainActivity passes
 *  `prefs.a11yReducedMotion || bridge.isReduceMotionRequested` to
 *  fold the per-app pref onto the OS signal.
 * @param fontScale — extra multiplier on top of Android's system font
 *  scale (#486 Phase 2). 1.0 = no extra scale. Applied to every
 *  fontSize / lineHeight in [LibraryNocturneTypography] via
 *  [scaledTypography]. The system scale already affects the rendered
 *  px size; this layers on top.
 */
@Composable
fun LibraryNocturneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useHighContrast: Boolean = false,
    reducedMotion: Boolean? = null,
    fontScale: Float = 1.0f,
    /**
     * Issue #589 — global animation-speed master multiplier propagated
     * via [LocalAnimationSpeedScale]. Default 1.0 keeps existing
     * behavior bit-identical for callers (previews, tests, legacy
     * mounts) that don't wire the user pref. MainActivity passes
     * `prefs.animationSpeedScale`.
     */
    animationSpeedScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val colors = when {
        useHighContrast && darkTheme -> HighContrastDarkColors
        useHighContrast && !darkTheme -> HighContrastLightColors
        darkTheme -> DarkColors
        else -> LightColors
    }
    // "Remove animations" / "Reduce motion" — same signal ValueAnimator
    // checks. Read once per process; toggling this in Developer Options
    // effectively requires an app restart. MainActivity overrides this
    // via the [reducedMotion] param when wiring the per-app pref +
    // AccessibilityStateBridge.
    val context = LocalContext.current
    val systemReducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val effectiveReducedMotion = reducedMotion ?: systemReducedMotion
    val typography = remember(fontScale) { scaledTypography(fontScale) }
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotion provides Motion(),
        LocalReducedMotion provides effectiveReducedMotion,
        LocalAnimationSpeedScale provides animationSpeedScale,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = LibraryNocturneShapes,
            content = content,
        )
    }
}
