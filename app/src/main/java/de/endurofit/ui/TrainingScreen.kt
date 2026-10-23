@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import de.endurofit.data.Calc
import de.endurofit.data.GymSet
import de.endurofit.data.Repo
import de.endurofit.data.SessionType
import de.endurofit.hr.BleScanner
import de.endurofit.hr.HrService
import de.endurofit.hr.HrState
import de.endurofit.hr.Recorder
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val DEFAULT_EXERCISES = listOf(
    "Kniebeuge", "Kreuzheben", "Bankdrücken", "Klimmzüge", "Rudern", "Schulterdrücken",
    "Ausfallschritte", "Beinpresse", "Unterarm-Curls", "Plank", "Liegestütze", "Dips"
)

@Composable
fun TrainingScreen(onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val conn by HrState.conn.collectAsState()
    val bpm by HrState.bpm.collectAsState()
    val err by HrState.error.collectAsState()
    val devName by HrState.deviceName.collectAsState()
    val live by Recorder.live.collectAsState()
    val rev by Recorder.revision.collectAsState()
    val profile by Repo.profile.collectAsState()
    val maxHr = profile.effectiveMaxHr

    // ---------- Sensor-Suche ----------
    val scanner = remember { BleScanner(ctx) }
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<BleScanner.Found>() }
    var pendingConnect by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }
    LaunchedEffect(scanning) {
        if (scanning) {
            delay(20_000)
            scanner.stop()
            scanning = false
        }
    }

    val btPerms = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val allPerms = btPerms + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    fun hasBt() = btPerms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun startScan() {
        if (!scanner.bluetoothOn()) {
            Toast.makeText(ctx, "Bitte Bluetooth einschalten", Toast.LENGTH_LONG).show()
            return
        }
        found.clear()
        scanning = scanner.start { f ->
            val i = found.indexOfFirst { it.address == f.address }
            if (i >= 0) found[i] = f else found.add(f)
        }
    }

    fun connectTo(address: String, name: String?) {
        scanner.stop(); scanning = false
        Repo.lastDeviceAddress = address
        if (name != null) Repo.lastDeviceName = name
        HrService.connect(ctx, address)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBt()) {
            val p = pendingConnect
            pendingConnect = null
            if (p != null) connectTo(p, null) else startScan()
        } else {
            Toast.makeText(ctx, "Ohne Bluetooth-Berechtigung kann ich den Sensor nicht finden.", Toast.LENGTH_LONG).show()
        }
    }

    fun requestScan() = if (hasBt()) startScan() else { pendingConnect = null; permLauncher.launch(allPerms.toTypedArray()) }
    fun requestConnect(addr: String) = if (hasBt()) connectTo(addr, null) else { pendingConnect = addr; permLauncher.launch(allPerms.toTypedArray()) }

    // ---------- Uhr für die Laufzeit ----------
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live?.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    var confirmStop by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("Training")

        // ---------- Sensor ----------
        SectionCard("Pulssensor") {
            when (conn) {
                HrState.Conn.CONNECTED -> {
                    val zone = Calc.zoneOf(bpm, maxHr)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (bpm > 0) "$bpm" else "–", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = zoneColor(zone))
                        Text(" bpm", fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Calc.zoneNames[zone], color = zoneColor(zone), fontWeight = FontWeight.SemiBold)
                            Hint("${(bpm * 100.0 / maxHr).roundToInt()} % von HFmax $maxHr")
                        }
                    }
                    Hint("Verbunden mit ${devName ?: "Sensor"}")
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { HrService.disconnect(ctx) }) { Text("Trennen") }
                }
                HrState.Conn.CONNECTING, HrState.Conn.RECONNECTING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(
                            if (conn == HrState.Conn.RECONNECTING) "  Verbindung verloren – verbinde neu …" else "  Verbinde mit ${devName ?: "Sensor"} …",
                            Modifier.padding(start = 4.dp)
                        )
                    }
                    Hint("Die Verity Sense muss eingeschaltet sein (LED blinkt).", Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { HrService.disconnect(ctx) }) { Text("Abbrechen") }
                }
                HrState.Conn.IDLE -> {
                    val last = Repo.lastDeviceAddress
                    if (last != null) {
                        Button(onClick = { requestConnect(last) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Mit ${Repo.lastDeviceName ?: "letztem Sensor"} verbinden")
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedButton(onClick = { requestScan() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (scanning) "Suche läuft …" else "Sensor suchen")
                    }
                    if (scanning || found.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        if (found.isEmpty()) Hint("Schalte die Verity Sense ein und halte sie in die Nähe.")
                        found.sortedByDescending { it.rssi }.forEach { f ->
                            TextButton(onClick = { connectTo(f.address, f.name) }, modifier = Modifier.fillMaxWidth()) {
                                Text((if (f.isHr) "❤️ " else "") + f.name, Modifier.weight(1f))
                                Text("Signal ${f.rssi}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (err != null && conn != HrState.Conn.CONNECTED) {
                Text(err!!, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---------- Aufnahme ----------
        val l = live
        if (l == null) SickTrainingBanner()
        if (l == null) {
            var type by remember { mutableStateOf(SessionType.ENDURO) }
            SectionCard("Neue Aufnahme") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text("${t.emoji} ${t.label}") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (conn != HrState.Conn.CONNECTED) {
                    Hint("Tipp: Erst den Sensor verbinden, dann starten. Ohne Sensor werden nur Sätze gespeichert.")
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = { Recorder.start(type) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("▶  Aufnahme starten", fontSize = 18.sp)
                }
            }
        } else {
            val samples = remember(rev) { Recorder.snapshot() }
            val elapsed = now - l.start
            val avg = if (samples.isEmpty()) 0 else samples.map { it.bpm }.average().roundToInt()
            val mx = samples.maxOfOrNull { it.bpm } ?: 0
            val kcal = Calc.sessionKcal(samples, profile)
            val recent = samples.filter { it.t >= now - 10 * 60_000 }
            val zones = Calc.timeInZones(samples, maxHr)

            SectionCard("🔴 ${l.type.emoji} ${l.type.label} läuft") {
                Text(Calc.fmtDuration(elapsed), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                StatRow("Ø Puls" to "$avg", "Max" to "$mx", "kcal" to Calc.fmtNum(kcal), "Z4–5" to Calc.fmtDuration(zones[4] + zones[5]))
                Spacer(Modifier.height(10.dp))
                Hint("Letzte 10 Minuten")
                HrChart(recent, maxHr, start = maxOf(l.start, now - 10 * 60_000), markers = l.markers, height = 120.dp, interactive = false)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        Recorder.addMarker()
                        Toast.makeText(ctx, "Stelle markiert", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) { Text("⚑ Markieren") }
                    Button(
                        onClick = { confirmStop = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = androidx.compose.ui.graphics.Color.White),
                        modifier = Modifier.weight(1f)
                    ) { Text("■ Beenden") }
                }
                Hint("„Markieren“ setzt eine Marke, z. B. an einer harten Stelle. Sie erscheint später im Diagramm.", Modifier.padding(top = 6.dp))
                if (l.markers.isNotEmpty()) Hint("${l.markers.size} Markierung(en) gesetzt")
            }

            if (l.type == SessionType.GYM) GymPanel(l)
            else {
                TextButton(onClick = { Recorder.setType(SessionType.GYM) }) { Text("Doch Gym? Sätze eintragen") }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Training beenden?") },
            text = { Text("Die Aufnahme wird gespeichert und erscheint im Verlauf.") },
            confirmButton = {
                Button(onClick = {
                    confirmStop = false
                    Recorder.stop()?.let { onSaved(it.id) }
                }) { Text("Speichern") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmStop = false; Recorder.discard() }) { Text("Verwerfen", color = Bad) }
                    TextButton(onClick = { confirmStop = false }) { Text("Weiter") }
                }
            },
        )
    }
}

@Composable
private fun GymPanel(l: Recorder.Live) {
    val sessions by Repo.sessions.collectAsState()
    var exercise by remember { mutableStateOf(l.sets.lastOrNull()?.exercise ?: "") }
    var reps by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf(l.sets.lastOrNull()?.weightKg?.let { Calc.fmtNum(it, 1) } ?: "") }

    val suggestions = remember(sessions) {
        (sessions.flatMap { it.sets }.sortedByDescending { it.t }.map { it.exercise } + DEFAULT_EXERCISES)
            .distinctBy { it.lowercase() }.take(16)
    }
    val lastTime = remember(sessions, exercise) {
        if (exercise.isBlank()) null
        else sessions.filter { it.id != l.id }.firstOrNull { s -> s.sets.any { it.exercise.equals(exercise.trim(), true) } }
            ?.let { s -> s.start to s.sets.filter { it.exercise.equals(exercise.trim(), true) } }
    }

    SectionCard("🏋️ Satz eintragen") {
        OutlinedTextField(
            value = exercise, onValueChange = { exercise = it },
            label = { Text("Übung") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            items(suggestions) { s -> AssistChip(onClick = { exercise = s }, label = { Text(s) }) }
        }
        if (lastTime != null) {
            Hint(
                "Letztes Mal (${Calc.fmtDate(lastTime.first)}): " +
                    lastTime.second.joinToString(" · ") { "${it.reps}×${Calc.fmtNum(it.weightKg, 1)} kg" }
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Wiederholungen", reps, { reps = it }, Modifier.weight(1f), decimal = false)
            NumField("Gewicht kg", weight, { weight = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val r = reps.toIntOrNull()
        Button(
            enabled = exercise.isNotBlank() && r != null && r > 0,
            onClick = {
                Recorder.addSet(GymSet(exercise.trim(), r ?: 0, Calc.parseNum(weight) ?: 0.0, System.currentTimeMillis()))
                reps = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Satz speichern") }

        if (l.sets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val volume = l.sets.sumOf { it.reps * it.weightKg }
            Text("Heute: ${l.sets.size} Sätze · ${Calc.fmtNum(volume)} kg bewegt", fontWeight = FontWeight.SemiBold)
            l.sets.withIndex().reversed().forEach { (i, s) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${s.exercise}", Modifier.weight(1f))
                    Text("${s.reps} × ${Calc.fmtNum(s.weightKg, 1)} kg")
                    TextButton(onClick = { Recorder.removeSet(i) }) { Text("✕") }
                }
            }
        }
    }
}
