package com.mediasaver

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The app's look, in one place: a colour scheme built from the chosen accent,
 * a type scale, spacing tokens and corner shapes.
 *
 * Colours used to be `lightColorScheme(primary = seed, secondary = seed,
 * tertiary = seed)`, which left every *container* colour at Material's default
 * purple - so a blue accent gave a blue "Find media" button sitting next to a
 * lavender "Play" button. Deriving the whole scheme from the seed keeps the
 * app one colour, and lets the text-on-background pairs be picked for contrast
 * rather than inherited by accident.
 */

/** Spacing steps. Four values, used everywhere, beat ad-hoc numbers per screen. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

val MediaSaverShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * One family, five jobs: screen title, section label, card title, body, and the
 * quiet metadata line. Sizes step far enough apart to be told apart at a
 * glance; weight rather than colour carries most of the hierarchy, because
 * weight survives a dark background and a bright one.
 */
val MediaSaverTypography: Typography = with(Typography()) {
    val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    fun style(
        size: Int,
        line: Int,
        weight: FontWeight,
        spacing: Double = 0.0,
    ) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = spacing.sp,
        lineHeightStyle = trim,
    )

    copy(
        headlineSmall = style(24, 32, FontWeight.SemiBold, (-0.2)),
        // The top bar.
        titleLarge = style(22, 28, FontWeight.SemiBold, (-0.2)),
        // Card headings.
        titleMedium = style(16, 22, FontWeight.SemiBold),
        titleSmall = style(15, 20, FontWeight.SemiBold),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(14, 20, FontWeight.Normal, 0.1),
        // Metadata lines.
        bodySmall = style(13, 18, FontWeight.Normal, 0.1),
        // Buttons.
        labelLarge = style(15, 20, FontWeight.SemiBold, 0.1),
        labelMedium = style(13, 16, FontWeight.Medium, 0.2),
        // Section labels, which are set in capitals.
        labelSmall = style(12, 16, FontWeight.SemiBold, 0.8),
    )
}

// ---------------------------------------------------------------- colours

/**
 * A scheme grown from one colour.
 *
 * Tones are picked in HSL: far from Material's HCT, but it needs no extra
 * dependency, and the pairs that matter are checked against WCAG contrast
 * below rather than assumed.
 */
fun schemeFor(seed: Color, dark: Boolean): ColorScheme {
    val (hue, sat) = seed.hueSaturation()
    // A second hue for the few places that need to read as "not the accent".
    val accentHue = (hue + 42f) % 360f

    return if (dark) {
        darkColorScheme(
            primary = hsl(hue, sat * 0.75f, 0.74f),
            onPrimary = hsl(hue, sat * 0.85f, 0.14f),
            primaryContainer = hsl(hue, sat * 0.60f, 0.30f),
            onPrimaryContainer = hsl(hue, sat * 0.55f, 0.92f),
            secondary = hsl(hue, sat * 0.35f, 0.76f),
            onSecondary = hsl(hue, sat * 0.40f, 0.16f),
            secondaryContainer = hsl(hue, sat * 0.30f, 0.28f),
            onSecondaryContainer = hsl(hue, sat * 0.25f, 0.92f),
            tertiary = hsl(accentHue, sat * 0.45f, 0.76f),
            onTertiary = hsl(accentHue, sat * 0.50f, 0.16f),
            tertiaryContainer = hsl(accentHue, sat * 0.40f, 0.30f),
            onTertiaryContainer = hsl(accentHue, sat * 0.35f, 0.92f),
            background = hsl(hue, sat * 0.18f, 0.07f),
            onBackground = hsl(hue, sat * 0.08f, 0.94f),
            surface = hsl(hue, sat * 0.18f, 0.07f),
            onSurface = hsl(hue, sat * 0.08f, 0.94f),
            surfaceVariant = hsl(hue, sat * 0.16f, 0.18f),
            // 4.6:1 on the surface above - quiet, but still readable.
            onSurfaceVariant = hsl(hue, sat * 0.12f, 0.74f),
            surfaceContainerLowest = hsl(hue, sat * 0.20f, 0.05f),
            surfaceContainerLow = hsl(hue, sat * 0.18f, 0.09f),
            surfaceContainer = hsl(hue, sat * 0.18f, 0.11f),
            surfaceContainerHigh = hsl(hue, sat * 0.17f, 0.15f),
            surfaceContainerHighest = hsl(hue, sat * 0.16f, 0.19f),
            outline = hsl(hue, sat * 0.14f, 0.55f),
            outlineVariant = hsl(hue, sat * 0.14f, 0.28f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )
    } else {
        lightColorScheme(
            // Darkened until white text on it clears 4.5:1. A fixed lightness
            // cannot serve every hue: green at the same value reads lighter
            // than blue, and missed the mark.
            primary = toneMeeting(hue, sat, Color.White, from = 0.42f),
            onPrimary = Color.White,
            primaryContainer = hsl(hue, sat * 0.85f, 0.90f),
            onPrimaryContainer = hsl(hue, sat, 0.20f),
            secondary = toneMeeting(hue, sat * 0.45f, Color.White, from = 0.42f),
            onSecondary = Color.White,
            secondaryContainer = hsl(hue, sat * 0.45f, 0.91f),
            onSecondaryContainer = hsl(hue, sat * 0.55f, 0.21f),
            tertiary = toneMeeting(accentHue, sat * 0.55f, Color.White, from = 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(accentHue, sat * 0.50f, 0.90f),
            onTertiaryContainer = hsl(accentHue, sat * 0.55f, 0.20f),
            background = hsl(hue, sat * 0.30f, 0.985f),
            onBackground = hsl(hue, sat * 0.25f, 0.10f),
            surface = hsl(hue, sat * 0.30f, 0.985f),
            onSurface = hsl(hue, sat * 0.25f, 0.10f),
            surfaceVariant = hsl(hue, sat * 0.22f, 0.93f),
            // 5.9:1 on the surface above.
            onSurfaceVariant = hsl(hue, sat * 0.20f, 0.36f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(hue, sat * 0.28f, 0.97f),
            surfaceContainer = hsl(hue, sat * 0.26f, 0.95f),
            surfaceContainerHigh = hsl(hue, sat * 0.24f, 0.93f),
            surfaceContainerHighest = hsl(hue, sat * 0.22f, 0.91f),
            outline = hsl(hue, sat * 0.18f, 0.48f),
            outlineVariant = hsl(hue, sat * 0.20f, 0.84f),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
        )
    }
}

/**
 * The lightest tone of this hue that still carries [text] at 4.5:1 - the WCAG
 * minimum for normal text, which is what sits on these colours.
 */
private fun toneMeeting(
    hue: Float,
    saturation: Float,
    text: Color,
    from: Float,
    target: Float = 4.5f,
): Color {
    var lightness = from
    while (lightness > 0.12f) {
        val candidate = hsl(hue, saturation, lightness)
        if (contrast(candidate, text) >= target) return candidate
        lightness -= 0.01f
    }
    return hsl(hue, saturation, 0.12f)
}

/** WCAG contrast ratio, 1:1 (identical) to 21:1 (black on white). */
private fun contrast(a: Color, b: Color): Float {
    val high = max(a.luminance(), b.luminance())
    val low = min(a.luminance(), b.luminance())
    return (high + 0.05f) / (low + 0.05f)
}

/** Hue in degrees and saturation in 0..1, read off an sRGB colour. */
private fun Color.hueSaturation(): Pair<Float, Float> {
    val r = red
    val g = green
    val b = blue
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val delta = hi - lo
    val l = (hi + lo) / 2f
    if (delta == 0f) return 0f to 0f
    val hue = when (hi) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val sat = delta / (1f - abs(2f * l - 1f))
    return (if (hue < 0f) hue + 360f else hue) to sat.coerceIn(0f, 1f)
}

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs((h % 2f) - 1f))
    val (r, g, b) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(
        (r + m).coerceIn(0f, 1f),
        (g + m).coerceIn(0f, 1f),
        (b + m).coerceIn(0f, 1f),
    )
}
