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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("t", t); put("name", name); put("kcal", kcal)
        put("protein", protein); put("carbs", carbs); put("fat", fat)
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
