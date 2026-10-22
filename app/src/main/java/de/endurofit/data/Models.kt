package de.endurofit.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class HrSample(val t: Long, val bpm: Int)

data class GymSet(val exercise: String, val reps: Int, val weightKg: Double, val t: Long)

enum class SessionType(val label: String, val emoji: String) {
    ENDURO("Enduro", "🏍️"),
    GYM("Gym", "🏋️"),
    OTHER("Sonstiges", "⚡");

    companion object {
        fun of(name: String?): SessionType = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

const val SOURCE_SENSOR = "sensor"
const val SOURCE_SUUNTO = "suunto"

data class Session(
    val id: String,
    val type: SessionType,
    val title: String,
    val start: Long,
    val end: Long,
    val source: String,
    val samples: List<HrSample> = emptyList(),
    val markers: List<Long> = emptyList(),
    val sets: List<GymSet> = emptyList(),
    val kcal: Double = 0.0,
    val note: String = "",
    val track: List<Fit.Point> = emptyList(),
    val distanceM: Double? = null,
    val ascentM: Double? = null,
) {
    val durationMs: Long get() = (end - start).coerceAtLeast(0)
    val avgBpm: Int get() = if (samples.isEmpty()) 0 else samples.map { it.bpm }.average().roundToInt()
    val maxBpm: Int get() = samples.maxOfOrNull { it.bpm } ?: 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("title", title)
        put("start", start)
        put("end", end)
        put("source", source)
        put("kcal", kcal)
        put("note", note)
        // Pulsdaten kompakt: [zeit, bpm, zeit, bpm, ...] relativ zum Start
        put("hr", JSONArray().also { arr ->
            samples.forEach { arr.put(it.t - start); arr.put(it.bpm) }
        })
        put("markers", JSONArray().also { arr -> markers.forEach { arr.put(it) } })
        // GPS kompakt: [zeit, lat*1e6, lon*1e6, höhe dm (-99999 = keine), ...]
        if (track.isNotEmpty()) put("track", JSONArray().also { arr ->
            track.forEach {
                arr.put(it.t - start)
                arr.put(Math.round(it.lat * 1e6))
                arr.put(Math.round(it.lon * 1e6))
                arr.put(it.alt?.let { a -> Math.round(a * 10) } ?: -99999L)
            }
        })
        distanceM?.let { put("dist", it) }
        ascentM?.let { put("ascent", it) }
        put("sets", JSONArray().also { arr ->
            sets.forEach {
                arr.put(JSONObject().apply {
                    put("exercise", it.exercise)
                    put("reps", it.reps)
                    put("weight", it.weightKg)
                    put("t", it.t)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val start = o.getLong("start")
            val hr = o.optJSONArray("hr") ?: JSONArray()
            val samples = ArrayList<HrSample>(hr.length() / 2)
            var i = 0
            while (i + 1 < hr.length()) {
                samples.add(HrSample(start + hr.getLong(i), hr.getInt(i + 1)))
                i += 2
            }
            val tr = o.optJSONArray("track") ?: JSONArray()
            val track = ArrayList<Fit.Point>(tr.length() / 4)
            var j = 0
            while (j + 3 < tr.length()) {
                val alt = tr.getLong(j + 3)
                track.add(Fit.Point(start + tr.getLong(j), tr.getLong(j + 1) / 1e6, tr.getLong(j + 2) / 1e6, if (alt == -99999L) null else alt / 10.0))
                j += 4
            }
            val m = o.optJSONArray("markers") ?: JSONArray()
            val markers = (0 until m.length()).map { m.getLong(it) }
            val s = o.optJSONArray("sets") ?: JSONArray()
            val sets = (0 until s.length()).map {
                val so = s.getJSONObject(it)
                GymSet(so.optString("exercise"), so.optInt("reps"), so.optDouble("weight", 0.0), so.optLong("t"))
            }
            return Session(
                id = o.getString("id"),
                type = SessionType.of(o.optString("type")),
                title = o.optString("title"),
                start = start,
                end = o.optLong("end", start),
                source = o.optString("source", SOURCE_SENSOR),
                samples = samples,
                markers = markers,
                sets = sets,
                kcal = o.optDouble("kcal", 0.0),
                note = o.optString("note", ""),
                track = track,
                distanceM = if (o.has("dist")) o.optDouble("dist") else null,
                ascentM = if (o.has("ascent")) o.optDouble("ascent") else null,
            )
        }
    }
}

data class FoodEntry(
    val id: String,
    val t: Long,
    val name: String,
    val kcal: Double,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val fromPhoto: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("t", t); put("name", name); put("kcal", kcal)
        put("protein", protein); put("carbs", carbs); put("fat", fat)
        if (fromPhoto) put("photo", true)
    }

    companion object {
        fun fromJson(o: JSONObject) = FoodEntry(
            id = o.getString("id"),
            t = o.getLong("t"),
            name = o.optString("name"),
            kcal = o.optDouble("kcal", 0.0),
            protein = o.optDouble("protein", 0.0),
            carbs = o.optDouble("carbs", 0.0),
            fat = o.optDouble("fat", 0.0),
            fromPhoto = o.optBoolean("photo", false),
        )
    }
}

data class Profile(
    val age: Int = 35,
    val weightKg: Double = 80.0,
    val heightCm: Int = 180,
    val male: Boolean = true,
    /** 0 = automatisch aus dem Alter berechnen */
    val maxHr: Int = 0,
    val restHr: Int = 60,
) {
    val effectiveMaxHr: Int get() = if (maxHr > 0) maxHr else (208 - 0.7 * age).roundToInt()

    fun toJson(): JSONObject = JSONObject().apply {
        put("age", age); put("weight", weightKg); put("height", heightCm)
        put("male", male); put("maxHr", maxHr); put("restHr", restHr)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            age = o.optInt("age", 35),
            weightKg = o.optDouble("weight", 80.0),
            heightCm = o.optInt("height", 180),
            male = o.optBoolean("male", true),
            maxHr = o.optInt("maxHr", 0),
            restHr = o.optInt("restHr", 60),
        )
    }
}

// ---------- Trinken ----------

data class WaterEntry(val id: String, val t: Long, val ml: Int) {
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("t", t); put("ml", ml) }

    companion object {
        fun fromJson(o: JSONObject) = WaterEntry(o.getString("id"), o.getLong("t"), o.optInt("ml"))
    }
}

// ---------- Schritte (von der Uhr) ----------

/** Schritte eines Tages. [outsideTraining] zählt nur Schritte außerhalb von Trainings. */
data class DaySteps(val day: Long, val total: Int, val outsideTraining: Int, val source: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("day", day); put("total", total); put("outside", outsideTraining); put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject) = DaySteps(
            o.getLong("day"), o.optInt("total"), o.optInt("outside", o.optInt("total")), o.optString("source")
        )
    }
}

// ---------- Schlaf ----------

data class SleepEntry(
    val id: String,
    val start: Long,
    val end: Long,
    val source: String,
    val deepMs: Long = 0,
    val lightMs: Long = 0,
    val remMs: Long = 0,
    val awakeMs: Long = 0,
) {
    val durationMs: Long get() = (end - start).coerceAtLeast(0)
    /** Geschlafene Zeit ohne Wachphasen */
    val asleepMs: Long get() = (durationMs - awakeMs).coerceAtLeast(0)
    val hasStages: Boolean get() = deepMs + lightMs + remMs > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("start", start); put("end", end); put("source", source)
        put("deep", deepMs); put("light", lightMs); put("rem", remMs); put("awake", awakeMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = SleepEntry(
            o.getString("id"), o.getLong("start"), o.getLong("end"), o.optString("source"),
            o.optLong("deep"), o.optLong("light"), o.optLong("rem"), o.optLong("awake"),
        )
    }
}

// ---------- Ziele ----------

enum class KcalMode(val label: String, val lo: Int, val hi: Int, val hint: String) {
    HALTEN("Gewicht halten", -300, 300, "Verbrauch und Essen in etwa ausgleichen."),
    ABNEHMEN("Abnehmen", -600, -200, "Ein Defizit von 200–600 kcal am Tag ist gesund und lässt sich durchhalten. Mehr kostet Kraft und Muskeln."),
    AUFBAUEN("Muskeln aufbauen", 150, 450, "Ein kleiner Überschuss von 150–450 kcal plus Krafttraining baut Muskeln ohne viel Fett auf.");

    companion object {
        fun of(name: String?): KcalMode = entries.firstOrNull { it.name == name } ?: HALTEN
    }
}

/**
 * Gesunde Standardwerte:
 * - 8.000 Schritte: Studien zeigen den größten Gesundheitsnutzen zwischen 7.000 und 10.000.
 * - Wasser 35 ml pro kg Körpergewicht + 500 ml je Trainingsstunde (0 = automatisch).
 * - Eiweiß 1,6 g pro kg – empfohlen bei regelmäßigem Krafttraining.
 * - 150 Minuten aktive Bewegung pro Woche und 2× Krafttraining (WHO-Empfehlung).
 * - 7–9 Stunden Schlaf für Erwachsene.
 */
data class Goals(
    val steps: Int = 8000,
    val waterMl: Int = 0,
    val proteinPerKg: Double = 1.6,
    val weeklyActiveMin: Int = 150,
    val weeklyStrength: Int = 2,
    val sleepHours: Double = 7.5,
    val mode: KcalMode = KcalMode.HALTEN,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("steps", steps); put("water", waterMl); put("protein", proteinPerKg)
        put("activeMin", weeklyActiveMin); put("strength", weeklyStrength)
        put("sleep", sleepHours); put("mode", mode.name)
    }

    companion object {
        fun fromJson(o: JSONObject) = Goals(
            steps = o.optInt("steps", 8000),
            waterMl = o.optInt("water", 0),
            proteinPerKg = o.optDouble("protein", 1.6),
            weeklyActiveMin = o.optInt("activeMin", 150),
            weeklyStrength = o.optInt("strength", 2),
            sleepHours = o.optDouble("sleep", 7.5),
            mode = KcalMode.of(o.optString("mode")),
        )
    }
}

// ---------- Blutsauerstoff ----------

data class Spo2Entry(val id: String, val t: Long, val pct: Double, val source: String) {
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("t", t); put("pct", pct); put("source", source) }

    companion object {
        fun fromJson(o: JSONObject) = Spo2Entry(o.getString("id"), o.getLong("t"), o.optDouble("pct"), o.optString("source"))
    }
}

// ---------- Herzfrequenz über den Tag ----------

/**
 * Puls eines ganzen Tages (von der Uhr). [points] sind 5-Minuten-Mittelwerte.
 * [resting] = Ruhepuls; [restingMeasured] = von der Uhr gemessen, sonst aus den Daten geschätzt.
 */
data class DayHr(
    val day: Long,
    val points: List<HrSample>,
    val min: Int,
    val max: Int,
    val avg: Int,
    val resting: Int?,
    val restingMeasured: Boolean,
    val source: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("day", day); put("min", min); put("max", max); put("avg", avg)
        resting?.let { put("rest", it) }
        put("restM", restingMeasured); put("source", source)
        put("p", JSONArray().also { a -> points.forEach { p -> a.put((p.t - day) / 60_000); a.put(p.bpm) } })
    }

    companion object {
        fun fromJson(o: JSONObject): DayHr {
            val day = o.getLong("day")
            val arr = o.optJSONArray("p") ?: JSONArray()
            val pts = ArrayList<HrSample>(arr.length() / 2)
            var i = 0
            while (i + 1 < arr.length()) {
                pts.add(HrSample(day + arr.getLong(i) * 60_000, arr.getInt(i + 1))); i += 2
            }
            return DayHr(
                day, pts, o.optInt("min"), o.optInt("max"), o.optInt("avg"),
                if (o.has("rest")) o.optInt("rest") else null, o.optBoolean("restM"), o.optString("source"),
            )
        }
    }
}
