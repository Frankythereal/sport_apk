package de.endurofit.ui

import androidx.compose.ui.text.drawText

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.endurofit.data.Calc
import de.endurofit.data.HrSample
import kotlin.math.abs
import kotlin.math.max

/**
 * Pulskurve mit farbigen Zonen, roten Extremabschnitten und gestrichelten Markierungen.
 * Antippen zeigt Uhrzeit und Puls an dieser Stelle.
 */
@Composable
fun HrChart(
    samples: List<HrSample>,
    maxHr: Int,
    modifier: Modifier = Modifier,
    start: Long? = null,
    markers: List<Long> = emptyList(),
    peaks: List<Calc.Peak> = emptyList(),
    height: Dp = 180.dp,
    interactive: Boolean = true,
) {
    if (samples.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Hint("Noch keine Pulsdaten")
        }
        return
    }
    val t0 = start ?: samples.first().t
    val t1 = max(samples.last().t, t0 + 1)
    val span = (t1 - t0).toFloat()
    val lo = (samples.minOf { it.bpm } - 10).coerceAtLeast(30).toFloat()
    val hi = (max(maxHr, samples.maxOf { it.bpm }) + 5).toFloat()

    // Auf ca. 400 Punkte verdichten (Maximum je Abschnitt, damit Spitzen sichtbar bleiben)
    val points = remember(samples) {
        val buckets = 400
        if (samples.size <= buckets) samples
        else {
            val size = samples.size.toDouble() / buckets
            (0 until buckets).mapNotNull { b ->
                val from = (b * size).toInt()
                val to = ((b + 1) * size).toInt().coerceAtMost(samples.size)
                if (from >= to) null else samples.subList(from, to).maxByOrNull { it.bpm }
            }
        }
    }

    var sel by remember(samples) { mutableStateOf<HrSample?>(null) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val bubbleStyle = MaterialTheme.typography.labelSmall.copy(color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFamily = Mono)

    Column(modifier) {
        if (interactive) {
            val s = sel
            Hint(
                if (s != null) "${Calc.fmtTime(s.t)} · ${Calc.fmtDuration(s.t - t0)} nach Start · ${s.bpm} bpm (Z${Calc.zoneOf(s.bpm, maxHr)})"
                else "Mit dem Finger über das Diagramm fahren für Uhrzeit und Puls",
                Modifier.padding(bottom = 4.dp)
            )
        }
        val tapMod = if (interactive) Modifier.pointerInput(samples) {
            detectTapGestures { off ->
                val t = t0 + (off.x.coerceIn(0f, size.width.toFloat()) / size.width * span).toLong()
                sel = nearest(samples, t)
            }
        }.pointerInput(samples) {
            // Mit dem Finger fahren – der Schieber bleibt nach dem Loslassen stehen
            androidx.compose.foundation.gestures.detectHorizontalDragGestures(
                onDragStart = { off -> sel = nearest(samples, t0 + (off.x.coerceIn(0f, size.width.toFloat()) / size.width * span).toLong()) },
            ) { change, _ ->
                change.consume()
                sel = nearest(samples, t0 + (change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * span).toLong())
            }
        } else Modifier

        Canvas(Modifier.fillMaxWidth().height(height).then(tapMod)) {
            val w = size.width
            val h = size.height
            fun x(t: Long) = (t - t0) / span * w
            fun y(b: Float) = (h - (b - lo) / (hi - lo) * h).coerceIn(0f, h)

            // Zonen 3–5 als Hintergrund
            val bands = listOf(3 to (0.7f to 0.8f), 4 to (0.8f to 0.9f), 5 to (0.9f to 2f))
            for ((z, range) in bands) {
                val top = y(maxHr * range.second)
                val bottom = y(maxHr * range.first)
                if (bottom > top) {
                    drawRect(zoneColor(z).copy(alpha = 0.10f), Offset(0f, top), Size(w, bottom - top))
                }
            }
            // Extremabschnitte
            for (p in peaks) {
                val x0 = x(p.start)
                drawRect(Color(0x40EF5350), Offset(x0, 0f), Size(max(3f, x(p.end) - x0), h))
            }
            // Pulslinie
            val path = Path()
            points.forEachIndexed { i, s ->
                val px = x(s.t)
                val py = y(s.bpm.toFloat())
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, Accent, style = Stroke(width = 2.dp.toPx()))
            // Markierungen
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            for (m in markers) {
                val mx = x(m)
                drawLine(Color.White, Offset(mx, 0f), Offset(mx, h), strokeWidth = 2f, pathEffect = dash)
            }
            sel?.let {
                val sx = x(it.t)
                drawLine(Accent, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2.dp.toPx())
                drawCircle(Color.Black, 6.dp.toPx(), Offset(sx, y(it.bpm.toFloat())))
                drawCircle(Color.White, 4.5.dp.toPx(), Offset(sx, y(it.bpm.toFloat())))
                val tl = measurer.measure("${Calc.fmtClock(it.t)}  ·  ${it.bpm} bpm", bubbleStyle)
                val bw = tl.size.width + 12.dp.toPx(); val bh = tl.size.height + 6.dp.toPx()
                val bx = (sx - bw / 2).coerceIn(0f, w - bw)
                drawRoundRect(Accent, Offset(bx, 0f), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                drawText(tl, topLeft = Offset(bx + 6.dp.toPx(), 3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Calc.fmtClock(t0), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(Calc.fmtClock(t1), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun nearest(samples: List<HrSample>, t: Long): HrSample {
    val idx = samples.binarySearchBy(t) { it.t }
    if (idx >= 0) return samples[idx]
    val ins = -idx - 1
    val a = samples.getOrNull(ins - 1)
    val b = samples.getOrNull(ins)
    return when {
        a == null -> b!!
        b == null -> a
        abs(a.t - t) <= abs(b.t - t) -> a
        else -> b
    }
}
