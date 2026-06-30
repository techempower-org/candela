package `in`.jphe.storyvox.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import `in`.jphe.storyvox.ui.R as CoreUiR
import `in`.jphe.storyvox.ui.theme.StatusTokens

/**
 * Wear OS port of the phone/tablet Library Nocturne palette.
 *
 * Wear Compose Material is still on M2 (`Colors`, not `ColorScheme`), and OLED
 * watches prefer always-dark surfaces — there is no system "light theme" toggle
 * here the way phones have one. So this theme is dark-only, brass-on-warm-dark.
 *
 * Light-mode parchment tokens stay in `:core-ui` for the phone/tablet code
 * paths; if a future Wear watch ships with a daylight-readable LCD we can branch
 * here, but for the OLED form factor that's the dominant case in 2026 the
 * always-dark palette is the right default.
 *
 * Colors mirror [in.jphe.storyvox.ui.theme.BrassRamp] / [SurfaceTokens] one-to-
 * one so the brass identity carries across surfaces.
 */
internal val BrassPrimary = Color(0xFFB48C5A)        // BrassRamp.Brass500
internal val BrassPrimaryVariant = Color(0xFF7A5A30) // BrassRamp.Brass550
internal val BrassTint = Color(0xFFC9A774)           // BrassRamp.Brass400
internal val BrassMuted = Color(0xFF3A2A14)          // BrassRamp.Brass700
internal val WarmDarkSurface = Color(0xFF0E0C12)     // SurfaceTokens.SurfaceDark
internal val WarmDarkContainer = Color(0xFF15131A)   // SurfaceTokens.SurfaceContainerDark
internal val WarmDarkContainerHigh = Color(0xFF1B1822) // SurfaceTokens.SurfaceContainerHighDark
internal val ParchmentOn = Color(0xFFE8DFD1)         // SurfaceTokens.OnSurfaceDark
internal val ParchmentOnMuted = Color(0xFFB8AE9F)    // SurfaceTokens.OnSurfaceVariantDark
internal val BrassRingTrack = Color(0xFF3A3530)      // SurfaceTokens.OutlineVariantDark
// Semantic status color: referenced from the shared token rather than
// duplicated by value (the brass/surface tokens above mirror :core-ui by
// literal; the one error role stays single-source via StatusTokens.ErrorDark).
internal val ErrorTerracotta = StatusTokens.ErrorDark

private val WearNocturneColors = Colors(
    primary = BrassPrimary,
    primaryVariant = BrassPrimaryVariant,
    secondary = BrassTint,
    secondaryVariant = BrassMuted,
    background = WarmDarkSurface,
    surface = WarmDarkContainer,
    error = ErrorTerracotta,
    onPrimary = WarmDarkSurface,
    onSecondary = WarmDarkSurface,
    onBackground = ParchmentOn,
    onSurface = ParchmentOn,
    onSurfaceVariant = ParchmentOnMuted,
    onError = WarmDarkSurface,
)

// Reuse the same Google Fonts provider config as :core-ui so the watch pulls
// EB Garamond + Inter from Google Play Services (zero-bytes on-disk on every
// Wear OS device that has GMS, which is "all of them").
private val GoogleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = CoreUiR.array.com_google_android_gms_fonts_certs,
)

private val EbGaramond = GoogleFont("EB Garamond")
private val Inter = GoogleFont("Inter")

private val BookFamily = FontFamily(
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.Normal, style = FontStyle.Italic),
)

private val UiFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = Inter, fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = Inter, fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
)

/**
 * Wear typography — tighter sizes than the phone, EB Garamond reserved for the
 * book/chapter titles, Inter for everything else (transport labels, metadata).
 * Wear M2's [Typography] has a different slot set than M3, so this isn't a
 * direct copy of [in.jphe.storyvox.ui.theme.LibraryNocturneTypography].
 */
private val WearNocturneTypography = Typography(
    display1 = TextStyle(fontFamily = BookFamily, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp),
    display2 = TextStyle(fontFamily = BookFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    display3 = TextStyle(fontFamily = BookFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    title1 = TextStyle(fontFamily = BookFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    title2 = TextStyle(fontFamily = BookFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
    title3 = TextStyle(fontFamily = BookFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    body1 = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    body2 = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.25.sp),
    button = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    caption1 = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    caption2 = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.4.sp),
    caption3 = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.4.sp),
)

/**
 * Library Nocturne theme wrapper for the Wear app.
 *
 * Drop-in replacement for [androidx.wear.compose.material.MaterialTheme] —
 * call this once at [in.jphe.storyvox.wear.WearAppRoot] and every Wear
 * Material composable below it inherits brass + parchment defaults.
 */
@Composable
fun WearLibraryNocturneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WearNocturneColors,
        typography = WearNocturneTypography,
        content = content,
    )
}
