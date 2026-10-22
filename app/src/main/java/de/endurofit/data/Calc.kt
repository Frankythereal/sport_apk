package de.endurofit.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

object Calc {

    /** Kalorienverbrauch pro Minute aus dem Puls (Formel nach Keytel et al. 2005). */
    fun kcalPerMin(bpm: Int, p: Profile): Double {
        if (bpm <= 0) return 0.0
        val v = if (p.male) {
            (-55.0969 + 0.6309 * bpm + 0.1988 * p.weightKg + 0.2017 * p.age) / 4.184
        } else {
            (-20.4022 + 0.4472 * bpm - 0.1263 * p.weightKg + 0.074 * p.age) / 4.184
        }
        return max(0.0, v)
    }

    /** Summiert den Verbrauch über alle Pulswerte. Lücken über 10 s werden nicht gezählt. */
    fun sessionKcal(samples: List<HrSample>, p: Profile): Double {
        var sum = 0.0
        for (i in 1 until samples.size) {
            val dt = samples[i].t - samples[i - 1].t
            if (dt <= 0 || dt > 10_000) continue
            sum += kcalPerMin(samples[i].bpm, p) * dt / 60_000.0
        }
        return sum
    }

    /** Zone 1–5 nach Prozent der maximalen Herzfrequenz, 0 = unter 50 %. */
    fun zoneOf(bpm: Int, maxHr: Int): Int {
        if (maxHr <= 0) return 0
        val pct = bpm.toDouble() / maxHr
        return when {
            pct >= 0.9 -> 5
            pct >= 0.8 -> 4
            pct >= 0.7 -> 3
            pct >= 0.6 -> 2
            pct >= 0.5 -> 1
            else -> 0
        }
    }

    val zoneNames = listOf("Ruhe", "Zone 1 · locker", "Zone 2 · Grundlage", "Zone 3 · mittel", "Zone 4 · hart", "Zone 5 · Maximum")

    /** Zeit in Millisekunden je Zone (Index 0..5). */
    fun timeInZones(samples: List<HrSample>, maxHr: Int): LongArray {
        val out = LongArray(6)
        for (i in 1 until samples.size) {
            val dt = samples[i].t - samples[i - 1].t
            if (dt <= 0 || dt > 10_000) continue
            out[zoneOf(samples[i].bpm, maxHr)] += dt
        }
        return out
    }

    data class Peak(val start: Long, val end: Long, val maxBpm: Int, val maxAt: Long) {
        val durationMs get() = end - start
    }

    /**
     * Findet Abschnitte, in denen der Puls mindestens [minMs] lang über [pct] der
     * maximalen Herzfrequenz lag – das sind die "Extrempunkte".
     */
    fun peaks(samples: List<HrSample>, maxHr: Int, pct: Double = 0.9, minMs: Long = 10_000): List<Peak> {
        if (samples.isEmpty() || maxHr <= 0) return emptyList()
        val limit = maxHr * pct
        val out = ArrayList<Peak>()
        var segStart = -1L
        var segEnd = 0L
        var segMax = 0
        var segMaxAt = 0L
        var prevT = 0L
        fun close() {
            if (segStart >= 0 && segEnd - segStart >= minMs) out.add(Peak(segStart, segEnd, segMax, segMaxAt))
            segStart = -1L
        }
        for (s in samples) {
            if (segStart >= 0 && s.t - prevT > 15_000) close()
            if (s.bpm >= limit) {
                if (segStart < 0) { segStart = s.t; segMax = 0 }
                segEnd = s.t
                if (s.bpm > segMax) { segMax = s.bpm; segMaxAt = s.t }
            } else if (segStart >= 0) {
                close()
            }
            prevT = s.t
        }
        close()
        return out
    }

    /** Der Zeitabschnitt mit dem höchsten Durchschnittspuls (Standard: 1 Minute). */
    fun hardestWindow(samples: List<HrSample>, windowMs: Long = 60_000): Pair<Long, Int>? {
        if (samples.size < 2 || samples.last().t - samples.first().t < windowMs) return null
        var bestStart = samples.first().t
        var best = 0.0
        var sum = 0L
        var left = 0
        for (right in samples.indices) {
            sum += samples[right].bpm
            while (samples[right].t - samples[left].t > windowMs) {
                sum -= samples[left].bpm
                left++
            }
            val count = right - left + 1
            if (samples[right].t - samples[left].t >= windowMs * 0.8 && count > 0) {
                val avg = sum.toDouble() / count
                if (avg > best) { best = avg; bestStart = samples[left].t }
            }
        }
        return if (best > 0) bestStart to best.toInt() else null
    }

    /** Grundumsatz pro Tag (Mifflin-St-Jeor). */
    fun bmr(p: Profile): Double {
        val base = 10 * p.weightKg + 6.25 * p.heightCm - 5 * p.age
        return if (p.male) base + 5 else base - 161
    }

    fun dayStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Montag der Woche, in der [t] liegt. */
    fun weekStart(t: Long): Long = Calendar.getInstance(Locale.GERMANY).apply {
        timeInMillis = dayStart(t)
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (timeInMillis > t) add(Calendar.DAY_OF_YEAR, -7)
    }.timeInMillis

    /** Erster Tag des Monats, verschoben um [offset] Monate. */
    fun monthStart(t: Long, offset: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(t)
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, offset)
    }.timeInMillis

    fun daysInMonth(monthStart: Long): Int = Calendar.getInstance().apply { timeInMillis = monthStart }
        .getActualMaximum(Calendar.DAY_OF_MONTH)

    fun fmtMonth(t: Long): String = SimpleDateFormat("LLLL yyyy", de).format(Date(t))
    fun fmtShortDate(t: Long): String = SimpleDateFormat("EE dd.MM.", de).format(Date(t))
    fun fmtHours(ms: Long): String {
        val m = ms / 60_000
        return "${m / 60}:${String.format(de, "%02d", m % 60)} h"
    }

    fun addDays(dayStart: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis

    /**
     * Trainings eines Tages, ohne doppelte Zählung: Überschneiden sich zwei Aufnahmen
     * zu mehr als der Hälfte, zählt nur eine. Vorrang: eigener Sensor, dann FIT-Import,
     * dann Health Connect.
     */
    fun sessionsOfDay(all: List<Session>, day: Long): List<Session> {
        val end = addDays(day, 1)
        val ofDay = all.filter { it.start in day until end }
        return dedupe(ofDay)
    }

    fun priority(s: Session): Int = when {
        s.source == SOURCE_SENSOR -> 0
        s.id.startsWith("fit_") -> 1
        else -> 2
    }

    fun overlaps(a: Session, b: Session): Boolean {
        val o = minOf(a.end, b.end) - maxOf(a.start, b.start)
        val shorter = minOf(a.durationMs, b.durationMs).coerceAtLeast(1)
        return o > shorter / 2
    }

    fun dedupe(list: List<Session>): List<Session> {
        val accepted = ArrayList<Session>()
        for (s in list.sortedWith(compareBy<Session> { priority(it) }.thenBy { it.start })) {
            if (accepted.none { overlaps(it, s) }) accepted.add(s)
        }
        return accepted.sortedBy { it.start }
    }

    /** Eine andere Aufnahme zur selben Zeit (z. B. Suunto-GPS zu einer Sensor-Aufnahme). */
    fun partner(all: List<Session>, s: Session, need: (Session) -> Boolean): Session? =
        all.filter { it.id != s.id && need(it) && overlaps(it, s) }
            .maxByOrNull { minOf(it.end, s.end) - maxOf(it.start, s.start) }

    // ---------- Formatierung ----------

    private val de = Locale.GERMANY
    fun fmtDateTime(t: Long): String = SimpleDateFormat("EE dd.MM.yyyy, HH:mm", de).format(Date(t))
    fun fmtDate(t: Long): String = SimpleDateFormat("EEEE, dd.MM.", de).format(Date(t))
    fun fmtTime(t: Long): String = SimpleDateFormat("HH:mm:ss", de).format(Date(t))
    fun fmtClock(t: Long): String = SimpleDateFormat("HH:mm", de).format(Date(t))

    fun fmtDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(de, "%d:%02d:%02d", h, m, s) else String.format(de, "%d:%02d", m, s)
    }

    fun fmtNum(v: Double, decimals: Int = 0): String = String.format(de, "%.${decimals}f", v)

    fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
}
