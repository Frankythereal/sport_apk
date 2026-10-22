package de.endurofit.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.endurofit.data.Calc
import de.endurofit.data.FoodEntry
import de.endurofit.data.FoodText
import kotlinx.coroutines.flow.MutableStateFlow

/** Text, der per "Teilen" an EnduroFit geschickt wurde (z. B. eine ChatGPT-Antwort). */
object SharedFoodText {
    val text = MutableStateFlow<String?>(null)
}

/**
 * Text einfügen (z. B. Antwort von ChatGPT) – die App liest Kalorien und Nährwerte
 * selbst heraus, ganz ohne KI. Danach kann man alles im normalen Dialog prüfen.
 */
@Composable
fun PasteFoodDialog(initial: String, onDismiss: () -> Unit, onParsed: (FoodEntry) -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember {
        mutableStateOf(initial.ifBlank { clipboard.getText()?.text?.takeIf { FoodText.parse(it).found } ?: "" })
    }
    val parsed = remember(text) { FoodText.parse(text) }
    var showHelp by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text einfügen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Füge hier die Antwort von ChatGPT, eine Nährwerttabelle oder eigene Notizen ein. Die App liest Kalorien, Eiweiß, Kohlenhydrate und Fett selbst heraus.")
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Text") },
                    minLines = 4, maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.getText()?.text?.let { text = it }
                            ?: Toast.makeText(ctx, "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
                    }) { Text("📋 Einfügen") }
                    TextButton(onClick = { showHelp = !showHelp }) { Text("ChatGPT-Tipp") }
                }
                if (showHelp) {
                    Hint("Gib ChatGPT diesen Text vor deiner Beschreibung oder deinem Foto. Dann endet die Antwort immer mit einem Block, den die App sicher erkennt:")
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(FoodText.CHATGPT_PROMPT))
                        Toast.makeText(ctx, "Text kopiert – jetzt in ChatGPT einfügen", Toast.LENGTH_LONG).show()
                    }) { Text("Vorlage für ChatGPT kopieren") }
                    Hint("Tipp: In ChatGPT kannst du die Antwort auch über „Teilen“ direkt an EnduroFit schicken.")
                }
                if (text.isNotBlank()) {
                    HorizontalDivider()
                    if (parsed.found) {
                        Text("Erkannt: ${parsed.name}", fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                parsed.kcal?.let { "${Calc.fmtNum(it)} kcal" },
                                parsed.protein?.let { "E ${Calc.fmtNum(it)} g" },
                                parsed.carbs?.let { "K ${Calc.fmtNum(it)} g" },
                                parsed.fat?.let { "F ${Calc.fmtNum(it)} g" },
                            ).joinToString(" · ")
                        )
                        if (parsed.kcal == null) Hint("Keine Kalorien gefunden – du kannst sie im nächsten Schritt eintragen.")
                    } else {
                        Text("Im Text habe ich keine Nährwerte gefunden.", color = Bad)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = parsed.found, onClick = {
                onParsed(
                    FoodEntry(
                        id = "f_${System.currentTimeMillis()}",
                        t = System.currentTimeMillis(),
                        name = parsed.name,
                        kcal = parsed.kcal ?: 0.0,
                        protein = parsed.protein ?: 0.0,
                        carbs = parsed.carbs ?: 0.0,
                        fat = parsed.fat ?: 0.0,
                    )
                )
            }) { Text("Weiter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
