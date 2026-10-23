package de.endurofit.data

import kotlin.math.roundToInt

/** Alles, was an einem Tag passiert ist – Grundlage für "Heute" und die Analyse. */
data class DaySummary(
    val day: Long,
    val bmr: Double,
    val trainingKcal: Double,
    val walkKcal: Double,
    val steps: Int,
    val eaten: Double,
    val protein: Double,
    val foodCount: Int,
    val waterMl: Int,
    val waterGoal: Int,
    val proteinGoal: Double,
    val sleep: SleepEntry?,
    val sessions: List<Session>,
    val activeMs: Long,
    /** An diesem Tag war der Krankheitsmodus an */
    val sick: Boolean = false,
) {
    val burned: Double get() = bmr + trainingKcal + walkKcal
    val balance: Double get() = eaten - burned
    val trainingMs: Long get() = sessions.sumOf { it.durationMs }
}

data class GoalCheck(
    val emoji: String,
    val label: String,
    val valueText: String,
    val progress: Float,
    val done: Boolean,
    /** null = noch nicht bewertbar (z. B. Kalorienbilanz am laufenden Tag) */
    val judged: Boolean = true,
)

object Analysis {

    /** Kalorien fürs Gehen, geschätzt aus Schritten, Schrittlänge und Gewicht. */
    fun walkKcal(steps: Int, p: Profile): Double {
        if (steps <= 0) return 0.0
        val strideM = p.heightCm * 0.415 / 100.0
        val miles = steps * strideM / 1609.34
        return miles * 0.5 * p.weightKg * 2.2046
    }

    fun waterGoal(g: Goals, p: Profile, trainingMs: Long): Int {
        if (g.waterMl > 0) return g.waterMl
        val ml = 35 * p.weightKg + trainingMs / 3_600_000.0 * 500
        return ((ml / 50).roundToInt() * 50)
    }

    fun proteinGoal(g: Goals, p: Profile): Double = g.proteinPerKg * p.weightKg

    /** Aktive Zeit eines Trainings: mit Puls die Zeit ab Zone 2, sonst die ganze Dauer. */
    fun activeMs(s: Session, maxHr: Int): Long {
        if (s.samples.size < 2) return s.durationMs
        val z = Calc.timeInZones(s.samples, maxHr)
        return z[2] + z[3] + z[4] + z[5]
    }

    /**
     * Aktive Minuten aus Schritten: Alles über eine Alltagsbasis von 4.000 Schritten zählt als
     * bewusstes Gehen, ca. 100 Schritte entsprechen einer Minute.
     */
    private fun walkActiveMs(stepsOutside: Int): Long = ((stepsOutside - 4000) / 100L).coerceAtLeast(0) * 60_000L

    fun sleepOfDay(all: List<SleepEntry>, day: Long): SleepEntry? {
        val end = Calc.addDays(day, 1)
        // Die Nacht zählt zu dem Tag, an dem du aufwachst
        return all.filter { it.end in day until end && it.durationMs > 2 * 3_600_000L }
            .maxByOrNull { it.durationMs }
    }

    fun day(
        day: Long,
        sessions: List<Session>,
        food: List<FoodEntry>,
        water: List<WaterEntry>,
        steps: Map<Long, DaySteps>,
        sleep: List<SleepEntry>,
        p: Profile,
        g: Goals,
    ): DaySummary {
        val end = Calc.addDays(day, 1)
        val ses = Calc.sessionsOfDay(sessions, day)
        val f = food.filter { it.t in day until end }
        val w = water.filter { it.t in day until end }.sumOf { it.ml }
        val st = steps[day]
        val trainingMs = ses.sumOf { it.durationMs }
        val maxHr = p.effectiveMaxHr
        val sick = Sick.isSickDay(Repo.sick.value, day)
        return DaySummary(
            day = day,
            bmr = Calc.bmr(p),
            trainingKcal = ses.sumOf { it.kcal },
            walkKcal = walkKcal(st?.outsideTraining ?: 0, p),
            steps = st?.total ?: 0,
            eaten = f.sumOf { it.kcal },
            protein = f.sumOf { it.protein },
            foodCount = f.size,
            waterMl = w,
            // Krank: 0,5 l mehr trinken
            waterGoal = waterGoal(g, p, trainingMs) + if (sick) 500 else 0,
            proteinGoal = proteinGoal(g, p),
            sleep = sleepOfDay(sleep, day),
            sessions = ses,
            activeMs = ses.sumOf { activeMs(it, maxHr) } + walkActiveMs(st?.outsideTraining ?: 0),
            sick = sick,
        )
    }

    fun days(from: Long, count: Int): List<Long> {
        val today = Calc.dayStart(System.currentTimeMillis())
        return (0 until count).map { Calc.addDays(from, it) }.filter { it <= today }
    }

    /** Tagesziele. Die Kalorienbilanz wird erst bewertet, wenn der Tag vorbei ist oder du Essen eingetragen hast. */
    fun dayGoals(d: DaySummary, g: Goals): List<GoalCheck> {
        val list = dayGoalsRaw(d, g)
        if (!d.sick) return list
        // Kranktage: Schritte, Eiweiß und Bilanz zählen nicht – nur Trinken und Schlaf
        return list.map { c ->
            if (c.emoji == "💧" || c.emoji == "😴") c
            else c.copy(judged = false, valueText = c.valueText + " · krank", done = false)
        }
    }

    private fun dayGoalsRaw(d: DaySummary, g: Goals): List<GoalCheck> {
        val today = Calc.dayStart(System.currentTimeMillis())
        val out = ArrayList<GoalCheck>()
        out.add(
            GoalCheck(
                "👣", "Schritte", "${fmtInt(d.steps)} / ${fmtInt(g.steps)}",
                (d.steps.toFloat() / g.steps).coerceIn(0f, 1f), d.steps >= g.steps
            )
        )
        out.add(
            GoalCheck(
                "💧", "Trinken", "${Calc.fmtNum(d.waterMl / 1000.0, 1)} / ${Calc.fmtNum(d.waterGoal / 1000.0, 1)} l",
                (d.waterMl.toFloat() / d.waterGoal.coerceAtLeast(1)).coerceIn(0f, 1f), d.waterMl >= d.waterGoal
            )
        )
        out.add(
            GoalCheck(
                "🥩", "Eiweiß", "${Calc.fmtNum(d.protein)} / ${Calc.fmtNum(d.proteinGoal)} g",
                (d.protein / d.proteinGoal.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f), d.protein >= d.proteinGoal
            )
        )
        val sleepGoalMs = (g.sleepHours * 3_600_000).toLong()
        val slept = d.sleep?.asleepMs ?: 0L
        out.add(
            GoalCheck(
                "😴", "Schlaf", if (d.sleep == null) "keine Daten" else "${Calc.fmtHours(slept)} / ${Calc.fmtNum(g.sleepHours, 1)} h",
                (slept.toFloat() / sleepGoalMs).coerceIn(0f, 1f), slept >= sleepGoalMs, judged = d.sleep != null
            )
        )
        val judged = d.foodCount > 0 && d.day < today
        val inRange = d.balance >= g.mode.lo && d.balance <= g.mode.hi
        out.add(
            GoalCheck(
                "⚖️", "Bilanz (${g.mode.label})",
                (if (d.balance < 0) "−" else "+") + Calc.fmtNum(kotlin.math.abs(d.balance)) +
                    " kcal · Ziel ${signed(g.mode.lo)} bis ${signed(g.mode.hi)}",
                if (inRange) 1f else 0.4f, inRange, judged = judged
            )
        )
        return out
    }

    data class WeekGoals(val activeMs: Long, val strength: Int, val goals: Goals, val sickDays: Int = 0) {
        /** Ziele anteilig verkleinert um die Kranktage der Woche */
        private val factor get() = ((7 - sickDays).coerceAtLeast(0)) / 7.0
        val activeTargetMin get() = (goals.weeklyActiveMin * factor).toInt()
        val strengthTarget get() = Math.round(goals.weeklyStrength * factor).toInt()
        val activeDone get() = activeMs >= activeTargetMin * 60_000L
        val strengthDone get() = strength >= strengthTarget
    }

    fun week(summaries: List<DaySummary>, g: Goals): WeekGoals = WeekGoals(
        activeMs = summaries.sumOf { it.activeMs },
        strength = summaries.sumOf { d -> d.sessions.count { it.type == SessionType.GYM } },
        goals = g,
        sickDays = summaries.count { it.sick },
    )

    fun fmtInt(v: Int): String = String.format(java.util.Locale.GERMANY, "%,d", v)
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
}
