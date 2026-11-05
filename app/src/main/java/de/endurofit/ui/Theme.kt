package de.endurofit.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.R

// ---------- Farben: Schwarz, dunkles Petrol, Mint ----------

/** Mint – die Akzentfarbe für Zahlen, Auswahl und Hauptknöpfe */
val Accent = Color(0xFF59EBD8)
val Good = Color(0xFF59EBD8)
val Bad = Color(0xFFFF6B6B)

object Ef {
    val Ink = Color(0xFF000000)          // Hintergrund
    val Panel = Color(0xFF0A1715)        // Kacheln
    val PanelHi = Color(0xFF0F2A26)      // Listenzeilen, Auswahl, Zweitknopf
    val Line = Color(0xFF1D3833)         // Trennlinien, Rahmen
    val Teal = Color(0xFF1E5B51)         // Balken
    val TealHi = Color(0xFF2E8A7B)
    val Text = Color(0xFFF2F5F4)
    val Muted = Color(0xFF7D918D)
    val Warn = Color(0xFFFFB45C)
    val Radius = 4.dp
}

// ---------- Schrift: Space Grotesk für Text, Space Mono für Zahlen ----------

@OptIn(ExperimentalTextApi::class)
private fun grotesk(w: Int) = Font(R.font.space_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Grotesk = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))
val Mono = FontFamily(Font(R.font.space_mono, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))

private val base = Typography()
private fun TextStyle.g(spacing: Float = 0f) = copy(fontFamily = Grotesk, letterSpacing = spacing.sp)

private val type = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Mono),
    displayMedium = base.displayMedium.copy(fontFamily = Mono),
    displaySmall = base.displaySmall.copy(fontFamily = Mono),
    headlineLarge = base.headlineLarge.g(-0.5f).copy(fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.g(-0.3f).copy(fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.g(-0.2f).copy(fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.g(),
    titleMedium = base.titleMedium.g(),
    titleSmall = base.titleSmall.g(),
    bodyLarge = base.bodyLarge.g(),
    bodyMedium = base.bodyMedium.g(),
    bodySmall = base.bodySmall.g(),
    labelLarge = base.labelLarge.g(1.2f).copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.g(1f),
    labelSmall = base.labelSmall.g(1.1f),
)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    primaryContainer = Ef.PanelHi,
    onPrimaryContainer = Accent,
    secondary = Accent,
    onSecondary = Color.Black,
    secondaryContainer = Ef.PanelHi,
    onSecondaryContainer = Accent,
    tertiary = Ef.TealHi,
    background = Ef.Ink,
    onBackground = Ef.Text,
    surface = Ef.Ink,
    onSurface = Ef.Text,
    surfaceVariant = Ef.Panel,
    onSurfaceVariant = Ef.Muted,
    surfaceContainer = Ef.Panel,
    surfaceContainerLow = Ef.Panel,
    surfaceContainerHigh = Ef.PanelHi,
    surfaceContainerHighest = Ef.PanelHi,
    surfaceContainerLowest = Ef.Ink,
    outline = Ef.Line,
    outlineVariant = Ef.Line,
    error = Bad,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(Ef.Radius),
    medium = RoundedCornerShape(Ef.Radius),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun EnduroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}

fun zoneColor(zone: Int): Color = when (zone) {
    1 -> Color(0xFF5C7A75)
    2 -> Color(0xFF3FA7D6)
    3 -> Color(0xFF59EBD8)
    4 -> Color(0xFFFFB45C)
    5 -> Color(0xFFFF6B6B)
    else -> Color(0xFF3A4A47)
}
