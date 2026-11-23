package knightlink.app.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one theme of the app: dark, calm, low-contrast surfaces with one soft accent. Every colour here has at least 4.5:1 against the surface
 * it is drawn on (checked by [KlContrast] in the tests); the status colours are never the only carrier of meaning, each comes with a word.
 */
object KlColors {
    val Background = Color(0xFF0D1116)
    val Surface = Color(0xFF151A21)
    val SurfaceRaised = Color(0xFF1C232C)
    val Outline = Color(0xFF2B343F)
    val OnSurface = Color(0xFFE7ECF1)
    val OnSurfaceMuted = Color(0xFFA3AEBB)
    val Accent = Color(0xFF7CC0FF)
    val OnAccent = Color(0xFF04121F)
    val Good = Color(0xFF6FD6A6)
    val Attention = Color(0xFFF2BB66)
    val Problem = Color(0xFFFF9A90)
}

/** Spacing on a 4 dp grid; screens use these and nothing else. */
object KlSpacing {
    val Xs = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp

    /** The side gutter of every screen. */
    val Gutter = 16.dp

    /** The smallest thing a finger is asked to hit. */
    val Touch = 48.dp
}

@Immutable
object KlShapes {
    val Card = RoundedCornerShape(20.dp)
    val Chip = RoundedCornerShape(50)
    val Button = RoundedCornerShape(16.dp)
    val Field = RoundedCornerShape(14.dp)
}

/**
 * The type scale. Eight steps, each with one job: [Typography.displayLarge] is the one big figure on a screen, `headlineMedium` a screen's
 * live sentence, `titleMedium` a card's heading, `bodyLarge` ordinary text, `bodyMedium` secondary text, `labelLarge` buttons, `labelMedium` chips and
 * captions that say where a figure came from.
 */
private val KlTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Light, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 33.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
)

private val KlColorScheme = darkColorScheme(
    primary = KlColors.Accent,
    onPrimary = KlColors.OnAccent,
    primaryContainer = KlColors.SurfaceRaised,
    onPrimaryContainer = KlColors.OnSurface,
    secondary = KlColors.Accent,
    onSecondary = KlColors.OnAccent,
    secondaryContainer = KlColors.SurfaceRaised,
    onSecondaryContainer = KlColors.OnSurface,
    background = KlColors.Background,
    onBackground = KlColors.OnSurface,
    surface = KlColors.Surface,
    onSurface = KlColors.OnSurface,
    surfaceVariant = KlColors.SurfaceRaised,
    onSurfaceVariant = KlColors.OnSurfaceMuted,
    surfaceContainerLowest = KlColors.Background,
    surfaceContainerLow = KlColors.Surface,
    surfaceContainer = KlColors.Surface,
    surfaceContainerHigh = KlColors.SurfaceRaised,
    surfaceContainerHighest = KlColors.SurfaceRaised,
    outline = KlColors.OnSurfaceMuted,
    outlineVariant = KlColors.Outline,
    error = KlColors.Problem,
    onError = KlColors.OnAccent,
)

private val KlMaterialShapes = Shapes(
    small = KlShapes.Field,
    medium = KlShapes.Button,
    large = KlShapes.Card,
)

@Composable
fun KnightLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KlColorScheme, typography = KlTypography, shapes = KlMaterialShapes, content = content)
}

/** The contrast ratio of two colours, as the WCAG defines it; the tests hold every text colour to 4.5. */
object KlContrast {
    fun ratio(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
