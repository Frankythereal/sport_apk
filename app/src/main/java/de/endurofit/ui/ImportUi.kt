package de.endurofit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.endurofit.data.Calc
import de.endurofit.health.FitImport
import kotlinx.coroutines.launch

/** Ergebnis eines FIT-Imports als Dialog. */
@Composable
fun FitResultDialog(result: FitImport.Result, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.sessions.isNotEmpty()) "✅ Import fertig" else "Import fehlgeschlagen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                result.sessions.forEach { s ->
                    Text("${s.type.emoji} ${s.title}", fontWeight = FontWeight.SemiBold)
                    Hint(
                        Calc.fmtDateTime(s.start) + " · " + Calc.fmtDuration(s.durationMs) +
                            (if (s.samples.isNotEmpty()) " · max ${s.maxBpm} bpm" else "") +
                            (if (s.track.size > 1) " · mit GPS-Strecke" else " · ohne GPS")
                    )
                }
                result.errors.forEach { Text("⚠️ $it", color = Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (result.sessions.size == 1) {
                Button(onClick = { onOpen(result.sessions.first().id) }) { Text("Ansehen") }
            } else {
                Button(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (result.sessions.size == 1) TextButton(onClick = onDismiss) { Text("Schließen") }
        },
    )
}

/** Knopf "FIT-Dateien importieren" plus kurze Anleitung. */
@Composable
fun FitImportSection(onOpen: (String) -> Unit, showHelp: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FitImport.Result?>(null) }
    var help by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            result = FitImport.import(ctx, uris)
            busy = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (busy) "Lese …" else "📂 FIT-Dateien importieren")
        }
        if (showHelp) TextButton(onClick = { help = !help }) { Text(if (help) "Weniger" else "Wie?") }
    }
    if (help) {
        Hint(
            "In der Suunto-App ein Training öffnen → oben rechts ⋮ → „Training als FIT exportieren“ (bzw. „Teilen“) → " +
                "EnduroFit auswählen. Gespeicherte .fit-Dateien kannst du auch hier über den Knopf öffnen. " +
                "Du kannst mehrere Dateien auf einmal wählen; doppelt importierte Trainings werden aktualisiert, nicht verdoppelt.",
            Modifier.padding(top = 4.dp)
        )
    }
    result?.let { r -> FitResultDialog(r, onOpen = { result = null; onOpen(it) }, onDismiss = { result = null }) }
}
