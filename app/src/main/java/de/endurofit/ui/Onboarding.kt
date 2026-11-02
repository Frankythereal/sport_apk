package de.endurofit.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.KcalMode
import de.endurofit.data.Repo
import de.endurofit.data.Watches

/** Geführte Einrichtung beim ersten Start – lässt sich jederzeit überspringen und im Profil erneut starten. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val pages = 7
    var page by rememberSaveable { mutableIntStateOf(0) }
    fun finish() { Repo.onboarded = true; onDone() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            // Fortschritt
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in 0 until pages) {
                        val w by animateFloatAsState(if (i == page) 1f else 0f, label = "dot")
                        Box(
                            Modifier.height(6.dp).width((10 + 18 * w).dp).clip(RoundedCornerShape(3.dp))
                                .background(if (i <= page) Accent else MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
                if (page < pages - 1) TextButton(onClick = { finish() }) { Text("Überspringen") }
            }
            Spacer(Modifier.height(12.dp))

            AnimatedContent(
                targetState = page, modifier = Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn() + slideInHorizontally { it * dir / 5 }) togetherWith fadeOut()
                }, label = "page",
            ) { p ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (p) {
                        0 -> Welcome()
                        1 -> AboutYou()
                        2 -> ChooseWatch()
                        3 -> ConnectWatch()
                        4 -> SensorPage()
                        5 -> GoalsPage()
                        else -> DonePage()
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (page > 0) OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f)) { Text("Zurück") }
                Button(
                    onClick = { if (page < pages - 1) page++ else finish() },
                    modifier = Modifier.weight(2f),
                ) { Text(if (page == 0) "Los geht's" else if (page < pages - 1) "Weiter" else "Fertig – zur App") }
            }
        }
    }
}

@Composable
private fun Big(emoji: String, title: String, text: String) {
    Text(emoji, fontSize = 52.sp, modifier = Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFBDBDBD), modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
}

@Composable
private fun Feature(emoji: String, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text(emoji, fontSize = 24.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Hint(text)
        }
    }
}

@Composable
private fun Welcome() {
    Big("🏍️", "Willkommen bei EnduroFit", "Dein Training, Essen, Schlaf und deine Uhr an einem Ort. In ein paar Schritten ist alles eingerichtet.")
    Feature("❤️", "Puls & Training", "Live-Puls mit Zonen und Spitzen, Gym-Sätze, Trainings von der Uhr")
    Feature("🍽️", "Essen & Trinken", "Per Foto, Text oder von Hand – mit Kalorienbilanz")
    Feature("😴", "Schlaf & Erholung", "Von deiner Uhr, mit Ruhepuls-Verlauf")
    Feature("💊", "Tabletten, Kaffee, Gewicht", "Erinnerungen, Koffein-Warnung, Waage")
    Hint("Alle Daten bleiben auf deinem Handy.", Modifier.padding(top = 10.dp))
}

@Composable
private fun AboutYou() {
    val p0 = remember { Repo.profile.value }
    var age by remember { mutableStateOf(p0.age.toString()) }
    var weight by remember { mutableStateOf(de.endurofit.data.Calc.fmtNum(p0.weightKg, 1)) }
    var height by remember { mutableStateOf(p0.heightCm.toString()) }
    var male by remember { mutableStateOf(p0.male) }
    LaunchedEffect(age, weight, height, male) {
        val p = Repo.profile.value
        Repo.saveProfile(
            p.copy(
                age = age.toIntOrNull()?.takeIf { it in 10..99 } ?: p.age,
                weightKg = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..250.0 } ?: p.weightKg,
                heightCm = height.toIntOrNull()?.takeIf { it in 120..230 } ?: p.heightCm,
                male = male,
            )
        )
    }
    Big("👤", "Über dich", "Damit stimmen Kalorien, Grundumsatz und deine Pulszonen.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(true to "🙋‍♂️ Mann", false to "🙋‍♀️ Frau").forEach { (m, l) ->
            FilterChip(male == m, onClick = { male = m }, label = { Text(l, fontSize = 16.sp) }, modifier = Modifier.weight(1f).height(48.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    NumField("Alter", age, { age = it }, Modifier.fillMaxWidth(), decimal = false)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumField("Gewicht (kg)", weight, { weight = it }, Modifier.weight(1f))
        NumField("Größe (cm)", height, { height = it }, Modifier.weight(1f), decimal = false)
    }
    Hint("Max. Puls und Ruhepuls rechnet die App selbst aus – mit Uhr später genauer.", Modifier.padding(top = 8.dp))
}

@Composable
private fun ChooseWatch() {
    var sel by remember { mutableStateOf(Repo.watchBrand) }
    Big("⌚", "Welche Uhr hast du?", "Die App liest die Daten über Health Connect – fast jede Uhr kann das.")
    WatchPicker(sel) { sel = it.id; Repo.watchBrand = it.id }
}

@Composable
private fun ConnectWatch() {
    val b = Watches.selected() ?: Watches.byId("none")!!
    Big("🔗", "Uhr verbinden", "Einmal einrichten – danach holt die App die Daten bei jedem Öffnen automatisch.")
    WatchInstructions(b)
    if (b.polarDirect) Hint("Tipp: Im Profil unter „Polar direkt“ bekommst du zusätzlich Erholung, HRV und Hauttemperatur.", Modifier.padding(top = 8.dp))
}

@Composable
private fun SensorPage() {
    Big("📡", "Puls beim Training", "Für genaue Pulsspitzen (z. B. beim Enduro) kannst du einen Bluetooth-Pulssensor verbinden.")
    Feature("💪", "Armband oder Brustgurt", "z. B. Polar Verity Sense, Polar H10, Garmin HRM, Wahoo – alles mit Bluetooth-Puls")
    Feature("▶️", "So geht's", "Im Tab „Training“ auf „Suchen“ tippen, Sensor einschalten – fertig. Die App merkt ihn sich.")
    Feature("⌚", "Ohne Sensor", "Kein Problem: Trainings von der Uhr kommen automatisch dazu.")
}

@Composable
private fun GoalsPage() {
    var goals by remember { mutableStateOf(Repo.goals.value) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Big("🎯", "Deine Ziele", "Gesunde Startwerte – alles später im Profil änderbar.")
    Text("Was ist dein Ziel?", fontWeight = FontWeight.SemiBold)
    KcalMode.entries.forEach { m ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(if (goals.mode == m) Accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
                .clickable { goals = goals.copy(mode = m); Repo.saveGoals(goals) }.padding(12.dp),
        ) {
            Column {
                Text(m.label, fontWeight = FontWeight.SemiBold)
                Hint(m.hint)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Schritte am Tag", fontWeight = FontWeight.SemiBold)
    Segmented(listOf(6000 to "6.000", 8000 to "8.000", 10000 to "10.000", 12000 to "12.000"), goals.steps, {
        goals = goals.copy(steps = it); Repo.saveGoals(goals)
    }, Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(14.dp))
    Text("Erinnerungen", fontWeight = FontWeight.SemiBold)
    Hint("Für Tabletten, Bewegungspausen und Trainings braucht die App die Erlaubnis für Benachrichtigungen.")
    if (Build.VERSION.SDK_INT >= 33) {
        OutlinedButton(onClick = { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.padding(top = 6.dp)) { Text("🔔 Benachrichtigungen erlauben") }
    }
}

@Composable
private fun DonePage() {
    Big("🎉", "Alles bereit!", "Noch ein paar Tipps:")
    Feature("🏠", "Heute", "Deine Übersicht – mit „✏️ Kacheln anordnen“ ganz unten baust du sie dir zusammen.")
    Feature("📱", "Widgets", "Lange auf den Startbildschirm drücken → Widgets → EnduroFit: Überblick, Essen & Trinken, Tabletten, Training.")
    Feature("🔋", "Akku", "Damit Erinnerungen und Pulsaufnahme zuverlässig laufen: Einstellungen → Apps → EnduroFit → Akku → „Nicht eingeschränkt“.")
    Feature("👤", "Profil", "Uhr, Waage, Ziele, Essensfoto-KI – und diese Einrichtung erneut starten.")
}
