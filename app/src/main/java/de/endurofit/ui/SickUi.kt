@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package de.endurofit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import de.endurofit.data.Sick
import de.endurofit.data.SickPeriod
import de.endurofit.data.Symptom
import kotlin.math.roundToInt

private val SickColor = Color(0xFF7E57C2)

private fun adviceColor(a: Sick.Advice): Color = when (a) {
    Sick.Advice.ARZT -> Bad
    Sick.Advice.PAUSE -> Color(0xFFFFA726)
    Sick.Advice.LOCKER -> Good
}

/** Auf "Heute": Krankheitskarte, Wiedereinstieg oder der Knopf zum Starten. */
@Composable
fun SickSection() {
    val all by Repo.sick.collectAsState()
    val current = Sick.current(all)
    val returning = Sick.returning(all)
    var start by remember { mutableStateOf(false) }

    when {
        current != null -> SickCard(current)
        returning != null -> ReturnCard(returning)
        else -> OutlinedButton(onClick = { start = true }, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("🤒 Ich bin krank")
        }
    }
    if (start) SymptomDialog(null) { start = false }
}

@Composable
private fun SickCard(p: SickPeriod) {
    val dayHr by Repo.dayHr.collectAsState()
    var edit by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val advice = Sick.advice(p.symptoms)

    SectionCard("🤒 Krankheitsmodus · Tag ${Sick.dayIndex(p.start)}") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.symptoms.isEmpty()) Hint("Keine Symptome ausgewählt")
            p.symptoms.forEach { s -> AssistChip(onClick = { edit = true }, label = { Text("${s.emoji} ${s.label}") }) }
        }
        Spacer(Modifier.height(8.dp))
        Text(Sick.adviceTitle(advice), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = adviceColor(advice))
        Hint(Sick.adviceText(advice), Modifier.padding(top = 2.dp))

        RestingCompare(p, dayHr)

        Spacer(Modifier.height(8.dp))
        Text("Was jetzt hilft", fontWeight = FontWeight.SemiBold)
        Hint(
            "• Viel trinken – dein Trinkziel ist um 0,5 l erhöht\n" +
                "• Früh schlafen gehen, Schlaf ist die beste Medizin\n" +
                "• Kein Kaloriendefizit – der Körper braucht jetzt Energie\n" +
                "• Schritte, Eiweiß und Wochenziele sind pausiert und zählen nicht in die Statistik"
        )
        if (p.symptoms.contains(Symptom.FIEBER)) {
            Hint("🌡️ Bei Fieber über 39 °C oder länger als 3 Tage: ärztlich abklären lassen.", Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { edit = true }) { Text("Symptome ändern") }
            Button(onClick = { confirmEnd = true }) { Text("✅ Wieder gesund") }
        }
    }

    if (edit) SymptomDialog(p) { edit = false }
    if (confirmEnd) {
        val days = Sick.returnDays(p, System.currentTimeMillis())
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("Wieder gesund? 🎉") },
            text = {
                Text(
                    "Die App begleitet dich jetzt $days Tage beim Wiedereinstieg. " +
                        if (p.hadSystemic) "Weil du Beschwerden unterhalb des Halses hattest (z. B. Fieber), steigst du langsam wieder ein."
                        else "Nach einer leichten Erkältung geht es meist schnell wieder."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val now = System.currentTimeMillis()
                    Repo.saveSick(p.copy(end = now, returnUntil = Calc.addDays(Calc.dayStart(now), days + 1)))
                    confirmEnd = false
                }) { Text("Ja, Wiedereinstieg starten") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Noch nicht") } },
        )
    }
}

@Composable
private fun ReturnCard(p: SickPeriod) {
    val dayHr by Repo.dayHr.collectAsState()
    val total = Sick.returnDays(p, p.end ?: System.currentTimeMillis())
    val day = Sick.dayIndex(p.end ?: System.currentTimeMillis()).coerceAtMost(total)
    SectionCard("💪 Wiedereinstieg · Tag $day von $total") {
        LinearProgressIndicator(progress = { day.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Hint(
            (if (day <= total / 2)
                "• Erste Einheiten kurz und locker: nur Zone 1–2, etwa halber Umfang\n• Noch kein hartes Enduro und kein schweres Krafttraining\n"
            else
                "• Umfang langsam wieder steigern, Intensität erst danach\n• Kurze harte Abschnitte sind wieder okay, wenn du dich gut fühlst\n") +
                "• Kommen Fieber, Husten oder Schlappheit zurück: sofort wieder pausieren\n" +
                "• Brustschmerz, Herzstolpern oder Atemnot beim Sport: abbrechen und ärztlich abklären lassen"
        )
        RestingCompare(p, dayHr)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { Repo.saveSick(p.copy(end = null, returnUntil = null)) }) { Text("Doch wieder krank") }
            TextButton(onClick = { Repo.saveSick(p.copy(returnUntil = System.currentTimeMillis())) }) { Text("Wiedereinstieg beenden") }
        }
    }
}

/** Ruhepuls heute im Vergleich zu vor der Krankheit – ein guter Erholungsanzeiger. */
@Composable
private fun RestingCompare(p: SickPeriod, dayHr: Map<Long, de.endurofit.data.DayHr>) {
    val base = Sick.restingBaseline(dayHr, p.start) ?: return
    val today = Calc.dayStart(System.currentTimeMillis())
    val now = (dayHr[today] ?: dayHr[Calc.addDays(today, -1)])?.resting ?: return
    val diff = now - base
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("❤️ Ruhepuls $now", fontWeight = FontWeight.SemiBold)
        Text("  (vor der Krankheit Ø ${base.roundToInt()})", style = MaterialTheme.typography.bodySmall)
    }
    Hint(
        when {
            diff >= 5 -> "Noch ${diff.roundToInt()} Schläge erhöht – dein Körper arbeitet noch. Weiter schonen."
            diff >= 2 -> "Leicht erhöht – fast wieder normal."
            else -> "Wieder im normalen Bereich – gutes Zeichen."
        }
    )
}

/** Symptome auswählen – zum Starten oder Ändern. */
@Composable
private fun SymptomDialog(existing: SickPeriod?, onDismiss: () -> Unit) {
    var sel by remember { mutableStateOf(existing?.symptoms ?: emptySet()) }
    val advice = Sick.advice(sel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Was hast du?" else "Symptome ändern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Oberhalb des Halses", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Symptom.entries.filter { !it.belowNeck }.forEach { s ->
                        FilterChip(selected = s in sel, onClick = { sel = if (s in sel) sel - s else sel + s }, label = { Text("${s.emoji} ${s.label}") })
                    }
                }
                Text("Unterhalb des Halses / ganzer Körper", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Symptom.entries.filter { it.belowNeck }.forEach { s ->
                        FilterChip(selected = s in sel, onClick = { sel = if (s in sel) sel - s else sel + s }, label = { Text("${s.emoji} ${s.label}") })
                    }
                }
                if (sel.isNotEmpty()) {
                    HorizontalDivider()
                    Text(Sick.adviceTitle(advice), fontWeight = FontWeight.Bold, color = adviceColor(advice))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val systemic = sel.any { it.belowNeck } || (existing?.hadSystemic == true)
                val now = System.currentTimeMillis()
                Repo.saveSick(
                    existing?.copy(symptoms = sel, hadSystemic = systemic)
                        ?: SickPeriod(id = "sick_$now", start = now, symptoms = sel, hadSystemic = systemic)
                )
                onDismiss()
            }) { Text(if (existing == null) "Krankheitsmodus starten" else "Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Hinweis über "Neue Aufnahme" im Training. */
@Composable
fun SickTrainingBanner() {
    val all by Repo.sick.collectAsState()
    val cur = Sick.current(all)
    val ret = Sick.returning(all)
    if (cur != null) {
        val a = Sick.advice(cur.symptoms)
        SectionCard {
            Text("🤒 ${Sick.adviceTitle(a)}", fontWeight = FontWeight.Bold, color = adviceColor(a))
            Hint(
                if (a == Sick.Advice.LOCKER) "Du bist im Krankheitsmodus. Höchstens locker in Zone 1–2 – die Zone siehst du oben in der Pulsanzeige."
                else "Du bist im Krankheitsmodus. Heute lieber kein Training – Erholung geht vor."
            )
        }
    } else if (ret != null) {
        SectionCard {
            Text("💪 Wiedereinstieg nach Krankheit", fontWeight = FontWeight.Bold, color = SickColor)
            Hint("Locker bleiben: vor allem Zone 1–2, kürzer als sonst. Bei Beschwerden sofort abbrechen.")
        }
    }
}

/**
 * Frühwarnung: Hauttemperatur über dem Normalwert, dazu erhöhter Ruhepuls oder gesunkene HRV –
 * oft die ersten Anzeichen eines Infekts, bevor man selbst etwas merkt.
 */
@Composable
fun SickEarlyWarning() {
    val sick by Repo.sick.collectAsState()
    val skin by Repo.skin.collectAsState()
    val rec by Repo.recovery.collectAsState()
    val dayHr by Repo.dayHr.collectAsState()
    var start by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    if (Sick.current(sick) != null || dismissed) return

    val today = Calc.dayStart(System.currentTimeMillis())
    val s = skin[today] ?: skin[Calc.addDays(today, -1)] ?: return
    val dev = s.deviation ?: return

    val restNow = (rec[s.day]?.nightHr ?: dayHr[s.day]?.resting)
    val restBase = (1..14).mapNotNull { rec[Calc.addDays(s.day, -it)]?.nightHr ?: dayHr[Calc.addDays(s.day, -it)]?.resting }
        .takeIf { it.size >= 3 }?.average()
    val hrvNow = rec[s.day]?.hrv
    val hrvBase = (1..14).mapNotNull { rec[Calc.addDays(s.day, -it)]?.hrv }.takeIf { it.size >= 3 }?.average()

    val restUp = restNow != null && restBase != null && restNow - restBase >= 4
    val hrvDown = hrvNow != null && hrvBase != null && hrvNow < hrvBase * 0.85
    val warn = dev >= 0.8 || (dev >= 0.5 && (restUp || hrvDown))
    if (!warn) return

    SectionCard("⚠️ Möglicher Infekt im Anmarsch") {
        Text(
            "Deine Hauttemperatur liegt heute Nacht ${Calc.fmtNum(dev, 1)} °C über deinem Normalwert" +
                (if (restUp) ", der Ruhepuls ist erhöht" else "") +
                (if (hrvDown) ", die HRV ist gesunken" else "") + ".",
        )
        Hint(
            "Das sind oft die ersten Anzeichen einer Erkältung – noch bevor man selbst etwas merkt. " +
                "Heute lieber locker trainieren, viel trinken und früh schlafen. Kann aber auch von Alkohol, spätem Essen, " +
                "einem warmen Schlafzimmer oder hartem Training am Vortag kommen.",
            Modifier.padding(top = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { start = true }) { Text("🤒 Krankheitsmodus") }
            TextButton(onClick = { dismissed = true }) { Text("Mir geht's gut") }
        }
    }
    if (start) SymptomDialog(null) { start = false }
}
