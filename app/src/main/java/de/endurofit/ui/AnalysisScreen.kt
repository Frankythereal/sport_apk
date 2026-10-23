@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.DaySummary
import de.endurofit.data.Goals
import de.endurofit.data.Repo
import de.endurofit.data.SessionType
import kotlin.math.abs
import kotlin.math.max

private val ColBmr = Color(0xFF5C6370)
private val ColWalk = Color(0xFF42A5F5)
private val ColTraining = Accent

enum class Period(val label: String) { WOCHE("Woche"), MONAT("Monat"), QUARTAL("3 Monate") }

@Composable
fun AnalysisScreen(selected: String?, onSelect: (String?) -> Unit) {
    if (selected != null) {
        BackHandler { onSelect(null) }
        SessionDetail(selected, onBack = { onSelect(null) })
        return
    }
    val view by Nav.analysisView.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = view == 0, onClick = { Nav.analysisView.value = 0 }, label = { Text("📊 Auswertung") })
            FilterChip(selected = view == 2, onClick = { Nav.analysisView.value = 2 }, label = { Text("❤️ Herz") })
            FilterChip(selected = view == 1, onClick = { Nav.analysisView.value = 1 }, label = { Text("📋 Trainings") })
        }
        Box(Modifier.weight(1f)) {
            when (view) {
                0 -> AnalysisContent(onSelect = { onSelect(it) })
                2 -> HeartHistory()
                else -> HistoryScreen(selected = null, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun AnalysisContent(onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val sessions by Repo.sessions.collectAsState()
    val food by Repo.food.collectAsState()
    val water by Repo.water.collectAsState()
    val steps by Repo.steps.collectAsState()
    val sleep by Repo.sleep.collectAsState()
    val profile by Repo.profile.collectAsState()
    val goals by Repo.goals.collectAsState()
    val sick by Repo.sick.collectAsState()

    var period by rememberSaveable { mutableStateOf(Period.WOCHE) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val today = Calc.dayStart(System.currentTimeMillis())

    val start: Long
    val count: Int
    val label: String
    when (period) {
        Period.WOCHE -> {
            start = Calc.addDays(Calc.weekStart(today), offset * 7)
            count = 7
            label = if (offset == 0) "Diese Woche" else "${Calc.fmtShortDate(start)} – ${Calc.fmtShortDate(Calc.addDays(start, 6))}"
        }
        Period.MONAT -> {
            start = Calc.monthStart(today, offset)
            count = Calc.daysInMonth(start)
            label = Calc.fmtMonth(start)
        }
        Period.QUARTAL -> {
            val end = Calc.addDays(today, offset * 90)
            start = Calc.addDays(end, -89)
            count = 90
            label = "${Calc.fmtShortDate(start)} – ${Calc.fmtShortDate(end)}"
        }
    }
    val sums = remember(start, count, sessions, food, water, steps, sleep, profile, goals, sick) {
        Analysis.days(start, count).map { Analysis.day(it, sessions, food, water, steps, sleep, profile, goals) }
    }

    val exportDays = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = Csv.write(ctx, uri, daysCsv(sums))
            Toast.makeText(ctx, if (ok) "Exportiert" else "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEach { p ->
                FilterChip(selected = period == p, onClick = { period = p; offset = 0 }, label = { Text(p.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { offset-- }) { Text("◀") }
            Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("▶") }
        }

        if (sums.isEmpty()) {
            Hint("Für diesen Zeitraum gibt es noch keine Daten.")
            return@Column
        }

        KcalCard(sums, period)
        BreakdownCard(sums)
        ActivitiesCard(sums, profile.effectiveMaxHr, onSelect)
        GoalsCard(sums, goals, period)
        SleepCard(sums)

        Text("Tag für Tag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
        sums.reversed().forEach { d -> DayRow(d, goals, onSelect) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { exportDays.launch("auswertung.csv") }, modifier = Modifier.fillMaxWidth()) {
            Text("Zeitraum als CSV exportieren")
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Kalorien

@Composable
private fun KcalCard(sums: List<DaySummary>, period: Period) {
    val withFood = sums.filter { it.foodCount > 0 }
    var sel by remember(sums) { mutableStateOf<DaySummary?>(null) }
    SectionCard("🔥 Kalorien") {
        StatRow(
            "Verbrannt gesamt" to Calc.fmtNum(sums.sumOf { it.burned }),
            "Ø pro Tag" to Calc.fmtNum(sums.sumOf { it.burned } / sums.size),
        )
        Spacer(Modifier.height(8.dp))
        StatRow(
            "Ø gegessen" to (if (withFood.isEmpty()) "–" else Calc.fmtNum(withFood.sumOf { it.eaten } / withFood.size)),
            "Ø Bilanz" to (if (withFood.isEmpty()) "–" else signedKcal(withFood.sumOf { it.balance } / withFood.size)),
        )
        Spacer(Modifier.height(12.dp))

        val s = sel
        Hint(
            if (s != null) "${Calc.fmtShortDate(s.day)}: verbrannt ${Calc.fmtNum(s.burned)} · gegessen ${Calc.fmtNum(s.eaten)} · Bilanz ${signedKcal(s.balance)}"
            else "Tippe auf einen Balken für Details"
        )
        val maxV = max(sums.maxOf { it.burned }, sums.maxOf { it.eaten }).coerceAtLeast(1.0).toFloat() * 1.08f
        Canvas(
            Modifier.fillMaxWidth().height(170.dp).padding(top = 6.dp).pointerInput(sums) {
                detectTapGestures { off ->
                    val i = (off.x / size.width * sums.size).toInt().coerceIn(0, sums.size - 1)
                    sel = sums[i]
                }
            }
        ) {
            val n = sums.size
            val slot = size.width / n
            val barW = (slot * 0.7f).coerceAtLeast(2f)
            val h = size.height
            fun hy(v: Double) = (v.toFloat() / maxV) * h
            sums.forEachIndexed { i, d ->
                val x = i * slot + (slot - barW) / 2
                var top = h
                listOf(d.bmr to ColBmr, d.walkKcal to ColWalk, d.trainingKcal to ColTraining).forEach { (v, c) ->
                    val bh = hy(v)
                    if (bh > 0) {
                        drawRect(c.copy(alpha = if (sel == null || sel == d) 1f else 0.45f), Offset(x, top - bh), Size(barW, bh))
                        top -= bh
                    }
                }
                if (d.foodCount > 0) {
                    val ey = h - hy(d.eaten)
                    drawRect(Color.White, Offset(x - 2f, ey - 2f), Size(barW + 4f, 4f))
                }
            }
        }
        if (period == Period.WOCHE) {
            Row(Modifier.fillMaxWidth()) {
                listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Text(Calc.fmtShortDate(sums.first().day), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(Calc.fmtShortDate(sums.last().day), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(ColBmr, "Grundumsatz"); Legend(ColWalk, "Gehen"); Legend(ColTraining, "Training"); Legend(Color.White, "Gegessen")
        }
    }
}

@Composable
private fun Legend(c: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
        Text(" $text", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun BreakdownCard(sums: List<DaySummary>) {
    val bmr = sums.sumOf { it.bmr }
    val walk = sums.sumOf { it.walkKcal }
    val train = sums.sumOf { it.trainingKcal }
    val total = (bmr + walk + train).coerceAtLeast(1.0)
    SectionCard("Woher kommt der Verbrauch?") {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            listOf(bmr to ColBmr, walk to ColWalk, train to ColTraining).forEach { (v, c) ->
                if (v > 0) Box(Modifier.weight((v / total).toFloat()).fillMaxHeight().background(c))
            }
        }
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple("Grundumsatz (Körper in Ruhe)", bmr, ColBmr),
            Triple("Gehen im Alltag", walk, ColWalk),
            Triple("Training", train, ColTraining),
        ).forEach { (name, v, c) ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Text("  $name", Modifier.weight(1f))
                Text("${Calc.fmtNum(v)} kcal", fontWeight = FontWeight.SemiBold)
                Text("  ${Calc.fmtNum(v / total * 100)} %", Modifier.width(52.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------------------------------------------------------- Aktivitäten

@Composable
private fun ActivitiesCard(sums: List<DaySummary>, maxHr: Int, onSelect: (String) -> Unit) {
    val all = sums.flatMap { it.sessions }
    val profile by Repo.profile.collectAsState()
    SectionCard("Was wurde gemacht?") {
        SessionType.entries.forEach { t ->
            val list = all.filter { it.type == t }
            if (list.isNotEmpty()) {
                val samples = list.flatMap { it.samples }
                val avg = if (samples.isEmpty()) "" else " · Ø ${samples.sumOf { it.bpm } / samples.size} bpm"
                ActivityLine(
                    t.emoji, t.label,
                    "${list.size}× · ${Calc.fmtDuration(list.sumOf { it.durationMs })}$avg" +
                        if (t == SessionType.GYM) " · ${list.sumOf { it.sets.size }} Sätze" else "",
                    list.sumOf { it.kcal }
                )
            }
        }
        val stepsTotal = sums.sumOf { it.steps }
        if (stepsTotal > 0) {
            val km = stepsTotal * profile.heightCm * 0.415 / 100.0 / 1000.0
            ActivityLine(
                "🚶", "Gehen",
                "${Analysis.fmtInt(stepsTotal)} Schritte · Ø ${Analysis.fmtInt(stepsTotal / sums.size)}/Tag · ca. ${Calc.fmtNum(km, 1)} km",
                sums.sumOf { it.walkKcal }
            )
        }
        if (all.isEmpty() && stepsTotal == 0) Hint("In diesem Zeitraum noch keine Aktivitäten.")

        val hardest = all.maxByOrNull { s -> Calc.timeInZones(s.samples, maxHr).let { it[4] + it[5] } }
        if (hardest != null && hardest.samples.size > 1) {
            val z = Calc.timeInZones(hardest.samples, maxHr)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("💪 Härteste Einheit", fontWeight = FontWeight.SemiBold)
            Hint("${hardest.type.emoji} ${hardest.title} am ${Calc.fmtShortDate(hardest.start)} · ${Calc.fmtDuration(z[4] + z[5])} in Zone 4–5 · max ${hardest.maxBpm} bpm")
            TextButton(onClick = { onSelect(hardest.id) }) { Text("Ansehen") }
        }
    }
}

@Composable
private fun ActivityLine(emoji: String, name: String, detail: String, kcal: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 22.sp, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Hint(detail)
        }
        Text("${Calc.fmtNum(kcal)} kcal", fontWeight = FontWeight.Bold, color = Accent)
    }
}

// ---------------------------------------------------------------- Ziele

@Composable
private fun GoalsCard(sums: List<DaySummary>, goals: Goals, period: Period) {
    val checksPerDay = sums.map { Analysis.dayGoals(it, goals) }
    SectionCard("🎯 Ziele") {
        val labels = checksPerDay.first().map { it.emoji to it.label }
        labels.forEachIndexed { idx, (emoji, name) ->
            val judged = checksPerDay.map { it[idx] }.filter { it.judged }
            val done = judged.count { it.done }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, Modifier.width(30.dp))
                Text(name.substringBefore(" ("), Modifier.weight(1f))
                Text(if (judged.isEmpty()) "–" else "$done von ${judged.size} Tagen", fontWeight = FontWeight.SemiBold)
            }
            if (sums.size <= 31) {
                Row(Modifier.padding(start = 30.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    checksPerDay.forEach { day ->
                        val c = day[idx]
                        Box(
                            Modifier.size(if (sums.size <= 7) 22.dp else 9.dp).clip(RoundedCornerShape(3.dp)).background(
                                when {
                                    !c.judged -> MaterialTheme.colorScheme.surface
                                    c.done -> Good
                                    else -> Color(0xFF6D4C41)
                                }
                            )
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val weeks = (sums.size / 7.0).coerceAtLeast(1.0)
        val activeMin = sums.sumOf { it.activeMs } / 60_000.0
        val strength = sums.sumOf { d -> d.sessions.count { it.type == SessionType.GYM } }
        val sickDays = sums.count { it.sick }
        if (period == Period.WOCHE) {
            val w = Analysis.week(sums, goals)
            GoalRow(de.endurofit.data.GoalCheck("🔥", "Aktive Minuten", "${activeMin.toInt()} / ${w.activeTargetMin}", (activeMin / w.activeTargetMin.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f), w.activeDone))
            GoalRow(de.endurofit.data.GoalCheck("🏋️", "Krafttraining", "$strength / ${w.strengthTarget}×", (strength.toFloat() / w.strengthTarget.coerceAtLeast(1)).coerceIn(0f, 1f), w.strengthDone))
        } else {
            Text("🔥 Ø aktive Minuten pro Woche: ${Calc.fmtNum(activeMin / weeks)} (Ziel ${goals.weeklyActiveMin})")
            Text("🏋️ Ø Krafttraining pro Woche: ${Calc.fmtNum(strength / weeks, 1)}× (Ziel ${goals.weeklyStrength}×)")
        }
        if (sickDays > 0) Hint("🤒 $sickDays Kranktag(e) – an diesen Tagen zählen nur Trinken und Schlaf.", Modifier.padding(top = 6.dp))
        Hint("Grün = geschafft · braun = verfehlt · grau = noch offen, krank oder keine Daten", Modifier.padding(top = 6.dp))
    }
}

// ---------------------------------------------------------------- Schlaf

@Composable
private fun SleepCard(sums: List<DaySummary>) {
    val nights = sums.mapNotNull { it.sleep }
    SectionCard("😴 Schlaf") {
        if (nights.isEmpty()) {
            Hint("Keine Schlafdaten in diesem Zeitraum. Trag den Schlaf unter „Heute“ ein.")
            Spo2Summary(sums)
            return@SectionCard
        }
        val avg = nights.sumOf { it.asleepMs } / nights.size
        val staged = nights.filter { it.hasStages }
        StatRow(
            "Ø Schlaf" to Calc.fmtHours(avg),
            "Nächte" to "${nights.size}",
            "Ø Tiefschlaf" to (if (staged.isEmpty()) "–" else Calc.fmtHours(staged.sumOf { it.deepMs } / staged.size)),
        )
        Spacer(Modifier.height(6.dp))
        val best = nights.maxBy { it.asleepMs }
        val worst = nights.minBy { it.asleepMs }
        Hint("Längste Nacht: ${Calc.fmtShortDate(best.end)} (${Calc.fmtHours(best.asleepMs)}) · Kürzeste: ${Calc.fmtShortDate(worst.end)} (${Calc.fmtHours(worst.asleepMs)})")
        val bedtimes = nights.map {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = it.start }
            var m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
            if (m < 12 * 60) m += 24 * 60
            m
        }
        val avgBed = (bedtimes.sum() / bedtimes.size) % (24 * 60)
        Hint("Ø Einschlafzeit: ${avgBed / 60}:${String.format(java.util.Locale.GERMANY, "%02d", avgBed % 60)} Uhr")
        Spo2Summary(sums)
    }
}

// ---------------------------------------------------------------- Tagesliste

@Composable
private fun DayRow(d: DaySummary, goals: Goals, onSelect: (String) -> Unit) {
    var open by remember(d.day) { mutableStateOf(false) }
    val checks = Analysis.dayGoals(d, goals)
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Calc.fmtShortDate(d.day) + if (d.sick) "  🤒" else "", fontWeight = FontWeight.SemiBold)
                    val parts = ArrayList<String>()
                    d.sessions.groupBy { it.type }.forEach { (t, l) -> parts.add("${t.emoji} ${Calc.fmtDuration(l.sumOf { it.durationMs })}") }
                    if (d.steps > 0) parts.add("🚶 ${Analysis.fmtInt(d.steps)}")
                    if (d.sleep != null) parts.add("😴 ${Calc.fmtHours(d.sleep.asleepMs)}")
                    Hint(if (parts.isEmpty()) (if (d.sick) "krank – Pause" else "Ruhetag") else parts.joinToString("  "))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${Calc.fmtNum(d.burned)} kcal", fontWeight = FontWeight.Bold, color = Accent)
                    Hint(if (d.foodCount > 0) "gegessen ${Calc.fmtNum(d.eaten)}" else "nichts eingetragen")
                }
            }
            Text(
                checks.joinToString("  ") { it.emoji + if (!it.judged) "·" else if (it.done) "✓" else "✗" },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
            )
            if (open) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                StatRow(
                    "Grundumsatz" to Calc.fmtNum(d.bmr),
                    "Gehen" to Calc.fmtNum(d.walkKcal),
                    "Training" to Calc.fmtNum(d.trainingKcal),
                )
                Spacer(Modifier.height(6.dp))
                StatRow(
                    "Bilanz" to (if (d.foodCount > 0) signedKcal(d.balance) else "–"),
                    "Eiweiß" to "${Calc.fmtNum(d.protein)} g",
                    "Getrunken" to "${Calc.fmtNum(d.waterMl / 1000.0, 1)} l",
                )
                Spacer(Modifier.height(6.dp))
                d.sessions.forEach { s -> SessionRow(s) { onSelect(s.id) } }
            }
        }
    }
}

private fun signedKcal(v: Double) = (if (v < 0) "−" else "+") + Calc.fmtNum(abs(v))

private fun daysCsv(sums: List<DaySummary>): String = buildString {
    append("datum;verbrannt;grundumsatz;training;gehen;gegessen;bilanz;eiweiss_g;getrunken_l;schritte;schlaf_h;trainings\n")
    sums.forEach { d ->
        append(Calc.fmtShortDate(d.day)).append(';')
            .append(Calc.fmtNum(d.burned)).append(';')
            .append(Calc.fmtNum(d.bmr)).append(';')
            .append(Calc.fmtNum(d.trainingKcal)).append(';')
            .append(Calc.fmtNum(d.walkKcal)).append(';')
            .append(Calc.fmtNum(d.eaten)).append(';')
            .append(if (d.foodCount > 0) Calc.fmtNum(d.balance) else "").append(';')
            .append(Calc.fmtNum(d.protein)).append(';')
            .append(Calc.fmtNum(d.waterMl / 1000.0, 2)).append(';')
            .append(d.steps).append(';')
            .append(d.sleep?.let { Calc.fmtNum(it.asleepMs / 3_600_000.0, 1) } ?: "").append(';')
            .append(d.sessions.joinToString(" | ") { "${it.type.label} ${Calc.fmtDuration(it.durationMs)}" }).append('\n')
    }
}


@Composable
private fun Spo2Summary(sums: List<DaySummary>) {
    val all by Repo.spo2.collectAsState()
    if (sums.isEmpty()) return
    val from = sums.first().day
    val to = Calc.addDays(sums.last().day, 1)
    val vals = all.filter { it.t in from until to }
    if (vals.isEmpty()) return
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    val avg = vals.map { it.pct }.average()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🩸 Blutsauerstoff Ø ", fontWeight = FontWeight.SemiBold)
        Text("${Calc.fmtNum(avg, 1)} %", fontWeight = FontWeight.Bold, color = spo2Color(avg))
    }
    Hint("Niedrigster Wert ${Calc.fmtNum(vals.minOf { it.pct }, 0)} % · ${vals.size} Messungen")
}
