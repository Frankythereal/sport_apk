@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import de.endurofit.data.Calc
import de.endurofit.data.Profile
import de.endurofit.data.Repo
import de.endurofit.health.SuuntoImport
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("Profil")

        SectionCard("Deine Daten") {
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
                "Kennst du deinen echten Maximalpuls (z. B. aus einem Rennen oder der Suunto-Uhr), trag ihn ein – " +
                    "das macht die Zonen viel genauer.",
                Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(10.dp))
            Button(enabled = draft != profile, onClick = {
                Repo.saveProfile(draft)
                Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
            }) { Text("Speichern") }
        }

        SectionCard("Deine Pulszonen") {
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

        SectionCard("Suunto-Daten") { SuuntoImportSection(compact = false) }

        SectionCard("Export") {
            Hint("Als CSV-Datei, die sich in Excel oder Google Tabellen öffnen lässt.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportSessions.launch("trainings.csv") }, modifier = Modifier.weight(1f)) { Text("Trainings") }
                OutlinedButton(onClick = { exportFood.launch("ernaehrung.csv") }, modifier = Modifier.weight(1f)) { Text("Ernährung") }
            }
        }

        SectionCard("Tipps für saubere Aufnahmen") {
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
                val base = "${r.imported} neu, ${r.updated} aktualisiert"
                if (r.imported + r.updated == 0 && r.skippedOtherApps == 0)
                    "Nichts gefunden. Ist in der Suunto-App „In Health Connect speichern“ aktiviert?"
                else base + if (r.skippedOtherApps > 0) " · ${r.skippedOtherApps} von anderen Apps übersprungen" else ""
            } catch (e: Exception) {
                "Fehler: ${e.message}"
            }
            busy = false
        }
    }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(SuuntoImport.PERMISSIONS)) runImport()
        else status = "Ohne Berechtigung kann ich die Suunto-Daten nicht lesen."
    }

    if (!compact) {
        Hint(
            "Holt deine Uhr-Aktivitäten (Puls, Kalorien) der letzten 30 Tage über Health Connect. " +
                "Dafür in der Suunto-App unter Einstellungen „In Health Connect speichern“ einschalten."
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nur Suunto-Daten", Modifier.weight(1f))
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
        }) { Text(if (busy) "Importiere …" else "⌚ Suunto-Daten importieren") }
        if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
    }
    status?.let { Hint(it, Modifier.padding(top = 4.dp)) }
    if (!compact && Repo.lastImport > 0 && status == null) {
        Hint("Zuletzt importiert: ${Calc.fmtDateTime(Repo.lastImport)}", Modifier.padding(top = 4.dp))
    }
}
