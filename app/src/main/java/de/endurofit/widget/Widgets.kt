package de.endurofit.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.endurofit.MainActivity
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import de.endurofit.data.SessionType
import de.endurofit.hr.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Daten

private data class WidgetData(
    val waterMl: Int,
    val waterGoal: Int,
    val steps: Int,
    val stepGoal: Int,
    val eaten: Double,
    val balance: Double?,
    val protein: Double,
    val proteinGoal: Double,
    val sleepMs: Long?,
    val sleepGoalMs: Long,
    val resting: Int?,
    val recovery: Int?,
    val trainingMin: Int,
    val trainingKcal: Double,
    val sick: Boolean,
    val liveType: SessionType?,
    val liveStart: Long?,
    val pillsDue: Int,
)

/**
 * Glance hält ein Widget eine Weile „am Leben“ und zeichnet bei updateAll nur neu –
 * deshalb die Daten innerhalb der Oberfläche lesen und auf Änderungen hören.
 */
@Composable
private fun liveData(ctx: Context): WidgetData {
    Repo.init(ctx)
    val w by Repo.water.collectAsState()
    val f by Repo.food.collectAsState()
    val st by Repo.steps.collectAsState()
    val se by Repo.sessions.collectAsState()
    val sl by Repo.sleep.collectAsState()
    val r by Repo.recovery.collectAsState()
    val h by Repo.dayHr.collectAsState()
    val su by Repo.supps.collectAsState()
    val si by Repo.suppIntakes.collectAsState()
    val ca by Repo.caffeine.collectAsState()
    val live by Recorder.live.collectAsState()
    return androidx.compose.runtime.remember(w, f, st, se, sl, r, h, su, si, ca, live) { load(ctx) }
}

private fun load(ctx: Context): WidgetData {
    Repo.init(ctx)
    val today = Calc.dayStart(System.currentTimeMillis())
    val g = Repo.goals.value
    val d = Analysis.day(
        today, Repo.sessions.value, Repo.food.value, Repo.water.value, Repo.steps.value,
        Repo.sleep.value, Repo.profile.value, g,
    )
    val rec = Repo.recovery.value[today] ?: Repo.recovery.value[Calc.addDays(today, -1)]
    val hr = Repo.dayHr.value[today] ?: Repo.dayHr.value[Calc.addDays(today, -1)]
    val live = Recorder.live.value
    return WidgetData(
        waterMl = d.waterMl,
        waterGoal = d.waterGoal,
        steps = d.steps,
        stepGoal = g.steps,
        eaten = d.eaten,
        balance = if (d.foodCount > 0) d.balance else null,
        protein = d.protein,
        proteinGoal = d.proteinGoal,
        sleepMs = d.sleep?.asleepMs,
        sleepGoalMs = (g.sleepHours * 3_600_000).toLong(),
        resting = rec?.nightHr ?: hr?.resting,
        recovery = rec?.status,
        trainingMin = (d.trainingMs / 60_000).toInt(),
        trainingKcal = d.trainingKcal,
        sick = d.sick,
        liveType = live?.type,
        liveStart = live?.start,
        pillsDue = de.endurofit.data.Supps.dueToday(
            Repo.supps.value, Repo.suppIntakes.value, d.sessions.isNotEmpty(), d.sick,
        ).count { it.priority > 0 },
    )
}

// ------------------------------------------------------------------ Farben & Bausteine

private fun cp(c: Long) = ColorProvider(Color(c))
private val Bg = cp(0xF0161616)
private val Cell = cp(0xFF242424)
private val White = cp(0xFFEDEDED)
private val Grey = cp(0xFF9E9E9E)
private val Orange = cp(0xFFFF6D00)
private val Green = cp(0xFF66BB6A)
private val Blue = cp(0xFF42A5F5)
private val Red = cp(0xFFEF5350)
private val Yellow = cp(0xFFFFCA28)
private val Purple = cp(0xFFAB47BC)
private val Track = cp(0xFF3A3A3A)

private fun liters(ml: Int) = Calc.fmtNum(ml / 1000.0, 1)
private fun k(v: Int) = if (v >= 10_000) Calc.fmtNum(v / 1000.0, 1) + "k" else Analysis.fmtInt(v)
private fun hm(ms: Long) = "${ms / 3_600_000}:${"%02d".format((ms / 60_000) % 60)}"

/** Öffnet die App mit einem Auftrag (z. B. Foto machen, Training starten). */
private fun open(ctx: Context, what: String): Action =
    actionStartActivity(
        Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("endurofit://widget/$what"))
            .putExtra(WidgetLinks.EXTRA, what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )

/** Fortschrittsbalken aus 10 Segmenten – Glance kennt keine Prozent-Breiten. */
@Composable
private fun Segments(frac: Float, color: ColorProvider, h: androidx.compose.ui.unit.Dp = 4.dp) {
    val n = (frac.coerceIn(0f, 1f) * 10).roundToInt()
    Row(GlanceModifier.fillMaxWidth().height(h)) {
        for (i in 0 until 10) {
            Box(GlanceModifier.defaultWeight().height(h).padding(end = if (i < 9) 1.dp else 0.dp)) {
                Box(GlanceModifier.fillMaxSize().cornerRadius(1.dp).background(if (i < n) color else Track)) {}
            }
        }
    }
}

/** Schriftgrößen passend zur Widget-Größe – je größer gezogen, desto größer die Schrift. */
private data class Sz(val value: Float, val label: Float, val bar: Float)

/** [rows] Zeilen à [cols] Spalten auf der aktuellen Fläche */
@Composable
private fun sizing(cols: Int, rows: Int): Sz {
    val s = LocalSize.current
    // Höhe je Zeile ≈ Wert + Beschriftung + Balken + Abstand ≈ 2,6 × Schriftgröße
    val byHeight = ((s.height.value - 20) / rows) / 2.6f
    // Breite je Spalte ≈ 6 Zeichen (Emoji + Zahl) ≈ 4,2 × Schriftgröße
    val byWidth = ((s.width.value - 22) / cols) / 4.2f
    val v = minOf(byHeight, byWidth).coerceIn(13f, 40f)
    return Sz(v, (v * 0.6f).coerceIn(10f, 20f), (v / 3.5f).coerceIn(4f, 10f))
}

/** Eine Kennzahl: Emoji + Wert, darunter kurze Beschriftung und Balken. */
@Composable
private fun Metric(emoji: String, value: String, label: String, frac: Float?, color: ColorProvider, modifier: GlanceModifier, sz: Sz = Sz(13f, 10f, 4f)) {
    Column(modifier.padding(horizontal = 3.dp)) {
        Text(
            "$emoji $value",
            style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = sz.value.sp),
            maxLines = 1,
        )
        Text(label, style = TextStyle(color = Grey, fontSize = sz.label.sp), maxLines = 1)
        Spacer(GlanceModifier.height((sz.bar * 0.8f).dp))
        if (frac != null) Segments(frac, color, sz.bar.dp) else Spacer(GlanceModifier.height(sz.bar.dp))
    }
}

/** Kleine farbige Taste. */
@Composable
private fun Key(text: String, sub: String?, color: ColorProvider, action: Action, modifier: GlanceModifier, size: TextUnit = 15.sp) {
    Box(
        modifier.fillMaxHeight().padding(horizontal = 2.dp).clickable(action),
    ) {
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(12.dp).background(Cell).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = size, textAlign = TextAlign.Center), maxLines = 1)
            if (sub != null) Text(sub, style = TextStyle(color = Grey, fontSize = (size.value * 0.6f).coerceIn(10f, 14f).sp, textAlign = TextAlign.Center), maxLines = 1)
        }
    }
}

@Composable
private fun Frame(content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(Bg).padding(horizontal = 8.dp, vertical = 6.dp)) {
        content()
    }
}

private val SIZES = SizeMode.Exact

// ------------------------------------------------------------------ 1) Überblick (kompakt)

class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SIZES
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { OverviewContent(context, liveData(context)) }
    }
}

private data class M(val emoji: String, val value: String, val label: String, val frac: Float?, val color: ColorProvider)

@Composable
private fun OverviewContent(ctx: Context, d: WidgetData) {
    val bal = d.balance
    val all = listOf(
        M("⚖️", if (bal == null) "–" else (if (bal < 0) "−" else "+") + Calc.fmtNum(abs(bal)), "kcal Bilanz", null,
            if (bal == null) White else if (bal <= 0) Green else Orange),
        M("👣", k(d.steps), if (d.sick) "krank – Pause" else "von ${k(d.stepGoal)}",
            d.steps.toFloat() / d.stepGoal.coerceAtLeast(1), if (d.steps >= d.stepGoal) Green else Orange),
        M("💧", liters(d.waterMl), "von ${liters(d.waterGoal)} l",
            d.waterMl.toFloat() / d.waterGoal.coerceAtLeast(1), if (d.waterMl >= d.waterGoal) Green else Blue),
        M("🥩", "${d.protein.roundToInt()}g", "von ${d.proteinGoal.roundToInt()} g",
            (d.protein / d.proteinGoal.coerceAtLeast(1.0)).toFloat(), if (d.protein >= d.proteinGoal) Green else Yellow),
        M("😴", d.sleepMs?.let { hm(it) } ?: "–", "Schlaf",
            d.sleepMs?.let { it.toFloat() / d.sleepGoalMs.coerceAtLeast(1) }, if ((d.sleepMs ?: 0) >= d.sleepGoalMs) Green else Purple),
        M("❤️", d.resting?.toString() ?: "–", d.recovery?.let { "Erholung " + de.endurofit.ui.rechargeText(it) } ?: "Ruhepuls", null,
            when (d.recovery) { null -> White; 1, 2 -> Red; 3 -> Orange; else -> Green }),
        M("💊", if (d.pillsDue == 0) "✓" else "${d.pillsDue}", if (d.pillsDue == 0) "Tabletten" else "Tabl. offen", null,
            if (d.pillsDue == 0) Green else Purple),
        if (d.liveType != null) M("⏺", "läuft", d.liveType.label, null, Red)
        else M("🏍️", "${d.trainingMin}′", if (d.trainingMin > 0) "${Calc.fmtNum(d.trainingKcal)} kcal" else "Training", null, White),
    )
    val size = LocalSize.current
    val tall = size.height >= 110.dp
    val items = if (tall) all else all.take(4)
    // Aufteilung wählen, bei der die Schrift am größten wird (4 nebeneinander oder 2 nebeneinander)
    val options = listOf(4, 2).map { cols -> cols to sizing(cols, (items.size + cols - 1) / cols) }
    val (cols, sz) = options.maxBy { it.second.value }
    Frame {
        Column(GlanceModifier.fillMaxSize().clickable(open(ctx, "today")), verticalAlignment = Alignment.CenterVertically) {
            items.chunked(cols).forEachIndexed { r, row ->
                if (r > 0) Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { m -> Metric(m.emoji, m.value, m.label, m.frac, m.color, GlanceModifier.defaultWeight(), sz) }
                }
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

// ------------------------------------------------------------------ 2) Essen & Trinken

class WaterWidget : GlanceAppWidget() {
    override val sizeMode = SIZES
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { FoodWaterContent(context, liveData(context)) }
    }
}

private val ML = ActionParameters.Key<Int>("ml")

@Composable
private fun FoodWaterContent(ctx: Context, d: WidgetData) {
    val tall = LocalSize.current.height >= 110.dp
    val hs = (LocalSize.current.width.value / 22f).coerceIn(12f, 17f).sp
    val ks = (LocalSize.current.height.value / (if (tall) 7f else 4.5f)).coerceIn(15f, 26f).sp
    Frame {
        Column(GlanceModifier.fillMaxSize()) {
            Row(
                GlanceModifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 1.dp).clickable(open(ctx, "food")),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "💧 ${liters(d.waterMl)}/${liters(d.waterGoal)} l",
                    style = TextStyle(color = if (d.waterMl >= d.waterGoal) Green else Blue, fontWeight = FontWeight.Bold, fontSize = hs),
                    modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                )
                Text(
                    "🍽️ ${Calc.fmtNum(d.eaten)} kcal · 🥩 ${d.protein.roundToInt()} g",
                    style = TextStyle(color = White, fontSize = hs), maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            val water = @Composable { m: GlanceModifier ->
                Key("+250", "💧 ml", Blue, actionRunCallback<AddWaterAction>(actionParametersOf(ML to 250)), m, size = ks)
                Key("+500", "💧 ml", Blue, actionRunCallback<AddWaterAction>(actionParametersOf(ML to 500)), m, size = ks)
                Key("☕", "Kaffee", cp(0xFFA1887F), open(ctx, "caffeine"), m, size = ks)
            }
            val food = @Composable { m: GlanceModifier ->
                Key("📷", "Foto", Orange, open(ctx, "food_photo"), m, size = ks)
                Key("✏️", "Eintrag", Green, open(ctx, "food_manual"), m, size = ks)
                Key("📋", "Text", Yellow, open(ctx, "food_paste"), m, size = ks)
            }
            if (tall) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) { water(GlanceModifier.defaultWeight()); Key("+1 l", "💧", Blue, actionRunCallback<AddWaterAction>(actionParametersOf(ML to 1000)), GlanceModifier.defaultWeight(), size = ks) }
                Spacer(GlanceModifier.height(4.dp))
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) { food(GlanceModifier.defaultWeight()) }
            } else {
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    water(GlanceModifier.defaultWeight())
                    food(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}

// ------------------------------------------------------------------ 3) Training starten

class TrainingWidget : GlanceAppWidget() {
    override val sizeMode = SIZES
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { TrainingContent(context, liveData(context)) }
    }
}

private fun typeColor(t: SessionType) = when (t) {
    SessionType.ENDURO -> Orange
    SessionType.GYM -> Purple
    SessionType.OTHER -> Blue
}

@Composable
private fun TrainingContent(ctx: Context, d: WidgetData) {
    Frame {
        if (d.liveType != null) {
            Row(GlanceModifier.fillMaxSize().clickable(open(ctx, "training")), verticalAlignment = Alignment.CenterVertically) {
                Key("⏺", null, Red, open(ctx, "training"), GlanceModifier.width(56.dp), size = 22.sp)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text("${d.liveType.emoji} ${d.liveType.label} läuft", style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp), maxLines = 1)
                    Text("seit ${Calc.fmtClock(d.liveStart ?: 0)} · antippen zum Öffnen/Beenden", style = TextStyle(color = Grey, fontSize = 11.sp), maxLines = 1)
                }
            }
        } else {
            Row(GlanceModifier.fillMaxSize()) {
                SessionType.entries.forEach { t ->
                    Key(t.emoji, t.label, typeColor(t), open(ctx, "start_${t.name}"), GlanceModifier.defaultWeight(), size = 20.sp)
                }
                Key("🧘", "Pause", cp(0xFF26C6DA), open(ctx, "exercise_next"), GlanceModifier.defaultWeight(), size = 20.sp)
            }
        }
    }
}

class TrainingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainingWidget()
}

// ------------------------------------------------------------------ 4) Tabletten

class PillWidget : GlanceAppWidget() {
    override val sizeMode = SIZES
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val d = liveData(context)
            val today = Calc.dayStart(System.currentTimeMillis())
            val trained = Calc.sessionsOfDay(Repo.sessions.value, today).isNotEmpty()
            val due = de.endurofit.data.Supps.dueToday(Repo.supps.value, Repo.suppIntakes.value, trained, d.sick).filter { it.priority > 0 }
            val taken = Repo.suppIntakes.value.filter { it.t >= today }
                .mapNotNull { i -> Repo.supps.value.firstOrNull { it.id == i.suppId } }.distinctBy { it.id }
            PillContent(context, due.map { it.supp }, taken)
        }
    }
}

private val SUPP = ActionParameters.Key<String>("supp")

@Composable
private fun PillContent(ctx: Context, due: List<de.endurofit.data.Supplement>, taken: List<de.endurofit.data.Supplement>) {
    val size = LocalSize.current
    val now = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
    // Zeilenhöhe ~ 2,4 × Schrift; so viele Zeilen wie passen
    val fs = (size.height.value / 4.2f).coerceIn(13f, 22f)
    val rowsFit = (((size.height.value - 16) / (fs * 2.3f)).toInt()).coerceAtLeast(1)
    Frame {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth().clickable(open(ctx, "supps")), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (due.isEmpty()) "💊 Alles genommen ✓" else "💊 ${due.size} offen",
                    style = TextStyle(color = if (due.isEmpty()) Green else White, fontWeight = FontWeight.Bold, fontSize = fs.sp),
                    modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                )
                Box(GlanceModifier.cornerRadius(10.dp).background(Cell).padding(horizontal = 10.dp, vertical = 2.dp).clickable(open(ctx, "supp_add"))) {
                    Text("＋", style = TextStyle(color = Purple, fontWeight = FontWeight.Bold, fontSize = fs.sp))
                }
            }
            if (due.isEmpty()) {
                if (rowsFit > 1 && taken.isNotEmpty()) Text(
                    "Heute: " + taken.joinToString("  ") { it.emoji + " " + it.name.substringBefore(' ') },
                    style = TextStyle(color = Grey, fontSize = (fs * 0.75f).sp), maxLines = 2,
                )
            } else {
                due.take((rowsFit - 1).coerceAtLeast(1)).forEach { s ->
                    Spacer(GlanceModifier.height(4.dp))
                    val late = s.at <= now
                    Row(
                        GlanceModifier.fillMaxWidth().cornerRadius(12.dp).background(Cell).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(GlanceModifier.defaultWeight().clickable(open(ctx, "supps"))) {
                            Text("${s.emoji} ${s.name}", style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = (fs * 0.85f).sp), maxLines = 1)
                            Text(
                                "⏰ ${s.atText}" + if (late) " · jetzt fällig" else "",
                                style = TextStyle(color = if (late) Orange else Grey, fontSize = (fs * 0.65f).sp), maxLines = 1,
                            )
                        }
                        Box(
                            GlanceModifier.cornerRadius(10.dp).background(Purple).padding(horizontal = 12.dp, vertical = 4.dp)
                                .clickable(actionRunCallback<TakeSuppAction>(actionParametersOf(SUPP to s.id))),
                        ) {
                            Text("✓", style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = fs.sp))
                        }
                    }
                }
                if (due.size > rowsFit - 1 && rowsFit > 1) {
                    Text("+ ${due.size - (rowsFit - 1)} weitere", style = TextStyle(color = Grey, fontSize = (fs * 0.65f).sp))
                }
            }
        }
    }
}

class PillWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PillWidget()
}

class TakeSuppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[SUPP] ?: return
        de.endurofit.supp.SuppActions.take(context, listOf(id))
        Widgets.refresh(context)
    }
}

// ------------------------------------------------------------------ Aktionen & Aktualisierung

class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repo.init(context)
        Repo.addWater(parameters[ML] ?: 250)
        Widgets.refresh(context)
    }
}

/** Aufträge, die ein Widget an die App schickt. */
object WidgetLinks {
    const val EXTRA = "de.endurofit.widget"
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun from(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA) ?: intent?.data?.takeIf { it.scheme == "endurofit" }?.lastPathSegment
}

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(ctx: Context) {
        try {
            TodayWidget().updateAll(ctx)
            WaterWidget().updateAll(ctx)
            TrainingWidget().updateAll(ctx)
            PillWidget().updateAll(ctx)
        } catch (_: Exception) {
        }
    }

    /** Von überall aufrufbar, z. B. nach dem Synchronisieren oder wenn du etwas einträgst. */
    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { refresh(app) }
    }
}
