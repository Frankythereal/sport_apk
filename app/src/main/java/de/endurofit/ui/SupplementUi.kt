package de.endurofit.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.endurofit.data.Calc
import de.endurofit.data.Nutrient
import de.endurofit.data.Repo
import de.endurofit.data.Sick
import de.endurofit.data.SuppIntake
import de.endurofit.data.SuppKind
import de.endurofit.data.SuppSlot
import de.endurofit.data.Supplement
import de.endurofit.data.Supps
import de.endurofit.supp.SuppActions
import de.endurofit.supp.SuppCalendar
import de.endurofit.supp.SuppReminders
import java.util.Calendar

val PillColor = Color(0xFFAB47BC)

private fun suppColor(k: SuppKind) = when (k) {
    SuppKind.MULTI -> Color(0xFF66BB6A)
    SuppKind.D3K2 -> Color(0xFFFFCA28)
    SuppKind.MAGNESIUM -> Color(0xFF42A5F5)
    SuppKind.IRON -> Color(0xFFEF5350)
    SuppKind.OTHER -> PillColor
}

@Composable
private fun dueNow(): List<Supps.Due> {
    val supps by Repo.supps.collectAsState()
    val intakes by Repo.suppIntakes.collectAsState()
    val sessions by Repo.sessions.collectAsState()
    val sick by Repo.sick.collectAsState()
    val today = Calc.dayStart(System.currentTimeMillis())
    return remember(supps, intakes, sessions, sick) {
        Supps.dueToday(supps, intakes, Calc.sessionsOfDay(sessions, today).isNotEmpty(), Sick.isSickDay(sick, today))
            .filter { it.priority > 0 }
    }
}

/** Inhalt der Kachel auf „Heute“. */
@Composable
fun SuppTileContent() {
    val ctx = LocalContext.current
    val due = dueNow()
    val intakes by Repo.suppIntakes.collectAsState()
    val supps by Repo.supps.collectAsState()
    val today = Calc.dayStart(System.currentTimeMillis())
    val taken = intakes.filter { it.t >= today }.mapNotNull { i -> supps.firstOrNull { it.id == i.suppId } }.distinctBy { it.id }
    if (due.isEmpty()) {
        Text(if (taken.isEmpty()) "Heute nichts fällig" else "Alles genommen ✓", fontWeight = FontWeight.SemiBold, color = if (taken.isEmpty()) Color.Unspecified else Good)
        if (taken.isNotEmpty()) Hint(taken.joinToString(" ") { it.emoji + " " + it.name.substringBefore(' ') })
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${due.size} offen: " + due.joinToString(", ") { it.supp.name.substringBefore(" +").substringBefore(" Multi") }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Hint(due.first().reason, Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { SuppActions.take(ctx, due.map { it.supp.id }) }) { Text("✓ Alle") }
        }
    }
}

/** Alles zu Tabletten: Tipps, Einnahmen, Vitamin-Haushalt, Kalender, Vorrat. */
@Composable
fun SuppCard(collapsed: Boolean = false) {
    val ctx = LocalContext.current
    val supps by Repo.supps.collectAsState()
    val intakes by Repo.suppIntakes.collectAsState()
    val due = dueNow()
    val today = Calc.dayStart(System.currentTimeMillis())
    var reminders by remember { mutableStateOf(Repo.suppReminders) }
    var calendar by remember { mutableStateOf(Repo.suppCalendar && SuppCalendar.allowed(ctx)) }
    var editing by remember { mutableStateOf<Supplement?>(null) }
    var adding by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calendar = SuppCalendar.allowed(ctx)
        Repo.suppCalendar = calendar
    }

    SectionCard("💊 Nahrungsergänzung", collapsible = collapsed, startOpen = !collapsed, subtitle = if (collapsed) (if (due.isEmpty()) "Heute alles erledigt ✓" else "${due.size} heute noch offen") else null) {
        // ---------- Was ist dran ----------
        if (due.isEmpty()) {
            Text("✓ Für heute ist alles erledigt", color = Good, fontWeight = FontWeight.SemiBold)
        } else {
            Text("Jetzt sinnvoll", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            due.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                        .background(suppColor(d.supp.kind).copy(alpha = 0.12f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(d.supp.emoji, fontSize = 22.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(d.supp.name + if (d.supp.unitsPerDose > 1) " · ${d.supp.unitsPerDose}×" else "", fontWeight = FontWeight.SemiBold)
                        Hint(d.reason)
                    }
                    FilledTonalButton(onClick = { SuppActions.take(ctx, listOf(d.supp.id)) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("✓") }
                }
            }
            if (due.size > 1) TextButton(onClick = { SuppActions.take(ctx, due.map { it.supp.id }) }) { Text("✓ Alle genommen") }
        }

        // ---------- Heute genommen ----------
        val todays = intakes.filter { it.t >= today }.sortedBy { it.t }
        if (todays.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Heute genommen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            todays.forEach { i -> IntakeRow(i, supps) }
        }

        // ---------- Nährstoffe ----------
        Spacer(Modifier.height(12.dp))
        NutrientBalance(supps, intakes)

        // ---------- Kalender ----------
        Spacer(Modifier.height(12.dp))
        SuppCalendarView(supps, intakes)

        // ---------- Präparate & Vorrat ----------
        Spacer(Modifier.height(12.dp))
        Text("Deine Präparate & Vorrat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        supps.forEach { s -> SuppRow(s) { editing = s } }
        TextButton(onClick = { adding = true }) { Text("+ Eigenes Präparat") }

        // ---------- Einstellungen ----------
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Erinnern")
                Hint("8:00, 12:30 und 21:00 – nur wenn etwas fällig ist")
            }
            Switch(reminders, onCheckedChange = {
                reminders = it
                Repo.suppReminders = it
                SuppReminders.schedule(ctx)
                if (it && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("In den Handy-Kalender eintragen")
                Hint("Jede Einnahme als kurzer Termin „💊 …“")
            }
            Switch(calendar, onCheckedChange = {
                if (it && !SuppCalendar.allowed(ctx)) calPerm.launch(SuppCalendar.PERMISSIONS)
                else { calendar = it; Repo.suppCalendar = it }
            })
        }
        Hint("Hinweis: Das sind allgemeine Infos, keine ärztliche Beratung. Bei Medikamenten (z. B. Blutverdünner) oder Beschwerden mit dem Arzt sprechen.", Modifier.padding(top = 6.dp))
    }

    editing?.let { s -> SuppEditDialog(s, onDismiss = { editing = null }) }
    if (adding) SuppEditDialog(null, onDismiss = { adding = false })
}

@Composable
private fun IntakeRow(i: SuppIntake, supps: List<Supplement>) {
    val s = supps.firstOrNull { it.id == i.suppId }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${Calc.fmtClock(i.t)}  ${s?.emoji ?: "💊"} ${s?.name ?: i.suppId}" + if (i.units > 1) " ×${i.units}" else "", Modifier.weight(1f))
        TextButton(onClick = { Repo.deleteSuppIntake(i.id) }) { Text("↶") }
    }
}

/** „Vitamin-Haushalt“: was du über Tabletten bekommst – im Vergleich zur Referenzmenge. */
@Composable
private fun NutrientBalance(supps: List<Supplement>, intakes: List<SuppIntake>) {
    var span by rememberSaveable { mutableIntStateOf(7) }
    val today = Calc.dayStart(System.currentTimeMillis())
    val values = if (span == 1) Supps.nutrientsOn(supps, intakes, today) else Supps.averageNutrients(supps, intakes, span)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dein Vitamin-Haushalt", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
    Segmented(listOf(1 to "Heute", 7 to "Ø 7 Tage", 30 to "Ø 30 Tage"), span, { span = it }, Modifier.padding(vertical = 6.dp))
    if (values.isEmpty()) {
        Hint("Noch keine Einnahmen in diesem Zeitraum.")
        return
    }
    val order = Nutrient.entries.filter { (values[it] ?: 0.0) > 0 }
    order.forEach { n ->
        val v = values[n] ?: 0.0
        val over = n.limit != null && v > n.limit
        val frac = if (n.nrv > 0) (v / n.nrv).toFloat() else 0f
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(n.label, Modifier.width(118.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f)) {
                AnimatedBar(frac.coerceAtMost(1f), if (over) Bad else if (frac >= 1f) Good else PillColor, height = 7.dp)
            }
            Text(
                Supps.fmt(n, v) + if (n.nrv > 0) "  ${(frac * 100).toInt()} %" else "",
                Modifier.width(104.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
                color = if (over) Bad else Color.Unspecified,
            )
        }
        if (over) Hint("⚠️ ${n.label} über der Obergrenze (${n.limitNote.ifBlank { "Höchstmenge ${Supps.fmt(n, n.limit!!)}" }}).")
    }
    Hint("% = Anteil der EU-Referenzmenge pro Tag. Gezählt wird nur, was du über Präparate nimmst – nicht das Essen.", Modifier.padding(top = 4.dp))
}

/** Monatskalender: farbige Punkte = genommene Präparate. Tippen zeigt den Tag. */
@Composable
private fun SuppCalendarView(supps: List<Supplement>, intakes: List<SuppIntake>) {
    var month by rememberSaveable { mutableIntStateOf(0) } // 0 = dieser Monat, -1 = Vormonat …
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var addFor by remember { mutableStateOf<Long?>(null) }
    val cal = Calendar.getInstance().apply {
        add(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val first = cal.timeInMillis
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Montag = 0
    val today = Calc.dayStart(System.currentTimeMillis())
    val byId = supps.associateBy { it.id }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Kalender", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = { month--; selected = null }) { Text("‹", fontSize = 22.sp) }
        Text(Calc.fmtMonth(first), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { month++; selected = null }, enabled = month < 0) { Text("›", fontSize = 22.sp) }
    }
    Row {
        listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach {
            Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        }
    }
    val cells = offset + daysInMonth
    for (row in 0 until (cells + 6) / 7) {
        Row {
            for (col in 0 until 7) {
                val idx = row * 7 + col - offset
                Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (idx in 0 until daysInMonth) {
                        val day = Calc.addDays(first, idx)
                        val dayEnd = Calc.addDays(day, 1)
                        val taken = intakes.filter { it.t in day until dayEnd }.mapNotNull { byId[it.suppId] }.distinctBy { it.id }
                        val isSel = selected == day
                        Column(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) PillColor.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (day > today) 0.2f else 0.6f))
                                .then(if (day == today) Modifier.border(1.5.dp, PillColor, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable(enabled = day <= today) { selected = if (isSel) null else day },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("${idx + 1}", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                taken.take(4).forEach { s -> Box(Modifier.size(5.dp).clip(CircleShape).background(suppColor(s.kind))) }
                            }
                        }
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        supps.filter { it.active }.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(suppColor(s.kind)))
                Text(" " + s.name.substringBefore(' '), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    selected?.let { day ->
        val list = intakes.filter { it.t in day until Calc.addDays(day, 1) }.sortedBy { it.t }
        Spacer(Modifier.height(6.dp))
        Text(Calc.fmtDate(day), fontWeight = FontWeight.SemiBold)
        if (list.isEmpty()) Hint("Nichts eingetragen.")
        list.forEach { IntakeRow(it, supps) }
        TextButton(onClick = { addFor = day }) { Text("+ Nachtragen") }
    }
    addFor?.let { day -> BackfillDialog(day, supps) { addFor = null } }
}

/** Einnahme für einen vergangenen Tag nachtragen. */
@Composable
private fun BackfillDialog(day: Long, supps: List<Supplement>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val chosen = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nachtragen · ${Calc.fmtShortDate(day)}") },
        text = {
            Column {
                supps.filter { it.active }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (s.id in chosen) chosen.remove(s.id) else chosen.add(s.id) }) {
                        Checkbox(s.id in chosen, onCheckedChange = { if (it) chosen.add(s.id) else chosen.remove(s.id) })
                        Text("${s.emoji} ${s.name}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                // Zeit: zur Uhrzeit des jeweiligen Zeitfensters
                val bySlot = supps.filter { it.id in chosen }.groupBy { it.slot }
                bySlot.forEach { (slot, list) ->
                    val t = day + slot.hour * 3_600_000L + slot.minute * 60_000L
                    SuppActions.take(ctx, list.map { it.id }, minOf(t, System.currentTimeMillis()))
                }
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun SuppRow(s: Supplement, onEdit: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val low = s.active && Supps.relevant(s) && s.daysLeft < 14
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (s.active) 0.7f else 0.3f))
            .clickable { open = !open }.padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.emoji, fontSize = 20.sp)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(s.name, fontWeight = FontWeight.SemiBold)
                Hint(
                    "${s.brand} · " + (if (s.everyDays > 1) "alle ${s.everyDays} Tage 1" else "${s.unitsPerDose}× täglich") +
                        " · ${s.slot.emoji} ${s.slot.label}" + if (!s.active) " · pausiert" else ""
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${s.stock} St.", fontWeight = FontWeight.SemiBold, color = if (low) Bad else Color.Unspecified)
                Text(if (s.daysLeft > 900) "" else "reicht ${s.daysLeft} T.", style = MaterialTheme.typography.labelSmall, color = if (low) Bad else Color.Unspecified)
            }
        }
        if (low) Hint("🛒 Bald nachkaufen", Modifier.padding(top = 4.dp))
        if (s.onlyIfAdvised && !s.advised) Hint("Wird nicht vorgeschlagen – nur nach Bluttest/ärztlichem Rat.", Modifier.padding(top = 4.dp))
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text("Pro ${s.unit}:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Hint(s.perUnit.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { "${it.key.label} ${Supps.fmt(it.key, it.value)}" })
            if (s.howTo.isNotBlank()) Hint(s.howTo, Modifier.padding(top = 4.dp))
            if (s.source.isNotBlank()) Hint("Quelle: ${s.source}", Modifier.padding(top = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilledTonalButton(onClick = { Repo.saveSupp(s.copy(stock = s.stock + s.packSize)) }) { Text("+ Packung (${s.packSize})") }
                OutlinedButton(onClick = onEdit) { Text("Ändern") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Aktiv", Modifier.weight(1f))
                Switch(s.active, onCheckedChange = { Repo.saveSupp(s.copy(active = it)) })
            }
            if (s.onlyIfAdvised) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Arzt/Bluttest empfiehlt es", Modifier.weight(1f))
                    Switch(s.advised, onCheckedChange = { Repo.saveSupp(s.copy(advised = it)) })
                }
            }
        }
    }
}

/** Präparat ändern oder neu anlegen. */
@Composable
private fun SuppEditDialog(orig: Supplement?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(orig?.name ?: "") }
    var stock by remember { mutableStateOf((orig?.stock ?: 60).toString()) }
    var pack by remember { mutableStateOf((orig?.packSize ?: 60).toString()) }
    var every by remember { mutableIntStateOf(orig?.everyDays ?: 1) }
    var perDose by remember { mutableIntStateOf(orig?.unitsPerDose ?: 1) }
    var slot by remember { mutableStateOf(orig?.slot ?: SuppSlot.MORNING) }
    val editable = listOf(Nutrient.VIT_C, Nutrient.VIT_D, Nutrient.VIT_E, Nutrient.IRON, Nutrient.MAGNESIUM, Nutrient.ZINC, Nutrient.B12)
    val values = remember { mutableStateMapOf<Nutrient, String>().apply { orig?.perUnit?.forEach { (k, v) -> put(k, Calc.fmtNum(v, if (v < 10) 1 else 0)) } } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (orig == null) "Neues Präparat" else "Präparat ändern") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Vorrat (Stück)", stock, { stock = it }, Modifier.weight(1f), decimal = false)
                    NumField("Packung", pack, { pack = it }, Modifier.weight(1f), decimal = false)
                }
                Spacer(Modifier.height(6.dp))
                Text("Wie oft", style = MaterialTheme.typography.labelMedium)
                Segmented(listOf(1 to "täglich", 2 to "alle 2 Tage", 7 to "1× Woche"), every, { every = it })
                if (every == 1) {
                    Spacer(Modifier.height(4.dp))
                    Segmented(listOf(1 to "1 Stück", 2 to "2 Stück"), perDose, { perDose = it })
                }
                Spacer(Modifier.height(6.dp))
                Text("Wann", style = MaterialTheme.typography.labelMedium)
                Segmented(SuppSlot.entries.map { it to it.emoji + " " + it.label }, slot, { slot = it })
                if (orig == null || orig.kind == SuppKind.OTHER) {
                    Spacer(Modifier.height(6.dp))
                    Text("Inhalt pro Stück (leer lassen = nichts)", style = MaterialTheme.typography.labelMedium)
                    editable.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { n -> NumField("${n.label} (${n.unit})", values[n] ?: "", { values[n] = it }, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val perDay = if (every > 1) 1.0 / every else perDose.toDouble()
                val per = if (orig == null || orig.kind == SuppKind.OTHER)
                    values.mapNotNull { (k, v) -> v.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { k to it } }.toMap()
                else orig.perUnit
                val base = orig ?: Supplement(
                    "own_${System.currentTimeMillis()}", name, "eigenes", "Stück", per, perDay, slot, SuppKind.OTHER,
                )
                Repo.saveSupp(
                    base.copy(
                        name = name.trim(), perUnit = per, perDay = perDay, slot = slot,
                        stock = stock.toIntOrNull() ?: base.stock, packSize = pack.toIntOrNull() ?: base.packSize,
                    )
                )
                de.endurofit.supp.SuppReminders.schedule(ctx)
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (orig != null && orig.kind == SuppKind.OTHER) TextButton(onClick = { Repo.deleteSupp(orig.id); onDismiss() }) { Text("Löschen", color = Bad) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
