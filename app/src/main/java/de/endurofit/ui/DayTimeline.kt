package de.endurofit.ui

import androidx.compose.foundation.clickable

import androidx.compose.foundation.gestures.detectHorizontalDragGestures

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.endurofit.data.Calc
import de.endurofit.data.DayHr
import de.endurofit.data.FoodEntry
import de.endurofit.data.Session
import de.endurofit.data.SessionType
import de.endurofit.data.SleepEntry
import de.endurofit.data.WaterEntry
import kotlin.math.abs

private val SleepColor = Color(0xFF5C6BC0)
private val FoodColor = Color(0xFFFFCA28)
private val WaterColor = Color(0xFF42A5F5)
private val HrColor = Color(0xFFFF7043)

private fun sessionColor(t: SessionType) = when (t) {
    SessionType.ENDURO -> Color(0xFF59EBD8)
    SessionType.GYM -> Color(0xFF66BB6A)
    SessionType.OTHER -> Color(0xFF26C6DA)
}

/**
 * Der ganze Tag auf einer Zeitleiste von 0 bis 24 Uhr: Schlaf, Trainings, Mahlzeiten,
 * Trinken und der Puls im Hintergrund. Antippen zeigt, was zu dieser Uhrzeit war.
 */
@Composable
fun DayTimeline(
    day: Long,
    sleep: SleepEntry?,
    sessions: List<Session>,
    food: List<FoodEntry>,
    water: List<WaterEntry>,
    hr: DayHr?,
    maxHr: Int = 185,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFBDBDBD))
    val end = Calc.addDays(day, 1)
    val now = System.currentTimeMillis()
    val span = (end - day).toFloat()
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(day) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    var selT by remember(day) { mutableStateOf<Long?>(null) }

    fun describe(t: Long): String {
        val parts = ArrayList<String>()
        parts.add(Calc.fmtClock(t) + " Uhr")
        if (sleep != null && t in sleep.start..sleep.end) parts.add("😴 Schlaf")
        sessions.firstOrNull { t in it.start..it.end }?.let { parts.add("${it.type.emoji} ${it.title}") }
        food.filter { abs(it.t - t) < 40 * 60_000 }.forEach { parts.add("🍽️ ${it.name} (${Calc.fmtNum(it.kcal)} kcal)") }
        val w = water.filter { abs(it.t - t) < 40 * 60_000 }.sumOf { it.ml }
        if (w > 0) parts.add("💧 $w ml")
        hr?.points?.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) < 20 * 60_000 }?.let { parts.add("❤️ ${it.bpm} bpm") }
        return parts.joinToString(" · ")
    }
    val info = selT?.let { describe(it) }

    Column {
        Canvas(
            Modifier.fillMaxWidth().height(if (hr != null && hr.points.size > 1) 190.dp else 92.dp)
                .pointerInput(day) {
                    // Antippen setzt den Schieber
                    detectTapGestures { off ->
                        val gw = size.width - (if (hr != null && hr.points.size > 1) 34.dp.toPx() else 0f)
                        selT = day + (off.x.coerceIn(0f, gw) / gw * span).toLong()
                    }
                }
                .pointerInput(day) {
                    // Mit dem Finger seitlich fahren – der Schieber bleibt danach stehen
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            val gw = size.width - (if (hr != null && hr.points.size > 1) 34.dp.toPx() else 0f)
                            selT = day + (off.x.coerceIn(0f, gw) / gw * span).toLong()
                        },
                    ) { change, _ ->
                        change.consume()
                        val gw = size.width - (if (hr != null && hr.points.size > 1) 34.dp.toPx() else 0f)
                        selT = day + (change.position.x.coerceIn(0f, gw) / gw * span).toLong()
                    }
                }
        ) {
            val hasHr = hr != null && hr.points.size > 1
            // Rechts Platz für die Puls-Skala
            val w = if (hasHr) size.width - 34.dp.toPx() else size.width
            val h = size.height
            // Oben: Balken & Punkte, unten: Pulsbereich
            val barTop = if (hasHr) h * 0.04f else h * 0.08f
            val barH = if (hasHr) h * 0.20f else h * 0.34f
            val dotY = if (hasHr) h * 0.32f else h * 0.5f
            fun x(t: Long) = ((t - day) / span * w).coerceIn(0f, w)
            val limit = w * reveal.value

            // Raster: alle 3 Stunden
            for (hh in 0..24 step 3) {
                val gx = w * hh / 24f
                drawLine(Color(0x22FFFFFF), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            }
            // Nacht schattieren (0–6 und 22–24 Uhr)
            drawRect(Color(0x12000000), Offset(0f, 0f), Size(w * 6 / 24f, h))

            // Schlaf
            sleep?.let {
                val x0 = x(maxOf(it.start, day)); val x1 = minOf(x(it.end), limit)
                if (x1 > x0) drawRoundRect(SleepColor.copy(alpha = 0.55f), Offset(x0, barTop), Size(x1 - x0, barH), CornerRadius(8f))
            }
            // Trainings
            sessions.forEach {
                val x0 = x(it.start); val x1 = minOf(maxOf(x(it.end), x0 + 6f), limit)
                if (x1 > x0) drawRoundRect(sessionColor(it.type), Offset(x0, barTop), Size(x1 - x0, barH), CornerRadius(8f))
            }
            // Puls: Skala mit Querlinien rechts, Punkte in Zonenfarbe, dünne Linie dazwischen
            if (hasHr) {
                val pts = hr!!.points
                val lo = (((pts.minOf { it.bpm } - 5) / 10) * 10).coerceAtLeast(30).toFloat()
                val hi = (((pts.maxOf { it.bpm } + 14) / 10) * 10).toFloat().coerceAtLeast(lo + 30)
                val top = h * 0.42f; val bot = h * 0.98f
                fun y(b: Float) = bot - (b - lo) / (hi - lo) * (bot - top)
                val step = if (hi - lo > 80) 20 else 10
                var g = ((lo.toInt() + step - 1) / step) * step
                while (g <= hi) {
                    val gy = y(g.toFloat())
                    drawLine(Color(0x30FFFFFF), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    val tl = measurer.measure("$g", labelStyle)
                    drawText(tl, topLeft = Offset(w + 6.dp.toPx(), gy - tl.size.height / 2f))
                    g += step
                }
                // Ruhepuls als farbige Linie
                hr.resting?.let { r ->
                    val ry = y(r.toFloat())
                    drawLine(HrColor.copy(alpha = 0.5f), Offset(0f, ry), Offset(w, ry), strokeWidth = 1.5f)
                }
                // Linie, abschnittsweise in der Farbe der Pulszone – Lücken (Uhr abgelegt) bleiben offen
                var prev: Offset? = null
                var prevT = 0L
                for (p in pts) {
                    val px = x(p.t)
                    if (px > limit) break
                    val o = Offset(px, y(p.bpm.toFloat()))
                    val pr = prev
                    if (pr != null && p.t - prevT < 30 * 60_000L) {
                        drawLine(zoneColor(Calc.zoneOf(p.bpm, maxHr)), pr, o, strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    prev = o; prevT = p.t
                }
                // Höchster Wert markieren
                pts.maxByOrNull { it.bpm }?.let { m ->
                    val px = x(m.t)
                    if (px <= limit) {
                        val tl = measurer.measure("▲${m.bpm}", labelStyle.copy(color = HrColor))
                        drawText(tl, topLeft = Offset((px - tl.size.width / 2f).coerceIn(0f, w - tl.size.width), y(m.bpm.toFloat()) - tl.size.height - 2f))
                    }
                }
            }
            // Wasser: kleine Tropfen
            water.forEach {
                val px = x(it.t)
                if (px <= limit) drawCircle(WaterColor, 3.5.dp.toPx(), Offset(px, dotY))
            }
            // Mahlzeiten: gelbe Punkte
            food.forEach {
                val px = x(it.t)
                if (px <= limit) {
                    drawCircle(FoodColor, 5.dp.toPx(), Offset(px, dotY))
                    drawCircle(Color(0xFF000000), 2.dp.toPx(), Offset(px, dotY))
                }
            }
            // Jetzt-Linie
            if (now in day until end) {
                val nx = x(now)
                drawLine(Color.White.copy(alpha = 0.35f), Offset(nx, 0f), Offset(nx, h), strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
            // ---------- Schieber ----------
            selT?.let { t ->
                val sx = x(t)
                drawLine(Accent, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2.dp.toPx())
                val p = hr?.points?.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) < 20 * 60_000 }
                var label = Calc.fmtClock(t)
                if (hasHr && p != null) {
                    val pts = hr!!.points
                    val lo = (((pts.minOf { it.bpm } - 5) / 10) * 10).coerceAtLeast(30).toFloat()
                    val hi = (((pts.maxOf { it.bpm } + 14) / 10) * 10).toFloat().coerceAtLeast(lo + 30)
                    val top = h * 0.42f; val bot = h * 0.98f
                    val py = bot - (p.bpm - lo) / (hi - lo) * (bot - top)
                    drawCircle(Color.Black, 6.dp.toPx(), Offset(sx, py))
                    drawCircle(zoneColor(Calc.zoneOf(p.bpm, maxHr)).takeIf { Calc.zoneOf(p.bpm, maxHr) >= 2 } ?: Accent, 4.5.dp.toPx(), Offset(sx, py))
                    label += "  ·  ${p.bpm} bpm"
                }
                // Etikett oben am Schieber
                val tl = measurer.measure(label, labelStyle.copy(color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFamily = Mono))
                val bw = tl.size.width + 12.dp.toPx(); val bh = tl.size.height + 6.dp.toPx()
                val bx = (sx - bw / 2).coerceIn(0f, size.width - bw)
                val by = if (hasHr) h * 0.42f - bh - 4.dp.toPx() else 0f
                drawRoundRect(Accent, Offset(bx, by), Size(bw, bh), CornerRadius(2.dp.toPx()))
                drawText(tl, topLeft = Offset(bx + 6.dp.toPx(), by + 3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = if (hr != null && hr.points.size > 1) 34.dp else 0.dp)) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, l ->
                Text(
                    l, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (info != null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(info, style = MaterialTheme.typography.bodySmall, color = Ef.Text, modifier = Modifier.weight(1f))
                Text("✕", color = Ef.Muted, modifier = Modifier.clickable { selT = null }.padding(8.dp))
            }
        } else Hint(if (hr != null && hr.points.size > 1) "Mit dem Finger über das Diagramm fahren – der Schieber zeigt Uhrzeit, Puls und was du gemacht hast." else "Tippe auf die Zeitleiste, um zu sehen, was um diese Uhrzeit war.")
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(SleepColor to "Schlaf", Color(0xFF59EBD8) to "Training", FoodColor to "Essen", WaterColor to "Trinken", HrColor to "Puls").forEach { (c, l) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(c) }
                    Text(" $l", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
