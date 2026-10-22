@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.FoodEntry
import de.endurofit.data.Repo

@Composable
fun FoodScreen() {
    val food by Repo.food.collectAsState()
    val sessions by Repo.sessions.collectAsState()
    val profile by Repo.profile.collectAsState()
    val water by Repo.water.collectAsState()
    val steps by Repo.steps.collectAsState()
    val sleep by Repo.sleep.collectAsState()
    val goals by Repo.goals.collectAsState()
    val today = Calc.dayStart(System.currentTimeMillis())
    var day by rememberSaveable { mutableLongStateOf(today) }
    var dialog by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<FoodEntry?>(null) }
    var editing by remember { mutableStateOf<FoodEntry?>(null) }
    var paste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    // Text, der aus ChatGPT o. Ä. an EnduroFit geteilt wurde
    val sharedText by SharedFoodText.text.collectAsState()
    LaunchedEffect(sharedText) {
        sharedText?.let { pasteText = it; paste = true; SharedFoodText.text.value = null }
    }

    val next = Calc.addDays(day, 1)
    val entries = food.filter { it.t in day until next }.sortedBy { it.t }
    val recent = remember(food) { food.distinctBy { it.name.lowercase() }.take(15) }

    val summary = Analysis.day(day, sessions, food, water, steps, sleep, profile, goals)
    val eaten = summary.eaten
    val burned = summary.burned
    val dayWater = water.filter { it.t in day until next }.sortedBy { it.t }

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
                "Verbrannt (Grundumsatz + Training + Gehen): ${Calc.fmtNum(burned)} kcal · Bilanz " +
                    (if (bal < 0) "−" else "+") + Calc.fmtNum(kotlin.math.abs(bal)) + " kcal"
            )
        }

        SectionCard("💧 Trinken") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Calc.fmtNum(summary.waterMl / 1000.0, 2) + " l", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("  von ${Calc.fmtNum(summary.waterGoal / 1000.0, 1)} l", Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(6.dp))
            ProgressBar(summary.waterMl.toFloat() / summary.waterGoal.coerceAtLeast(1), if (summary.waterMl >= summary.waterGoal) Good else Color(0xFF42A5F5))
            Spacer(Modifier.height(8.dp))
            WaterButtons(day)
            var custom by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumField("Andere Menge (ml)", custom, { custom = it }, Modifier.weight(1f), decimal = false)
                TextButton(enabled = (custom.toIntOrNull() ?: 0) > 0, onClick = {
                    val now = System.currentTimeMillis()
                    Repo.addWater(custom.toInt(), if (day == today) now else day + 12 * 3_600_000L)
                    custom = ""
                }) { Text("Hinzufügen") }
            }
            if (dayWater.isNotEmpty()) {
                Hint(dayWater.joinToString(" · ") { "${Calc.fmtClock(it.t)} ${it.ml} ml" }, Modifier.padding(top = 4.dp))
            }
            Hint("Ziel: 35 ml pro kg Körpergewicht + 0,5 l je Trainingsstunde.", Modifier.padding(top = 4.dp))
        }

        FoodPhotoButtons(day)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { prefill = null; dialog = true }, modifier = Modifier.weight(1f)) {
                Text("✏️ Von Hand")
            }
            OutlinedButton(onClick = { pasteText = ""; paste = true }, modifier = Modifier.weight(1f)) {
                Text("📋 Text einfügen")
            }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 6.dp)
                ) {
                    Text(Calc.fmtClock(e.t), Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.weight(1f)) {
                        Text((if (e.fromPhoto) "📷 " else "") + e.name)
                        if (e.protein + e.carbs + e.fat > 0) {
                            Hint("E ${Calc.fmtNum(e.protein)} · K ${Calc.fmtNum(e.carbs)} · F ${Calc.fmtNum(e.fat)} g")
                        }
                    }
                    Text("${Calc.fmtNum(e.kcal)} kcal", fontWeight = FontWeight.SemiBold)
                    Text("  ✎", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entries.isNotEmpty()) Hint("Eintrag antippen zum Bearbeiten oder Löschen.", Modifier.padding(top = 4.dp))
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
    editing?.let { e ->
        FoodDialog(
            day = Calc.dayStart(e.t),
            editing = e,
            onDismiss = { editing = null },
            onSave = { Repo.addFood(it); editing = null },
            onDelete = { Repo.deleteFood(e.id); editing = null },
        )
    }
    if (paste) {
        PasteFoodDialog(
            initial = pasteText,
            onDismiss = { paste = false },
            onParsed = { entry -> paste = false; prefill = entry; dialog = true },
        )
    }
}
