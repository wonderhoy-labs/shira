package com.wonderhoylabs.shira.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Taken from the logo: her blue hair, amber eyes and mint jacket, on the warm peach behind her.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F56A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001551),
    secondary = Color(0xFF7E5700),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEA6),
    onSecondaryContainer = Color(0xFF281800),
    tertiary = Color(0xFF2A6A63),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB2EFE6),
    onTertiaryContainer = Color(0xFF00201D),
    background = Color(0xFFFFFBF8),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFEFE0D8),
    onSurfaceVariant = Color(0xFF4F4540),
    surfaceContainerLow = Color(0xFFFBF2EC),
    surfaceContainer = Color(0xFFF6ECE6),
    surfaceContainerHigh = Color(0xFFF0E6E0),
    outline = Color(0xFF81736D),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    onPrimary = Color(0xFF0B2674),
    primaryContainer = Color(0xFF263E8D),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFF8BD48),
    onSecondary = Color(0xFF422C00),
    secondaryContainer = Color(0xFF5F4100),
    onSecondaryContainer = Color(0xFFFFDEA6),
    tertiary = Color(0xFF94D3CA),
    onTertiary = Color(0xFF003732),
    tertiaryContainer = Color(0xFF0B504A),
    onTertiaryContainer = Color(0xFFB2EFE6),
    background = Color(0xFF171311),
    onBackground = Color(0xFFECE0DA),
    surface = Color(0xFF171311),
    onSurface = Color(0xFFECE0DA),
    surfaceVariant = Color(0xFF4F4540),
    onSurfaceVariant = Color(0xFFD3C3BB),
    surfaceContainerLow = Color(0xFF201B18),
    surfaceContainer = Color(0xFF241F1C),
    surfaceContainerHigh = Color(0xFF2F2926),
    outline = Color(0xFF9C8E87),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun ShiraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
