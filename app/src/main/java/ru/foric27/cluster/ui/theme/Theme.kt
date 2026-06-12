package ru.foric27.cluster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Тёмная цветовая схема приложения на основе Material3.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    background = BackgroundDark,
    surface = BackgroundDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
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
