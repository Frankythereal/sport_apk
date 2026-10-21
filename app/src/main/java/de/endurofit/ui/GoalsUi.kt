package de.endurofit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Calc
import de.endurofit.data.GoalCheck
import de.endurofit.data.Repo
import de.endurofit.data.SleepEntry
import java.util.Calendar

/** Eine Zeile mit Fortschrittsbalken für ein Ziel. */
@Composable
fun GoalRow(g: GoalCheck, extra: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.emoji, fontSize = 18.sp, modifier = Modifier.width(30.dp))
            Text(g.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(g.valueText, style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    !g.judged -> "  ·"
                    g.done -> "  ✓"
                    else -> "   "
                },
                color = if (g.done) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
        ProgressBar(g.progress, if (g.done) Good else if (g.judged) Accent else Color(0xFF757575), Modifier.padding(start = 30.dp, top = 4.dp))
        if (extra != null) Box(Modifier.padding(start = 30.dp, top = 4.dp)) { extra() }
    }
}

@Composable
fun ProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

/** Schnellknöpfe zum Trinken. */
@Composable
fun WaterButtons(day: Long) {
    val water by Repo.water.collectAsState()
    val now = System.currentTimeMillis()
    val t = if (Calc.dayStart(now) == day) now else day + 12 * 3_600_000L
    val lastOfDay = water.firstOrNull { it.t in day until Calc.addDays(day, 1) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(250, 330, 500).forEach { ml ->
            OutlinedButton(
                onClick = { Repo.addWater(ml, t) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) { Text("+$ml") }
        }
        if (lastOfDay != null) {
            TextButton(onClick = { Repo.deleteWater(lastOfDay.id) }) { Text("↶") }
        }
    }
}

/** Zeigt die letzte Nacht mit Schlafphasen. */
@Composable
fun SleepSummary(s: SleepEntry) {
    Text(
        "${Calc.fmtHours(s.asleepMs)} geschlafen · ${Calc.fmtClock(s.start)}–${Calc.fmtClock(s.end)}",
        fontWeight = FontWeight.SemiBold
    )
    if (s.hasStages) {
        val total = (s.deepMs + s.lightMs + s.remMs + s.awakeMs).coerceAtLeast(1).toFloat()
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
            listOf(
                s.deepMs to Color(0xFF3949AB), s.remMs to Color(0xFF8E24AA),
                s.lightMs to Color(0xFF64B5F6), s.awakeMs to Color(0xFFFFB74D)
            ).forEach { (ms, c) ->
                if (ms > 0) Box(Modifier.weight(ms / total).fillMaxHeight().background(c))
            }
        }
        Spacer(Modifier.height(4.dp))
        Hint(
            "Tief ${Calc.fmtHours(s.deepMs)} · REM ${Calc.fmtHours(s.remMs)} · " +
                "Leicht ${Calc.fmtHours(s.lightMs)} · Wach ${Calc.fmtHours(s.awakeMs)}"
        )
    }
    Hint(if (s.id.startsWith("hc_")) "von der Uhr" else "von Hand eingetragen")
}

/** Schlaf von Hand eintragen (z. B. wenn die Uhr nachts nicht getragen wurde). */
@Composable
fun SleepDialog(wakeDay: Long, onDismiss: () -> Unit) {
    var bed by remember { mutableStateOf("23:00") }
    var wake by remember { mutableStateOf("07:00") }

    fun parse(s: String): Pair<Int, Int>? {
        val p = s.trim().replace('.', ':').split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    val b = parse(bed)
    val w = parse(wake)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schlaf eintragen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Nacht auf ${Calc.fmtDate(wakeDay)}")
                OutlinedTextField(bed, { bed = it }, label = { Text("Eingeschlafen (z. B. 23:15)") }, singleLine = true)
                OutlinedTextField(wake, { wake = it }, label = { Text("Aufgewacht (z. B. 6:45)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = b != null && w != null, onClick = {
                val end = Calendar.getInstance().apply {
                    timeInMillis = wakeDay
                    set(Calendar.HOUR_OF_DAY, w!!.first); set(Calendar.MINUTE, w.second)
                }.timeInMillis
                var start = Calendar.getInstance().apply {
                    timeInMillis = wakeDay
                    set(Calendar.HOUR_OF_DAY, b!!.first); set(Calendar.MINUTE, b.second)
                }.timeInMillis
                if (start >= end) start = Calc.addDays(start, -1)
                Repo.saveSleep(SleepEntry("man_$wakeDay", start, end, "manuell"))
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
