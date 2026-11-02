package de.endurofit.work

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import de.endurofit.MainActivity
import de.endurofit.R
import de.endurofit.data.Calc
import de.endurofit.ui.ExerciseLib
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Arbeitsmodus: Solange er an ist, erinnert die App alle X Minuten an eine kurze Bewegungspause
 * und schlägt eine passende Übung vor. Endet automatisch nach der eingestellten Arbeitszeit.
 */
object BreakMode {
    private const val CHANNEL = "breaks"
    const val NOTIF_ID = 77
    private const val ACTION_FIRE = "de.endurofit.BREAK_FIRE"
    private const val ACTION_SNOOZE = "de.endurofit.BREAK_SNOOZE"
    private const val ACTION_STOP = "de.endurofit.BREAK_STOP"
    private const val ACTION_DONE = "de.endurofit.BREAK_DONE"

    /** Für die Oberfläche: läuft der Arbeitsmodus, wann ist die nächste Pause? */
    val active = MutableStateFlow(false)
    val nextAt = MutableStateFlow(0L)
    val doneToday = MutableStateFlow(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("breaks", Context.MODE_PRIVATE)

    var Context.intervalMin: Int
        get() = prefs(this).getInt("interval", 45)
        set(v) = prefs(this).edit().putInt("interval", v).apply()

    var Context.workHours: Int
        get() = prefs(this).getInt("hours", 8)
        set(v) = prefs(this).edit().putInt("hours", v).apply()

    fun interval(ctx: Context) = ctx.intervalMin
    fun setInterval(ctx: Context, v: Int) { ctx.intervalMin = v }
    fun hours(ctx: Context) = ctx.workHours
    fun setHours(ctx: Context, v: Int) { ctx.workHours = v }

    fun load(ctx: Context) {
        val p = prefs(ctx)
        val until = p.getLong("until", 0)
        active.value = p.getBoolean("active", false) && System.currentTimeMillis() < until
        nextAt.value = if (active.value) p.getLong("next", 0) else 0
        doneToday.value = countToday(ctx)
    }

    fun until(ctx: Context) = prefs(ctx).getLong("until", 0)

    private fun pi(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, BreakReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun start(ctx: Context) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putBoolean("active", true).putLong("until", now + ctx.workHours * 3_600_000L).apply()
        schedule(ctx, now + ctx.intervalMin * 60_000L)
        active.value = true
    }

    fun stop(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, ACTION_FIRE, 300))
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        prefs(ctx).edit().putBoolean("active", false).putLong("next", 0).apply()
        active.value = false
        nextAt.value = 0
    }

    private fun schedule(ctx: Context, at: Long) {
        if (at > until(ctx)) {
            // Arbeitszeit vorbei → Feierabend
            stop(ctx)
            return
        }
        ctx.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC_WAKEUP, at, 2 * 60_000L, pi(ctx, ACTION_FIRE, 300))
        prefs(ctx).edit().putLong("next", at).apply()
        nextAt.value = at
    }

    /** Pause gemacht – zählt mit und plant die nächste. */
    fun done(ctx: Context) {
        val p = prefs(ctx)
        val today = Calc.dayStart(System.currentTimeMillis())
        val list = (p.getString("log", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { it > today - 30 * 86_400_000L }
        p.edit().putString("log", (list + System.currentTimeMillis()).joinToString(",")).putInt("n", p.getInt("n", 0) + 1).apply()
        doneToday.value = countToday(ctx)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        if (active.value || p.getBoolean("active", false)) schedule(ctx, System.currentTimeMillis() + ctx.intervalMin * 60_000L)
    }

    fun countToday(ctx: Context): Int {
        val today = Calc.dayStart(System.currentTimeMillis())
        return (prefs(ctx).getString("log", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.count { it >= today }
    }

    /** Die Übung für die nächste Pause */
    fun nextExercise(ctx: Context) = ExerciseLib.suggestion(prefs(ctx).getInt("n", 0))

    internal fun onAlarm(ctx: Context, action: String?) {
        when (action) {
            ACTION_FIRE -> {
                if (!prefs(ctx).getBoolean("active", false)) return
                notifyBreak(ctx)
                // Falls keine Reaktion: in einem Intervall wieder erinnern
                schedule(ctx, System.currentTimeMillis() + ctx.intervalMin * 60_000L)
            }
            ACTION_SNOOZE -> {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
                schedule(ctx, System.currentTimeMillis() + 10 * 60_000L)
            }
            ACTION_DONE -> done(ctx)
            ACTION_STOP -> stop(ctx)
        }
    }

    private fun notifyBreak(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bewegungspausen", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val ex = nextExercise(ctx)
        val open = PendingIntent.getActivity(
            ctx, 301,
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("endurofit://widget/exercise_${ex.id}"))
                .putExtra(de.endurofit.widget.WidgetLinks.EXTRA, "exercise_${ex.id}"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("🧘 Zeit für eine Bewegungspause")
            .setContentText("${ex.cat.emoji} ${ex.name} – ${ex.reps}. Tippen zeigt dir, wie es geht.")
            .setStyle(Notification.BigTextStyle().bigText("${ex.cat.emoji} ${ex.name} – ${ex.reps}\n" + ex.steps.joinToString(" ")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "✓ Gemacht", pi(ctx, ACTION_DONE, 302)).build())
            .addAction(Notification.Action.Builder(null, "In 10 Min.", pi(ctx, ACTION_SNOOZE, 303)).build())
            .addAction(Notification.Action.Builder(null, "Feierabend", pi(ctx, ACTION_STOP, 304)).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** Nach Neustart: läuft der Arbeitsmodus noch, Wecker neu setzen. */
    fun restore(ctx: Context) {
        load(ctx)
        if (!active.value) { if (prefs(ctx).getBoolean("active", false)) stop(ctx); return }
        val next = prefs(ctx).getLong("next", 0).coerceAtLeast(System.currentTimeMillis() + 60_000L)
        schedule(ctx, next)
    }
}

class BreakReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        BreakMode.onAlarm(ctx, intent.action)
    }
}
