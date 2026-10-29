package de.endurofit.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.endurofit.MainActivity
import de.endurofit.data.Analysis
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Daten für die Widgets

private data class WidgetData(
    val waterMl: Int,
    val waterGoal: Int,
    val steps: Int,
    val stepGoal: Int,
    val balance: Double?,
    val burned: Double,
    val resting: Int?,
    val recovery: String?,
    val recoveryGood: Boolean?,
    val sick: Boolean,
)

private fun load(ctx: Context): WidgetData {
    Repo.init(ctx)
    val today = Calc.dayStart(System.currentTimeMillis())
    val d = Analysis.day(
        today, Repo.sessions.value, Repo.food.value, Repo.water.value, Repo.steps.value,
        Repo.sleep.value, Repo.profile.value, Repo.goals.value,
    )
    val rec = Repo.recovery.value[today] ?: Repo.recovery.value[Calc.addDays(today, -1)]
    val hr = Repo.dayHr.value[today] ?: Repo.dayHr.value[Calc.addDays(today, -1)]
    return WidgetData(
        waterMl = d.waterMl,
        waterGoal = d.waterGoal,
        steps = d.steps,
        stepGoal = Repo.goals.value.steps,
        balance = if (d.foodCount > 0) d.balance else null,
        burned = d.burned,
        resting = rec?.nightHr ?: hr?.resting,
        recovery = rec?.status?.let { de.endurofit.ui.rechargeText(it) },
        recoveryGood = rec?.status?.let { it >= 4 },
        sick = d.sick,
    )
}

private val Bg = ColorProvider(Color(0xF01A1A1A))
private val White = ColorProvider(Color(0xFFEDEDED))
private val Grey = ColorProvider(Color(0xFFAAAAAA))
private val Orange = ColorProvider(Color(0xFFFF6D00))
private val Green = ColorProvider(Color(0xFF66BB6A))
private val Blue = ColorProvider(Color(0xFF42A5F5))
private val Red = ColorProvider(Color(0xFFEF5350))
private val Bar = ColorProvider(Color(0xFF333333))

private fun liters(ml: Int) = Calc.fmtNum(ml / 1000.0, 1) + " l"

@Composable
private fun ProgressLine(frac: Float, color: ColorProvider, width: Int) {
    val full = (width * frac.coerceIn(0f, 1f)).toInt()
    Row(GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp).background(Bar)) {
        if (full > 0) Spacer(GlanceModifier.width(full.dp).height(6.dp).cornerRadius(3.dp).background(color))
    }
}

@Composable
private fun WaterButton() {
    Box(
        GlanceModifier.cornerRadius(14.dp).background(Blue).padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(actionRunCallback<AddWaterAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Text("+250 ml", style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 13.sp))
    }
}

// ------------------------------------------------------------------ Großes Widget: Heute

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = load(context)
        provideContent { TodayContent(d) }
    }
}

@Composable
private fun TodayContent(d: WidgetData) {
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("EnduroFit · Heute", style = TextStyle(color = Orange, fontWeight = FontWeight.Bold, fontSize = 14.sp))
            if (d.sick) Text("  🤒 krank", style = TextStyle(color = Grey, fontSize = 12.sp))
        }
        Spacer(GlanceModifier.height(8.dp))
        // Erholung und Ruhepuls
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🔋 " + (d.recovery?.replaceFirstChar { it.uppercase() } ?: "–"),
                style = TextStyle(
                    color = when (d.recoveryGood) { true -> Green; false -> Red; null -> White },
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text("❤️ " + (d.resting?.let { "$it Ruhepuls" } ?: "–"), style = TextStyle(color = White, fontSize = 13.sp))
        }
        Spacer(GlanceModifier.height(6.dp))
        // Kalorien
        Text(
            if (d.balance != null) "⚖️ " + (if (d.balance < 0) "−" else "+") + Calc.fmtNum(abs(d.balance)) + " kcal Bilanz"
            else "🔥 ${Calc.fmtNum(d.burned)} kcal verbrannt",
            style = TextStyle(color = White, fontSize = 13.sp),
        )
        Spacer(GlanceModifier.height(6.dp))
        // Schritte
        Text("👣 ${Analysis.fmtInt(d.steps)} / ${Analysis.fmtInt(d.stepGoal)} Schritte", style = TextStyle(color = White, fontSize = 13.sp))
        Spacer(GlanceModifier.height(3.dp))
        ProgressLine(d.steps.toFloat() / d.stepGoal.coerceAtLeast(1), if (d.steps >= d.stepGoal) Green else Orange, 220)
        Spacer(GlanceModifier.height(8.dp))
        // Trinken
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "💧 ${liters(d.waterMl)} / ${liters(d.waterGoal)}",
                style = TextStyle(color = White, fontSize = 13.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            WaterButton()
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

// ------------------------------------------------------------------ Kleines Widget: Trinken

class WaterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = load(context)
        provideContent { WaterContent(d) }
    }
}

@Composable
private fun WaterContent(d: WidgetData) {
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "💧 ${liters(d.waterMl)}",
            style = TextStyle(color = if (d.waterMl >= d.waterGoal) Green else White, fontWeight = FontWeight.Bold, fontSize = 17.sp),
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
        )
        Text("von ${liters(d.waterGoal)} · ${(d.waterMl * 100f / d.waterGoal.coerceAtLeast(1)).roundToInt()} %", style = TextStyle(color = Grey, fontSize = 11.sp))
        Spacer(GlanceModifier.height(6.dp))
        WaterButton()
    }
}

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}

// ------------------------------------------------------------------ Aktionen & Aktualisierung

class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repo.init(context)
        Repo.addWater(250)
        Widgets.refresh(context)
    }
}

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(ctx: Context) {
        try {
            TodayWidget().updateAll(ctx)
            WaterWidget().updateAll(ctx)
        } catch (_: Exception) {
        }
    }

    /** Von überall aufrufbar, z. B. nach dem Synchronisieren oder wenn du etwas einträgst. */
    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { refresh(app) }
    }
}
