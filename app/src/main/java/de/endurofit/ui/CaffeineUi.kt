package de.endurofit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Caffeine
import de.endurofit.data.CaffeineDrink
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow

val CoffeeBrown = Color(0xFFA1887F)

/** Aus dem Widget: Getränkeauswahl öffnen */
object CaffeineNav {
    val pick = MutableStateFlow(false)
}

private fun levelColor(l: Int) = when (l) { 2 -> Bad; 1 -> Color(0xFFFFA726); else -> Good }

/** Kurzinfo für die Kachel auf „Heute“. */
@Composable
fun CaffeineTileContent() {
    val list by Repo.caffeine.collectAsState()
    val sleep by Repo.sleep.collectAsState()
    val today = Calc.dayStart(System.currentTimeMillis())
    val mg = Caffeine.todayMg(list, today)
    val v = Caffeine.verdict(list, today, sleep)
    Row(verticalAlignment = Alignment.Bottom) {
        CountUp(mg.toInt(), fontSize = 24.sp, color = levelColor(v.level))
        Text(" / 400 mg", Modifier.padding(bottom = 3.dp))
        Spacer(Modifier.weight(1f))
        Text(v.title, color = levelColor(v.level), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
    AnimatedBar((mg / Caffeine.DAY_LIMIT).toFloat().coerceAtMost(1f), levelColor(v.level), height = 7.dp)
}

/** Kaffee & Energy-Drinks: eintragen, Tagesmenge, Rest beim Schlafengehen, Warnung bei zu viel. */
@Composable
fun CaffeineCard(collapsed: Boolean = false) {
    val list by Repo.caffeine.collectAsState()
    val sleep by Repo.sleep.collectAsState()
    val now = System.currentTimeMillis()
    val today = Calc.dayStart(now)
    val mg = Caffeine.todayMg(list, today)
    val v = Caffeine.verdict(list, today, sleep)
    var warn by remember { mutableStateOf<Caffeine.Verdict?>(null) }
    var custom by remember { mutableStateOf(false) }
    val pick by CaffeineNav.pick.collectAsState()
    LaunchedEffect(pick) { if (pick) { custom = true; CaffeineNav.pick.value = false } }

    fun add(d: CaffeineDrink, mgOverride: Double = d.mg, ml: Int = d.ml) {
        val before = Caffeine.verdict(Repo.caffeine.value, today, Repo.sleep.value).level
        Repo.addCaffeine(d, mg = mgOverride, ml = ml)
        val after = Caffeine.verdict(Repo.caffeine.value, today, Repo.sleep.value)
        if (after.level == 2 || (after.level == 1 && before == 0)) warn = after
    }

    SectionCard(
        "☕ Kaffee & Energy", collapsible = collapsed, startOpen = !collapsed,
        subtitle = if (collapsed) "${mg.toInt()} von 400 mg · ${v.title}" else null,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(mg.toInt(), fontSize = 34.sp, color = levelColor(v.level))
            Text(" mg Koffein heute", Modifier.padding(bottom = 6.dp))
        }
        AnimatedBar((mg / Caffeine.DAY_LIMIT).toFloat().coerceAtMost(1f), levelColor(v.level), height = 9.dp)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("400 mg = Tageslimit", style = MaterialTheme.typography.labelSmall)
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(4.dp))
                .background(levelColor(v.level).copy(alpha = 0.12f)).padding(10.dp)
        ) {
            Text((if (v.level == 2) "⚠️ " else if (v.level == 1) "☝️ " else "✓ ") + v.title, fontWeight = FontWeight.Bold, color = levelColor(v.level))
            Text(v.text, style = MaterialTheme.typography.bodySmall)
        }

        // ---------- Schnell eintragen ----------
        Spacer(Modifier.height(10.dp))
        Text("Eintragen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            items(Caffeine.drinks) { d ->
                Column(
                    Modifier.width(92.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { add(d) }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.emoji, fontSize = 24.sp)
                    Text(d.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, fontWeight = FontWeight.SemiBold)
                    Text("${d.mg.toInt()} mg", style = MaterialTheme.typography.labelSmall, color = CoffeeBrown)
                }
            }
        }
        TextButton(onClick = { custom = true }) { Text("+ Anderes Getränk / eigene Menge") }

        // ---------- Heute ----------
        val todays = list.filter { it.t >= today }.sortedBy { it.t }
        todays.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Calc.fmtClock(e.t)}  ${e.emoji} ${e.name}", Modifier.weight(1f))
                Text("${e.mg.toInt()} mg", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { Repo.deleteCaffeine(e.id) }) { Text("✕") }
            }
        }

        // ---------- Verlauf im Körper ----------
        if (list.any { it.t > today - 86_400_000L }) {
            Spacer(Modifier.height(8.dp))
            Text("Koffein im Körper heute", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            CaffeineCurve(today)
        }

        // ---------- Woche ----------
        val week = (6 downTo 0).map { Calc.addDays(today, -it) }.map { it to Caffeine.todayMg(list, it) }
        if (week.any { it.second > 0 }) {
            Spacer(Modifier.height(8.dp))
            Text("Letzte 7 Tage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                week.forEach { (d, m) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val c = if (m > Caffeine.DAY_LIMIT) Bad else if (m > Caffeine.DAY_WARN) Color(0xFFFFA726) else CoffeeBrown
                        Box(Modifier.width(14.dp).height((44 * (m / 500.0).coerceAtMost(1.0)).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(4.dp)).background(c))
                        Text(Calc.fmtShortDate(d).take(2), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Hint("Richtwerte nach EFSA: bis 400 mg am Tag und 200 mg auf einmal gelten für gesunde Erwachsene als unbedenklich. Koffein baut sich nur langsam ab (Halbwertszeit ca. 5 Std.). Kaffee & Tee zählen auch als Trinken, Zucker aus Energy-Drinks als Essen.", Modifier.padding(top = 8.dp))
    }

    if (custom) CustomDrinkDialog(onDismiss = { custom = false }) { d, m, ml -> custom = false; add(d, m, ml) }

    warn?.let { w ->
        AlertDialog(
            onDismissRequest = { warn = null },
            title = { Text((if (w.level == 2) "⚠️ " else "☝️ ") + w.title) },
            text = { Text(w.text) },
            confirmButton = { TextButton(onClick = { warn = null }) { Text("Verstanden") } },
        )
    }
}

/** Kurve: wie viel Koffein im Laufe des Tages im Körper ist, mit Schlafenszeit. */
@Composable
private fun CaffeineCurve(day: Long) {
    val list by Repo.caffeine.collectAsState()
    val sleep by Repo.sleep.collectAsState()
    val bed = Caffeine.bedtime(day, sleep)
    val from = day + 6 * 3_600_000L
    val to = maxOf(bed + 2 * 3_600_000L, day + 24 * 3_600_000L)
    val now = System.currentTimeMillis()
    val pts = (0..120).map { i -> val t = from + (to - from) * i / 120; t to Caffeine.remainingAt(list, t) }
    val maxV = maxOf(pts.maxOf { it.second }, 150.0)
    Canvas(Modifier.fillMaxWidth().height(90.dp).padding(top = 4.dp)) {
        val w = size.width; val h = size.height
        fun x(t: Long) = (t - from).toFloat() / (to - from) * w
        fun y(v: Double) = (h - v / maxV * h).toFloat()
        // Grenze „stört den Schlaf“
        val sy = y(Caffeine.SLEEP_REST)
        drawLine(Color(0x55FFFFFF), Offset(0f, sy), Offset(w, sy), 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        // Fläche
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h)
            pts.forEach { (t, v) -> lineTo(x(t), y(v)) }
            lineTo(w, h); close()
        }
        drawPath(path, CoffeeBrown.copy(alpha = 0.35f))
        // Schlafenszeit
        val bx = x(bed)
        drawRoundRect(Color(0xFF5C6BC0).copy(alpha = 0.25f), Offset(bx, 0f), Size(w - bx, h), CornerRadius(6f))
        if (now in from..to) drawLine(Color.White, Offset(x(now), 0f), Offset(x(now), h), 2f)
    }
    Row(Modifier.fillMaxWidth()) {
        Text("6 Uhr", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("🌙 ${Calc.fmtClock(bed)}", style = MaterialTheme.typography.labelSmall)
    }
    Hint("Gestrichelt: ab hier stört Koffein den Schlaf spürbar. Blau: deine übliche Schlafenszeit.")
}

@Composable
private fun CustomDrinkDialog(onDismiss: () -> Unit, onAdd: (CaffeineDrink, Double, Int) -> Unit) {
    var sel by remember { mutableStateOf(Caffeine.drinks[1]) }
    var mg by remember { mutableStateOf(sel.mg.toInt().toString()) }
    var ml by remember { mutableStateOf(sel.ml.toString()) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Getränk eintragen") },
        text = {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Caffeine.drinks) { d ->
                        FilterChip(sel == d, onClick = { sel = d; mg = d.mg.toInt().toString(); ml = d.ml.toString() }, label = { Text(d.emoji + " " + d.name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    NumField("Koffein (mg)", mg, { mg = it }, Modifier.weight(1f), decimal = false)
                    NumField("Menge (ml)", ml, { ml = it }, Modifier.weight(1f), decimal = false)
                }
                Hint("Energy-Drinks haben meist 32 mg pro 100 ml, Filterkaffee ca. 40–60 mg pro 100 ml.", Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = mg.toDoubleOrNull() ?: sel.mg
                val v = ml.toIntOrNull() ?: sel.ml
                val scale = if (sel.ml > 0) v.toDouble() / sel.ml else 1.0
                onAdd(sel.copy(name = name.ifBlank { sel.name }, kcal = sel.kcal * scale, carbs = sel.carbs * scale), m, v)
            }) { Text("Eintragen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
