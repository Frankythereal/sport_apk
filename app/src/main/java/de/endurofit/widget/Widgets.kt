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
)

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
private fun Segments(frac: Float, color: ColorProvider) {
    val n = (frac.coerceIn(0f, 1f) * 10).roundToInt()
    Row(GlanceModifier.fillMaxWidth().height(4.dp)) {
        for (i in 0 until 10) {
            Box(GlanceModifier.defaultWeight().height(4.dp).padding(end = if (i < 9) 1.dp else 0.dp)) {
                Box(GlanceModifier.fillMaxSize().cornerRadius(1.dp).background(if (i < n) color else Track)) {}
            }
        }
    }
}

/** Eine Kennzahl: Emoji + Wert, darunter kurze Beschriftung und Balken. */
@Composable
private fun Metric(emoji: String, value: String, label: String, frac: Float?, color: ColorProvider, modifier: GlanceModifier) {
    Column(modifier.padding(horizontal = 3.dp)) {
        Text(
            "$emoji $value",
            style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp),
            maxLines = 1,
        )
        Text(label, style = TextStyle(color = Grey, fontSize = 10.sp), maxLines = 1)
        Spacer(GlanceModifier.height(3.dp))
        if (frac != null) Segments(frac, color) else Spacer(GlanceModifier.height(4.dp))
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
            if (sub != null) Text(sub, style = TextStyle(color = Grey, fontSize = 10.sp, textAlign = TextAlign.Center), maxLines = 1)
        }
    }
}

@Composable
private fun Frame(content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(Bg).padding(horizontal = 8.dp, vertical = 6.dp)) {
        content()
    }
}

private val SIZES = SizeMode.Responsive(setOf(DpSize(250.dp, 60.dp), DpSize(250.dp, 120.dp)))

// ------------------------------------------------------------------ 1) Überblick (kompakt)

class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SIZES
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = load(context)
        provideContent { OverviewContent(context, d) }
    }
}

@Composable
private fun OverviewContent(ctx: Context, d: WidgetData) {
    val tall = LocalSize.current.height >= 110.dp
    Frame {
        Column(GlanceModifier.fillMaxSize().clickable(open(ctx, "today")), verticalAlignment = Alignment.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val bal = d.balance
                Metric(
                    "⚖️", if (bal == null) "–" else (if (bal < 0) "−" else "+") + Calc.fmtNum(abs(bal)),
                    if (bal == null) "kcal Bilanz" else "Bilanz",
                    null, if (bal == null) White else if (bal <= 0) Green else Orange,
                    GlanceModifier.defaultWeight(),
                )
                Metric(
                    "👣", k(d.steps), if (d.sick) "krank – Pause" else "von ${k(d.stepGoal)}",
                    d.steps.toFloat() / d.stepGoal.coerceAtLeast(1),
                    if (d.steps >= d.stepGoal) Green else Orange, GlanceModifier.defaultWeight(),
                )
                Metric(
                    "💧", liters(d.waterMl), "von ${liters(d.waterGoal)} l",
                    d.waterMl.toFloat() / d.waterGoal.coerceAtLeast(1),
                    if (d.waterMl >= d.waterGoal) Green else Blue, GlanceModifier.defaultWeight(),
                )
                Metric(
                    "🥩", "${d.protein.roundToInt()}g", "von ${d.proteinGoal.roundToInt()} g",
                    (d.protein / d.proteinGoal.coerceAtLeast(1.0)).toFloat(),
                    if (d.protein >= d.proteinGoal) Green else Yellow, GlanceModifier.defaultWeight(),
                )
            }
            if (tall) {
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Metric(
                        "😴", d.sleepMs?.let { hm(it) } ?: "–", "Schlaf",
                        d.sleepMs?.let { it.toFloat() / d.sleepGoalMs.coerceAtLeast(1) },
                        if ((d.sleepMs ?: 0) >= d.sleepGoalMs) Green else Purple, GlanceModifier.defaultWeight(),
                    )
                    Metric(
                        "🔋", de.endurofit.ui.rechargeText(d.recovery), "Erholung", null,
                        when (d.recovery) { null -> White; 1, 2 -> Red; 3 -> Orange; else -> Green },
                        GlanceModifier.defaultWeight(),
                    )
                    Metric("❤️", d.resting?.toString() ?: "–", "Ruhepuls", null, White, GlanceModifier.defaultWeight())
                    Metric(
                        if (d.liveType != null) "⏺" else "🏍️",
                        if (d.liveType != null) "läuft" else "${d.trainingMin}′",
                        if (d.liveType != null) d.liveType.label else if (d.trainingMin > 0) "${Calc.fmtNum(d.trainingKcal)} kcal" else "Training",
                        null, if (d.liveType != null) Red else White, GlanceModifier.defaultWeight(),
                    )
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
        val d = load(context)
        provideContent { FoodWaterContent(context, d) }
    }
}

private val ML = ActionParameters.Key<Int>("ml")

@Composable
private fun FoodWaterContent(ctx: Context, d: WidgetData) {
    val tall = LocalSize.current.height >= 110.dp
    Frame {
        Column(GlanceModifier.fillMaxSize()) {
            Row(
                GlanceModifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 1.dp).clickable(open(ctx, "food")),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "💧 ${liters(d.waterMl)}/${liters(d.waterGoal)} l",
                    style = TextStyle(color = if (d.waterMl >= d.waterGoal) Green else Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                )
                Text(
                    "🍽️ ${Calc.fmtNum(d.eaten)} kcal · 🥩 ${d.protein.roundToInt()} g",
                    style = TextStyle(color = White, fontSize = 12.sp), maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            val water = @Composable { m: GlanceModifier ->
                Key("+250", "💧 ml", Blue, actionRunCallback<AddWaterAction>(actionParametersOf(ML to 250)), m)
                Key("+500", "💧 ml", Blue, actionRunCallback<AddWaterAction>(actionParametersOf(ML to 500)), m)
            }
            val food = @Composable { m: GlanceModifier ->
                Key("📷", "Foto", Orange, open(ctx, "food_photo"), m)
                Key("✏️", "Eintrag", Green, open(ctx, "food_manual"), m)
                Key("📋", "Text", Yellow, open(ctx, "food_paste"), m)
            }
            if (tall) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) { water(GlanceModifier.defaultWeight()); Key("+1 l", "💧", Blue, actionRunCallback<AddWaterAction>(actionParametersOf(ML to 1000)), GlanceModifier.defaultWeight()) }
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
        val d = load(context)
        provideContent { TrainingContent(context, d) }
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
            }
        }
    }
}

class TrainingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainingWidget()
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
        } catch (_: Exception) {
        }
    }

    /** Von überall aufrufbar, z. B. nach dem Synchronisieren oder wenn du etwas einträgst. */
    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { refresh(app) }
    }
}
