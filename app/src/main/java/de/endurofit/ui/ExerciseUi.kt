package de.endurofit.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.endurofit.data.Calc
import de.endurofit.work.BreakMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

val MoveColor = Color(0xFF26C6DA)

/** Von außen (Benachrichtigung) eine Übung öffnen */
object ExerciseNav {
    val open = MutableStateFlow<String?>(null)
}

private fun catColor(c: ExCat) = when (c) {
    ExCat.DEHNEN -> Color(0xFFAB47BC)
    ExCat.MOBIL -> MoveColor
    ExCat.KRAFT -> Accent
    ExCat.KREISLAUF -> Color(0xFFEF5350)
    ExCat.RUHE -> Color(0xFF66BB6A)
}

/** Arbeitsmodus ein/aus mit Intervall – für Training-Tab und Heute-Kachel. */
@Composable
fun WorkModeCard() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BreakMode.load(ctx) }
    val active by BreakMode.active.collectAsState()
    val next by BreakMode.nextAt.collectAsState()
    val done by BreakMode.doneToday.collectAsState()
    var interval by remember { mutableIntStateOf(BreakMode.interval(ctx)) }
    var hours by remember { mutableIntStateOf(BreakMode.hours(ctx)) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { BreakMode.start(ctx) }

    Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "💻 Arbeitsmodus & Pausen", accent = MoveColor) {
        if (active) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Läuft ✓", fontWeight = FontWeight.Bold, color = Good)
                    Hint("Nächste Pause ${if (next > 0) Calc.fmtClock(next) + " Uhr" else "–"} · bis ${Calc.fmtClock(BreakMode.until(ctx))} Uhr · heute $done Pause(n)")
                }
                OutlinedButton(onClick = { BreakMode.stop(ctx) }) { Text("Feierabend") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilledTonalButton(onClick = { ExerciseNav.open.value = BreakMode.nextExercise(ctx).id }) { Text("Pause jetzt") }
            }
        } else {
            Hint("Wenn du am PC arbeitest: Die App erinnert dich regelmäßig an eine kurze Bewegungspause und zeigt dir eine passende Übung. Nur solange du es einschaltest.")
            Spacer(Modifier.height(8.dp))
            Text("Pause alle", style = MaterialTheme.typography.labelMedium)
            Segmented(listOf(30 to "30 Min.", 45 to "45 Min.", 60 to "60 Min.", 90 to "90 Min."), interval, { interval = it; BreakMode.setInterval(ctx, it) })
            Spacer(Modifier.height(6.dp))
            Text("Arbeitszeit (danach automatisch aus)", style = MaterialTheme.typography.labelMedium)
            Segmented(listOf(4 to "4 Std.", 6 to "6 Std.", 8 to "8 Std.", 10 to "10 Std."), hours, { hours = it; BreakMode.setHours(ctx, it) })
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else BreakMode.start(ctx)
            }, modifier = Modifier.fillMaxWidth()) { Text("💻 Ich arbeite jetzt – Pausen an") }
            if (done > 0) Hint("Heute schon $done Bewegungspause(n) 👍", Modifier.padding(top = 4.dp))
        }
    }
}

/** Kachel auf „Heute“: nächste Übung + Arbeitsmodus. */
@Composable
fun ExerciseTodayTile() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BreakMode.load(ctx) }
    val active by BreakMode.active.collectAsState()
    val next by BreakMode.nextAt.collectAsState()
    val done by BreakMode.doneToday.collectAsState()
    val ex = BreakMode.nextExercise(ctx)
    Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), title = "🧘 Bewegungspause", accent = MoveColor, onClick = { ExerciseNav.open.value = ex.id }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExerciseFigure(ex, Modifier.size(70.dp), catColor(ex.cat))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(ex.name, fontWeight = FontWeight.SemiBold)
                Hint(ex.reps)
                Hint(
                    if (active) "💻 Arbeitsmodus · nächste Pause ${Calc.fmtClock(next)}" else if (done > 0) "Heute $done Pause(n) ✓" else "Tippen zum Mitmachen",
                    Modifier.padding(top = 2.dp),
                )
            }
            if (!active) TextButton(onClick = { BreakMode.start(ctx) }) { Text("💻 Arbeit") }
        }
    }
}

/** Übungen für zwischendurch im Training-Tab. */
@Composable
fun ExerciseSection() {
    var cat by remember { mutableStateOf<ExCat?>(null) }
    var round by remember { mutableStateOf<List<Exercise>?>(null) }
    val list = ExerciseLib.all.filter { cat == null || it.cat == cat }

    Text("Übungen für zwischendurch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    WorkModeCard()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        item { FilterChip(cat == null, onClick = { cat = null }, label = { Text("Alle") }) }
        items(ExCat.entries) { c -> FilterChip(cat == c, onClick = { cat = if (cat == c) null else c }, label = { Text(c.emoji + " " + c.label) }) }
    }
    Button(
        onClick = { round = listOf("nacken", "kniebeuge", "seitbeuge", "wade").mapNotNull { ExerciseLib.byId(it) }.shuffled().take(3) + ExerciseLib.all.filter { it.cat == ExCat.RUHE }.shuffled().take(1) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MoveColor),
    ) { Text("▶ 3-Minuten-Runde (4 Übungen)") }
    list.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            pair.forEach { ex ->
                Tile(Modifier.weight(1f).height(190.dp), onClick = { ExerciseNav.open.value = ex.id }) {
                    ExerciseFigure(ex, Modifier.fillMaxWidth().height(110.dp), catColor(ex.cat))
                    Text(ex.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Hint("${ex.cat.emoji} ${ex.reps}")
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(8.dp))

    round?.let { r -> ExercisePlayer(r, onClose = { round = null }) }
}

/** Global: Übung anzeigen, wenn eine geöffnet werden soll (z. B. aus der Pausen-Benachrichtigung). */
@Composable
fun ExerciseHost() {
    val id by ExerciseNav.open.collectAsState()
    ExerciseLib.byId(id)?.let { ex -> ExercisePlayer(listOf(ex), onClose = { ExerciseNav.open.value = null }) }
}

/** Großansicht mit Animation, Anleitung und Timer. Mehrere Übungen = Runde. */
@Composable
fun ExercisePlayer(list: List<Exercise>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }
    val ex = list[idx]
    var left by remember(idx) { mutableIntStateOf(ex.seconds) }
    var running by remember(idx) { mutableStateOf(false) }
    LaunchedEffect(running, idx) {
        while (running && left > 0) { delay(1000); left-- }
        if (running && left == 0) running = false
    }
    fun finish() {
        BreakMode.done(ctx)
        if (idx < list.size - 1) idx++ else onClose()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (list.size > 1) Hint("Übung ${idx + 1} von ${list.size}")
                        Text(ex.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${ex.cat.emoji} ${ex.cat.label} · ${ex.reps}", color = catColor(ex.cat))
                    }
                    IconButton(onClick = onClose) { Text("✕", fontSize = 20.sp) }
                }
                Box(
                    Modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(4.dp))
                        .background(catColor(ex.cat).copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    ExerciseFigure(ex, Modifier.fillMaxSize().padding(12.dp), catColor(ex.cat))
                }
                if (ex.seconds > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${left / 60}:${"%02d".format(left % 60)}",
                            fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            color = if (left == 0) Good else Color.Unspecified,
                        )
                        if (left > 0) Button(onClick = { running = !running }) { Text(if (running) "⏸ Pause" else "▶ Start") }
                        else Text("Geschafft!", color = Good, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { 1f - left.toFloat() / ex.seconds },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = catColor(ex.cat),
                    )
                    if (ex.breathe && running) {
                        val phase = (ex.seconds - left) % 10
                        Text(if (phase < 4) "Einatmen …" else "Ausatmen …", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(8.dp))
                ex.steps.forEachIndexed { i, st ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", Modifier.width(22.dp), fontWeight = FontWeight.Bold, color = catColor(ex.cat))
                        Text(st)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { finish() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Good)) {
                        Text(if (idx < list.size - 1) "✓ Weiter" else "✓ Erledigt")
                    }
                    OutlinedButton(onClick = {
                        val q = Uri.encode(ex.search.ifBlank { ex.name + " Übung" })
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
                    }) { Text("▶ Videos") }
                }
                Hint("„Videos“ sucht passende Anleitungen auf YouTube. Bei Schmerzen abbrechen.", Modifier.padding(top = 6.dp))
            }
        }
    }
}
