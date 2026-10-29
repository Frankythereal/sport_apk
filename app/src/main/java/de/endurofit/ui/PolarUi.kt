package de.endurofit.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import de.endurofit.data.Calc
import de.endurofit.data.CardioLoad
import de.endurofit.data.Recovery
import de.endurofit.data.Repo
import de.endurofit.polar.PolarApi
import kotlin.math.roundToInt

private val PolarRed = Color(0xFFD7192D)

// ------------------------------------------------------------------ Einrichtung im Profil

@Composable
fun PolarSettings() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var id by remember { mutableStateOf(Repo.polarClientId) }
    var secret by remember { mutableStateOf(Repo.polarSecret) }
    LaunchedEffect(Unit) { PolarApi.refreshConnected() }
    val connected by PolarApi.isConnected.collectAsState()
    val waiting by PolarApi.waiting.collectAsState()
    val syncing by PolarApi.syncing.collectAsState()
    val status by PolarApi.status.collectAsState()
    var help by remember { mutableStateOf(!PolarApi.connected) }
    var pasted by remember { mutableStateOf("") }

    SectionCard("🔴 Polar direkt verbinden") {
        Hint(
            "Holt zusätzlich, was Polar nicht an Health Connect gibt: Puls rund um die Uhr, Nightly Recharge (HRV, Nacht-Puls, Atmung), " +
                "Schlaf-Score und Trainingsbelastung – und jedes Training mit GPS-Strecke."
        )
        Spacer(Modifier.height(8.dp))
        if (connected) {
            Text("✅ Verbunden" + if (Repo.polarLastSync > 0) " · zuletzt ${Calc.fmtDateTime(Repo.polarLastSync)}" else "", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !syncing, onClick = { PolarApi.syncInBackground(ctx, 7) }) { Text(if (syncing) "Lade …" else "Jetzt synchronisieren") }
                OutlinedButton(enabled = !syncing, onClick = { PolarApi.syncInBackground(ctx, 28) }) { Text("28 Tage") }
            }
            TextButton(onClick = { PolarApi.logout() }) { Text("Verbindung trennen", color = Bad) }
        } else {
            TextButton(onClick = { help = !help }) { Text(if (help) "Anleitung ausblenden" else "Anleitung (einmalig, ca. 5 Minuten)") }
            if (help) {
                Text(
                    "1. Im Browser admin.polaraccesslink.com öffnen und mit deinem Polar-Konto anmelden.\n" +
                        "2. Einen neuen „API client“ anlegen (Name z. B. EnduroFit), alle drei Datentypen einschalten.\n" +
                        "3. Als „Authorization callback domain“ genau diese Adresse eintragen:",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(PolarApi.REDIRECT, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(PolarApi.REDIRECT))
                        Toast.makeText(ctx, "Kopiert", Toast.LENGTH_SHORT).show()
                    }) { Text("Kopieren") }
                }
                Text(
                    "4. „Client ID“ und „Client Secret“ hier eintragen.\n" +
                        "5. „Mit Polar verbinden“ tippen – die Anmeldung muss hier auf dem Handy im Browser passieren, nicht am PC.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
            }
            OutlinedTextField(id, { id = it.trim() }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                secret, { secret = it.trim() }, label = { Text("Client Secret") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Button(enabled = !syncing && id.isNotBlank() && secret.isNotBlank(), onClick = {
                Repo.polarClientId = id
                Repo.polarSecret = secret
                pasted = ""
                PolarApi.startLogin(ctx)
            }) { Text(if (waiting) "Nochmal öffnen" else "Mit Polar verbinden") }

            if (waiting || status?.startsWith("❌") == true) {
                Spacer(Modifier.height(10.dp))
                Text("Browser zeigt „Seite nicht erreichbar“?", fontWeight = FontWeight.SemiBold)
                Hint("Kein Problem: Tippe im Browser oben in die Adresszeile, kopiere die ganze Adresse (beginnt mit http://localhost:5000/oauth2_callback?code=…) und füge sie hier ein. Der Code gilt nur wenige Minuten.")
                OutlinedTextField(
                    pasted, { pasted = it }, label = { Text("Adresse aus dem Browser") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.getText()?.text?.let { pasted = it } }) { Text("📋 Einfügen") }
                    Button(enabled = pasted.isNotBlank() && !syncing, onClick = { PolarApi.finishWithPasted(pasted) }) { Text("Verbinden") }
                }
            }
        }
        status?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
        if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        if (PolarApi.debugFile().exists()) {
            TextButton(onClick = {
                try {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", PolarApi.debugFile())
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Polar-Rohdaten teilen"
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Teilen nicht möglich", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Polar-Rohdaten teilen (zur Fehlersuche)") }
        }
        Hint("Tipp: Auf der Uhr die kontinuierliche Herzfrequenzmessung einschalten, sonst gibt es keinen 24/7-Puls.", Modifier.padding(top = 4.dp))
    }
}

// ------------------------------------------------------------------ Erholung auf "Heute"

fun rechargeText(status: Int?): String = when (status) {
    1 -> "sehr schlecht"; 2 -> "schlecht"; 3 -> "eingeschränkt"; 4 -> "OK"; 5 -> "gut"; 6 -> "sehr gut"; else -> "–"
}

fun rechargeColor(status: Int?): Color = when (status) {
    1, 2 -> Bad; 3 -> Color(0xFFFFA726); 4 -> Color(0xFF9CCC65); 5, 6 -> Good; else -> Color.Gray
}

fun cardioText(status: String): String? {
    val s = status.uppercase()
    return when {
        s.isBlank() || "NOT_AVAILABLE" in s || "UNKNOWN" in s -> null
        "DETRAIN" in s -> "Formverlust – du trainierst gerade weniger als gewohnt"
        "MAINTAIN" in s -> "Erhaltend – Belastung hält dein Niveau"
        "PRODUCTIVE" in s -> "Produktiv – du wirst fitter"
        "OVERREACH" in s || "STRAIN" in s -> "Überlastet – mehr Erholung einplanen"
        else -> status.replace('_', ' ').lowercase()
    }
}

@Composable
fun RecoveryCard() {
    val rec by Repo.recovery.collectAsState()
    val cardio by Repo.cardio.collectAsState()
    val today = Calc.dayStart(System.currentTimeMillis())
    val r = rec[today] ?: rec[Calc.addDays(today, -1)]
    // "Nicht verfügbar" (Polar braucht erst eine Trainingshistorie) wie fehlend behandeln
    val c = (0..3).firstNotNullOfOrNull { cardio[Calc.addDays(today, -it)]?.takeIf { cl -> cardioText(cl.status) != null } }
    if (r == null && c == null) return

    SectionCard("🔋 Erholung") {
        if (r != null) {
            if (r.day != today) Hint("Von gestern – heute noch nicht synchronisiert.")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(rechargeText(r.status).replaceFirstChar { it.uppercase() }, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = rechargeColor(r.status))
                Text("  Nightly Recharge", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            val hrvBase = (1..28).mapNotNull { rec[Calc.addDays(r.day, -it)]?.hrv }.takeIf { it.size >= 3 }?.average()
            StatRow(
                "HRV" to (r.hrv?.let { "${it.roundToInt()} ms" } ?: "–"),
                "Nacht-Puls" to (r.nightHr?.toString() ?: "–"),
                "Atmung" to (r.breathing?.let { Calc.fmtNum(it, 1) } ?: "–"),
                "Schlaf-Score" to (r.sleepScore?.toString() ?: "–"),
            )
            if (r.hrv != null && hrvBase != null) {
                val d = (r.hrv ?: 0.0) - hrvBase
                Hint(
                    "HRV ${if (d >= 0) "+" else "−"}${kotlin.math.abs(d).roundToInt()} ms gegenüber deinem Schnitt (${hrvBase.roundToInt()} ms)" +
                        when {
                            d <= -8 -> " – Körper ist gestresst."
                            d >= 5 -> " – gut erholt."
                            else -> "."
                        },
                    Modifier.padding(top = 4.dp)
                )
            }
            r.ansCharge?.let { Hint("ANS-Charge (Nervensystem): ${if (it >= 0) "+" else ""}${Calc.fmtNum(it, 1)} (Skala −10 bis +10)") }
        }
        val ct = c?.let { cardioText(it.status) }
        if (c != null && ct != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("🏋️ Trainingsbelastung: $ct", fontWeight = FontWeight.SemiBold)
            Hint("Belastung ${Calc.fmtNum(c.strain, 0)} · Toleranz ${Calc.fmtNum(c.tolerance, 0)} · Verhältnis ${Calc.fmtNum(c.ratio, 2)}")
        }
        // Empfehlung für heute
        val bad = (r?.status ?: 4) <= 2 || (r?.ansCharge ?: 0.0) < -3 || c?.status?.uppercase()?.contains("OVERREACH") == true
        val good = (r?.status ?: 0) >= 5 && c?.status?.uppercase()?.contains("OVERREACH") != true
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                bad -> "👉 Heute eher locker: Zone 1–2 oder Ruhetag."
                good -> "👉 Gute Voraussetzungen – heute darf es auch hart werden."
                else -> "👉 Normales Training möglich."
            },
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ------------------------------------------------------------------ HRV-Verlauf (Herz-Ansicht)

@Composable
fun HrvTrendCard(days: List<Long>) {
    val rec by Repo.recovery.collectAsState()
    val pts = days.mapNotNull { d -> rec[d]?.hrv?.let { d to it } }
    if (pts.size < 2) return
    val night = days.mapNotNull { d -> rec[d]?.nightHr?.let { d to it.toDouble() } }
    SectionCard("🧠 HRV & Nacht-Puls (Polar)") {
        val half = pts.size / 2
        val trend = if (pts.size >= 6) pts.takeLast(half).map { it.second }.average() - pts.take(half).map { it.second }.average() else null
        StatRow(
            "Ø HRV" to "${pts.map { it.second }.average().roundToInt()} ms",
            "Ø Nacht-Puls" to (if (night.isEmpty()) "–" else "${night.map { it.second }.average().roundToInt()}"),
        )
        trend?.let {
            Hint(
                when {
                    it >= 3 -> "📈 HRV steigt – du erholst dich besser."
                    it <= -3 -> "📉 HRV sinkt – achte auf Schlaf, Stress und Belastung."
                    else -> "HRV stabil."
                },
                Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        val lo = (pts.minOf { it.second } - 5).coerceAtLeast(0.0)
        val hi = pts.maxOf { it.second } + 5
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = days.size
            val slot = size.width / n
            val path = Path()
            pts.forEachIndexed { i, (d, v) ->
                val x = days.indexOf(d) * slot + slot / 2
                val y = (size.height - ((v - lo) / (hi - lo)) * size.height).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(Color(0xFF26C6DA), 3.5f, Offset(x, y))
            }
            drawPath(path, Color(0xFF26C6DA), style = Stroke(width = 2.5f))
        }
        Hint("Höhere HRV = besser erholt. Vergleiche nur mit deinen eigenen Werten.", Modifier.padding(top = 4.dp))
    }
}
