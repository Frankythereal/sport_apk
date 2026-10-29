package de.endurofit.data

/** Baut aus vielen Pulswerten eines Tages eine DayHr (5-Minuten-Mittel, Ruhepuls). */
object DayHrBuilder {

    /** Mindestens so viele 5-Minuten-Abschnitte mit Daten, damit es als "Puls über den Tag" zählt (= 8 Stunden). */
    const val MIN_BUCKETS = 96

    fun build(day: Long, raw: List<HrSample>, resting: Int?, restingMeasured: Boolean, source: String, requireFullDay: Boolean = true): DayHr? {
        val next = Calc.addDays(day, 1)
        val sorted = raw.filter { it.t in day until next && it.bpm in 25..230 }.sortedBy { it.t }
        if (sorted.isEmpty()) return null
        val buckets = sorted.groupBy { (it.t - day) / 300_000 }
            .map { (b, l) -> HrSample(day + b * 300_000 + 150_000, l.sumOf { it.bpm } / l.size) }
            .sortedBy { it.t }
        // Nur Trainingspuls (z. B. Polar über Health Connect) ist kein Tagesverlauf
        if (requireFullDay && buckets.size < MIN_BUCKETS) return null
        // Ruhigste 30 Minuten (6 aufeinanderfolgende 5-Minuten-Werte) als Schätzung
        var est: Int? = null
        // Ruhepuls nur schätzen, wenn auch Nachtwerte da sind (sonst ist der ruhigste Moment z. B. nur der Nachmittag)
        val hasNight = buckets.any { it.t - day < 7 * 3_600_000L }
        if (buckets.size >= 6 && hasNight) {
            for (i in 0..buckets.size - 6) {
                val win = buckets.subList(i, i + 6)
                if (win.last().t - win.first().t > 40 * 60_000) continue
                val a = win.sumOf { it.bpm } / 6
                if (est == null || a < est) est = a
            }
        }
        return DayHr(
            day = day,
            points = buckets,
            min = sorted.minOf { it.bpm },
            max = sorted.maxOf { it.bpm },
            avg = sorted.sumOf { it.bpm } / sorted.size,
            resting = resting ?: est,
            restingMeasured = resting != null && restingMeasured,
            source = source,
        )
    }
}
