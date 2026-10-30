@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.Goals
import de.endurofit.data.KcalMode
import de.endurofit.data.Profile
import de.endurofit.data.Repo
import de.endurofit.health.SuuntoImport
import de.endurofit.ai.FoodVision
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(onOpenSession: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val profile by Repo.profile.collectAsState()
    val sessions by Repo.sessions.collectAsState()
    val food by Repo.food.collectAsState()

    var age by remember(profile) { mutableStateOf(profile.age.toString()) }
    var weight by remember(profile) { mutableStateOf(Calc.fmtNum(profile.weightKg, 1)) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.toString()) }
    var male by remember(profile) { mutableStateOf(profile.male) }
    var maxHr by remember(profile) { mutableStateOf(if (profile.maxHr > 0) profile.maxHr.toString() else "") }
    var restHr by remember(profile) { mutableStateOf(profile.restHr.toString()) }

    val draft = Profile(
        age = age.toIntOrNull() ?: profile.age,
        weightKg = Calc.parseNum(weight) ?: profile.weightKg,
        heightCm = height.toIntOrNull() ?: profile.heightCm,
        male = male,
        maxHr = maxHr.toIntOrNull() ?: 0,
        restHr = restHr.toIntOrNull() ?: profile.restHr,
    )

    val exportSessions = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = Csv.write(ctx, uri, Csv.sessions(sessions))
            Toast.makeText(ctx, if (ok) "Trainings exportiert" else "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }
    val exportFood = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = Csv.write(ctx, uri, Csv.food(food))
            Toast.makeText(ctx, if (ok) "Ernährung exportiert" else "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    val body by Repo.watchBody.collectAsState()
    val polarOn by de.endurofit.polar.PolarApi.isConnected.collectAsState()
    LaunchedEffect(Unit) { de.endurofit.polar.PolarApi.refreshConnected() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        // ---------- Kopf ----------
        Appear(0) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(58.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Accent, androidx.compose.ui.graphics.Color(0xFFD7192D)))),
                        contentAlignment = Alignment.Center,
                    ) { Text("🏍️", fontSize = 28.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dein Profil", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Hint("${profile.age} Jahre · ${Calc.fmtNum(profile.weightKg, 1)} kg · ${profile.heightCm} cm")
                    }
                }
                Spacer(Modifier.height(12.dp))
                StatRow(
                    "HFmax" to "${profile.effectiveMaxHr}",
                    "Ruhepuls" to "${profile.restHr}",
                    "VO2max" to (body.vo2max?.let { Calc.fmtNum(it) } ?: "–"),
                    "Grundums." to Calc.fmtNum(Calc.bmr(profile)),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill("Polar", polarOn)
                    StatusPill("Sensor", Repo.lastDeviceAddress != null)
                    StatusPill("Waage", Repo.scaleAddress != null)
                    StatusPill("Foto-KI", Repo.claudeKey.isNotBlank())
                }
            }
        }

        WeightCard()

        SectionCard("👤 Deine Daten", collapsible = true, startOpen = false, subtitle = "Alter, Gewicht, Größe, Puls") {
            Hint("Damit rechnet die App Kalorien, Grundumsatz und Pulszonen.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Alter", age, { age = it }, Modifier.weight(1f), decimal = false)
                NumField("Gewicht kg", weight, { weight = it }, Modifier.weight(1f))
                NumField("Größe cm", height, { height = it }, Modifier.weight(1f), decimal = false)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = male, onClick = { male = true }, label = { Text("Mann") })
                FilterChip(selected = !male, onClick = { male = false }, label = { Text("Frau") })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Max. Puls (leer = auto)", maxHr, { maxHr = it }, Modifier.weight(1f), decimal = false)
                NumField("Ruhepuls", restHr, { restHr = it }, Modifier.weight(1f), decimal = false)
            }
            Hint(
                "Kennst du deinen echten Maximalpuls (z. B. aus einem Rennen oder von der Uhr), trag ihn ein – " +
                    "das macht die Zonen viel genauer.",
                Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(10.dp))
            Button(enabled = draft != profile, onClick = {
                Repo.saveProfile(draft)
                Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
            }) { Text("Speichern") }
        }

        SectionCard("❤️ Deine Pulszonen", collapsible = true, startOpen = false, subtitle = "Z1–Z5 nach deinem max. Puls") {
            val m = draft.effectiveMaxHr
            Text("HFmax: $m bpm" + if (draft.maxHr == 0) " (geschätzt aus dem Alter)" else "", fontWeight = FontWeight.SemiBold)
            Hint("Grundumsatz: ${Calc.fmtNum(Calc.bmr(draft))} kcal pro Tag")
            Spacer(Modifier.height(6.dp))
            val bounds = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
            for (z in 5 downTo 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Z$z", Modifier.width(32.dp), color = zoneColor(z), fontWeight = FontWeight.Bold)
                    Text(Calc.zoneNames[z].substringAfter("· "), Modifier.weight(1f))
                    Text("${(m * bounds[z - 1]).toInt()}–${(m * bounds[z]).toInt()} bpm")
                }
            }
        }

        GoalsEditor()

        PolarSettings()
        WatchBodyCard()

        SectionCard("📂 Trainings als FIT-Datei", collapsible = true, startOpen = false, subtitle = "Import aus Suunto & Co.") {
            Hint("Falls deine Uhr keine Daten an Health Connect gibt (z. B. Suunto): Trainings als FIT-Datei aus der Uhren-App teilen. Damit kommen Puls, Kalorien und die GPS-Strecke mit.")
            Spacer(Modifier.height(8.dp))
            FitImportSection(onOpen = onOpenSession)
        }

        SectionCard("🔗 Health Connect", collapsible = true, startOpen = false, subtitle = "Schritte, Blutsauerstoff, Trainings") {
            SuuntoImportSection(compact = false)
            Spacer(Modifier.height(8.dp))
            HealthDiagnosis()
        }

        FoodAiSettings()

        SectionCard("📤 Export", collapsible = true, startOpen = false, subtitle = "CSV für Excel") {
            Hint("Als CSV-Datei, die sich in Excel oder Google Tabellen öffnen lässt.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportSessions.launch("trainings.csv") }, modifier = Modifier.weight(1f)) { Text("Trainings") }
                OutlinedButton(onClick = { exportFood.launch("ernaehrung.csv") }, modifier = Modifier.weight(1f)) { Text("Ernährung") }
            }
        }

        SectionCard("💡 Tipps für saubere Aufnahmen", collapsible = true, startOpen = false) {
            Text("• Samsung beendet gern Apps im Hintergrund. Stell unter Einstellungen → Apps → EnduroFit → Akku auf „Nicht eingeschränkt“.")
            Text("• Die Verity Sense am Oberarm oder oberen Unterarm tragen, nicht direkt am Ellbogenprotektor.")
            Text("• Das Handy muss beim Fahren dabei sein (Rucksack oder Jacke), damit es den Puls empfängt.")
            Text("• Reißt die Verbindung ab, verbindet sich die App automatisch neu.")
        }
        Hint("EnduroFit · alle Daten bleiben auf deinem Handy", Modifier.padding(bottom = 24.dp))
    }
}

/** Knopf + Status für den Import aus Health Connect. */
@Composable
fun SuuntoImportSection(compact: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = remember { SuuntoImport.available(ctx) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var onlySuunto by remember { mutableStateOf(Repo.onlySuunto) }

    fun runImport() {
        scope.launch {
            busy = true
            status = try {
                val r = SuuntoImport.import(ctx)
                val src = r.stepSource?.let { " (heute von ${SuuntoImport.appLabel(ctx, it)})" } ?: ""
                val base = "Trainings: ${r.imported} neu, ${r.updated} aktualisiert · Schritte: ${r.stepDays} Tage$src · " +
                    "Puls: ${r.hrDays} Tage · Schlaf: ${r.nights} Nächte · Blutsauerstoff: ${r.spo2} Werte"
                if (r.imported + r.updated + r.stepDays + r.nights + r.spo2 + r.hrDays == 0 && r.skippedOtherApps == 0)
                    "Health Connect hat keine Daten geliefert. Unten bei „Health Connect prüfen“ siehst du, welche App was liefert."
                else base + if (r.skippedOtherApps > 0) " · ${r.skippedOtherApps} Trainings anderer Apps übersprungen" else ""
            } catch (e: Exception) {
                "Fehler: ${e.message}"
            }
            busy = false
        }
    }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(SuuntoImport.PERMISSIONS)) runImport()
        else status = "Ohne Berechtigung kann ich die Daten der Uhr nicht lesen."
    }

    if (!compact) {
        Hint(
            "Holt Puls über den Tag, Ruhepuls, Schritte, Blutsauerstoff, Schlaf und Trainings der letzten 30 Tage aus Health Connect – " +
                "von der Uhr, und wenn die nichts liefert, die Schritte vom Handy (Samsung Health)."
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trainings nur von der Uhr (Polar, Suunto, Garmin)", Modifier.weight(1f))
            Switch(checked = onlySuunto, onCheckedChange = { onlySuunto = it; Repo.onlySuunto = it })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = {
            if (!available) {
                status = "Health Connect ist auf diesem Handy nicht verfügbar."
            } else {
                scope.launch {
                    try {
                        if (SuuntoImport.hasPermissions(ctx)) runImport() else launcher.launch(SuuntoImport.PERMISSIONS)
                    } catch (e: Exception) {
                        status = "Fehler: ${e.message}"
                    }
                }
            }
        }) { Text(if (busy) "Importiere …" else "⌚ Uhr-Daten importieren") }
        if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
    }
    status?.let { Hint(it, Modifier.padding(top = 4.dp)) }
    if (!compact && Repo.lastImport > 0 && status == null) {
        Hint("Zuletzt importiert: ${Calc.fmtDateTime(Repo.lastImport)}", Modifier.padding(top = 4.dp))
    }
}

/** Ziele anpassen – mit gesunden Standardwerten. */
@Composable
private fun GoalsEditor() {
    val ctx = LocalContext.current
    val goals by Repo.goals.collectAsState()
    val profile by Repo.profile.collectAsState()

    var mode by remember(goals) { mutableStateOf(goals.mode) }
    var steps by remember(goals) { mutableStateOf(goals.steps.toString()) }
    var water by remember(goals) { mutableStateOf(if (goals.waterMl > 0) goals.waterMl.toString() else "") }
    var protein by remember(goals) { mutableStateOf(Calc.fmtNum(goals.proteinPerKg, 1)) }
    var sleep by remember(goals) { mutableStateOf(Calc.fmtNum(goals.sleepHours, 1)) }
    var active by remember(goals) { mutableStateOf(goals.weeklyActiveMin.toString()) }
    var strength by remember(goals) { mutableStateOf(goals.weeklyStrength.toString()) }

    val draft = Goals(
        steps = steps.toIntOrNull()?.coerceAtLeast(1000) ?: goals.steps,
        waterMl = water.toIntOrNull() ?: 0,
        proteinPerKg = Calc.parseNum(protein)?.coerceIn(0.5, 3.0) ?: goals.proteinPerKg,
        weeklyActiveMin = active.toIntOrNull()?.coerceAtLeast(10) ?: goals.weeklyActiveMin,
        weeklyStrength = strength.toIntOrNull()?.coerceIn(0, 14) ?: goals.weeklyStrength,
        sleepHours = Calc.parseNum(sleep)?.coerceIn(4.0, 12.0) ?: goals.sleepHours,
        mode = mode,
    )

    SectionCard("🎯 Ziele", collapsible = true, startOpen = false, subtitle = "${goals.mode.label} · ${goals.steps} Schritte · ${Calc.fmtNum(goals.sleepHours, 1)} h Schlaf") {
        Text("Was willst du erreichen?", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
            KcalMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
            }
        }
        Hint(mode.hint + " Zielbereich: ${mode.lo} bis +${mode.hi} kcal am Tag.")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Schritte/Tag", steps, { steps = it }, Modifier.weight(1f), decimal = false)
            NumField("Wasser ml (leer = auto)", water, { water = it }, Modifier.weight(1f), decimal = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Eiweiß g pro kg", protein, { protein = it }, Modifier.weight(1f))
            NumField("Schlaf Stunden", sleep, { sleep = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Aktive Min./Woche", active, { active = it }, Modifier.weight(1f), decimal = false)
            NumField("Krafttraining/Woche", strength, { strength = it }, Modifier.weight(1f), decimal = false)
        }
        Spacer(Modifier.height(6.dp))
        Hint(
            "Für dich heißt das: ${Calc.fmtNum(Analysis.proteinGoal(draft, profile))} g Eiweiß und " +
                "${Calc.fmtNum(Analysis.waterGoal(draft, profile, 0) / 1000.0, 1)} l Wasser am Tag (an Trainingstagen mehr)."
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = draft != goals, onClick = {
                Repo.saveGoals(draft)
                Toast.makeText(ctx, "Ziele gespeichert", Toast.LENGTH_SHORT).show()
            }) { Text("Speichern") }
            TextButton(onClick = { Repo.saveGoals(Goals(mode = mode)) }) { Text("Empfohlene Werte") }
        }
        Spacer(Modifier.height(8.dp))
        Hint(
            "Warum diese Werte? 7.000–10.000 Schritte am Tag senken laut Studien das Risiko für Herz-Kreislauf-" +
                "Erkrankungen deutlich. Die WHO empfiehlt 150 Minuten Bewegung und 2× Krafttraining pro Woche. " +
                "1,6 g Eiweiß pro kg unterstützt den Muskelaufbau bei Krafttraining, 7–9 Stunden Schlaf die Erholung. " +
                "Das sind allgemeine Richtwerte – bei Vorerkrankungen besser mit dem Arzt absprechen."
        )
    }
}


/** Zeigt, welche App welche Daten an Health Connect liefert – hilft bei "kommt nichts an". */
@Composable
private fun HealthDiagnosis() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<Map<String, List<SuuntoImport.SourceInfo>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    OutlinedButton(enabled = !busy, onClick = {
        if (!SuuntoImport.available(ctx)) {
            error = "Health Connect ist auf diesem Handy nicht verfügbar."
        } else scope.launch {
            busy = true
            error = null
            result = try { SuuntoImport.diagnose(ctx) } catch (e: Exception) { error = e.message; null }
            busy = false
        }
    }) { Text(if (busy) "Prüfe …" else "🔍 Health Connect prüfen") }
    error?.let { Hint(it, Modifier.padding(top = 4.dp)) }

    val r = result ?: return
    Spacer(Modifier.height(8.dp))
    Text("Daten der letzten 7 Tage:", fontWeight = FontWeight.SemiBold)
    r.forEach { (type, sources) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(type, Modifier.width(120.dp))
            Column(Modifier.weight(1f)) {
                when {
                    sources.isEmpty() -> Text("– nichts angekommen", color = Bad)
                    sources.first().count < 0 -> Text(sources.first().label, color = Bad)
                    else -> sources.forEach {
                        Text("${it.label}: ${it.count}× · zuletzt ${Calc.fmtDateTime(it.last)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    val watch = r.values.flatten().firstOrNull { SuuntoImport.isWatch(it.pkg) }?.label
    Spacer(Modifier.height(6.dp))
    Hint(
        if (watch != null) "Deine Uhr ($watch) liefert Daten. Was oben fehlt, gibt die Uhren-App nicht an Health Connect weiter."
        else "Von deiner Uhr kommt nichts an. So prüfst du es: 1) In der App der Uhr die Health-Connect-Verbindung einschalten (Polar Flow: Allgemeine Einstellungen → Health Connect). " +
            "2) Android-Einstellungen → Health Connect → App-Berechtigungen → App der Uhr → alles erlauben. " +
            "3) Die Uhr einmal mit ihrer App synchronisieren. Trainings kannst du zur Not immer als FIT-Datei teilen."
    )
}


@Composable
private fun StatusPill(label: String, ok: Boolean) {
    val c = if (ok) Good else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "✓ " else "○ ", color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
