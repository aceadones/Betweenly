package app.loviedowpie.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Light and dark colors for the whole app. Follows the system setting.
 * Flat on purpose: no dynamic color, no gradients, no tinted elevation.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF9E3D52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF3F0A16),
    secondary = Color(0xFF7A555C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DDE1),
    onSecondaryContainer = Color(0xFF2C1519),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF22191A),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF22191A),
    surfaceVariant = Color(0xFFF3E6E8),
    onSurfaceVariant = Color(0xFF524345),
    outline = Color(0xFF847376),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color(0xFFFFF8F7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB2BE),
    onPrimary = Color(0xFF5E1226),
    primaryContainer = Color(0xFF7D2940),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFE4BDC4),
    onSecondary = Color(0xFF44282E),
    secondaryContainer = Color(0xFF5C3E45),
    onSecondaryContainer = Color(0xFFFFD9DF),
    background = Color(0xFF1A1113),
    onBackground = Color(0xFFF0DEE1),
    surface = Color(0xFF1A1113),
    onSurface = Color(0xFFF0DEE1),
    surfaceVariant = Color(0xFF2C2225),
    onSurfaceVariant = Color(0xFFD5C2C5),
    outline = Color(0xFF9E8C90),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = Color(0xFF1A1113),
)

@Composable
fun LoviedowpieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
