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
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import de.endurofit.hr.Recorder
import kotlin.math.abs

@Composable
fun TodayScreen(onOpenTraining: () -> Unit, onOpenSession: (String) -> Unit) {
    val sessions by Repo.sessions.collectAsState()
    val food by Repo.food.collectAsState()
    val profile by Repo.profile.collectAsState()
    val live by Recorder.live.collectAsState()
    var showFood by remember { mutableStateOf(false) }

    val today = Calc.dayStart(System.currentTimeMillis())
    val tomorrow = Calc.addDays(today, 1)
    val todaySessions = Calc.sessionsOfDay(sessions, today)
    val todayFood = food.filter { it.t in today until tomorrow }

    val eaten = todayFood.sumOf { it.kcal }
    val protein = todayFood.sumOf { it.protein }
    val bmr = Calc.bmr(profile)
    val training = todaySessions.sumOf { it.kcal }
    val burned = bmr + training
    val balance = eaten - burned

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

        SectionCard("Kalorienbilanz") {
            val deficit = balance < 0
            Text(
                (if (deficit) "−" else "+") + Calc.fmtNum(abs(balance)) + " kcal",
                fontSize = 34.sp, fontWeight = FontWeight.Bold,
                color = if (deficit) Good else Accent
            )
            Hint(if (deficit) "Defizit – du hast mehr verbrannt als gegessen" else "Überschuss – du hast mehr gegessen als verbrannt")
            Spacer(Modifier.height(12.dp))
            StatRow(
                "Gegessen" to Calc.fmtNum(eaten),
                "Grundumsatz" to Calc.fmtNum(bmr),
                "Training" to Calc.fmtNum(training),
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (burned <= 0) 0f else (eaten / burned).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Hint("Eiweiß heute: ${Calc.fmtNum(protein)} g · Grundumsatz gilt für den ganzen Tag")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showFood = true }) { Text("+ Essen eintragen") }
        }

        SectionCard("Training heute") {
            if (todaySessions.isEmpty()) {
                Hint("Heute noch kein Training.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenTraining) { Text("Training starten") }
            } else {
                todaySessions.forEach { s -> SessionRow(s) { onOpenSession(s.id) } }
            }
        }

        // Wochenüberblick
        val weekStart = Calc.addDays(today, -6)
        val week = (0..6).flatMap { Calc.sessionsOfDay(sessions, Calc.addDays(weekStart, it)) }
        val hard = week.sumOf { s ->
            val z = Calc.timeInZones(s.samples, profile.effectiveMaxHr)
            z[4] + z[5]
        }
        SectionCard("Letzte 7 Tage") {
            StatRow(
                "Einheiten" to week.size.toString(),
                "Trainingszeit" to Calc.fmtDuration(week.sumOf { it.durationMs }),
                "kcal Training" to Calc.fmtNum(week.sumOf { it.kcal }),
            )
            Spacer(Modifier.height(8.dp))
            StatRow(
                "Zeit in Zone 4–5" to Calc.fmtDuration(hard),
                "Gym-Sätze" to week.sumOf { it.sets.size }.toString(),
                "Ø kcal gegessen" to Calc.fmtNum(
                    food.filter { it.t in weekStart until tomorrow }.sumOf { it.kcal } / 7.0
                ),
            )
        }
    }

    if (showFood) {
        FoodDialog(day = today, onDismiss = { showFood = false }, onSave = { Repo.addFood(it); showFood = false })
    }
}
