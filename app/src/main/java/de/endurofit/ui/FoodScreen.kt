@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.FoodEntry
import de.endurofit.data.Repo
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

private val KcalC = Color(0xFFFFCA28)
private val ProtC = Color(0xFFEF5350)
private val CarbC = Color(0xFF42A5F5)
private val FatC = Color(0xFFAB47BC)

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
    var deleted by remember { mutableStateOf<FoodEntry?>(null) }
    var customWater by remember { mutableStateOf(false) }
    // Text, der aus ChatGPT o. Ä. an EnduroFit geteilt wurde
    val sharedText by SharedFoodText.text.collectAsState()
    LaunchedEffect(sharedText) {
        sharedText?.let { pasteText = it; paste = true; SharedFoodText.text.value = null }
    }
    LaunchedEffect(deleted) { if (deleted != null) { delay(5000); deleted = null } }

    val next = Calc.addDays(day, 1)
    val entries = food.filter { it.t in day until next }.sortedBy { it.t }
    val recent = remember(food) { food.distinctBy { it.name.lowercase() }.take(15) }
    val summary = Analysis.day(day, sessions, food, water, steps, sleep, profile, goals)
    val target = summary.burned + (goals.mode.lo + goals.mode.hi) / 2.0
    val protGoal = summary.proteinGoal
    val carbGoal = target * 0.45 / 4
    val fatGoal = target * 0.30 / 9
    val carbs = entries.sumOf { it.carbs }
    val fat = entries.sumOf { it.fat }
    var i = 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        // ---------- Tag wählen ----------
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { day = Calc.addDays(day, -1) }) { Text("‹", fontSize = 26.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (day == today) "Heute" else Calc.fmtDate(day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (day != today) Text("zurück zu heute", style = MaterialTheme.typography.labelSmall, color = Accent, modifier = Modifier.clickable { day = today })
            }
            IconButton(onClick = { day = Calc.addDays(day, 1) }, enabled = day < today) { Text("›", fontSize = 26.sp) }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- Makro-Ringe ----------
        Appear(i++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "Kalorien & Nährwerte", accent = KcalC) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        Rings(
                            listOf(
                                RingData((summary.eaten / target).toFloat(), KcalC),
                                RingData((summary.protein / protGoal.coerceAtLeast(1.0)).toFloat(), ProtC),
                                RingData((carbs / carbGoal.coerceAtLeast(1.0)).toFloat(), CarbC),
                                RingData((fat / fatGoal.coerceAtLeast(1.0)).toFloat(), FatC),
                            ),
                            Modifier.size(132.dp), stroke = 11.dp,
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CountUp(summary.eaten.roundToInt(), fontSize = 18.sp)
                            Text("kcal", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        RingLegend(KcalC, "Kalorien", "${Calc.fmtNum(summary.eaten)} / ${Calc.fmtNum(target)}")
                        RingLegend(ProtC, "Eiweiß", "${Calc.fmtNum(summary.protein)} / ${Calc.fmtNum(protGoal)} g")
                        RingLegend(CarbC, "Kohlenh.", "${Calc.fmtNum(carbs)} / ${Calc.fmtNum(carbGoal)} g")
                        RingLegend(FatC, "Fett", "${Calc.fmtNum(fat)} / ${Calc.fmtNum(fatGoal)} g")
                        val bal = summary.eaten - summary.burned
                        Hint(
                            "Bilanz " + (if (bal < 0) "−" else "+") + Calc.fmtNum(abs(bal)) + " kcal · verbrannt ${Calc.fmtNum(summary.burned)}",
                            Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // ---------- Eintragen ----------
        Appear(i++) {
            Column(Modifier.padding(bottom = 12.dp)) {
                FoodPhotoButtons(day, asTiles = true, onManual = { prefill = null; dialog = true }, onPaste = { pasteText = ""; paste = true })
                if (recent.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                        items(recent) { f ->
                            SuggestionChip(
                                onClick = { prefill = f; dialog = true },
                                label = { Text("${if (f.fromPhoto) "📷 " else ""}${f.name} · ${Calc.fmtNum(f.kcal)}", maxLines = 1) },
                                shape = RoundedCornerShape(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---------- Trinken ----------
        Appear(i++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "💧 Trinken", accent = CarbC) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaterGlass(summary.waterMl.toFloat() / summary.waterGoal.coerceAtLeast(1), Modifier.width(44.dp).height(70.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        CountUp(summary.waterMl, fontSize = 22.sp, suffix = " ml")
                        Hint("von ${Calc.fmtNum(summary.waterGoal / 1000.0, 1)} l · " +
                            "${(summary.waterMl * 100f / summary.waterGoal.coerceAtLeast(1)).roundToInt()} %")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val t = if (day == today) System.currentTimeMillis() else day + 12 * 3_600_000L
                    listOf(250, 330, 500).forEach { ml ->
                        FilledTonalButton(onClick = { Repo.addWater(ml, t) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("+$ml") }
                    }
                    OutlinedButton(onClick = { customWater = !customWater }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("…") }
                }
                AnimatedVisibility(customWater) {
                    var custom by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        NumField("Menge in ml", custom, { custom = it }, Modifier.weight(1f), decimal = false)
                        TextButton(enabled = (custom.toIntOrNull() ?: 0) > 0, onClick = {
                            Repo.addWater(custom.toInt(), if (day == today) System.currentTimeMillis() else day + 12 * 3_600_000L)
                            custom = ""; customWater = false
                        }) { Text("Hinzufügen") }
                    }
                }
                val dayWater = water.filter { it.t in day until next }.sortedBy { it.t }
                if (dayWater.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        items(dayWater, key = { it.id }) { w ->
                            InputChip(
                                selected = false, onClick = { Repo.deleteWater(w.id) },
                                label = { Text("${Calc.fmtClock(w.t)} · ${w.ml}") }, trailingIcon = { Text("✕", fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }
        }

        // ---------- Mahlzeiten ----------
        if (entries.isEmpty()) {
            Appear(i++) {
                Tile(Modifier.fillMaxWidth()) {
                    Text("🍽️  Noch nichts eingetragen", fontWeight = FontWeight.SemiBold)
                    Hint("Fotografiere dein Essen oder tippe oben auf „Von Hand“.")
                }
            }
        }
        mealGroups(entries).forEach { (meal, list) ->
            Appear(i++) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp, top = 4.dp)) {
                        Text(meal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${Calc.fmtNum(list.sumOf { it.kcal })} kcal", color = KcalC, fontWeight = FontWeight.SemiBold)
                    }
                    list.forEach { e ->
                        key(e.id) {
                            SwipeFoodRow(e, onClick = { editing = e }, onDelete = { Repo.deleteFood(e.id); deleted = e })
                        }
                    }
                }
            }
        }
        if (entries.isNotEmpty()) Hint("Antippen zum Bearbeiten · nach links wischen zum Löschen", Modifier.padding(top = 2.dp, bottom = 8.dp))

        AnimatedVisibility(deleted != null, enter = fadeIn(), exit = fadeOut()) {
            val d = deleted
            if (d != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF333333)).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("„${d.name}“ gelöscht", Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { Repo.addFood(d); deleted = null }) { Text("Rückgängig") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (dialog) {
        FoodDialog(day = day, prefill = prefill, onDismiss = { dialog = false }, onSave = { Repo.addFood(it); dialog = false })
    }
    editing?.let { e ->
        FoodDialog(
            day = Calc.dayStart(e.t), editing = e,
            onDismiss = { editing = null },
            onSave = { Repo.addFood(it); editing = null },
            onDelete = { Repo.deleteFood(e.id); editing = null },
        )
    }
    if (paste) {
        PasteFoodDialog(initial = pasteText, onDismiss = { paste = false }, onParsed = { entry -> paste = false; prefill = entry; dialog = true })
    }
}

/** Einträge nach Tageszeit gruppieren. */
private fun mealGroups(entries: List<FoodEntry>): List<Pair<String, List<FoodEntry>>> {
    fun meal(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return when {
            m < 10 * 60 + 30 -> "☀️ Frühstück"
            m < 14 * 60 + 30 -> "🍝 Mittag"
            m < 17 * 60 + 30 -> "🍎 Nachmittag"
            else -> "🌙 Abend"
        }
    }
    val order = listOf("☀️ Frühstück", "🍝 Mittag", "🍎 Nachmittag", "🌙 Abend")
    return entries.groupBy { meal(it.t) }.toList().sortedBy { order.indexOf(it.first) }
}

/** Eintrag als Kachel – nach links wischen löscht ihn. */
@Composable
private fun SwipeFoodRow(e: FoodEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.padding(bottom = 8.dp),
        backgroundContent = {
            val c by animateColorAsState(if (state.targetValue == SwipeToDismissBoxValue.EndToStart) Bad else Color(0xFF3A1F1F), label = "swipe")
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(c).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text("🗑️", fontSize = 22.sp)
            }
        },
    ) {
        Tile(Modifier.fillMaxWidth(), onClick = onClick) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(KcalC.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Text(if (e.fromPhoto) "📷" else "🍽️", fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    val macro = if (e.protein + e.carbs + e.fat > 0)
                        " · E ${Calc.fmtNum(e.protein)} · K ${Calc.fmtNum(e.carbs)} · F ${Calc.fmtNum(e.fat)}" else ""
                    Hint(Calc.fmtClock(e.t) + macro)
                }
                Text("${Calc.fmtNum(e.kcal)}", fontWeight = FontWeight.Bold, color = KcalC)
                Text(" kcal", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
