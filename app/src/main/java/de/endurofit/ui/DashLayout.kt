package de.endurofit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.endurofit.data.Repo

/** Reihenfolge und Sichtbarkeit der Kacheln auf „Heute“ – frei einstellbar. */
object DashLayout {
    val all = linkedMapOf(
        "rings" to "🎯 Dein Tag in Ringen",
        "timeline" to "🕒 Dein Tag 0–24 Uhr",
        "recovery" to "🔋 Erholung & Puls",
        "sleepwater" to "😴 Schlaf & 💧 Trinken",
        "kcal" to "🔥 Kalorien & Vitalwerte",
        "pills" to "💊 Tabletten",
        "caffeine" to "☕ Kaffee & Energy",
        "weight" to "⚖️ Gewicht",
        "exercise" to "🧘 Bewegungspause",
        "training" to "🏍️ Training & Woche",
    )

    fun order(): List<String> {
        val saved = Repo.dashOrder.split(',').filter { it in all.keys }
        // Neue Kacheln, die es in der gespeicherten Liste noch nicht gibt, hinten anhängen
        return saved + all.keys.filter { it !in saved }
    }

    fun hidden(): Set<String> = Repo.dashHidden.split(',').filter { it.isNotBlank() }.toSet()

    /** [rev] nur damit Compose nach dem Ändern neu liest */
    @Suppress("UNUSED_PARAMETER")
    fun visible(rev: Int): List<String> = order().filter { it !in hidden() }

    fun save(order: List<String>, hidden: Set<String>) {
        Repo.dashOrder = order.joinToString(",")
        Repo.dashHidden = hidden.joinToString(",")
    }
}

@Composable
fun DashEditDialog(onDismiss: () -> Unit) {
    val order = remember { mutableStateListOf<String>().apply { addAll(DashLayout.order()) } }
    val hidden = remember { mutableStateListOf<String>().apply { addAll(DashLayout.hidden()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kacheln anordnen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Hint("Mit ▲▼ verschieben, mit dem Schalter ein- oder ausblenden.")
                Spacer(Modifier.height(6.dp))
                order.forEachIndexed { i, key ->
                    val on = key !in hidden
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (on) 0.8f else 0.3f))
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(DashLayout.all[key] ?: key, Modifier.weight(1f), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        IconButton(enabled = i > 0, onClick = { order.add(i - 1, order.removeAt(i)) }) { Text("▲") }
                        IconButton(enabled = i < order.size - 1, onClick = { order.add(i + 1, order.removeAt(i)) }) { Text("▼") }
                        Switch(on, onCheckedChange = { if (it) hidden.remove(key) else hidden.add(key) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { DashLayout.save(order.toList(), hidden.toSet()); onDismiss() }) { Text("Fertig") }
        },
        dismissButton = {
            TextButton(onClick = { DashLayout.save(DashLayout.all.keys.toList(), emptySet()); onDismiss() }) { Text("Standard") }
        },
    )
}
