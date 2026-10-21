@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.endurofit.data.Calc
import de.endurofit.data.FoodEntry
import de.endurofit.data.Repo

@Composable
fun FoodScreen() {
    val food by Repo.food.collectAsState()
    val sessions by Repo.sessions.collectAsState()
    val profile by Repo.profile.collectAsState()
    val today = Calc.dayStart(System.currentTimeMillis())
    var day by rememberSaveable { mutableLongStateOf(today) }
    var dialog by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<FoodEntry?>(null) }

    val next = Calc.addDays(day, 1)
    val entries = food.filter { it.t in day until next }.sortedBy { it.t }
    val recent = remember(food) { food.distinctBy { it.name.lowercase() }.take(15) }

    val eaten = entries.sumOf { it.kcal }
    val burned = Calc.bmr(profile) + Calc.sessionsOfDay(sessions, day).sumOf { it.kcal }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day = Calc.addDays(day, -1) }) { Text("◀") }
            Text(
                if (day == today) "Heute" else Calc.fmtDate(day),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { day = Calc.addDays(day, 1) }, enabled = day < today) { Text("▶") }
        }

        SectionCard("Summe") {
            StatRow(
                "kcal" to Calc.fmtNum(eaten),
                "Eiweiß g" to Calc.fmtNum(entries.sumOf { it.protein }),
                "Kohlenh. g" to Calc.fmtNum(entries.sumOf { it.carbs }),
                "Fett g" to Calc.fmtNum(entries.sumOf { it.fat }),
            )
            Spacer(Modifier.height(8.dp))
            val bal = eaten - burned
            Hint(
                "Verbrannt (Grundumsatz + Training): ${Calc.fmtNum(burned)} kcal · Bilanz " +
                    (if (bal < 0) "−" else "+") + Calc.fmtNum(kotlin.math.abs(bal)) + " kcal"
            )
        }

        Button(onClick = { prefill = null; dialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Essen eintragen")
        }
        Spacer(Modifier.height(8.dp))

        if (recent.isNotEmpty()) {
            Hint("Schnell wieder eintragen")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(recent) { f ->
                    AssistChip(
                        onClick = { prefill = f; dialog = true },
                        label = { Text("${f.name} · ${Calc.fmtNum(f.kcal)}") }
                    )
                }
            }
        }

        SectionCard("Einträge") {
            if (entries.isEmpty()) Hint("Noch nichts eingetragen.")
            entries.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(Calc.fmtClock(e.t), Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.weight(1f)) {
                        Text(e.name)
                        if (e.protein + e.carbs + e.fat > 0) {
                            Hint("E ${Calc.fmtNum(e.protein)} · K ${Calc.fmtNum(e.carbs)} · F ${Calc.fmtNum(e.fat)} g")
                        }
                    }
                    Text("${Calc.fmtNum(e.kcal)} kcal", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { Repo.deleteFood(e.id) }) { Text("✕") }
                }
            }
        }
    }

    if (dialog) {
        FoodDialog(
            day = day,
            prefill = prefill,
            onDismiss = { dialog = false },
            onSave = { Repo.addFood(it); dialog = false },
        )
    }
}
