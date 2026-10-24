package app.mawsoaa.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.mawsoaa.data.Prefs

val Emerald = Color(0xFF0B6B4F)
val Gold = Color(0xFFC9A24B)
val Ivory = Color(0xFFFBF7EA)

private val LightColors = lightColorScheme(
    primary = Emerald, onPrimary = Color.White, primaryContainer = Color(0xFFCDEBDD), onPrimaryContainer = Color(0xFF00382A),
    secondary = Gold, onSecondary = Color(0xFF2B2000), background = Ivory, onBackground = Color(0xFF1B2B25),
    surface = Color(0xFFFFFDF6), onSurface = Color(0xFF1B2B25), surfaceVariant = Color(0xFFEFE8D2), onSurfaceVariant = Color(0xFF47473B)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD3A8), onPrimary = Color(0xFF00382A), primaryContainer = Color(0xFF0F4D3A), onPrimaryContainer = Color(0xFFCDEBDD),
    secondary = Color(0xFFE6C36E), onSecondary = Color(0xFF2B2000), background = Color(0xFF0E1714), onBackground = Color(0xFFE4EDE8),
    surface = Color(0xFF14211C), onSurface = Color(0xFFE4EDE8), surfaceVariant = Color(0xFF22332C), onSurfaceVariant = Color(0xFFC3CEC8)
)

@Composable
fun MawsoaaTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.nightMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

/** Amiri if the font files were bundled (scripts/download_sources.py), otherwise a system serif. */
@Composable
fun quranFont(): FontFamily {
    val ctx = LocalContext.current
    return remember {
        val have = ctx.assets.list("fonts")?.toSet().orEmpty()
        if ("Amiri-Regular.ttf" in have) {
            val fonts = mutableListOf(Font("fonts/Amiri-Regular.ttf", ctx.assets, FontWeight.Normal))
            if ("Amiri-Bold.ttf" in have) fonts += Font("fonts/Amiri-Bold.ttf", ctx.assets, FontWeight.Bold)
            FontFamily(fonts)
        } else FontFamily.Serif
    }
}
