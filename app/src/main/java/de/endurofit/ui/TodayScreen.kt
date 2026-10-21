package de.endurofit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.GoalCheck
import de.endurofit.data.Repo
import de.endurofit.hr.Recorder
import kotlin.math.abs

@Composable
fun TodayScreen(onOpenTraining: () -> Unit, onOpenSession: (String) -> Unit) {
    val sessions by Repo.sessions.collectAsState()
    val food by Repo.food.collectAsState()
    val water by Repo.water.collectAsState()
    val steps by Repo.steps.collectAsState()
    val sleep by Repo.sleep.collectAsState()
    val profile by Repo.profile.collectAsState()
    val goals by Repo.goals.collectAsState()
    val live by Recorder.live.collectAsState()
    var showFood by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    val today = Calc.dayStart(System.currentTimeMillis())
    val d = Analysis.day(today, sessions, food, water, steps, sleep, profile, goals)
    val checks = Analysis.dayGoals(d, goals)

    val weekStart = Calc.weekStart(today)
    val weekDays = Analysis.days(weekStart, 7).map { Analysis.day(it, sessions, food, water, steps, sleep, profile, goals) }
    val week = Analysis.week(weekDays, goals)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("Heute · " + Calc.fmtDate(today))

        if (live != null) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔴 Aufnahme läuft: ${live!!.type.label}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Button(onClick = onOpenTraining) { Text("Öffnen") }
                }
            }
        }

        // ---------- Kalorien ----------
        SectionCard("Kalorienbilanz") {
            val deficit = d.balance < 0
            Text(
                (if (deficit) "−" else "+") + Calc.fmtNum(abs(d.balance)) + " kcal",
                fontSize = 34.sp, fontWeight = FontWeight.Bold,
                color = if (d.balance >= goals.mode.lo && d.balance <= goals.mode.hi) Good else Accent
            )
            Hint("Ziel ${goals.mode.label}: ${goals.mode.lo} bis +${goals.mode.hi} kcal · Grundumsatz gilt für den ganzen Tag")
            Spacer(Modifier.height(12.dp))
            StatRow(
                "Gegessen" to Calc.fmtNum(d.eaten),
                "Verbrannt" to Calc.fmtNum(d.burned),
            )
            Spacer(Modifier.height(8.dp))
            StatRow(
                "Grundumsatz" to Calc.fmtNum(d.bmr),
                "Training" to Calc.fmtNum(d.trainingKcal),
                "Gehen" to Calc.fmtNum(d.walkKcal),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showFood = true }) { Text("+ Essen eintragen") }
        }

        // ---------- Tagesziele ----------
        SectionCard("Ziele heute") {
            checks.forEach { g ->
                when (g.emoji) {
                    "💧" -> GoalRow(g) { WaterButtons(today) }
                    "😴" -> GoalRow(g) {
                        if (d.sleep == null) TextButton(onClick = { showSleep = true }) { Text("Schlaf eintragen") }
                    }
                    else -> GoalRow(g)
                }
            }
            if (d.steps == 0) {
                Hint("Schritte und Schlaf kommen von der Uhr über den Suunto-Import.", Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(6.dp))
            SuuntoImportSection(compact = true)
        }

        // ---------- Wochenziele ----------
        SectionCard("Diese Woche") {
            val activeMin = week.activeMs / 60_000
            GoalRow(
                GoalCheck(
                    "🔥", "Aktive Minuten", "$activeMin / ${goals.weeklyActiveMin} min",
                    (activeMin.toFloat() / goals.weeklyActiveMin).coerceIn(0f, 1f), week.activeDone
                )
            )
            GoalRow(
                GoalCheck(
                    "🏋️", "Krafttraining", "${week.strength} / ${goals.weeklyStrength}×",
                    (week.strength.toFloat() / goals.weeklyStrength.coerceAtLeast(1)).coerceIn(0f, 1f), week.strengthDone
                )
            )
            Hint("Die WHO empfiehlt mindestens 150 Minuten Bewegung und 2× Krafttraining pro Woche.", Modifier.padding(top = 4.dp))
        }

        // ---------- Schlaf ----------
        SectionCard("Letzte Nacht") {
            val s = d.sleep
            if (s == null) {
                Hint("Noch kein Schlaf für heute Nacht.")
                TextButton(onClick = { showSleep = true }) { Text("Schlaf eintragen") }
            } else {
                SleepSummary(s)
            }
        }

        // ---------- Training ----------
        SectionCard("Training heute") {
            if (d.sessions.isEmpty()) {
                Hint("Heute noch kein Training.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenTraining) { Text("Training starten") }
            } else {
                d.sessions.forEach { s -> SessionRow(s) { onOpenSession(s.id) } }
            }
            if (d.steps > 0) {
                Hint(
                    "🚶 ${Analysis.fmtInt(d.steps)} Schritte · ca. ${Calc.fmtNum(d.walkKcal)} kcal fürs Gehen (ohne Trainingszeiten)",
                    Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    if (showFood) {
        FoodDialog(day = today, onDismiss = { showFood = false }, onSave = { Repo.addFood(it); showFood = false })
    }
    if (showSleep) SleepDialog(today) { showSleep = false }
}
