package de.endurofit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import de.endurofit.data.WeightEntry
import de.endurofit.scale.BeurerScale
import kotlin.math.abs

val WeightTeal = Color(0xFF26A69A)

private fun kg(v: Double) = Calc.fmtNum(v, 1)

/** Veränderung gegenüber dem Gewicht von vor [days] Tagen (nächstgelegene ältere Wiegung). */
private fun change(list: List<WeightEntry>, days: Int): Double? {
    val latest = list.firstOrNull() ?: return null
    val ref = list.firstOrNull { it.t <= latest.t - days * 86_400_000L } ?: return null
    return latest.kg - ref.kg
}

private fun signed(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "±") + Calc.fmtNum(abs(v), 1)

/** Inhalt der Kachel auf „Heute“. */
@Composable
fun WeightTileContent() {
    val list by Repo.weights.collectAsState()
    val latest = list.firstOrNull()
    if (latest == null) {
        Hint("Noch keine Wiegung – tippen zum Verbinden der Waage")
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(kg(latest.kg), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = WeightTeal)
        Text(" kg", Modifier.padding(bottom = 4.dp))
        Spacer(Modifier.weight(1f))
        change(list, 7)?.let {
            Text(signed(it) + " kg / Woche", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    Hint(
        listOfNotNull(
            latest.fatPct?.let { "Fett ${Calc.fmtNum(it, 1)} %" },
            latest.musclePct?.let { "Muskeln ${Calc.fmtNum(it, 1)} %" },
            Calc.fmtShortDate(latest.t) + " " + Calc.fmtClock(latest.t),
        ).joinToString(" · ")
    )
}

/** Alles rund ums Gewicht: Waage verbinden, Verlauf, Körperwerte, Liste. */
@Composable
fun WeightCard() {
    val ctx = LocalContext.current
    val list by Repo.weights.collectAsState()
    val phase by BeurerScale.phase.collectAsState()
    val status by BeurerScale.status.collectAsState()
    val live by BeurerScale.liveKg.collectAsState()
    var manual by remember { mutableStateOf(false) }
    var span by rememberSaveable { mutableIntStateOf(90) }
    var showAll by remember { mutableStateOf(false) }

    val perms = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun granted() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted()) BeurerScale.start(ctx)
    }
    fun weigh() = if (granted()) BeurerScale.start(ctx) else launcher.launch(perms.toTypedArray())

    SectionCard("⚖️ Gewicht & Körper") {
        val latest = list.firstOrNull()
        if (latest != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                CountUp((latest.kg * 10).toInt(), fontSize = 38.sp, color = WeightTeal, format = { Calc.fmtNum(it / 10.0, 1) })
                Text(" kg", Modifier.padding(bottom = 7.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    change(list, 7)?.let { Text(signed(it) + " kg in 7 Tagen", style = MaterialTheme.typography.labelMedium) }
                    change(list, 30)?.let { Text(signed(it) + " kg in 30 Tagen", style = MaterialTheme.typography.labelMedium) }
                }
            }
            Hint("Zuletzt " + Calc.fmtDateTime(latest.t) + " · " + sourceLabel(latest.source))
            val p = Repo.profile.value
            val bmi = latest.kg / ((p.heightCm / 100.0) * (p.heightCm / 100.0))
            Spacer(Modifier.height(8.dp))
            StatRow(
                "BMI" to Calc.fmtNum(bmi, 1),
                "Fett" to (latest.fatPct?.let { Calc.fmtNum(it, 1) + " %" } ?: "–"),
                "Muskeln" to (latest.musclePct?.let { Calc.fmtNum(it, 1) + " %" } ?: "–"),
                "Wasser" to (latest.waterPct?.let { Calc.fmtNum(it, 1) + " %" } ?: "–"),
            )
            if (latest.fatPct == null && latest.source == "scale") Hint("Körperfett & Co. misst die Waage nur barfuß.", Modifier.padding(top = 4.dp))
        } else {
            Hint("Noch keine Wiegung. Verbinde deine Beurer BF 720 – danach landet jedes Gewicht automatisch hier und im Profil (für Grundumsatz und Kalorien).")
        }

        // ---------- Waage ----------
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(
                if (BeurerScale.busy) "⏳" else "⚖️",
                if (BeurerScale.busy) "Abbrechen" else "Wiegen",
                Modifier.weight(1f), color = WeightTeal,
            ) { if (BeurerScale.busy) BeurerScale.stop() else weigh() }
            ActionTile("✏️", "Von Hand", Modifier.weight(1f), color = Color(0xFF66BB6A)) { manual = true }
        }
        if (BeurerScale.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = WeightTeal)
        status?.let {
            Text(
                it, Modifier.padding(top = 6.dp),
                color = if (phase == BeurerScale.Phase.ERROR) Bad else Color.Unspecified,
                fontWeight = if (phase == BeurerScale.Phase.CONNECTED) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (BeurerScale.busy && live != null) Text("${kg(live!!)} kg", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = WeightTeal)
        if (!BeurerScale.busy) {
            Hint(
                if (Repo.scaleAddress == null)
                    "So geht's: „Wiegen“ antippen und dich kurz auf die Waage stellen, damit sie aufwacht. EnduroFit legt sich dann als eigene Person auf der Waage an. " +
                        "Wenn die Beurer-App installiert ist, die bitte vorher schließen – die Waage spricht immer nur mit einer App."
                else "Waage: ${Repo.scaleName ?: "Beurer"} · Person P${Repo.scaleUserIndex.coerceAtLeast(0)}. Wiegungen ohne Handy speichert die Waage und schickt sie beim nächsten Verbinden mit.",
                Modifier.padding(top = 6.dp),
            )
        }

        // ---------- Verlauf ----------
        if (list.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Segmented(listOf(30 to "30 T.", 90 to "3 M.", 365 to "1 J."), span, { span = it })
            Spacer(Modifier.height(8.dp))
            WeightChart(list, span)
        }

        // ---------- Liste ----------
        if (list.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val shown = if (showAll) list.take(60) else list.take(5)
            shown.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${kg(w.kg)} kg", fontWeight = FontWeight.SemiBold)
                        Hint(Calc.fmtShortDate(w.t) + " " + Calc.fmtClock(w.t) + (w.fatPct?.let { " · Fett ${Calc.fmtNum(it, 1)} %" } ?: "") + " · " + sourceLabel(w.source))
                    }
                    TextButton(onClick = { Repo.deleteWeight(w.id) }) { Text("✕") }
                }
            }
            if (list.size > 5) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Weniger" else "Alle ${list.size} anzeigen") }
        }
        if (Repo.scaleAddress != null && !BeurerScale.busy) {
            TextButton(onClick = { BeurerScale.forget() }) { Text("Waage entkoppeln") }
        }
    }

    if (manual) ManualWeightDialog(onDismiss = { manual = false })
}

private fun sourceLabel(src: String) = when {
    src == "scale" -> "Waage"
    src == "manual" -> "von Hand"
    src.contains("beurer", true) -> "Beurer-App"
    src.startsWith("fi.polar") -> "Polar"
    else -> "Health Connect"
}

@Composable
private fun ManualWeightDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(Repo.weights.value.firstOrNull()?.let { kg(it.kg) } ?: Calc.fmtNum(Repo.profile.value.weightKg, 1)) }
    var fat by remember { mutableStateOf("") }
    val v = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gewicht eintragen") },
        text = {
            Column {
                NumField("Gewicht (kg)", text, { text = it })
                Spacer(Modifier.height(8.dp))
                NumField("Körperfett (%) – optional", fat, { fat = it })
            }
        },
        confirmButton = {
            TextButton(enabled = v != null && v in 30.0..250.0, onClick = {
                val now = System.currentTimeMillis()
                Repo.addWeights(listOf(WeightEntry("m_$now", now, v!!, fatPct = fat.replace(',', '.').toDoubleOrNull(), source = "manual")))
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Gewichtsverlauf als Linie mit gleitendem 7-Tage-Mittel. */
@Composable
private fun WeightChart(all: List<WeightEntry>, days: Int) {
    val now = System.currentTimeMillis()
    val from = now - days * 86_400_000L
    val pts = all.filter { it.t >= from }.sortedBy { it.t }
    if (pts.size < 2) {
        Hint("Für diesen Zeitraum gibt es noch zu wenige Wiegungen.")
        return
    }
    val lo = pts.minOf { it.kg } - 0.5
    val hi = pts.maxOf { it.kg } + 0.5
    val avg = pts.map { p -> pts.filter { it.t in (p.t - 7 * 86_400_000L)..p.t }.map { it.kg }.average() }
    Column {
        Row {
            Column(Modifier.height(150.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(kg(hi), style = MaterialTheme.typography.labelSmall)
                Text(kg(lo), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.weight(1f).height(150.dp)) {
                val t0 = pts.first().t.toFloat()
                val t1 = pts.last().t.toFloat().coerceAtLeast(t0 + 1)
                fun x(t: Long) = (t - t0) / (t1 - t0) * size.width
                fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
                // Hilfslinien
                for (i in 0..3) {
                    val yy = size.height * i / 3
                    drawLine(Color(0x22FFFFFF), Offset(0f, yy), Offset(size.width, yy), 1f)
                }
                pts.forEach { drawCircle(WeightTeal.copy(alpha = 0.45f), 3.5f, Offset(x(it.t), y(it.kg))) }
                val path = Path()
                pts.forEachIndexed { i, p ->
                    val o = Offset(x(p.t), y(avg[i]))
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, WeightTeal, style = Stroke(width = 3f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 30.dp)) {
            Text(Calc.fmtShortDate(pts.first().t), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(Calc.fmtShortDate(pts.last().t), style = MaterialTheme.typography.labelSmall)
        }
        Hint("Punkte = einzelne Wiegungen · Linie = 7-Tage-Mittel (glättet Wasserschwankungen)")
    }
}
