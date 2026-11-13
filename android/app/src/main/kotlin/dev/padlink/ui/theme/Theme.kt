// Theme.kt: PadLink Material 3 theme and the trackpad palette from the original screenshots.
package dev.padlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Trackpad surface colors. */
object PadColors {
    val Trackpad = Color(0xFF004B70)
    val Button = Color(0xFF3A7CA5)
    val ButtonPressed = Color(0xFF2A6189)
    val OnTrackpad = Color(0xFFCFE5FF)
    val PenCanvas = Color(0xFF001E30)
    val Letterbox = Color(0x33000000)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    onPrimary = Color.White,
    primaryContainer = PadColors.OnTrackpad,
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = PadColors.Button,
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFF6F8FB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BCEFF),
    onPrimary = Color(0xFF00344D),
    primaryContainer = PadColors.Trackpad,
    onPrimaryContainer = PadColors.OnTrackpad,
    secondary = PadColors.Button,
    background = Color(0xFF111418),
    surface = Color(0xFF111418),
)

/** Applies the PadLink color scheme, following the system dark mode. */
@Composable
fun PadLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
