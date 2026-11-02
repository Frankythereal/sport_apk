package de.endurofit.data

import org.json.JSONObject
import kotlin.math.pow

/** Ein Getränk mit Koffein. Wasser- und Essenseintrag (Zucker-kcal) hängen daran und werden mit gelöscht. */
data class CaffeineEntry(
    val id: String,
    val t: Long,
    val name: String,
    val emoji: String,
    val mg: Double,
    val ml: Int,
    val kcal: Double = 0.0,
    val waterId: String? = null,
    val foodId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("t", t); put("name", name); put("e", emoji); put("mg", mg); put("ml", ml); put("kcal", kcal)
        waterId?.let { put("w", it) }; foodId?.let { put("f", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = CaffeineEntry(
            o.getString("id"), o.getLong("t"), o.optString("name"), o.optString("e", "☕"), o.optDouble("mg"), o.optInt("ml"),
            o.optDouble("kcal", 0.0), o.optString("w").ifBlank { null }, o.optString("f").ifBlank { null },
        )
    }
}

/** Typische Getränke – Koffein laut Verbraucherzentrale/EFSA-Richtwerten, Energy-Drinks max. 32 mg/100 ml (gesetzliche Grenze). */
data class CaffeineDrink(
    val name: String, val emoji: String, val mg: Double, val ml: Int,
    val kcal: Double = 0.0, val carbs: Double = 0.0, val countsAsWater: Boolean = true,
)

object Caffeine {
    val drinks = listOf(
        CaffeineDrink("Espresso", "☕", 65.0, 30),
        CaffeineDrink("Tasse Kaffee", "☕", 90.0, 200),
        CaffeineDrink("Becher Kaffee", "☕", 140.0, 300),
        CaffeineDrink("Cappuccino", "☕", 65.0, 200, kcal = 80.0, carbs = 6.0),
        CaffeineDrink("Latte macchiato", "☕", 75.0, 300, kcal = 130.0, carbs = 10.0),
        CaffeineDrink("Energy 250 ml", "⚡", 80.0, 250, kcal = 113.0, carbs = 27.5),
        CaffeineDrink("Energy 500 ml", "⚡", 160.0, 500, kcal = 225.0, carbs = 55.0),
        CaffeineDrink("Energy zuckerfrei 500", "⚡", 160.0, 500, kcal = 10.0),
        CaffeineDrink("Cola 330 ml", "🥤", 33.0, 330, kcal = 139.0, carbs = 35.0),
        CaffeineDrink("Schwarztee", "🍵", 45.0, 250),
        CaffeineDrink("Grüntee", "🍵", 30.0, 250),
        CaffeineDrink("Pre-Workout", "💥", 200.0, 300, countsAsWater = false),
    )

    /** EFSA: bis 400 mg am Tag und 200 mg auf einmal gelten für gesunde Erwachsene als unbedenklich. */
    const val DAY_LIMIT = 400.0
    const val DAY_WARN = 300.0
    const val SINGLE_LIMIT = 200.0
    /** Halbwertszeit von Koffein bei Erwachsenen ca. 5 Stunden (schwankt 2–8 h). */
    const val HALF_LIFE_H = 5.0
    /** Ab diesem Rest beim Schlafengehen leidet der Tiefschlaf oft spürbar */
    const val SLEEP_REST = 50.0

    fun todayMg(list: List<CaffeineEntry>, day: Long): Double {
        val end = Calc.addDays(day, 1)
        return list.filter { it.t in day until end }.sumOf { it.mg }
    }

    /** Wie viel Koffein zu Zeitpunkt [at] noch im Körper ist (Abbau mit Halbwertszeit). */
    fun remainingAt(list: List<CaffeineEntry>, at: Long): Double =
        list.filter { it.t <= at && at - it.t < 36 * 3_600_000L }
            .sumOf { it.mg * 0.5.pow((at - it.t) / 3_600_000.0 / HALF_LIFE_H) }

    /** Geplante Schlafenszeit: aus den letzten Nächten, sonst 23 Uhr. */
    fun bedtime(day: Long, sleep: List<SleepEntry>): Long {
        val recent = sleep.filter { it.end > day - 10 * 86_400_000L }.map { s ->
            val m = ((s.start - Calc.dayStart(s.start)) / 60_000).toInt()
            if (m < 12 * 60) m + 24 * 60 else m   // nach Mitternacht → zählt zum Vorabend
        }
        val minutes = if (recent.size >= 3) recent.sorted()[recent.size / 2] else 23 * 60
        return day + minutes * 60_000L
    }

    data class Verdict(val level: Int, val title: String, val text: String)

    /** 0 = alles gut, 1 = Achtung, 2 = zu viel */
    fun verdict(list: List<CaffeineEntry>, day: Long, sleep: List<SleepEntry>, now: Long = System.currentTimeMillis()): Verdict {
        val total = todayMg(list, day)
        val bed = bedtime(day, sleep)
        val atBed = remainingAt(list, bed)
        val bedText = "Beim Schlafengehen (~${Calc.fmtClock(bed)}) sind noch etwa ${atBed.toInt()} mg im Körper."
        val last = list.filter { it.t in day until Calc.addDays(day, 1) }.maxByOrNull { it.t }
        return when {
            total > DAY_LIMIT -> Verdict(2, "Zu viel Koffein heute!", "${total.toInt()} mg – mehr als die 400 mg, die als unbedenklich gelten. Ab jetzt lieber Wasser. Mögliche Folgen: Herzrasen, Unruhe, schlechter Schlaf und schlechtere Erholung. $bedText")
            last != null && last.mg > SINGLE_LIMIT -> Verdict(2, "Zu viel auf einmal", "${last.mg.toInt()} mg in einem Getränk – mehr als 200 mg auf einmal belasten Herz und Kreislauf, gerade vor dem Training.")
            atBed > SLEEP_REST * 2 -> Verdict(2, "Schlecht für deinen Schlaf", "$bedText Das stört den Tiefschlaf und deine Erholung (Nightly Recharge). Kein Koffein mehr heute.")
            total > DAY_WARN -> Verdict(1, "Fast am Limit", "${total.toInt()} von 400 mg. Noch höchstens ein kleiner Kaffee – besser Wasser.")
            atBed > SLEEP_REST -> Verdict(1, "Achtung Schlaf", "$bedText Ab ca. 14 Uhr lieber koffeinfrei.")
            total > 0 && now - Calc.dayStart(now) > 14 * 3_600_000L && last != null && last.t > day + 14 * 3_600_000L ->
                Verdict(1, "Später Kaffee", "Koffein nach 14 Uhr wirkt oft bis in die Nacht. $bedText")
            else -> Verdict(0, "Alles im grünen Bereich", if (total > 0) "${total.toInt()} von 400 mg. $bedText" else "Heute noch kein Koffein.")
        }
    }
}
