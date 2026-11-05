package de.endurofit.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.KcalMode
import de.endurofit.data.Repo
import de.endurofit.data.Watches

/** Geführte Einrichtung beim ersten Start – eine Frage pro Seite, jederzeit überspringbar. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val last = 9
    var page by rememberSaveable { mutableIntStateOf(0) }
    fun finish() { Repo.onboarded = true; onDone() }

    Surface(Modifier.fillMaxSize(), color = Ef.Ink) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            if (page > 0) {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 14.dp)) {
                    SquareStepper(last, page - 1)
                }
            }
            AnimatedContent(
                targetState = page, modifier = Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn() + slideInHorizontally { it * dir / 6 }) togetherWith fadeOut()
                }, label = "page",
            ) { p ->
                if (p == 0) Welcome()
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    when (p) {
                        1 -> SexAge()
                        2 -> Weight()
                        3 -> Height()
                        4 -> Goal()
                        5 -> ChooseWatch()
                        6 -> ConnectWatch()
                        7 -> SensorPage()
                        8 -> StepsAndNotif()
                        else -> DonePage()
                    }
                    if (p in 1..8) TextButton(onClick = { finish() }, contentPadding = PaddingValues(0.dp)) {
                        Caps("Später einrichten ›", color = Accent)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                when (page) {
                    0 -> Button(onClick = { page++ }, modifier = Modifier.fillMaxWidth()) { Text("LOS GEHT'S  ▶") }
                    last -> Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("ZUR APP  ▶") }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { page-- }) { Text("‹") }
                        FilledTonalButton(onClick = { page++ }, modifier = Modifier.weight(1f)) { Text("WEITER  ›") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Question(before: String, accent: String = "", after: String = "", sub: String? = null) {
    AccentTitle(before, accent, after, Modifier.padding(top = 18.dp))
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Ef.Muted, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun Welcome() {
    val ex = remember { ExerciseLib.byId("strecken")!! }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF8FC9C0)), contentAlignment = Alignment.Center) {
            ExerciseFigure(ex, Modifier.fillMaxSize().padding(40.dp), color = Color(0xFF0A1715))
        }
        Column(Modifier.padding(20.dp)) {
            Caps("Dein Trainings-Begleiter", color = Accent)
            Spacer(Modifier.height(10.dp))
            Text("Willkommen bei EnduroFit", style = MaterialTheme.typography.headlineLarge, color = Ef.Text)
            Text(
                "Training, Puls, Essen, Schlaf, Tabletten und deine Uhr an einem Ort. In ein paar Schritten ist alles eingerichtet – alle Daten bleiben auf deinem Handy.",
                style = MaterialTheme.typography.bodyMedium, color = Ef.Muted, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun SexAge() {
    var male by remember { mutableStateOf(Repo.profile.value.male) }
    var age by remember { mutableIntStateOf(Repo.profile.value.age) }
    LaunchedEffect(male, age) { Repo.saveProfile(Repo.profile.value.copy(male = male, age = age)) }
    Question("Wer ", "bist", " du?")
    ChoiceRow("Mann", selected = male) { male = true }
    ChoiceRow("Frau", selected = !male) { male = false }
    Spacer(Modifier.height(20.dp))
    Caps("Alter")
    WheelPicker((14..90).toList(), age, { age = it }, unit = "Jahre")
}

@Composable
private fun Weight() {
    var w by remember { mutableIntStateOf(Repo.profile.value.weightKg.toInt()) }
    LaunchedEffect(w) { Repo.saveProfile(Repo.profile.value.copy(weightKg = w.toDouble())) }
    Question("Wie viel ", "wiegst", " du?", "Mit Waage wird das später automatisch aktuell gehalten.")
    WheelPicker((35..200).toList(), w, { w = it }, unit = "kg")
}

@Composable
private fun Height() {
    var h by remember { mutableIntStateOf(Repo.profile.value.heightCm) }
    LaunchedEffect(h) { Repo.saveProfile(Repo.profile.value.copy(heightCm = h)) }
    Question("Wie ", "groß", " bist du?")
    WheelPicker((130..220).toList(), h, { h = it }, unit = "cm")
}

@Composable
private fun Goal() {
    var goals by remember { mutableStateOf(Repo.goals.value) }
    Question("Was ist dein ", "Ziel", "?")
    val subs = mapOf(
        KcalMode.ABNEHMEN to "Fett verbrennen, leichter werden",
        KcalMode.HALTEN to "Fit bleiben, Gewicht halten",
        KcalMode.AUFBAUEN to "Muskeln & Kraft aufbauen",
    )
    listOf(KcalMode.ABNEHMEN, KcalMode.HALTEN, KcalMode.AUFBAUEN).forEach { m ->
        ChoiceRow(m.label, subs[m], selected = goals.mode == m) { goals = goals.copy(mode = m); Repo.saveGoals(goals) }
    }
    Text(goals.mode.hint, style = MaterialTheme.typography.bodySmall, color = Ef.Muted, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun ChooseWatch() {
    var sel by remember { mutableStateOf(Repo.watchBrand) }
    Question("Welche ", "Uhr", " hast du?", "Die Daten kommen über Health Connect – fast jede Uhr kann das.")
    Watches.all.forEach { b ->
        ChoiceRow(b.name, b.app + if (!b.healthConnect) " · nur über Umweg" else "", selected = sel == b.id) { sel = b.id; Repo.watchBrand = b.id }
    }
}

@Composable
private fun ConnectWatch() {
    val b = Watches.selected() ?: Watches.byId("none")!!
    Question("Uhr ", "verbinden", "", "Einmal einrichten – danach holt die App die Daten bei jedem Öffnen automatisch.")
    WatchInstructions(b)
    if (b.polarDirect) Hint("Tipp: Im Profil unter „Polar direkt“ kommen zusätzlich Erholung, HRV und Hauttemperatur.", Modifier.padding(top = 8.dp))
}

@Composable
private fun Info(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 1.dp).background(Ef.Panel).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ef.Text)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ef.Muted)
    }
}

@Composable
private fun SensorPage() {
    Question("Puls beim ", "Training", "", "Für genaue Pulsspitzen (z. B. beim Enduro) kannst du einen Bluetooth-Sensor verbinden.")
    Info("Armband oder Brustgurt", "z. B. Polar Verity Sense, Polar H10, Garmin HRM, Wahoo – alles mit Bluetooth-Puls")
    Info("So geht's", "Im Tab „Training“ auf „Suchen“ tippen und den Sensor einschalten. Die App merkt ihn sich.")
    Info("Ohne Sensor", "Kein Problem – Trainings von der Uhr kommen automatisch dazu.")
}

@Composable
private fun StepsAndNotif() {
    var goals by remember { mutableStateOf(Repo.goals.value) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Question("Wie viele ", "Schritte", " am Tag?")
    WheelPicker((2..20).map { it * 1000 }, (goals.steps / 1000) * 1000, { goals = goals.copy(steps = it); Repo.saveGoals(goals) },
        unit = "Schritte", format = { de.endurofit.data.Analysis.fmtInt(it) })
    Spacer(Modifier.height(16.dp))
    Caps("Erinnerungen")
    Text("Für Tabletten, Bewegungspausen und Trainings braucht die App die Erlaubnis für Benachrichtigungen.", style = MaterialTheme.typography.bodySmall, color = Ef.Muted, modifier = Modifier.padding(vertical = 6.dp))
    if (Build.VERSION.SDK_INT >= 33) {
        FilledTonalButton(onClick = { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("BENACHRICHTIGUNGEN ERLAUBEN") }
    }
}

@Composable
private fun DonePage() {
    Question("Alles ", "bereit", "!", "Noch ein paar Tipps für den Start:")
    Info("Heute", "Deine Übersicht – mit „Kacheln anordnen“ ganz unten baust du sie dir zusammen.")
    Info("Widgets", "Lange auf den Startbildschirm drücken → Widgets → EnduroFit: Überblick, Essen & Trinken, Tabletten, Training.")
    Info("Akku", "Für zuverlässige Erinnerungen: Einstellungen → Apps → EnduroFit → Akku → „Nicht eingeschränkt“.")
    Info("Profil", "Uhr, Waage, Ziele, Essensfoto-KI – und diese Einrichtung erneut starten.")
}
