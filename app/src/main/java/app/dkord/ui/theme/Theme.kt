package app.dkord.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.dkord.data.settings.ThemeMode

/** YouTube Music look: near-black surfaces, white text, red accent. */
private val Red = Color(0xFFFF2D2D)
private val YtDark = darkColorScheme(
    primary = Red, onPrimary = Color.White,
    secondary = Color(0xFFAAAAAA), onSecondary = Color.Black,
    background = Color(0xFF0B0B0B), onBackground = Color.White,
    surface = Color(0xFF0B0B0B), onSurface = Color.White,
    surfaceVariant = Color(0xFF272727), onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceContainer = Color(0xFF181818), surfaceContainerHigh = Color(0xFF212121),
    surfaceContainerHighest = Color(0xFF2B2B2B), surfaceContainerLow = Color(0xFF111111),
    outline = Color(0xFF3F3F3F), outlineVariant = Color(0xFF2B2B2B),
    error = Color(0xFFFF6B6B),
)
private val YtLight = lightColorScheme(
    primary = Color(0xFFD50000), onPrimary = Color.White,
    background = Color.White, surface = Color.White,
    surfaceVariant = Color(0xFFEFEFEF), onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceContainer = Color(0xFFF5F5F5), surfaceContainerHigh = Color(0xFFEDEDED),
)

fun isDark(mode: ThemeMode, system: Boolean) = when (mode) {
    ThemeMode.SYSTEM -> system
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

@Composable
fun DkordTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isDark(mode, isSystemInDarkTheme())
    val ctx = LocalContext.current
    var scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> YtDark
        else -> YtLight
    }
    if (mode == ThemeMode.AMOLED) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black, surfaceContainerLow = Color.Black,
            surfaceContainer = Color(0xFF0A0A0A), surfaceContainerHigh = Color(0xFF141414),
        )
    }
    MaterialTheme(colorScheme = scheme) {
        // Sets the default icon/text colour for everything, including screens that draw their own background (full player).
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, content = content)
    }
}
