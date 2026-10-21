package de.endurofit.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speichert alles lokal auf dem Handy als JSON-Dateien. */
object Repo {
    private const val TAG = "Repo"
    private lateinit var baseDir: File
    private lateinit var sessionDir: File
    private lateinit var prefs: SharedPreferences

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    private val _food = MutableStateFlow<List<FoodEntry>>(emptyList())
    val food: StateFlow<List<FoodEntry>> = _food

    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile

    private val _water = MutableStateFlow<List<WaterEntry>>(emptyList())
    val water: StateFlow<List<WaterEntry>> = _water

    private val _steps = MutableStateFlow<Map<Long, DaySteps>>(emptyMap())
    val steps: StateFlow<Map<Long, DaySteps>> = _steps

    private val _sleep = MutableStateFlow<List<SleepEntry>>(emptyList())
    val sleep: StateFlow<List<SleepEntry>> = _sleep

    private val _goals = MutableStateFlow(Goals())
    val goals: StateFlow<Goals> = _goals

    private fun <T> loadList(name: String, parse: (JSONObject) -> T): List<T> {
        val f = File(baseDir, name)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "$name defekt", e); emptyList()
        }
    }

    private fun <T> saveList(name: String, list: List<T>, toJson: (T) -> JSONObject) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        writeAtomic(File(baseDir, name), arr.toString())
    }

    private fun loadExtras() {
        _water.value = loadList("water.json") { WaterEntry.fromJson(it) }.sortedByDescending { it.t }
        _steps.value = loadList("steps.json") { DaySteps.fromJson(it) }.associateBy { it.day }
        _sleep.value = loadList("sleep.json") { SleepEntry.fromJson(it) }.sortedByDescending { it.end }
        val g = File(baseDir, "goals.json")
        if (g.exists()) {
            try { _goals.value = Goals.fromJson(JSONObject(g.readText())) } catch (e: Exception) { Log.w(TAG, "goals.json defekt", e) }
        }
    }

    // ---------- Trinken ----------

    @Synchronized
    fun addWater(ml: Int, t: Long = System.currentTimeMillis()) {
        _water.value = (_water.value + WaterEntry("w_${System.nanoTime()}", t, ml)).sortedByDescending { it.t }
        saveList("water.json", _water.value) { it.toJson() }
    }

    @Synchronized
    fun deleteWater(id: String) {
        _water.value = _water.value.filter { it.id != id }
        saveList("water.json", _water.value) { it.toJson() }
    }

    // ---------- Schritte ----------

    /** Ersetzt die Schritte im Zeitraum [fromDay] bis [toDay] (inklusive) durch [list]. */
    @Synchronized
    fun replaceSteps(fromDay: Long, toDay: Long, list: List<DaySteps>) {
        val kept = _steps.value.filterKeys { it < fromDay || it > toDay }
        _steps.value = kept + list.associateBy { it.day }
        saveList("steps.json", _steps.value.values.sortedBy { it.day }) { it.toJson() }
    }

    // ---------- Schlaf ----------

    @Synchronized
    fun saveSleep(e: SleepEntry) {
        _sleep.value = (_sleep.value.filter { it.id != e.id } + e).sortedByDescending { it.end }
        saveList("sleep.json", _sleep.value) { it.toJson() }
    }

    @Synchronized
    fun saveSleepBatch(list: List<SleepEntry>) {
        val ids = list.map { it.id }.toSet()
        _sleep.value = (_sleep.value.filter { it.id !in ids } + list).sortedByDescending { it.end }
        saveList("sleep.json", _sleep.value) { it.toJson() }
    }

    @Synchronized
    fun deleteSleep(id: String) {
        _sleep.value = _sleep.value.filter { it.id != id }
        saveList("sleep.json", _sleep.value) { it.toJson() }
    }

    // ---------- Ziele ----------

    @Synchronized
    fun saveGoals(g: Goals) {
        _goals.value = g
        writeAtomic(File(baseDir, "goals.json"), g.toJson().toString())
    }

    @Synchronized
    fun init(ctx: Context) {
        if (::baseDir.isInitialized) return
        baseDir = ctx.applicationContext.filesDir
        sessionDir = File(baseDir, "sessions").apply { mkdirs() }
        prefs = ctx.applicationContext.getSharedPreferences("endurofit", Context.MODE_PRIVATE)
        load()
        loadExtras()
    }

    private fun load() {
        val list = sessionDir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { f ->
            try {
                Session.fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "Kaputte Datei ${f.name}", e); null
            }
        } ?: emptyList()
        _sessions.value = list.sortedByDescending { it.start }

        val foodFile = File(baseDir, "food.json")
        if (foodFile.exists()) {
            try {
                val arr = JSONArray(foodFile.readText())
                _food.value = (0 until arr.length()).map { FoodEntry.fromJson(arr.getJSONObject(it)) }
                    .sortedByDescending { it.t }
            } catch (e: Exception) {
                Log.w(TAG, "food.json defekt", e)
            }
        }
        val profileFile = File(baseDir, "profile.json")
        if (profileFile.exists()) {
            try {
                _profile.value = Profile.fromJson(JSONObject(profileFile.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "profile.json defekt", e)
            }
        }
    }

    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    // ---------- Trainings ----------

    @Synchronized
    fun saveSession(s: Session) {
        writeAtomic(File(sessionDir, safeName(s.id) + ".json"), s.toJson().toString())
        _sessions.value = (_sessions.value.filter { it.id != s.id } + s).sortedByDescending { it.start }
    }

    @Synchronized
    fun deleteSession(id: String) {
        File(sessionDir, safeName(id) + ".json").delete()
        _sessions.value = _sessions.value.filter { it.id != id }
    }

    fun session(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    // ---------- Essen ----------

    @Synchronized
    fun addFood(e: FoodEntry) {
        _food.value = (_food.value.filter { it.id != e.id } + e).sortedByDescending { it.t }
        saveFood()
    }

    @Synchronized
    fun deleteFood(id: String) {
        _food.value = _food.value.filter { it.id != id }
        saveFood()
    }

    private fun saveFood() {
        val arr = JSONArray()
        _food.value.forEach { arr.put(it.toJson()) }
        writeAtomic(File(baseDir, "food.json"), arr.toString())
    }

    // ---------- Profil ----------

    @Synchronized
    fun saveProfile(p: Profile) {
        _profile.value = p
        writeAtomic(File(baseDir, "profile.json"), p.toJson().toString())
    }

    // ---------- Einstellungen ----------

    var lastDeviceAddress: String?
        get() = prefs.getString("device_address", null)
        set(v) = prefs.edit().putString("device_address", v).apply()

    var lastDeviceName: String?
        get() = prefs.getString("device_name", null)
        set(v) = prefs.edit().putString("device_name", v).apply()

    var onlySuunto: Boolean
        get() = prefs.getBoolean("only_suunto", true)
        set(v) = prefs.edit().putBoolean("only_suunto", v).apply()

    var lastImport: Long
        get() = prefs.getLong("last_import", 0L)
        set(v) = prefs.edit().putLong("last_import", v).apply()
}
