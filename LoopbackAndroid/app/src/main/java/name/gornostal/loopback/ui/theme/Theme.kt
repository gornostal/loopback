package name.gornostal.loopback.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Brand colours lifted from the logo, desaturated and dimmed for comfortable night-time use: a near-black navy→indigo
 * backdrop, a cyan robot, a mint check mark, a lavender/pink human, and a loop that runs
 * cyan → violet → pink.
 */
object Brand {
    val Navy = Color(0xFF040820)
    val Indigo = Color(0xFF0B1440)
    val Cyan = Color(0xFF53B6CE)
    val Violet = Color(0xFF7C68D2)
    val Pink = Color(0xFFC89DD4)
    val Mint = Color(0xFF78C69C)

    /** The loop from the logo; used for primary actions and small accents. */
    val Loop = Brush.horizontalGradient(listOf(Cyan, Violet, Pink))
    val LoopVertical = Brush.verticalGradient(listOf(Cyan, Violet, Pink))
}

// The app is dark-only; the palette is the logo's.
private val Colors = darkColorScheme(
    primary = Brand.Cyan,
    onPrimary = Color(0xFF00242E),
    primaryContainer = Color(0xFF0B4A66),
    onPrimaryContainer = Color(0xFFC2F3FF),
    inversePrimary = Color(0xFF006781),

    secondary = Color(0xFFC2B4F0),
    onSecondary = Color(0xFF2A1366),
    secondaryContainer = Color(0xFF3F2A8C),
    onSecondaryContainer = Color(0xFFEADFFF),

    tertiary = Brand.Mint,
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF0C5A3B),
    onTertiaryContainer = Color(0xFFC6FFDC),

    error = Color(0xFFF093AE),
    onError = Color(0xFF4A0024),
    errorContainer = Color(0xFF6E1A3E),
    onErrorContainer = Color(0xFFFFD9E4),

    background = Brand.Navy,
    onBackground = Color(0xFFDADFF7),
    surface = Brand.Navy,
    onSurface = Color(0xFFDADFF7),
    surfaceVariant = Color(0xFF141D4A),
    onSurfaceVariant = Color(0xFFA5AED8),
    surfaceDim = Brand.Navy,
    surfaceBright = Color(0xFF1C295E),
    surfaceContainerLowest = Color(0xFF020516),
    surfaceContainerLow = Color(0xFF070D2A),
    surfaceContainer = Color(0xFF0B1335),
    surfaceContainerHigh = Color(0xFF101A42),
    surfaceContainerHighest = Color(0xFF16224F),
    inverseSurface = Color(0xFFDADFF7),
    inverseOnSurface = Color(0xFF070D2A),

    outline = Color(0xFF576194),
    outlineVariant = Color(0xFF1D2858),
    scrim = Color.Black,
)

private val LoopbackShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun LoopbackTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = LoopbackShapes, content = content)
}

/**
 * Full-screen backdrop echoing the logo: navy fading up into indigo, with a violet glow in the
 * top-right and a faint cyan glow bottom-left. Screens sit on top with transparent containers.
 */
fun Modifier.loopbackBackdrop(): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(0f to Brand.Indigo, 0.55f to Brand.Navy, 1f to Brand.Navy))
    val violetCenter = Offset(size.width * 1.05f, -size.height * 0.05f)
    val violetRadius = size.width * 0.85f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Brand.Violet.copy(alpha = 0.15f), Color.Transparent),
            center = violetCenter,
            radius = violetRadius,
        ),
        radius = violetRadius,
        center = violetCenter,
    )
    val cyanCenter = Offset(-size.width * 0.15f, size.height * 1.05f)
    val cyanRadius = size.width * 0.7f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Brand.Cyan.copy(alpha = 0.05f), Color.Transparent),
            center = cyanCenter,
            radius = cyanRadius,
        ),
        radius = cyanRadius,
        center = cyanCenter,
    )
}

/**
 * Root container every screen renders inside: the gradient backdrop with the light on-background
 * content colour, so text without an explicit colour reads correctly on the dark backdrop.
 */
@Composable
fun LoopbackSurface(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().loopbackBackdrop()) { content() }
    }
}
