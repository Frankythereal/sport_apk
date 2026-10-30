@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.GoalCheck
import de.endurofit.data.Repo
import de.endurofit.data.SessionType
import de.endurofit.hr.Recorder
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

private val StepsColor = Color(0xFFFF6D00)
private val WaterBlue = Color(0xFF42A5F5)
private val ActiveGreen = Color(0xFF66BB6A)
private val KcalYellow = Color(0xFFFFCA28)
private val SleepPurple = Color(0xFF7E57C2)

private enum class Detail { KCAL, GOALS, SLEEP, RECOVERY, HEART, VITALS, TRAINING, WATER, WEIGHT, SUPPS }

@Composable
fun TodayScreen(onOpenTraining: () -> Unit, onOpenSession: (String) -> Unit, onOpenHeart: () -> Unit = {}) {
    val ctx = LocalContext.current
    val sessions by Repo.sessions.collectAsState()
    val food by Repo.food.collectAsState()
    val water by Repo.water.collectAsState()
    val steps by Repo.steps.collectAsState()
    val sleep by Repo.sleep.collectAsState()
    val profile by Repo.profile.collectAsState()
    val goals by Repo.goals.collectAsState()
    val dayHrAll by Repo.dayHr.collectAsState()
    val recAll by Repo.recovery.collectAsState()
    val spo2 by Repo.spo2.collectAsState()
    val skin by Repo.skin.collectAsState()
    // Lesen abonniert Änderungen → neu berechnen, wenn sich der Krankheitsmodus ändert
    @Suppress("UNUSED_VARIABLE") val sickState = Repo.sick.collectAsState().value
    val live by Recorder.live.collectAsState()
    val running by de.endurofit.AutoSync.running.collectAsState()
    val last by de.endurofit.AutoSync.lastRun.collectAsState()

    var showFood by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Detail?>(null) }

    val today = Calc.dayStart(System.currentTimeMillis())
    val d = Analysis.day(today, sessions, food, water, steps, sleep, profile, goals)
    val weekDays = Analysis.days(Calc.addDays(today, -6), 7).map { Analysis.day(it, sessions, food, water, steps, sleep, profile, goals) }
    val hrToday = dayHrAll[today]
    val hr = hrToday ?: dayHrAll[Calc.addDays(today, -1)]
    val rec = recAll[today] ?: recAll[Calc.addDays(today, -1)]
    val activeGoalMin = (goals.weeklyActiveMin / 7.0).coerceAtLeast(1.0)
    val kcalTarget = d.burned + (goals.mode.lo + goals.mode.hi) / 2.0
    var i = 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        // ---------- Kopf ----------
        Appear(i++) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    Text(
                        when (h) { in 5..10 -> "Guten Morgen"; in 11..17 -> "Hallo"; in 18..22 -> "Guten Abend"; else -> "Gute Nacht" } + " 👋",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    )
                    Hint(
                        Calc.fmtDate(today) + " · " + when {
                            running -> "aktualisiere …"
                            last > 0 -> "aktualisiert ${Calc.fmtClock(last)} Uhr"
                            else -> ""
                        }
                    )
                }
                IconButton(enabled = !running, onClick = { de.endurofit.AutoSync.onAppVisible(ctx, force = true) }) {
                    SpinningSync(running)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        SickSection()
        SickEarlyWarning()

        if (live != null) {
            Appear(i++) {
                Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), accent = Bad, onClick = onOpenTraining) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BeatingHeart(null, size = 20.sp)
                        Text("  Aufnahme läuft: ${live!!.type.label}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("Öffnen ›", color = Accent)
                    }
                }
            }
        }

        // ---------- Tagesringe ----------
        Appear(i++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "Dein Tag in Ringen", onClick = { detail = Detail.GOALS }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Rings(
                        listOf(
                            RingData(d.steps.toFloat() / goals.steps, StepsColor),
                            RingData(d.waterMl.toFloat() / d.waterGoal.coerceAtLeast(1), WaterBlue),
                            RingData((d.activeMs / 60_000.0 / activeGoalMin).toFloat(), ActiveGreen),
                            RingData(if (d.foodCount > 0) (d.eaten / kcalTarget).toFloat() else 0f, KcalYellow),
                        ),
                        Modifier.size(140.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        RingLegend(StepsColor, "Schritte", "${Analysis.fmtInt(d.steps)} / ${Analysis.fmtInt(goals.steps)}")
                        RingLegend(WaterBlue, "Trinken", "${Calc.fmtNum(d.waterMl / 1000.0, 1)} / ${Calc.fmtNum(d.waterGoal / 1000.0, 1)} l")
                        RingLegend(ActiveGreen, "Aktiv", "${d.activeMs / 60_000} / ${activeGoalMin.roundToInt()} min")
                        RingLegend(KcalYellow, "Gegessen", "${Calc.fmtNum(d.eaten)} / ${Calc.fmtNum(kcalTarget)}")
                        val done = listOf(d.steps >= goals.steps, d.waterMl >= d.waterGoal, d.activeMs / 60_000 >= activeGoalMin).count { it }
                        Hint(if (done == 3) "🎉 Alle Ringe geschlossen!" else "$done von 3 Zielen erreicht", Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // ---------- Zeitleiste ----------
        Appear(i++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "Dein Tag · 0–24 Uhr") {
                DayTimeline(
                    day = today,
                    sleep = d.sleep,
                    sessions = d.sessions,
                    food = food.filter { it.t in today until Calc.addDays(today, 1) },
                    water = water.filter { it.t in today until Calc.addDays(today, 1) },
                    hr = hrToday,
                )
            }
        }

        // ---------- Erholung | Puls ----------
        Appear(i++) {
            TileRow {
                Tile(Modifier.weight(1f).fillMaxHeight(), title = "🔋 Erholung", accent = rechargeColor(rec?.status), onClick = { detail = Detail.RECOVERY }) {
                    if (rec?.status != null) {
                        Text(rechargeText(rec.status).replaceFirstChar { it.uppercase() }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = rechargeColor(rec.status))
                        Spacer(Modifier.height(4.dp))
                        rec.hrv?.let { Text("HRV ${it.roundToInt()} ms", style = MaterialTheme.typography.bodyMedium) }
                        rec.sleepScore?.let { Hint("Schlaf-Score $it") }
                        Spacer(Modifier.weight(1f))
                        AnimatedBar((rec.status / 6f), rechargeColor(rec.status))
                    } else {
                        Hint("Kommt nach den ersten Nächten mit der Uhr.")
                    }
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), title = "Puls", accent = Color(0xFFFF7043), onClick = { detail = Detail.HEART }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BeatingHeart(hr?.resting)
                        Spacer(Modifier.width(6.dp))
                        if (hr?.resting != null) CountUp(hr.resting, fontSize = 24.sp) else Text("–", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Hint(if (hr?.resting != null) "Ruhepuls" else "Ruhepuls nach der ersten Nacht")
                    Spacer(Modifier.weight(1f))
                    hrToday?.points?.let { Sparkline(it, Modifier.fillMaxWidth().height(36.dp), start = today, end = maxOf(System.currentTimeMillis(), today + 3_600_000)) }
                }
            }
        }

        // ---------- Schlaf | Trinken ----------
        Appear(i++) {
            TileRow {
                Tile(Modifier.weight(1f).fillMaxHeight(), title = "😴 Schlaf", accent = SleepPurple, onClick = { detail = Detail.SLEEP }) {
                    val s = d.sleep
                    if (s != null) {
                        val min = (s.asleepMs / 60_000).toInt()
                        CountUp(min, fontSize = 24.sp, format = { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }, suffix = " h")
                        Hint("${Calc.fmtClock(s.start)}–${Calc.fmtClock(s.end)}")
                        Spacer(Modifier.weight(1f))
                        if (s.hasStages) SleepStageBar(s.deepMs, s.remMs, s.lightMs, s.awakeMs)
                        else AnimatedBar(s.asleepMs / (goals.sleepHours * 3_600_000).toFloat(), SleepPurple)
                    } else {
                        Hint("Noch kein Schlaf für heute Nacht.")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showSleep = true }, contentPadding = PaddingValues(0.dp)) { Text("Eintragen") }
                    }
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), title = "💧 Trinken", accent = WaterBlue, onClick = { detail = Detail.WATER }) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        WaterGlass(d.waterMl.toFloat() / d.waterGoal.coerceAtLeast(1), Modifier.width(38.dp).fillMaxHeight())
                        Spacer(Modifier.width(10.dp))
                        Column {
                            CountUp(d.waterMl, fontSize = 20.sp, suffix = " ml")
                            Hint("von ${Calc.fmtNum(d.waterGoal / 1000.0, 1)} l")
                        }
                    }
                    FilledTonalButton(
                        onClick = { Repo.addWater(250) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp),
                    ) { Text("+250 ml", fontSize = 13.sp) }
                }
            }
        }

        // ---------- Kalorien | Vitalwerte ----------
        Appear(i++) {
            TileRow {
                Tile(Modifier.weight(1f).fillMaxHeight(), title = "🔥 Kalorien", accent = KcalYellow, onClick = { detail = Detail.KCAL }) {
                    val inRange = d.balance >= goals.mode.lo && d.balance <= goals.mode.hi
                    CountUp(
                        abs(d.balance).roundToInt(), fontSize = 22.sp,
                        prefix = if (d.balance < 0) "−" else "+", suffix = " kcal",
                        color = if (d.foodCount == 0) Color.Unspecified else if (inRange) Good else Accent,
                    )
                    Hint(if (d.foodCount == 0) "noch nichts gegessen" else "Bilanz")
                    Spacer(Modifier.weight(1f))
                    val maxV = maxOf(d.eaten, d.burned).coerceAtLeast(1.0)
                    Hint("Gegessen ${Calc.fmtNum(d.eaten)}")
                    AnimatedBar((d.eaten / maxV).toFloat(), KcalYellow, height = 6.dp)
                    Spacer(Modifier.height(4.dp))
                    Hint("Verbrannt ${Calc.fmtNum(d.burned)}")
                    AnimatedBar((d.burned / maxV).toFloat(), Accent, height = 6.dp)
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), title = "🩸 Vitalwerte", accent = Color(0xFFEF5350), onClick = { detail = Detail.VITALS }) {
                    val sp = spo2.firstOrNull()
                    if (sp != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            CountUp(sp.pct.roundToInt(), fontSize = 22.sp, color = spo2Color(sp.pct), suffix = " %")
                        }
                        Hint("SpO₂ · ${Calc.fmtShortDate(sp.t)}")
                    } else {
                        Hint("Noch kein Blutsauerstoff")
                    }
                    Spacer(Modifier.weight(1f))
                    val st = skin[today] ?: skin[Calc.addDays(today, -1)]
                    val dev = st?.deviation
                    Text(
                        "🌡️ " + when {
                            dev != null -> "${if (dev >= 0) "+" else "−"}${Calc.fmtNum(abs(dev), 1)} °C"
                            else -> "–"
                        },
                        color = if (dev != null && dev >= 0.5) Bad else Color.Unspecified, fontWeight = FontWeight.SemiBold,
                    )
                    Hint("Hauttemperatur")
                }
            }
        }

        // ---------- Tabletten ----------
        Appear(i++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "💊 Tabletten", accent = PillColor, onClick = { detail = Detail.SUPPS }) {
                SuppTileContent()
            }
        }

        // ---------- Gewicht ----------
        Appear(i++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "⚖️ Gewicht", accent = WeightTeal, onClick = { detail = Detail.WEIGHT }) {
                WeightTileContent()
            }
        }

        // ---------- Training + Woche ----------
        Appear(i++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "🏍️ Training & Woche", onClick = { detail = Detail.TRAINING }) {
                if (d.sessions.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint("Heute noch kein Training.", Modifier.weight(1f))
                        Button(onClick = onOpenTraining) { Text("Starten") }
                    }
                } else {
                    d.sessions.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(s.type.emoji, fontSize = 20.sp)
                            Text("  ${s.title}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${Calc.fmtDuration(s.durationMs)} · ${Calc.fmtNum(s.kcal)} kcal", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                WeekBars(weekDays.map { (it.activeMs / 60_000).toInt() to it.sessions.map { s -> s.type } }, activeGoalMin.toInt())
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ---------- Details von unten ----------
    detail?.let { which ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                when (which) {
                    Detail.KCAL -> {
                        SectionCard("Kalorienbilanz") {
                            StatRow("Gegessen" to Calc.fmtNum(d.eaten), "Verbrannt" to Calc.fmtNum(d.burned))
                            Spacer(Modifier.height(8.dp))
                            StatRow("Grundumsatz" to Calc.fmtNum(d.bmr), "Training" to Calc.fmtNum(d.trainingKcal), "Gehen" to Calc.fmtNum(d.walkKcal))
                            Hint("Ziel ${goals.mode.label}: ${goals.mode.lo} bis +${goals.mode.hi} kcal", Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(10.dp))
                            FoodPhotoButtons(today)
                            TextButton(onClick = { showFood = true }) { Text("✏️ Von Hand eintragen") }
                        }
                    }
                    Detail.GOALS -> GoalsDetail(d, weekDays, goals) { showSleep = true }
                    Detail.SLEEP -> SectionCard("Letzte Nacht") {
                        val s = d.sleep
                        if (s == null) {
                            Hint("Noch kein Schlaf für heute Nacht.")
                            TextButton(onClick = { showSleep = true }) { Text("Schlaf eintragen") }
                        } else SleepSummary(s)
                    }
                    Detail.RECOVERY -> {
                        RecoveryCard()
                        if (rec == null) Hint("Erholungsdaten kommen nach den ersten Nächten mit der Uhr (Polar Nightly Recharge).")
                    }
                    Detail.HEART -> HeartTodayCard(onHistory = { detail = null; onOpenHeart() })
                    Detail.VITALS -> Spo2Card()
                    Detail.WEIGHT -> WeightCard()
                    Detail.SUPPS -> SuppCard()
                    Detail.WATER -> SectionCard("💧 Trinken") {
                        Text("${Calc.fmtNum(d.waterMl / 1000.0, 2)} l von ${Calc.fmtNum(d.waterGoal / 1000.0, 1)} l", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        WaterButtons(today)
                        val list = water.filter { it.t in today until Calc.addDays(today, 1) }.sortedBy { it.t }
                        if (list.isNotEmpty()) Hint(list.joinToString(" · ") { "${Calc.fmtClock(it.t)} ${it.ml} ml" }, Modifier.padding(top = 6.dp))
                    }
                    Detail.TRAINING -> SectionCard("Training heute") {
                        if (d.sessions.isEmpty()) {
                            Hint("Heute noch kein Training.")
                            Button(onClick = { detail = null; onOpenTraining() }) { Text("Training starten") }
                        } else d.sessions.forEach { s -> SessionRow(s) { detail = null; onOpenSession(s.id) } }
                        if (d.steps > 0) Hint("🚶 ${Analysis.fmtInt(d.steps)} Schritte · ca. ${Calc.fmtNum(d.walkKcal)} kcal fürs Gehen", Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }

    if (showFood) {
        FoodDialog(day = today, onDismiss = { showFood = false }, onSave = { Repo.addFood(it); showFood = false })
    }
    if (showSleep) SleepDialog(today) { showSleep = false }
}

/** Ziele heute und diese Woche (im Detail-Fenster der Ringe). */
@Composable
private fun GoalsDetail(d: de.endurofit.data.DaySummary, weekDays: List<de.endurofit.data.DaySummary>, goals: de.endurofit.data.Goals, onSleep: () -> Unit) {
    val today = d.day
    SectionCard("Ziele heute") {
        Analysis.dayGoals(d, goals).forEach { g ->
            when (g.emoji) {
                "💧" -> GoalRow(g) { WaterButtons(today) }
                "😴" -> GoalRow(g) { if (d.sleep == null) TextButton(onClick = onSleep) { Text("Schlaf eintragen") } }
                else -> GoalRow(g)
            }
        }
    }
    val thisWeek = Analysis.days(Calc.weekStart(today), 7).mapNotNull { day -> weekDays.firstOrNull { it.day == day } }
    val week = Analysis.week(thisWeek, goals)
    SectionCard("Diese Woche") {
        val activeMin = week.activeMs / 60_000
        GoalRow(GoalCheck("🔥", "Aktive Minuten", "$activeMin / ${week.activeTargetMin} min",
            (activeMin.toFloat() / week.activeTargetMin.coerceAtLeast(1)).coerceIn(0f, 1f), week.activeDone))
        GoalRow(GoalCheck("🏋️", "Krafttraining", "${week.strength} / ${week.strengthTarget}×",
            (week.strength.toFloat() / week.strengthTarget.coerceAtLeast(1)).coerceIn(0f, 1f), week.strengthDone))
        Hint(
            if (week.sickDays > 0) "Wegen ${week.sickDays} Kranktag(en) sind die Wochenziele anteilig verkleinert."
            else "Die WHO empfiehlt mindestens 150 Minuten Bewegung und 2× Krafttraining pro Woche.",
            Modifier.padding(top = 4.dp)
        )
    }
}

/** Schlafphasen als animierter Balken. */
@Composable
private fun SleepStageBar(deep: Long, rem: Long, light: Long, awake: Long) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val p by animateFloatAsState(if (started) 1f else 0f, tween(1100, easing = FastOutSlowInEasing), label = "stages")
    val total = (deep + rem + light + awake).coerceAtLeast(1).toFloat()
    Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        var x = 0f
        listOf(deep to Color(0xFF3949AB), rem to Color(0xFF8E24AA), light to Color(0xFF64B5F6), awake to Color(0xFFFFB74D)).forEach { (ms, c) ->
            val w = size.width * ms / total * p
            drawRect(c, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
    Hint("Tief · REM · Leicht · Wach", Modifier.padding(top = 2.dp))
}

/** Aktive Minuten der letzten 7 Tage als wachsende Balken; Trainingstage farbig markiert. */
@Composable
private fun WeekBars(days: List<Pair<Int, List<SessionType>>>, goal: Int) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val maxV = maxOf(days.maxOfOrNull { it.first } ?: 0, goal, 1)
    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { idx, (min, types) ->
            val h by animateFloatAsState(if (started) min.toFloat() / maxV else 0f, tween(800, delayMillis = idx * 70, easing = FastOutSlowInEasing), label = "wb$idx")
            val color = when {
                SessionType.ENDURO in types -> Color(0xFFFF6D00)
                SessionType.GYM in types -> ActiveGreen
                min >= goal -> Color(0xFF26C6DA)
                else -> Color(0xFF555555)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(54.dp)) {
                    val gy = size.height * (1f - goal.toFloat() / maxV)
                    drawLine(Color(0x33FFFFFF), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                    val bh = (size.height * h).coerceAtLeast(3f)
                    drawRoundRect(color, Offset(size.width * 0.15f, size.height - bh), Size(size.width * 0.7f, bh), CornerRadius(6f))
                }
                val dayIdx = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, idx - 6) }.get(Calendar.DAY_OF_WEEK)
                Text(listOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")[dayIdx - 1], style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Hint("Aktive Minuten der letzten 7 Tage · Linie = Tagesziel · orange = Enduro, grün = Kraft", Modifier.padding(top = 2.dp))
}
