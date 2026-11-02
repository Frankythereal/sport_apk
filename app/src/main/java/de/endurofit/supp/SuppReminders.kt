package de.endurofit.supp

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import de.endurofit.MainActivity
import de.endurofit.R
import de.endurofit.data.Calc
import de.endurofit.data.Repo
import de.endurofit.data.Sick
import de.endurofit.data.SuppSlot
import de.endurofit.data.Supps
import java.util.Calendar
import java.util.TimeZone
import kotlin.concurrent.thread

/** Einnahme eintragen – aus der App oder direkt aus der Benachrichtigung. */
object SuppActions {
    fun take(ctx: Context, ids: List<String>, t: Long = System.currentTimeMillis()) {
        Repo.init(ctx)
        val supps = Repo.supps.value.associateBy { it.id }
        ids.forEach { id -> supps[id]?.let { Repo.takeSupp(id, it.unitsPerDose, t) } }
        if (Repo.suppCalendar) {
            val names = ids.mapNotNull { supps[it]?.name }
            if (names.isNotEmpty()) SuppCalendar.add(ctx.applicationContext, names, t)
        }
        ctx.getSystemService(NotificationManager::class.java)?.cancel(SuppReminders.NOTIF_ID)
        de.endurofit.widget.Widgets.updateAll(ctx)
    }
}

/** Trägt Einnahmen als kurzen Termin in den Handy-Kalender ein. */
object SuppCalendar {
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun allowed(ctx: Context) = PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun calendarId(ctx: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE,
        )
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val primary = c.getInt(1) == 1
                val access = c.getInt(2)
                val visible = c.getInt(3) == 1
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || !visible) continue
                if (primary) return id
                if (best == null) best = id
            }
            return best
        }
        return null
    }

    fun add(ctx: Context, names: List<String>, t: Long) {
        if (!allowed(ctx)) return
        thread {
            try {
                val cal = calendarId(ctx) ?: return@thread
                val v = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, cal)
                    put(CalendarContract.Events.TITLE, "💊 " + names.joinToString(", "))
                    put(CalendarContract.Events.DESCRIPTION, "Eingenommen – eingetragen von EnduroFit")
                    put(CalendarContract.Events.DTSTART, t)
                    put(CalendarContract.Events.DTEND, t + 5 * 60_000)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.HAS_ALARM, 0)
                }
                ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            } catch (e: Exception) {
                Log.w("SuppCalendar", "Kalender-Eintrag fehlgeschlagen", e)
            }
        }
    }
}

/** Erinnerungen morgens, mittags und abends – nur wenn wirklich etwas dran ist. */
object SuppReminders {
    const val NOTIF_ID = 42
    private const val CHANNEL = "supps"
    const val EXTRA_SLOT = "slot"
    const val EXTRA_IDS = "ids"

    const val EXTRA_MIN = "min"

    private fun pi(ctx: Context, minute: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 1000 + minute,
            Intent(ctx, SuppReminderReceiver::class.java).putExtra(EXTRA_MIN, minute),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun nextTime(minute: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 30_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("supp_alarms", Context.MODE_PRIVATE)

    /** Eine Erinnerung je Uhrzeit, die bei deinen Präparaten eingestellt ist. */
    fun schedule(ctx: Context) {
        Repo.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        // Alte Wecker (auch die früheren festen Zeiten) löschen
        val old = prefs(ctx).getStringSet("times", emptySet())!!.mapNotNull { it.toIntOrNull() }
        (old + SuppSlot.entries.map { it.hour * 60 + it.minute }).toSet().forEach { am.cancel(pi(ctx, it)) }
        SuppSlot.entries.forEach { slot ->
            am.cancel(PendingIntent.getBroadcast(ctx, 100 + slot.ordinal, Intent(ctx, SuppReminderReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        val times = if (Repo.suppReminders) Repo.supps.value.filter { Supps.relevant(it) }.map { it.at }.toSet() else emptySet()
        times.forEach { am.setWindow(AlarmManager.RTC_WAKEUP, nextTime(it), 10 * 60_000L, pi(ctx, it)) }
        prefs(ctx).edit().putStringSet("times", times.map { it.toString() }.toSet()).apply()
    }

    fun scheduleOne(ctx: Context, minute: Int) {
        if (!Repo.suppReminders) return
        if (Repo.supps.value.none { Supps.relevant(it) && it.at == minute }) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.setWindow(AlarmManager.RTC_WAKEUP, nextTime(minute), 10 * 60_000L, pi(ctx, minute))
    }

    /** Was bis jetzt fällig und noch nicht genommen ist */
    fun dueNow(ctx: Context, upTo: Int): List<Supps.Due> {
        Repo.init(ctx)
        val today = Calc.dayStart(System.currentTimeMillis())
        val trained = Calc.sessionsOfDay(Repo.sessions.value, today).isNotEmpty()
        val sick = Sick.isSickDay(Repo.sick.value, today)
        return Supps.dueToday(Repo.supps.value, Repo.suppIntakes.value, trained, sick)
            .filter { it.priority > 0 && it.supp.at <= upTo }
    }

    /** Prüft, was jetzt dran ist, und zeigt ggf. eine Benachrichtigung. */
    fun remind(ctx: Context, minute: Int) {
        Repo.init(ctx)
        if (!Repo.suppReminders) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val due = dueNow(ctx, minute)
        if (due.isEmpty()) return
        val slot = SuppSlot.forMinute(minute)

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tabletten-Erinnerung", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val ids = due.map { it.supp.id }
        val open = PendingIntent.getActivity(
            ctx, 200,
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("endurofit://widget/supps")).putExtra(de.endurofit.widget.WidgetLinks.EXTRA, "supps"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val taken = PendingIntent.getBroadcast(
            ctx, 201,
            Intent(ctx, SuppTakenReceiver::class.java).putExtra(EXTRA_IDS, ids.toTypedArray()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = due.joinToString("\n") { "${it.supp.emoji} ${it.supp.name}: ${it.reason}" }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("💊 ${slot.emoji} Zeit für: " + due.joinToString(", ") { it.supp.name })
            .setContentText(due.first().reason)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "✓ Alle genommen", taken).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

class SuppReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val minute = intent.getIntExtra(SuppReminders.EXTRA_MIN, -1)
        if (minute < 0) { SuppReminders.schedule(ctx); return }
        try {
            SuppReminders.remind(ctx, minute)
            de.endurofit.widget.Widgets.updateAll(ctx)
        } finally {
            SuppReminders.scheduleOne(ctx, minute)
        }
    }
}

class SuppTakenReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ids = intent.getStringArrayExtra(SuppReminders.EXTRA_IDS)?.toList() ?: return
        SuppActions.take(ctx, ids)
    }
}

/** Nach dem Neustart des Handys die Erinnerungen wieder planen. */
class SuppBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SuppReminders.schedule(ctx)
            de.endurofit.work.BreakMode.restore(ctx)
        }
    }
}
