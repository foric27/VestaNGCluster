package ru.foric27.cluster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Тёмная цветовая схема приложения на основе Material3.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = Primary.copy(alpha = 0.15f),
    onPrimaryContainer = Primary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = Secondary.copy(alpha = 0.15f),
    onSecondaryContainer = Secondary,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = BackgroundDark,
    surfaceVariant = SurfaceDark2,
    onSurfaceVariant = TextSecondary,
    onSurface = TextPrimary,
    error = Warning,
    onError = Color.Black,
    outline = SurfaceLine,
)

/**
 * Корневая тема приложения Cluster.
 *
 * Применяет тёмную цветовую схему ко всем Compose-экранам.
 *
 * @param content содержимое экрана
 */
@Composable
fun ClusterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
