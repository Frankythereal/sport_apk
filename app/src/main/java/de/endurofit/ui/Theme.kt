package de.endurofit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF6D00)
val Good = Color(0xFF66BB6A)
val Bad = Color(0xFFEF5350)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Color(0xFF4FC3F7),
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceContainer = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF3A2410),
    onSecondaryContainer = Color(0xFFFFD0A8),
)

@Composable
fun EnduroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

fun zoneColor(zone: Int): Color = when (zone) {
    1 -> Color(0xFF90A4AE)
    2 -> Color(0xFF42A5F5)
    3 -> Color(0xFF66BB6A)
    4 -> Color(0xFFFFA726)
    5 -> Color(0xFFEF5350)
    else -> Color(0xFF616161)
}
