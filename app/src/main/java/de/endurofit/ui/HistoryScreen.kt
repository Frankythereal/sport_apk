@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import de.endurofit.data.SOURCE_SUUNTO
import de.endurofit.data.SessionType

@Composable
fun HistoryScreen(selected: String?, onSelect: (String?) -> Unit) {
    if (selected != null) {
        BackHandler { onSelect(null) }
        SessionDetail(selected, onBack = { onSelect(null) })
        return
    }
    val sessions by Repo.sessions.collectAsState()
    var filter by remember { mutableStateOf<SessionType?>(null) }
    val shown = sessions.filter { filter == null || it.type == filter }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            FitImportSection(onOpen = { onSelect(it) })
            SuuntoImportSection(compact = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState())) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Alle") })
                SessionType.entries.forEach { t ->
                    FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(t.emoji + " " + t.label) })
                }
            }
            if (shown.isEmpty()) Hint("Noch keine Trainings gespeichert.")
        }
        items(shown, key = { it.id }) { s -> SessionRow(s) { onSelect(s.id) } }
    }
}

@Composable
fun SessionDetail(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sessions by Repo.sessions.collectAsState()
    val profile by Repo.profile.collectAsState()
    val s = sessions.firstOrNull { it.id == id }
    if (s == null) {
        Column(Modifier.padding(16.dp)) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Hint("Dieses Training gibt es nicht mehr.")
        }
        return
    }
    val maxHr = profile.effectiveMaxHr
    // Fehlt GPS oder Puls, nimm sie von einer gleichzeitigen Aufnahme (z. B. Suunto-FIT + Verity Sense)
    val gpsPartner = remember(s, sessions) { if (s.track.size > 1) null else Calc.partner(sessions, s) { it.track.size > 1 } }
    val track = if (s.track.size > 1) s.track else gpsPartner?.track ?: emptyList()
    val hrPartner = remember(s, sessions) { if (s.samples.size > 1) null else Calc.partner(sessions, s) { it.samples.size > 1 } }
    val hr = if (s.samples.size > 1) s.samples else hrPartner?.samples ?: emptyList()
    val peaks = remember(hr, maxHr) { Calc.peaks(hr, maxHr) }
    val ranked = peaks.sortedByDescending { it.maxBpm }
    val zones = remember(hr, maxHr) { Calc.timeInZones(hr, maxHr) }
    val hardest = remember(hr) { Calc.hardestWindow(hr) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember(s.id) { mutableStateOf(s.note) }
    var title by remember(s.id) { mutableStateOf(s.title) }

    val exportHr = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = Csv.write(ctx, uri, Csv.sessionHr(s, maxHr))
            Toast.makeText(ctx, if (ok) "Exportiert" else "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Zurück") }
        Text("${s.type.emoji} ${s.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Hint(
            Calc.fmtDateTime(s.start) + " – " + Calc.fmtClock(s.end) + when {
                s.id.startsWith("fit_") -> " · Uhr (FIT-Datei)"
                s.source == SOURCE_SUUNTO -> " · Uhr (Health Connect)"
                else -> " · Verity Sense"
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SessionType.entries.forEach { t ->
                FilterChip(
                    selected = s.type == t,
                    onClick = {
                        val newTitle = if (s.title == s.type.label) t.label else s.title
                        title = newTitle
                        Repo.saveSession(s.copy(type = t, title = newTitle))
                    },
                    label = { Text(t.emoji + " " + t.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionCard {
            val avg = if (hr.isEmpty()) 0 else hr.sumOf { it.bpm } / hr.size
            StatRow(
                "Dauer" to Calc.fmtDuration(s.durationMs),
                "Ø Puls" to (if (avg > 0) "$avg" else "–"),
                "Max" to (if (hr.isNotEmpty()) "${hr.maxOf { it.bpm }}" else "–"),
                "kcal" to Calc.fmtNum(s.kcal),
            )
            val dist = s.distanceM ?: gpsPartner?.distanceM
            val asc = s.ascentM ?: gpsPartner?.ascentM
            if (dist != null || asc != null) {
                Spacer(Modifier.height(8.dp))
                StatRow(
                    "Strecke" to (dist?.let { Calc.fmtNum(it / 1000.0, 1) + " km" } ?: "–"),
                    "Aufstieg" to (asc?.let { Calc.fmtNum(it) + " hm" } ?: "–"),
                    "Ø Tempo" to (dist?.takeIf { s.durationMs > 0 }?.let { Calc.fmtNum(it / 1000.0 / (s.durationMs / 3_600_000.0), 1) + " km/h" } ?: "–"),
                )
            }
            if (hrPartner != null) Hint("Puls von „${hrPartner.title}“ zur selben Zeit", Modifier.padding(top = 6.dp))
        }

        if (track.size > 1) {
            SectionCard("🗺️ Strecke") {
                TrackMap(track, hr, maxHr, peaks, s.markers)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (1..5).forEach { z ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(zoneColor(z)))
                            Text(" Z$z", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Hint(
                    "Farbe = Pulszone · rote Nummern = Extrempunkte · ⚑ = deine Markierungen. Marker antippen für Details." +
                        if (gpsPartner != null) " Strecke von „${gpsPartner.title}“." else "",
                    Modifier.padding(top = 4.dp)
                )
            }
        }

        if (hr.size > 1) {
            SectionCard("Pulsverlauf") {
                HrChart(hr, maxHr, start = s.start, markers = s.markers, peaks = peaks, height = 200.dp)
                Hint("Rot = Extremabschnitte über 90 % HFmax · gestrichelt = deine Markierungen", Modifier.padding(top = 6.dp))
            }

            SectionCard("Extrempunkte") {
                if (peaks.isEmpty()) {
                    Hint("Keine Abschnitte über 90 % deiner HFmax ($maxHr bpm), die länger als 10 Sekunden dauerten.")
                } else {
                    ranked.forEachIndexed { i, p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 10.dp).background(Bad, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 1.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text("${Calc.fmtTime(p.start)} Uhr", fontWeight = FontWeight.SemiBold)
                                Hint("${Calc.fmtDuration(p.start - s.start)} nach Start · ${Calc.fmtDuration(p.durationMs)} lang")
                            }
                            Text("max ${p.maxBpm}", color = Bad, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (hardest != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Härteste Minute: ab ${Calc.fmtTime(hardest.first)} Uhr · Ø ${hardest.second} bpm", fontWeight = FontWeight.SemiBold)
                }
                Hint(
                    if (track.size > 1) "Die Nummern findest du auf der Karte oben wieder."
                    else "Tipp: Mit einer GPS-Strecke der Uhr siehst du die Stellen auf der Karte.",
                    Modifier.padding(top = 6.dp)
                )
            }

            SectionCard("Zeit in den Zonen") { ZoneBars(zones, profile) }
        }

        if (s.markers.isNotEmpty()) {
            SectionCard("Markierungen") {
                s.markers.forEachIndexed { i, m ->
                    val near = hr.minByOrNull { kotlin.math.abs(it.t - m) }
                    Text("⚑ ${i + 1}: ${Calc.fmtTime(m)} Uhr (${Calc.fmtDuration(m - s.start)} nach Start)" + (near?.let { " · ${it.bpm} bpm" } ?: ""))
                }
            }
        }

        if (s.sets.isNotEmpty()) {
            SectionCard("Sätze") {
                s.sets.groupBy { it.exercise }.forEach { (ex, sets) ->
                    Text(ex, fontWeight = FontWeight.SemiBold)
                    Hint(sets.joinToString(" · ") { "${it.reps}×${Calc.fmtNum(it.weightKg, 1)} kg" })
                    Spacer(Modifier.height(6.dp))
                }
                Hint("Gesamt bewegt: ${Calc.fmtNum(s.sets.sumOf { it.reps * it.weightKg })} kg")
            }
        }

        SectionCard("Name & Notiz") {
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                note, { note = it }, label = { Text("Notiz (z. B. Strecke, Wetter, wie es lief)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2
            )
            Spacer(Modifier.height(6.dp))
            Button(
                enabled = note != s.note || (title != s.title && title.isNotBlank()),
                onClick = { Repo.saveSession(s.copy(note = note, title = title.ifBlank { s.title })) }
            ) { Text("Speichern") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = s.samples.isNotEmpty(),
                onClick = { exportHr.launch("puls_${Calc.fmtDateTime(s.start).replace(Regex("[^0-9]"), "")}.csv") },
                modifier = Modifier.weight(1f)
            ) { Text("Puls als CSV") }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Löschen", color = Bad) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Training löschen?") },
            text = {
                Text(
                    if (s.source == SOURCE_SUUNTO) "Es wird nur hier gelöscht. Beim nächsten Import kommt es wieder, solange es in der App der Uhr ist."
                    else "Das lässt sich nicht rückgängig machen."
                )
            },
            confirmButton = {
                Button(onClick = { confirmDelete = false; Repo.deleteSession(s.id); onBack() }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
