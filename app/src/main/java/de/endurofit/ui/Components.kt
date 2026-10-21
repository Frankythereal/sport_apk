@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Calc
import de.endurofit.data.FoodEntry
import de.endurofit.data.Profile
import de.endurofit.data.Session

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(bottom = 12.dp)
    )
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, value) -> Stat(label, value, Modifier.weight(1f)) }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Balken für die Zeit in den Pulszonen. */
@Composable
fun ZoneBars(times: LongArray, profile: Profile) {
    val total = times.sum().coerceAtLeast(1)
    val maxHr = profile.effectiveMaxHr
    val lows = listOf(0.0, 0.5, 0.6, 0.7, 0.8, 0.9)
    for (z in 5 downTo 1) {
        val frac = times[z].toFloat() / total
        val from = (maxHr * lows[z]).toInt()
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Z$z", Modifier.width(28.dp), fontWeight = FontWeight.Bold, color = zoneColor(z))
            Box(
                Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(7.dp)).background(zoneColor(z))
                )
            }
            Text(
                "${Calc.fmtDuration(times[z])} · ab $from",
                Modifier.width(110.dp).padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun SessionRow(s: Session, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.type.emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontWeight = FontWeight.SemiBold)
                Hint(Calc.fmtDateTime(s.start) + if (s.source == de.endurofit.data.SOURCE_SUUNTO) " · Suunto" else "")
                val parts = mutableListOf(Calc.fmtDuration(s.durationMs))
                if (s.avgBpm > 0) parts.add("Ø ${s.avgBpm} / max ${s.maxBpm} bpm")
                if (s.sets.isNotEmpty()) parts.add("${s.sets.size} Sätze")
                Hint(parts.joinToString(" · "))
            }
            Text("${Calc.fmtNum(s.kcal)} kcal", fontWeight = FontWeight.Bold, color = Accent)
        }
    }
}

/** Dialog zum Eintragen einer Mahlzeit. */
@Composable
fun FoodDialog(
    day: Long,
    prefill: FoodEntry? = null,
    onDismiss: () -> Unit,
    onSave: (FoodEntry) -> Unit,
) {
    var name by remember { mutableStateOf(prefill?.name ?: "") }
    var kcal by remember { mutableStateOf(prefill?.kcal?.let { Calc.fmtNum(it) } ?: "") }
    var protein by remember { mutableStateOf(prefill?.protein?.takeIf { it > 0 }?.let { Calc.fmtNum(it) } ?: "") }
    var carbs by remember { mutableStateOf(prefill?.carbs?.takeIf { it > 0 }?.let { Calc.fmtNum(it) } ?: "") }
    var fat by remember { mutableStateOf(prefill?.fat?.takeIf { it > 0 }?.let { Calc.fmtNum(it) } ?: "") }
    val kcalVal = Calc.parseNum(kcal)
    val valid = name.isNotBlank() && kcalVal != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Essen eintragen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Was? (z. B. Nudeln mit Hähnchen)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                NumField("Kalorien (kcal)", kcal, { kcal = it }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("Eiweiß g", protein, { protein = it }, Modifier.weight(1f))
                    NumField("Kohlenh. g", carbs, { carbs = it }, Modifier.weight(1f))
                    NumField("Fett g", fat, { fat = it }, Modifier.weight(1f))
                }
                Hint("Nährwerte sind optional. Kalorien findest du auf der Verpackung oder in einer Nährwert-Tabelle.")
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val now = System.currentTimeMillis()
                val t = if (Calc.dayStart(now) == day) now else day + 12 * 3_600_000L
                onSave(
                    FoodEntry(
                        id = "f_$now",
                        t = t,
                        name = name.trim(),
                        kcal = kcalVal ?: 0.0,
                        protein = Calc.parseNum(protein) ?: 0.0,
                        carbs = Calc.parseNum(carbs) ?: 0.0,
                        fat = Calc.parseNum(fat) ?: 0.0,
                    )
                )
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** CSV-Dateien, die sich direkt in Excel öffnen lassen (Semikolon, Komma als Dezimalzeichen). */
object Csv {
    fun sessionHr(s: Session, maxHr: Int): String = buildString {
        append("uhrzeit;sekunden_seit_start;puls;zone\n")
        s.samples.forEach {
            append(Calc.fmtTime(it.t)).append(';')
                .append((it.t - s.start) / 1000).append(';')
                .append(it.bpm).append(';')
                .append(Calc.zoneOf(it.bpm, maxHr)).append('\n')
        }
    }

    fun sessions(list: List<Session>): String = buildString {
        append("datum;art;titel;quelle;dauer_min;puls_avg;puls_max;kcal;saetze;notiz\n")
        list.sortedBy { it.start }.forEach {
            append(Calc.fmtDateTime(it.start)).append(';')
                .append(it.type.label).append(';')
                .append(it.title.replace(';', ',')).append(';')
                .append(it.source).append(';')
                .append(Calc.fmtNum(it.durationMs / 60_000.0, 1)).append(';')
                .append(it.avgBpm).append(';')
                .append(it.maxBpm).append(';')
                .append(Calc.fmtNum(it.kcal)).append(';')
                .append(it.sets.joinToString(" | ") { s -> "${s.exercise} ${s.reps}x${Calc.fmtNum(s.weightKg, 1)}kg" }.replace(';', ',')).append(';')
                .append(it.note.replace(';', ',').replace('\n', ' ')).append('\n')
        }
    }

    fun food(list: List<FoodEntry>): String = buildString {
        append("datum;name;kcal;eiweiss_g;kohlenhydrate_g;fett_g\n")
        list.sortedBy { it.t }.forEach {
            append(Calc.fmtDateTime(it.t)).append(';')
                .append(it.name.replace(';', ',')).append(';')
                .append(Calc.fmtNum(it.kcal)).append(';')
                .append(Calc.fmtNum(it.protein, 1)).append(';')
                .append(Calc.fmtNum(it.carbs, 1)).append(';')
                .append(Calc.fmtNum(it.fat, 1)).append('\n')
        }
    }

    fun write(ctx: Context, uri: Uri, text: String): Boolean = try {
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        true
    } catch (e: Exception) {
        false
    }
}
