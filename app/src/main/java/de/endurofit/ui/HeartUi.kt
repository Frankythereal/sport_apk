package de.endurofit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Calc
import de.endurofit.data.DayHr
import de.endurofit.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** Welche Ansicht im Analyse-Tab offen ist (0 = Auswertung, 1 = Trainings, 2 = Herz). */
object Nav {
    val analysisView = MutableStateFlow(0)
    /** Wird gesetzt, wenn Text geteilt wurde – dann zum Essen-Tab springen. */
    val openFood = MutableStateFlow(false)
    /** Auftrag aus dem Essen-Widget: "photo", "manual" oder "paste". */
    val foodAction = MutableStateFlow<String?>(null)
}

private val RestColor = Color(0xFFFF7043)

/** Ruhepuls der Vortage als Vergleich – für den Hinweis "erhöht". */
private fun baseline(all: Map<Long, DayHr>, day: Long): Double? {
    val prev = (1..14).mapNotNull { all[Calc.addDays(day, -it)]?.resting }
    return if (prev.size >= 3) prev.average() else null
}

/** Karte für "Heute": Puls über den Tag mit Ruhepuls. */
@Composable
fun HeartTodayCard(onHistory: () -> Unit) {
    val all by Repo.dayHr.collectAsState()
    val profile by Repo.profile.collectAsState()
    val today = Calc.dayStart(System.currentTimeMillis())
    val d = all[today] ?: all[Calc.addDays(today, -1)]
    SectionCard("❤️ Herzfrequenz") {
        if (d == null) {
            Hint("Noch keine Pulsdaten über den Tag. Sie kommen von der Uhr über Health Connect (Profil → Uhr-Daten importieren).")
            return@SectionCard
        }
        if (d.day != today) Hint("Gestern – für heute ist noch nichts angekommen.")
        HeartStats(d, baseline(all, d.day))
        Spacer(Modifier.height(8.dp))
        HrChart(d.points, profile.effectiveMaxHr, start = d.day, height = 130.dp)
        TextButton(onClick = onHistory) { Text("Verlauf ansehen →") }
    }
}

@Composable
private fun HeartStats(d: DayHr, base: Double?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(d.resting?.toString() ?: "–", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = RestColor)
        Text(
            when {
                d.resting == null -> " Ruhepuls – kommt nach der ersten Nacht mit der Uhr"
                !d.restingMeasured -> " bpm Ruhepuls (geschätzt)"
                else -> " bpm Ruhepuls"
            },
            Modifier.padding(bottom = 6.dp)
        )
    }
    StatRow("Niedrigster" to "${d.min}", "Ø Tag" to "${d.avg}", "Höchster" to "${d.max}")
    val r = d.resting
    if (r != null && base != null) {
        val diff = r - base
        Hint(
            when {
                diff >= 5 -> "⚠️ ${diff.roundToInt()} Schläge über deinem Schnitt (${base.roundToInt()}). Das kann auf Müdigkeit, Stress, zu wenig Schlaf oder einen Infekt hindeuten – heute lieber locker trainieren."
                diff <= -3 -> "👍 Unter deinem Schnitt (${base.roundToInt()}) – gut erholt."
                else -> "Im normalen Bereich (Schnitt ${base.roundToInt()})."
            },
            Modifier.padding(top = 4.dp)
        )
    }
}

/** Eigene Ansicht im Analyse-Tab: Ruhepuls-Trend und Tageskurven. */
@Composable
fun HeartHistory() {
    val all by Repo.dayHr.collectAsState()
    val profile by Repo.profile.collectAsState()
    val sessions by Repo.sessions.collectAsState()
    var span by rememberSaveable { mutableIntStateOf(30) }
    val today = Calc.dayStart(System.currentTimeMillis())
    var sel by rememberSaveable { mutableStateOf<Long?>(null) }

    val days = (span - 1 downTo 0).map { Calc.addDays(today, -it) }
    val data = days.mapNotNull { all[it] }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Segmented(listOf(7 to "7 T.", 30 to "30 T.", 90 to "3 M.", 365 to "1 J."), span, { span = it; sel = null })
        Spacer(Modifier.height(8.dp))

        if (data.isEmpty()) {
            SectionCard {
                Hint("Noch keine Pulsdaten über den Tag. Sobald die Uhr über Health Connect Pulswerte liefert, siehst du hier deinen Verlauf. Prüfen kannst du das unter Profil → „Health Connect prüfen“.")
            }
            return@Column
        }

        val rests = data.mapNotNull { it.resting }
        SectionCard("Ruhepuls-Verlauf") {
            if (rests.isNotEmpty()) {
                val half = rests.size / 2
                val trend = if (rests.size >= 6) rests.takeLast(half).average() - rests.take(half).average() else null
                StatRow(
                    "Ø Ruhepuls" to "${rests.average().roundToInt()}",
                    "Tiefster" to "${rests.min()}",
                    "Höchster" to "${rests.max()}",
                )
                trend?.let {
                    Hint(
                        when {
                            it <= -2 -> "📉 Ruhepuls sinkt (${Calc.fmtNum(it, 1)} bpm) – deine Ausdauer wird besser."
                            it >= 2 -> "📈 Ruhepuls steigt (+${Calc.fmtNum(it, 1)} bpm) – achte auf Erholung und Schlaf."
                            else -> "Ruhepuls stabil."
                        },
                        Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TrendChart(days, all, sel) { sel = it }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(RestColor, "Ruhepuls")
                LegendDot(Color(0xFF616161), "Tiefster bis höchster Puls")
            }
            Hint("Tippe auf einen Tag für die Tageskurve.", Modifier.padding(top = 4.dp))
        }

        val day = sel ?: data.last().day
        all[day]?.let { d ->
            SectionCard(if (day == today) "Heute" else Calc.fmtDate(day)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        sel = days.filter { it < day && all[it] != null }.maxOrNull() ?: day
                    }) { Text("◀") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        sel = days.filter { it > day && all[it] != null }.minOrNull() ?: day
                    }) { Text("▶") }
                }
                HeartStats(d, baseline(all, d.day))
                Spacer(Modifier.height(8.dp))
                HrChart(d.points, profile.effectiveMaxHr, start = d.day, height = 170.dp)
                val trainings = Calc.sessionsOfDay(sessions, d.day)
                if (trainings.isNotEmpty()) {
                    Hint(
                        "Trainings: " + trainings.joinToString(" · ") { "${it.type.emoji} ${Calc.fmtClock(it.start)}–${Calc.fmtClock(it.end)}" },
                        Modifier.padding(top = 6.dp)
                    )
                }
                Hint(
                    "Quelle: " + de.endurofit.health.SuuntoImport.appLabel(androidx.compose.ui.platform.LocalContext.current, d.source) +
                        " · 5-Minuten-Mittelwerte",
                    Modifier.padding(top = 2.dp)
                )
            }
        }

        HrvTrendCard(days)
        EcgCard()

        SectionCard("Gut zu wissen") {
            Hint(
                "Der Ruhepuls ist einer der besten Fitness-Anzeiger: Sinkt er über Wochen, wird dein Herz-Kreislauf-System leistungsfähiger. " +
                    "Liegt er morgens 5 oder mehr Schläge über deinem Schnitt, ist der Körper oft noch nicht erholt – dann lieber einen lockeren Tag einlegen. " +
                    "Bei Erwachsenen liegt er meist zwischen 50 und 80, bei Ausdauersportlern auch darunter."
            )
        }
    }
}

@Composable
private fun LegendDot(c: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c))
        Text(" $text", style = MaterialTheme.typography.labelSmall)
    }
}

/** Je Tag ein Balken vom tiefsten zum höchsten Puls, darüber die Ruhepuls-Linie. */
@Composable
private fun TrendChart(days: List<Long>, all: Map<Long, DayHr>, sel: Long?, onSelect: (Long) -> Unit) {
    val data = days.mapNotNull { all[it] }
    val lo = ((data.minOfOrNull { it.min } ?: 40) - 5).coerceAtLeast(30).toFloat()
    val hi = ((data.maxOfOrNull { it.max } ?: 180) + 5).toFloat()
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).pointerInput(days) {
                detectTapGestures { off ->
                    val i = (off.x / size.width * days.size).toInt().coerceIn(0, days.size - 1)
                    // nächster Tag mit Daten
                    val idx = days.indices.filter { all[days[it]] != null }.minByOrNull { kotlin.math.abs(it - i) }
                    if (idx != null) onSelect(days[idx])
                }
            }
        ) {
            val n = days.size
            val slot = size.width / n
            val h = size.height
            fun y(v: Float) = h - (v - lo) / (hi - lo) * h
            val barW = (slot * 0.5f).coerceIn(1.5f, 10f)
            val path = Path()
            var started = false
            days.forEachIndexed { i, day ->
                val d = all[day] ?: return@forEachIndexed
                val cx = i * slot + slot / 2
                val selected = day == sel
                drawLine(
                    if (selected) Color.White else Color(0xFF616161),
                    Offset(cx, y(d.max.toFloat())), Offset(cx, y(d.min.toFloat())),
                    strokeWidth = barW,
                )
                d.resting?.let { r ->
                    val p = Offset(cx, y(r.toFloat()))
                    if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
                    if (n <= 31 || selected) drawCircle(RestColor, if (selected) 6f else 3.5f, p)
                }
            }
            drawPath(path, RestColor, style = Stroke(width = 2.5f))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Calc.fmtShortDate(days.first()), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(Calc.fmtShortDate(days.last()), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
    }
}
