package de.endurofit.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.health.connect.client.PermissionController
import de.endurofit.data.Repo
import de.endurofit.data.WatchBrand
import de.endurofit.data.Watches
import de.endurofit.health.SuuntoImport
import kotlinx.coroutines.launch

/** Kachel-Raster zur Auswahl der Uhr. */
@Composable
fun WatchPicker(selected: String?, onSelect: (WatchBrand) -> Unit) {
    Watches.all.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            pair.forEach { b ->
                val on = b.id == selected
                val bg by animateColorAsState(if (on) Accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant, label = "bg")
                Column(
                    Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(4.dp)).background(bg)
                        .then(if (on) Modifier.border(2.dp, Accent, RoundedCornerShape(4.dp)) else Modifier)
                        .clickable { onSelect(b) }.padding(10.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (b.id == "none") "📱" else "⌚", fontSize = 22.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(b.name, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.weight(1f))
                        if (on) Text("✓", color = Accent, fontWeight = FontWeight.Bold)
                    }
                    Text(b.app, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    if (!b.healthConnect) Text("nur über Umweg", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFA726))
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Anleitung für die gewählte Uhr + Knopf zum Verbinden. */
@Composable
fun WatchInstructions(b: WatchBrand, onConnected: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    Text("So kommen die Daten deiner ${b.name} in die App", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    b.steps.forEachIndexed { i, st ->
        Row(Modifier.padding(vertical = 3.dp)) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(st, Modifier.weight(1f))
        }
    }
    Hint("Kommt an: ${b.gets}", Modifier.padding(top = 6.dp))
    if (b.note.isNotBlank()) Hint("💡 ${b.note}", Modifier.padding(top = 2.dp))
    if (b.packages.isNotEmpty() && b.id != "none") {
        val pkg = b.packages.first().takeIf { it.contains('.') }
        if (pkg != null) {
            val installed = remember(pkg) { try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false } }
            if (!installed && b.healthConnect) {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("${b.app} im Play Store öffnen") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    HealthConnectButton(onConnected)
}

/** Health Connect erlauben und gleich die ersten Daten holen. */
@Composable
fun HealthConnectButton(onConnected: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ok = try { SuuntoImport.hasPermissions(ctx) } catch (_: Exception) { false } }

    fun firstImport() {
        scope.launch {
            busy = true
            status = try {
                val r = SuuntoImport.import(ctx, days = 14)
                val src = r.stepSource?.let { SuuntoImport.appLabel(ctx, it) }
                if (r.stepDays + r.hrDays + r.nights + r.imported == 0)
                    "Verbunden ✓ – noch keine Daten angekommen. Synchronisiere die Uhr in ihrer App, dann kommen sie beim nächsten Öffnen."
                else "Verbunden ✓ – ${r.imported} Trainings, ${r.stepDays} Tage Schritte, ${r.hrDays} Tage Puls, ${r.nights} Nächte" + (src?.let { " (Quelle: $it)" } ?: "")
            } catch (e: Exception) { "Fehler: ${e.message}" }
            busy = false
            onConnected?.invoke()
        }
    }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        ok = granted.isNotEmpty()
        if (ok) firstImport() else status = "Ohne Erlaubnis kann EnduroFit keine Daten der Uhr lesen. Du kannst das später im Profil nachholen."
    }

    if (!SuuntoImport.available(ctx)) {
        Hint("Health Connect ist auf diesem Handy nicht installiert.")
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Health Connect installieren") }
        return
    }
    Button(
        onClick = { if (ok) firstImport() else launcher.launch(SuuntoImport.PERMISSIONS) },
        enabled = !busy, modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Hole Daten …" else if (ok) "✓ Verbunden – Daten jetzt holen" else "🔗 Mit Health Connect verbinden") }
    status?.let { Hint(it, Modifier.padding(top = 4.dp)) }
}

/** Abschnitt im Profil: Uhr wählen und einrichten. */
@Composable
fun WatchSection() {
    var sel by remember { mutableStateOf(Repo.watchBrand) }
    val brand = Watches.byId(sel)
    SectionCard(
        "⌚ Deine Uhr", collapsible = true, startOpen = brand == null,
        subtitle = brand?.let { "${it.name} · über ${it.app}" } ?: "Noch keine ausgewählt",
    ) {
        WatchPicker(sel) { sel = it.id; Repo.watchBrand = it.id }
        brand?.let {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            WatchInstructions(it)
            if (it.polarDirect) Hint("Zusätzlich kannst du Polar unten unter „Polar direkt“ verbinden – dann kommen auch Erholung, HRV und Hauttemperatur.", Modifier.padding(top = 6.dp))
            if (it.fitImport) Hint("Einzelne Trainings kannst du außerdem als FIT-Datei an EnduroFit teilen.", Modifier.padding(top = 2.dp))
        }
    }
}

/** Kleiner Hinweis, wenn noch keine Uhr gewählt ist */
@Composable
fun WatchMissingHint(onOpen: () -> Unit) {
    if (Repo.watchBrand != null) return
    Tile(Modifier.fillMaxWidth().padding(bottom = 12.dp), accent = Accent, onClick = onOpen) {
        Text("⌚ Welche Uhr hast du? Tippe hier, um sie einzurichten.", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
    }
}
