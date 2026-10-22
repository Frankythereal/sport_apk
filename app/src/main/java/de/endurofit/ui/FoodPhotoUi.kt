@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.endurofit.ui

import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.endurofit.ai.FoodVision
import de.endurofit.data.Calc
import de.endurofit.data.FoodEntry
import de.endurofit.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Knöpfe "Foto" und "Galerie" plus der ganze Ablauf bis zum Speichern. */
@Composable
fun FoodPhotoButtons(day: Long) {
    val ctx = LocalContext.current
    var photo by remember { mutableStateOf<Uri?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val shared by FoodVision.sharedImage.collectAsState()

    LaunchedEffect(shared) {
        shared?.let { photo = it; FoodVision.sharedImage.value = null }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo = cameraUri
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = uri
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            try {
                val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
                // Alte Fotos aufräumen – sie werden nur für die Analyse gebraucht
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "essen_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                cameraUri = uri
                camera.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Kamera nicht verfügbar", Toast.LENGTH_SHORT).show()
            }
        }, modifier = Modifier.weight(1f)) { Text("📷 Essen fotografieren") }
        OutlinedButton(onClick = {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text("🖼️ Galerie") }
    }

    photo?.let { uri ->
        FoodPhotoFlow(uri = uri, day = day, onDone = { photo = null })
    }
}

@Composable
private fun FoodPhotoFlow(uri: Uri, day: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var hint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<FoodVision.Result?>(null) }

    LaunchedEffect(uri) {
        preview = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    val s = 600.0 / max(info.size.width, info.size.height)
                    if (s < 1) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    val r = result
    if (r != null) {
        FoodResultDialog(r, preview, day, onDone)
        return
    }

    val noKey = Repo.claudeKey.isBlank()
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Essen erkennen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview?.let {
                    Image(
                        it, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                    )
                }
                if (noKey) {
                    Text("Für die Erkennung brauchst du einmalig einen Claude-API-Schlüssel.", fontWeight = FontWeight.SemiBold)
                    Hint("So geht's: Profil → „Essensfotos erkennen“ → Schlüssel eintragen. Den Schlüssel bekommst du unter console.anthropic.com (API Keys).")
                } else {
                    OutlinedTextField(
                        hint, { hint = it },
                        label = { Text("Zusatzinfo (optional)") },
                        placeholder = { Text("z. B. große Portion, mit Sahnesoße") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("  Claude schaut sich dein Essen an …")
                        }
                    }
                    error?.let { Text(it, color = Bad) }
                    Hint("Das Foto wird zur Auswertung an Anthropic (Claude) geschickt und nicht in der App gespeichert.")
                }
            }
        },
        confirmButton = {
            if (!noKey) Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        result = FoodVision.analyze(ctx, uri, hint)
                    } catch (e: Exception) {
                        error = e.message ?: "Unbekannter Fehler"
                    }
                    busy = false
                }
            }) { Text(if (error != null) "Nochmal" else "Analysieren") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text(if (noKey) "OK" else "Abbrechen") } },
    )
}

@Composable
private fun FoodResultDialog(r: FoodVision.Result, preview: ImageBitmap?, day: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(r.name) }
    var factor by remember { mutableDoubleStateOf(1.0) }
    var kcal by remember(factor) { mutableStateOf(Calc.fmtNum(r.kcal * factor)) }
    var addWater by remember { mutableStateOf(r.liquidMl > 0 && r.kcal < 60) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (r.isFood) "Erkannt" else "Kein Essen erkannt") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                preview?.let {
                    Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)))
                }
                if (!r.isFood) {
                    Text("Auf dem Foto habe ich nichts zu essen gefunden. Versuch es mit einem anderen Foto oder trag es von Hand ein.")
                    return@Column
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Portion", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5 to "½", 0.75 to "¾", 1.0 to "1×", 1.25 to "1¼", 1.5 to "1½", 2.0 to "2×").forEach { (f, l) ->
                        FilterChip(selected = factor == f, onClick = { factor = f }, label = { Text(l) })
                    }
                }

                r.items.forEach { item ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("• ${item.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${(item.grams * factor).roundToInt()} g · ${Calc.fmtNum(item.kcal * factor)} kcal",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    kcal, { v -> kcal = v.filter { it.isDigit() || it == ',' || it == '.' } },
                    label = { Text("Kalorien gesamt (kcal)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                StatRow(
                    "Eiweiß" to "${Calc.fmtNum(r.protein * factor)} g",
                    "Kohlenh." to "${Calc.fmtNum(r.carbs * factor)} g",
                    "Fett" to "${Calc.fmtNum(r.fat * factor)} g",
                )
                if (r.liquidMl > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = addWater, onCheckedChange = { addWater = it })
                        Text("${(r.liquidMl * factor).roundToInt()} ml beim Trinken mitzählen")
                    }
                }
                Hint(
                    "Sicherheit der Schätzung: ${r.confidence}" + if (r.note.isNotBlank()) " · ${r.note}" else "",
                )
                Hint("Fotos sind Schätzungen – wiege bei Bedarf nach oder passe die Kalorien an.")
            }
        },
        confirmButton = {
            if (r.isFood) Button(onClick = {
                val now = System.currentTimeMillis()
                val t = if (Calc.dayStart(now) == day) now else day + 12 * 3_600_000L
                Repo.addFood(
                    FoodEntry(
                        id = "f_$now",
                        t = t,
                        name = name.trim().ifBlank { "Mahlzeit" },
                        kcal = Calc.parseNum(kcal) ?: (r.kcal * factor),
                        protein = r.protein * factor,
                        carbs = r.carbs * factor,
                        fat = r.fat * factor,
                        fromPhoto = true,
                    )
                )
                if (addWater && r.liquidMl > 0) Repo.addWater((r.liquidMl * factor).roundToInt(), t)
                Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
                onDone()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(if (r.isFood) "Verwerfen" else "OK") } },
    )
}

/** Einstellungen für die Fotoerkennung im Profil. */
@Composable
fun FoodAiSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Repo.claudeKey) }
    var model by remember { mutableStateOf(Repo.claudeModel) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    SectionCard("📷 Essensfotos erkennen") {
        Hint(
            "Die App schickt dein Essensfoto an Claude und bekommt Kalorien, Eiweiß, Kohlenhydrate und Fett zurück. " +
                "Klappt auch mit Fotos von Nährwerttabellen. Dafür brauchst du einen eigenen API-Schlüssel: " +
                "auf console.anthropic.com anmelden, unter „Billing“ ein kleines Guthaben aufladen (5 € reichen lange) und unter „API Keys“ einen Schlüssel erstellen."
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            key, { key = it.trim() },
            label = { Text("API-Schlüssel (sk-ant-…)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        FoodVision.MODELS.forEach { (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = model == id, onClick = { model = id })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy && key.isNotBlank(), onClick = {
                Repo.claudeKey = key
                Repo.claudeModel = model
                scope.launch {
                    busy = true
                    status = "Teste …"
                    status = FoodVision.test()?.let { "❌ $it" } ?: "✅ Funktioniert"
                    busy = false
                }
            }) { Text("Speichern & testen") }
            if (Repo.claudeKey.isNotBlank()) {
                TextButton(onClick = { Repo.claudeKey = ""; key = ""; status = null }) { Text("Löschen") }
            }
        }
        status?.let { Text(it, Modifier.padding(top = 4.dp)) }
        Hint("Der Schlüssel bleibt nur auf deinem Handy.", Modifier.padding(top = 4.dp))
    }
}
