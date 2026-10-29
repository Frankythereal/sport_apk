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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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

    val sessions by Repo.sessions.collectAsState()
    var idx = 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        val l = live
        ScreenTitle("Training", subtitle = if (l == null) "Sensor verbinden, Sportart antippen – los geht's" else "Aufnahme läuft")

        // ---------- Pulssensor ----------
        Appear(idx++) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "📡 Pulssensor", accent = Color(0xFFFF7043)) {
                when (conn) {
                    HrState.Conn.CONNECTED -> {
                        val zone = Calc.zoneOf(bpm, maxHr)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BeatingHeart(bpm.takeIf { it > 0 }, size = 30.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (bpm > 0) "$bpm" else "–", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = zoneColor(zone))
                            Text(" bpm", fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp))
                            Spacer(Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                ZoneChip(zone)
                                Hint("${(bpm * 100.0 / maxHr).roundToInt()} % HFmax")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hint("✓ ${devName ?: "Sensor"}", Modifier.weight(1f))
                            TextButton(onClick = { HrService.disconnect(ctx) }) { Text("Trennen") }
                        }
                    }
                    HrState.Conn.CONNECTING, HrState.Conn.RECONNECTING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(
                                if (conn == HrState.Conn.RECONNECTING) "  Verbindung verloren – verbinde neu …" else "  Verbinde mit ${devName ?: "Sensor"} …",
                                Modifier.weight(1f)
                            )
                            TextButton(onClick = { HrService.disconnect(ctx) }) { Text("Abbrechen") }
                        }
                        Hint("Die Verity Sense muss eingeschaltet sein (LED blinkt).")
                    }
                    HrState.Conn.IDLE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val lastDev = Repo.lastDeviceAddress
                            if (lastDev != null) {
                                Button(onClick = { requestConnect(lastDev) }, modifier = Modifier.weight(1f)) {
                                    Text("Verbinden", maxLines = 1)
                                }
                            }
                            FilledTonalButton(onClick = { requestScan() }, modifier = Modifier.weight(1f)) {
                                Text(if (scanning) "Suche …" else "Suchen", maxLines = 1)
                            }
                        }
                        if (Repo.lastDeviceName != null) Hint("Zuletzt: ${Repo.lastDeviceName}", Modifier.padding(top = 4.dp))
                        if (scanning || found.isNotEmpty()) {
                            if (found.isEmpty()) Hint("Schalte die Verity Sense ein und halte sie in die Nähe.", Modifier.padding(top = 6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                items(found.sortedByDescending { it.rssi }) { f ->
                                    AssistChip(onClick = { connectTo(f.address, f.name) }, label = { Text((if (f.isHr) "❤️ " else "") + f.name) })
                                }
                            }
                        }
                    }
                }
                if (err != null && conn != HrState.Conn.CONNECTED) {
                    Text(err!!, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        if (l == null) {
            SickTrainingBanner()

            // ---------- Start-Kacheln ----------
            Appear(idx++) {
                Column {
                    Text("Training starten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SessionType.entries.forEach { t ->
                            ActionTile(t.emoji, t.label, Modifier.weight(1f), color = typeColor(t)) { Recorder.start(t) }
                        }
                    }
                    if (conn != HrState.Conn.CONNECTED) Hint("Tipp: Erst den Sensor verbinden – ohne Sensor werden nur Sätze gespeichert.", Modifier.padding(bottom = 12.dp))
                    else Spacer(Modifier.height(10.dp))
                }
            }

            // ---------- Letzte Trainings ----------
            val recent = sessions.take(10)
            if (recent.isNotEmpty()) {
                Appear(idx++) {
                    Column {
                        Text("Letzte Trainings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recent, key = { it.id }) { s ->
                                Tile(Modifier.width(190.dp).height(150.dp), onClick = { onSaved(s.id) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(s.type.emoji, fontSize = 20.sp)
                                        Text("  " + s.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                    Hint(Calc.fmtShortDate(s.start) + " · " + Calc.fmtDuration(s.durationMs))
                                    Spacer(Modifier.weight(1f))
                                    if (s.samples.size > 1) Sparkline(s.samples, Modifier.fillMaxWidth().height(34.dp), color = typeColor(s.type))
                                    Text("${Calc.fmtNum(s.kcal)} kcal" + if (s.maxBpm > 0) " · max ${s.maxBpm}" else "", style = MaterialTheme.typography.labelMedium, color = typeColor(s.type))
                                }
                            }
                        }
                    }
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

            // ---------- Live-Kachel ----------
            Appear(idx++) {
                Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "🔴 ${l.type.emoji} ${l.type.label}", accent = typeColor(l.type)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ZoneGauge(bpm, maxHr, Modifier.fillMaxWidth().height(150.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 60.dp)) {
                            Text(Calc.fmtDuration(elapsed), fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            Text(if (bpm > 0) "$bpm bpm" else "kein Puls", color = zoneColor(Calc.zoneOf(bpm, maxHr)), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    StatRow("Ø Puls" to "$avg", "Max" to "$mx", "kcal" to Calc.fmtNum(kcal), "Z4–5" to Calc.fmtDuration(zones[4] + zones[5]))
                }
            }

            // ---------- Steuerung ----------
            Appear(idx++) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("⚑", "Markieren" + if (l.markers.isNotEmpty()) " (${l.markers.size})" else "", Modifier.weight(1f), color = Color.White) {
                        Recorder.addMarker()
                        Toast.makeText(ctx, "Stelle markiert", Toast.LENGTH_SHORT).show()
                    }
                    if (l.type != SessionType.GYM) {
                        ActionTile("🏋️", "Sätze", Modifier.weight(1f), color = typeColor(SessionType.GYM)) { Recorder.setType(SessionType.GYM) }
                    }
                    ActionTile("■", "Beenden", Modifier.weight(1f), color = Bad) { confirmStop = true }
                }
            }

            // ---------- Verlauf ----------
            Appear(idx++) {
                Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "Letzte 10 Minuten") {
                    HrChart(recent, maxHr, start = maxOf(l.start, now - 10 * 60_000), markers = l.markers, height = 120.dp, interactive = false)
                }
            }

            if (l.type == SessionType.GYM) GymPanel(l)
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
    var reps by remember { mutableDoubleStateOf((l.sets.lastOrNull()?.reps ?: 10).toDouble()) }
    var weight by remember { mutableDoubleStateOf(l.sets.lastOrNull()?.weightKg ?: 20.0) }
    var custom by remember { mutableStateOf(false) }

    val suggestions = remember(sessions) {
        (sessions.flatMap { it.sets }.sortedByDescending { it.t }.map { it.exercise } + DEFAULT_EXERCISES)
            .distinctBy { it.lowercase() }.take(16)
    }
    val lastTime = remember(sessions, exercise) {
        if (exercise.isBlank()) null
        else sessions.filter { it.id != l.id }.firstOrNull { s -> s.sets.any { it.exercise.equals(exercise.trim(), true) } }
            ?.let { s -> s.start to s.sets.filter { it.exercise.equals(exercise.trim(), true) } }
    }

    Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "🏋️ Satz eintragen", accent = typeColor(SessionType.GYM)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(suggestions) { s ->
                FilterChip(selected = exercise.equals(s, true), onClick = { exercise = s; custom = false }, label = { Text(s) })
            }
            item { AssistChip(onClick = { custom = true; exercise = "" }, label = { Text("+ Eigene") }) }
        }
        if (custom) {
            OutlinedTextField(
                value = exercise, onValueChange = { exercise = it },
                label = { Text("Übung") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        if (lastTime != null) {
            Hint(
                "Letztes Mal (${Calc.fmtShortDate(lastTime.first)}): " +
                    lastTime.second.joinToString(" · ") { "${it.reps}×${Calc.fmtNum(it.weightKg, 1)}" },
                Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("Wiederholungen", reps, 1.0, 0, { reps = it }, Modifier.weight(1f), min = 1.0)
            Stepper("Gewicht kg", weight, 2.5, 1, { weight = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Button(
            enabled = exercise.isNotBlank(),
            onClick = { Recorder.addSet(GymSet(exercise.trim(), reps.roundToInt(), weight, System.currentTimeMillis())) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        ) { Text("✓  Satz speichern", fontSize = 16.sp) }

        if (l.sets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val volume = l.sets.sumOf { it.reps * it.weightKg }
            Text("${l.sets.size} Sätze · ${Calc.fmtNum(volume)} kg bewegt", fontWeight = FontWeight.SemiBold)
            l.sets.withIndex().groupBy { it.value.exercise }.forEach { (ex, list) ->
                Text(ex, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.index }) { (i, s) ->
                        InputChip(
                            selected = false,
                            onClick = { Recorder.removeSet(i) },
                            label = { Text("${s.reps}×${Calc.fmtNum(s.weightKg, 1)}") },
                            trailingIcon = { Text("✕", fontSize = 12.sp) },
                        )
                    }
                }
            }
            Hint("Satz antippen zum Löschen.", Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ZoneChip(zone: Int) {
    Box(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(zoneColor(zone).copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(Calc.zoneNames[zone].substringBefore(" ·"), color = zoneColor(zone), fontWeight = FontWeight.Bold) }
}

/** Halbkreis-Tacho mit den fünf Pulszonen und einer Nadel, die dem Puls folgt. */
@Composable
private fun ZoneGauge(bpm: Int, maxHr: Int, modifier: Modifier = Modifier) {
    val lo = maxHr * 0.45f
    val frac = ((bpm - lo) / (maxHr - lo)).coerceIn(0f, 1f)
    val needle by androidx.compose.animation.core.animateFloatAsState(frac, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 60f), label = "needle")
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 16.dp.toPx()
        val d = minOf(size.width, size.height * 2f) - stroke
        val tl = androidx.compose.ui.geometry.Offset((size.width - d) / 2, stroke / 2)
        val sz = androidx.compose.ui.geometry.Size(d, d)
        // Zonen als Bogenstücke: 45–50 %, 50–60 … 90–100 % HFmax
        val bounds = listOf(0.45f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
        for (z in 1..5) {
            val a0 = (bounds[z - 1] * maxHr - lo) / (maxHr - lo)
            val a1 = (bounds[z] * maxHr - lo) / (maxHr - lo)
            drawArc(zoneColor(z).copy(alpha = 0.8f), 180f + 180f * a0 + 1f, 180f * (a1 - a0) - 2f, false, tl, sz,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
        val angle = Math.toRadians((180.0 + 180.0 * needle))
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, stroke / 2 + d / 2)
        val r = d / 2 - stroke * 1.4f
        val tip = androidx.compose.ui.geometry.Offset(c.x + (r * Math.cos(angle)).toFloat(), c.y + (r * Math.sin(angle)).toFloat())
        drawLine(Color.White, c, tip, strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, 6.dp.toPx(), c)
    }
}
