package de.endurofit.data

import org.json.JSONArray
import org.json.JSONObject

/** Ein Symptom. [belowNeck] = Beschwerden unterhalb des Halses bzw. im ganzen Körper. */
enum class Symptom(val label: String, val emoji: String, val belowNeck: Boolean, val warning: Boolean = false) {
    SCHNUPFEN("Schnupfen", "🤧", false),
    HALS("Halskratzen", "😮‍💨", false),
    KOPF("Kopfweh", "🤕", false),
    HUSTEN("Husten", "😷", true),
    FIEBER("Fieber", "🌡️", true),
    GLIEDER("Gliederschmerzen", "🦴", true),
    MUEDE("Starke Erschöpfung", "😴", true),
    MAGEN("Magen-Darm", "🤢", true),
    BRUST("Brustschmerz / Atemnot", "❗", true, warning = true);

    companion object {
        fun of(name: String): Symptom? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Eine Krankheitsphase. [end] = null, solange du krank bist.
 * Danach folgt eine Wiedereinstiegsphase bis [returnUntil].
 */
data class SickPeriod(
    val id: String,
    val start: Long,
    val end: Long? = null,
    val symptoms: Set<Symptom> = emptySet(),
    /** Hatte zwischendurch Beschwerden unterhalb des Halses (auch wenn sie inzwischen weg sind) */
    val hadSystemic: Boolean = false,
    val returnUntil: Long? = null,
) {
    val active: Boolean get() = end == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("start", start)
        end?.let { put("end", it) }
        returnUntil?.let { put("ret", it) }
        put("sys", hadSystemic)
        put("symptoms", JSONArray(symptoms.map { it.name }))
    }

    companion object {
        fun fromJson(o: JSONObject): SickPeriod {
            val arr = o.optJSONArray("symptoms") ?: JSONArray()
            return SickPeriod(
                id = o.getString("id"),
                start = o.getLong("start"),
                end = if (o.has("end")) o.getLong("end") else null,
                symptoms = (0 until arr.length()).mapNotNull { Symptom.of(arr.getString(it)) }.toSet(),
                hadSystemic = o.optBoolean("sys", false),
                returnUntil = if (o.has("ret")) o.getLong("ret") else null,
            )
        }
    }
}

object Sick {

    enum class Advice { PAUSE, LOCKER, ARZT }

    /** "Neck-Check": nur oberhalb des Halses → locker möglich, sonst Pause. */
    fun advice(s: Set<Symptom>): Advice = when {
        s.any { it.warning } -> Advice.ARZT
        s.any { it.belowNeck } -> Advice.PAUSE
        else -> Advice.LOCKER
    }

    fun adviceTitle(a: Advice): String = when (a) {
        Advice.ARZT -> "Bitte ärztlich abklären lassen"
        Advice.PAUSE -> "Sportpause"
        Advice.LOCKER -> "Nur locker bewegen"
    }

    fun adviceText(a: Advice): String = when (a) {
        Advice.ARZT -> "Brustschmerzen oder Atemnot bei einem Infekt können auf eine Herzbeteiligung hindeuten. Kein Sport und bitte zeitnah ärztlich abklären lassen – bei starken Beschwerden den Notruf 112 wählen."
        Advice.PAUSE -> "Beschwerden unterhalb des Halses (z. B. Fieber, Gliederschmerzen, Husten): komplette Sportpause. Training würde die Heilung verzögern und belastet das Herz. Viel trinken, schlafen, ausruhen."
        Advice.LOCKER -> "Nur Beschwerden oberhalb des Halses: Wenn du dich fit fühlst, ist lockere Bewegung an der frischen Luft okay – Spaziergang, höchstens Zone 1–2, max. 30–45 Minuten. Kein Krafttraining, kein Enduro. Fühlst du dich schlapp: lieber ganz pausieren."
    }

    fun current(all: List<SickPeriod>): SickPeriod? = all.firstOrNull { it.active }

    /** Wiedereinstieg läuft gerade (nach "Wieder gesund"). */
    fun returning(all: List<SickPeriod>, now: Long = System.currentTimeMillis()): SickPeriod? =
        all.filter { !it.active && (it.returnUntil ?: 0) > now }.maxByOrNull { it.end ?: 0 }

    fun isSickDay(all: List<SickPeriod>, day: Long): Boolean {
        val dayEnd = Calc.addDays(day, 1)
        return all.any { p -> p.start < dayEnd && (p.end ?: Long.MAX_VALUE) > day }
    }

    /**
     * Länge der Wiedereinstiegsphase: nach einer reinen Erkältung 3 Tage,
     * nach Fieber/Gliederschmerzen 7–14 Tage (mindestens so lange wie die Krankheit).
     */
    fun returnDays(p: SickPeriod, end: Long): Int {
        val sickDays = ((Calc.dayStart(end) - Calc.dayStart(p.start)) / 86_400_000L).toInt() + 1
        return if (p.hadSystemic) sickDays.coerceIn(7, 14) else 3
    }

    fun dayIndex(start: Long, now: Long = System.currentTimeMillis()): Int =
        ((Calc.dayStart(now) - Calc.dayStart(start)) / 86_400_000L).toInt() + 1

    /** Durchschnittlicher Ruhepuls in den 14 Tagen vor der Krankheit. */
    fun restingBaseline(dayHr: Map<Long, DayHr>, start: Long): Double? {
        val d0 = Calc.dayStart(start)
        val vals = (1..14).mapNotNull { dayHr[Calc.addDays(d0, -it)]?.resting }
        return if (vals.size >= 3) vals.average() else null
    }
}
