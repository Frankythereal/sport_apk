package de.endurofit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/*
 * Knöpfe im EnduroFit-Stil: kantig, Mint oder dunkles Petrol mit Mint-Unterstrich.
 * Gleiche Namen wie in Material 3 – im Paket ui ersetzen sie damit überall die runden Standardknöpfe.
 */

private val BtnShape = RoundedCornerShape(2.dp)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = BtnShape,
    colors = colors, contentPadding = contentPadding, content = content,
)

/** Zweitknopf: dunkles Petrol mit Mint-Linie unten (wie „CONTINUE“) */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(containerColor = Ef.PanelHi, contentColor = Accent),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.FilledTonalButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = 44.dp).drawBehind {
        if (enabled) drawLine(Accent, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
    },
    enabled = enabled, shape = BtnShape, colors = colors, contentPadding = contentPadding, content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled, shape = BtnShape,
    colors = colors, border = BorderStroke(1.dp, if (enabled) Ef.Line else Ef.Line.copy(alpha = 0.4f)),
    contentPadding = contentPadding, content = content,
)

// ---------- Bausteine ----------

/** Kleine Großbuchstaben-Beschriftung mit Abstand (wie „TODAY'S GOAL“) */
@Composable
fun Caps(text: String, modifier: Modifier = Modifier, color: Color = Ef.Muted) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** Große Zahl in Mono-Schrift */
@Composable
fun BigNumber(text: String, modifier: Modifier = Modifier, size: Int = 40, color: Color = Ef.Text) {
    Text(text, modifier, fontFamily = Mono, fontSize = size.sp, color = color, letterSpacing = (-1).sp)
}

/** Wert unter kleiner Überschrift (wie „STEPS 5,440“) */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, color: Color = Accent, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, horizontalAlignment = align) {
        Caps(label)
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Bold, color = color, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun HLine(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Ef.Line))

/** Fortschritt als Quadrate auf einer Linie – erledigt durchgezogen, offen gepunktet. */
@Composable
fun SquareStepper(count: Int, current: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val sq = 7.dp.toPx()
        val gap = (size.width - sq) / (count - 1).coerceAtLeast(1)
        val cy = size.height / 2
        for (i in 0 until count - 1) {
            val x0 = i * gap + sq; val x1 = (i + 1) * gap
            if (i < current) drawLine(Accent, Offset(x0, cy), Offset(x1, cy), 1.5.dp.toPx())
            else drawLine(Ef.Line, Offset(x0, cy), Offset(x1, cy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
        }
        for (i in 0 until count) {
            val x = i * gap
            if (i <= current) drawRect(Accent, Offset(x, cy - sq / 2), androidx.compose.ui.geometry.Size(sq, sq))
            else drawRect(Ef.Line, Offset(x, cy - sq / 2), androidx.compose.ui.geometry.Size(sq, sq), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

/** Auswahlzeile (wie „Lose Weight / Burn fat and get lean  ›“) */
@Composable
fun ChoiceRow(title: String, subtitle: String? = null, selected: Boolean = false, trailing: String = "›", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 1.dp).background(if (selected) Ef.PanelHi else Ef.Panel)
            .then(if (selected) Modifier.border(1.dp, Accent.copy(alpha = 0.6f), RectangleShape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) Accent else Ef.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ef.Muted)
        }
        Text(if (selected) "✓" else trailing, color = if (selected) Accent else Ef.Muted, fontSize = 18.sp)
    }
}

/**
 * Zahlenrad: die gewählte Zahl groß, Nachbarn verblasst – wischen zum Ändern.
 * [values] sind die möglichen Werte, [format] macht daraus den Text.
 */
@Composable
fun WheelPicker(
    values: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    unit: String = "", format: (Int) -> String = { it.toString() }, itemHeight: Int = 76,
) {
    val start = values.indexOf(selected).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(state)
    val hPx = with(androidx.compose.ui.platform.LocalDensity.current) { itemHeight.dp.toPx() }
    val center by remember { derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > hPx / 2) 1 else 0 } }
    LaunchedEffect(center) { values.getOrNull(center)?.let { if (it != selected) onSelect(it) } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyColumn(
            state = state, flingBehavior = fling,
            modifier = Modifier.weight(1f).height((itemHeight * 3).dp),
            contentPadding = PaddingValues(vertical = itemHeight.dp),
        ) {
            items(values.size) { i ->
                val dist = abs(i - state.firstVisibleItemIndex - state.firstVisibleItemScrollOffset / hPx)
                val a = (1f - dist * 0.75f).coerceIn(0.12f, 1f)
                Box(Modifier.fillMaxWidth().height(itemHeight.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        format(values[i]), fontFamily = Mono, fontSize = 64.sp, color = Ef.Text,
                        modifier = Modifier.graphicsLayer { alpha = a; val s = 0.8f + 0.2f * a; scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) },
                    )
                }
            }
        }
        if (unit.isNotBlank()) Caps(unit, color = Accent)
    }
}

/** Titel mit hervorgehobenem Wort: „What's Your *Current* Weight?“ */
@Composable
fun AccentTitle(before: String, accent: String, after: String, modifier: Modifier = Modifier) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(before)
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Accent)); append(accent); pop()
            append(after)
        },
        modifier, style = MaterialTheme.typography.headlineLarge, color = Ef.Text,
    )
}

/** Wochenbalken im Stil des Designs: Ist (gefüllt) gegen Ziel (schraffiert, gestrichelte Linie). */
@Composable
fun GoalBars(values: List<Float>, goal: Float, labels: List<String>, highlight: Int, modifier: Modifier = Modifier) {
    val maxV = maxOf(values.maxOrNull() ?: 0f, goal) * 1.18f
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val n = values.size
            val slot = size.width / n
            val bw = slot * 0.86f
            val h = size.height
            fun y(v: Float) = h - v / maxV * h
            val gy = y(goal)
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - bw) / 2
                // Ziel schraffiert
                clipRectSafe(x, gy, x + bw, h) {
                    var s = -h
                    while (s < bw + h) {
                        drawLine(Ef.Line, Offset(x + s, h), Offset(x + s + h, h - h), 1.dp.toPx())
                        s += 6.dp.toPx()
                    }
                }
                if (v > 0) {
                    val c = if (i == highlight) Ef.TealHi else Ef.Teal
                    drawRect(c.copy(alpha = 0.85f), Offset(x, y(v)), androidx.compose.ui.geometry.Size(bw, h - y(v)))
                    drawLine(Accent.copy(alpha = if (i == highlight) 1f else 0.5f), Offset(x, y(v)), Offset(x + bw, y(v)), 1.5.dp.toPx())
                }
            }
            drawLine(Ef.Text.copy(alpha = 0.55f), Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Text(l.uppercase(), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                    color = if (i == highlight) Accent else Ef.Muted)
            }
        }
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRectSafe(l: Float, t: Float, r: Float, b: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    if (b <= t || r <= l) return
    drawContext.canvas.save()
    drawContext.canvas.clipRect(l, t, r, b)
    block()
    drawContext.canvas.restore()
}
