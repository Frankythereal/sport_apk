package de.endurofit.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speichert alles lokal auf dem Handy als JSON-Dateien. */
object Repo {
    private const val TAG = "Repo"
    private lateinit var baseDir: File
    private lateinit var cacheDir: File
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

    private val _spo2 = MutableStateFlow<List<Spo2Entry>>(emptyList())
    val spo2: StateFlow<List<Spo2Entry>> = _spo2

    private val _dayHr = MutableStateFlow<Map<Long, DayHr>>(emptyMap())
    val dayHr: StateFlow<Map<Long, DayHr>> = _dayHr

    private val _recovery = MutableStateFlow<Map<Long, Recovery>>(emptyMap())
    val recovery: StateFlow<Map<Long, Recovery>> = _recovery

    private val _cardio = MutableStateFlow<Map<Long, CardioLoad>>(emptyMap())
    val cardio: StateFlow<Map<Long, CardioLoad>> = _cardio

    private val _skin = MutableStateFlow<Map<Long, SkinTemp>>(emptyMap())
    val skin: StateFlow<Map<Long, SkinTemp>> = _skin

    private val _ecg = MutableStateFlow<List<Ecg>>(emptyList())
    val ecg: StateFlow<List<Ecg>> = _ecg

    private val _watchBody = MutableStateFlow(WatchBody())
    val watchBody: StateFlow<WatchBody> = _watchBody

    private val _sick = MutableStateFlow<List<SickPeriod>>(emptyList())
    val sick: StateFlow<List<SickPeriod>> = _sick

    private val _weights = MutableStateFlow<List<WeightEntry>>(emptyList())
    /** Wiegungen, neueste zuerst */
    val weights: StateFlow<List<WeightEntry>> = _weights

    private val _supps = MutableStateFlow<List<Supplement>>(emptyList())
    val supps: StateFlow<List<Supplement>> = _supps
    private val _suppIntakes = MutableStateFlow<List<SuppIntake>>(emptyList())
    /** Einnahmen, neueste zuerst */
    val suppIntakes: StateFlow<List<SuppIntake>> = _suppIntakes

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
        _spo2.value = loadList("spo2.json") { Spo2Entry.fromJson(it) }.sortedByDescending { it.t }
        _dayHr.value = loadList("dayhr.json") { DayHr.fromJson(it) }.associateBy { it.day }
        _sick.value = loadList("sick.json") { SickPeriod.fromJson(it) }.sortedByDescending { it.start }
        _recovery.value = loadList("recovery.json") { Recovery.fromJson(it) }.associateBy { it.day }
        _cardio.value = loadList("cardio.json") { CardioLoad.fromJson(it) }.associateBy { it.day }
        _skin.value = loadList("skin.json") { SkinTemp.fromJson(it) }.associateBy { it.day }
        _ecg.value = loadList("ecg.json") { Ecg.fromJson(it) }.sortedByDescending { it.t }
        _weights.value = loadList("weights.json") { WeightEntry.fromJson(it) }.sortedByDescending { it.t }
        // Beim ersten Start deine Präparate vom Foto anlegen
        _supps.value = if (File(baseDir, "supps.json").exists()) loadList("supps.json") { Supplement.fromJson(it) } else SuppPresets.all
        _suppIntakes.value = loadList("supp_intakes.json") { SuppIntake.fromJson(it) }.sortedByDescending { it.t }
        File(baseDir, "watchbody.json").takeIf { it.exists() }?.let {
            try { _watchBody.value = WatchBody.fromJson(JSONObject(it.readText())) } catch (_: Exception) {}
        }
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
        // Nur gelieferte Tage überschreiben – Tage ohne neue Daten bleiben erhalten
        _steps.value = _steps.value + list.associateBy { it.day }
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

    // ---------- Polar: Erholung und Belastung ----------

    @Synchronized
    fun putRecovery(list: List<Recovery>) {
        _recovery.value = _recovery.value + list.associateBy { it.day }
        saveList("recovery.json", _recovery.value.values.sortedBy { it.day }) { it.toJson() }
    }

    @Synchronized
    fun putCardio(list: List<CardioLoad>) {
        _cardio.value = _cardio.value + list.associateBy { it.day }
        saveList("cardio.json", _cardio.value.values.sortedBy { it.day }) { it.toJson() }
    }

    @Synchronized
    fun putSkin(list: List<SkinTemp>) {
        _skin.value = _skin.value + list.associateBy { it.day }
        saveList("skin.json", _skin.value.values.sortedBy { it.day }) { it.toJson() }
    }

    @Synchronized
    fun putEcg(list: List<Ecg>) {
        val ids = list.map { it.id }.toSet()
        _ecg.value = (_ecg.value.filter { it.id !in ids } + list).sortedByDescending { it.t }
        saveList("ecg.json", _ecg.value) { it.toJson() }
    }

    /** Neue Körperwerte von der Uhr; übernimmt sie ins Profil, wenn das eingeschaltet ist. */
    @Synchronized
    fun putWatchBody(b: WatchBody) {
        val merged = WatchBody(
            maxHr = b.maxHr ?: _watchBody.value.maxHr,
            restHr = b.restHr ?: _watchBody.value.restHr,
            weightKg = b.weightKg ?: _watchBody.value.weightKg,
            vo2max = b.vo2max ?: _watchBody.value.vo2max,
            updated = System.currentTimeMillis(),
        )
        _watchBody.value = merged
        writeAtomic(File(baseDir, "watchbody.json"), merged.toJson().toString())
        if (useWatchBody) {
            val p = _profile.value
            val np = p.copy(
                maxHr = merged.maxHr?.takeIf { it in 120..230 } ?: p.maxHr,
                restHr = merged.restHr?.takeIf { it in 30..110 } ?: p.restHr,
                // Gewicht von der Uhr nur, solange es keine Wiegungen gibt
                weightKg = merged.weightKg?.takeIf { it in 30.0..250.0 && _weights.value.isEmpty() } ?: p.weightKg,
            )
            if (np != p) saveProfile(np)
        }
    }

    /** Körperwerte der Uhr automatisch ins Profil übernehmen */
    var useWatchBody: Boolean
        get() = prefs.getBoolean("use_watch_body", true)
        set(v) = prefs.edit().putBoolean("use_watch_body", v).apply()

    // Zugangsdaten für Polar AccessLink (bleiben nur auf dem Handy)
    var polarClientId: String
        get() = prefs.getString("polar_id", "") ?: ""
        set(v) = prefs.edit().putString("polar_id", v.trim()).apply()
    var polarSecret: String
        get() = prefs.getString("polar_secret", "") ?: ""
        set(v) = prefs.edit().putString("polar_secret", v.trim()).apply()
    var polarToken: String
        get() = prefs.getString("polar_token", "") ?: ""
        set(v) = prefs.edit().putString("polar_token", v).apply()
    var polarUserId: String
        get() = prefs.getString("polar_user", "") ?: ""
        set(v) = prefs.edit().putString("polar_user", v).apply()
    var polarLastSync: Long
        get() = prefs.getLong("polar_sync", 0L)
        set(v) = prefs.edit().putLong("polar_sync", v).apply()

    fun baseDirPath(): File = baseDir
    fun cacheDirPath(): File = cacheDir

    // ---------- Krankheitsmodus ----------

    @Synchronized
    fun saveSick(p: SickPeriod) {
        _sick.value = (_sick.value.filter { it.id != p.id } + p).sortedByDescending { it.start }
        saveList("sick.json", _sick.value) { it.toJson() }
    }

    @Synchronized
    fun deleteSick(id: String) {
        _sick.value = _sick.value.filter { it.id != id }
        saveList("sick.json", _sick.value) { it.toJson() }
    }

    // ---------- Herzfrequenz über den Tag ----------

    @Synchronized
    fun replaceDayHr(list: List<DayHr>) {
        _dayHr.value = _dayHr.value + list.associateBy { it.day }
        // Höchstens ein Jahr aufheben
        val cutoff = System.currentTimeMillis() - 366L * 86_400_000L
        _dayHr.value = _dayHr.value.filterKeys { it >= cutoff }
        saveList("dayhr.json", _dayHr.value.values.sortedBy { it.day }) { it.toJson() }
    }

    // ---------- Blutsauerstoff ----------

    // ---------- Nahrungsergänzung ----------

    @Synchronized
    fun saveSupp(x: Supplement) {
        _supps.value = if (_supps.value.any { it.id == x.id }) _supps.value.map { if (it.id == x.id) x else it } else _supps.value + x
        saveList("supps.json", _supps.value) { it.toJson() }
    }

    @Synchronized
    fun deleteSupp(id: String) {
        _supps.value = _supps.value.filter { it.id != id }
        saveList("supps.json", _supps.value) { it.toJson() }
    }

    /** Einnahme eintragen und den Vorrat herunterzählen. */
    @Synchronized
    fun takeSupp(suppId: String, units: Int = 1, t: Long = System.currentTimeMillis()): SuppIntake {
        val i = SuppIntake("si_${System.nanoTime()}", t, suppId, units)
        _suppIntakes.value = (_suppIntakes.value + i).sortedByDescending { it.t }
        saveList("supp_intakes.json", _suppIntakes.value) { it.toJson() }
        _supps.value.firstOrNull { it.id == suppId }?.let { saveSupp(it.copy(stock = (it.stock - units).coerceAtLeast(0))) }
        return i
    }

    @Synchronized
    fun deleteSuppIntake(id: String) {
        val i = _suppIntakes.value.firstOrNull { it.id == id } ?: return
        _suppIntakes.value = _suppIntakes.value.filter { it.id != id }
        saveList("supp_intakes.json", _suppIntakes.value) { it.toJson() }
        _supps.value.firstOrNull { it.id == i.suppId }?.let { saveSupp(it.copy(stock = it.stock + i.units)) }
    }

    /** Erinnerungen an Tabletten */
    var suppReminders: Boolean
        get() = prefs.getBoolean("supp_reminders", true)
        set(v) = prefs.edit().putBoolean("supp_reminders", v).apply()

    /** Einnahmen zusätzlich in den Handy-Kalender eintragen */
    var suppCalendar: Boolean
        get() = prefs.getBoolean("supp_calendar", false)
        set(v) = prefs.edit().putBoolean("supp_calendar", v).apply()

    // ---------- Gewicht ----------

    /**
     * Neue Wiegungen speichern. Gleiche ID ersetzt; dieselbe Wiegung aus einer anderen Quelle
     * (z. B. Waage direkt und zusätzlich über Health Connect) wird nicht doppelt gezählt.
     */
    @Synchronized
    fun addWeights(list: List<WeightEntry>) {
        if (list.isEmpty()) return
        var cur = _weights.value
        for (w in list) {
            val same = cur.firstOrNull { it.id == w.id }
                ?: cur.firstOrNull { abs(it.t - w.t) < 5 * 60_000 && abs(it.kg - w.kg) < 0.3 }
            cur = if (same == null) cur + w else {
                // Werte zusammenführen, die Waage direkt hat Vorrang
                val keep = if (same.source == "scale" && w.source != "scale") same else w
                val other = if (keep === same) w else same
                cur.filter { it.id != same.id } + keep.copy(
                    fatPct = keep.fatPct ?: other.fatPct,
                    musclePct = keep.musclePct ?: other.musclePct,
                    waterPct = keep.waterPct ?: other.waterPct,
                    boneKg = keep.boneKg ?: other.boneKg,
                )
            }
        }
        _weights.value = cur.sortedByDescending { it.t }
        saveList("weights.json", _weights.value) { it.toJson() }
        // Neuestes Gewicht ins Profil übernehmen (für Grundumsatz & Kalorien)
        _weights.value.firstOrNull()?.let { latest ->
            val p = _profile.value
            if (latest.kg in 30.0..250.0 && abs(p.weightKg - latest.kg) >= 0.05) saveProfile(p.copy(weightKg = Math.round(latest.kg * 10) / 10.0))
        }
    }

    @Synchronized
    fun deleteWeight(id: String) {
        _weights.value = _weights.value.filter { it.id != id }
        saveList("weights.json", _weights.value) { it.toJson() }
    }

    /** Gekoppelte Waage und ihr Benutzerplatz (mit Zugangscode) */
    var scaleAddress: String?
        get() = prefs.getString("scale_address", null)
        set(v) = prefs.edit().putString("scale_address", v).apply()
    var scaleName: String?
        get() = prefs.getString("scale_name", null)
        set(v) = prefs.edit().putString("scale_name", v).apply()
    var scaleUserIndex: Int
        get() = prefs.getInt("scale_user", -1)
        set(v) = prefs.edit().putInt("scale_user", v).apply()
    var scaleConsent: Int
        get() = prefs.getInt("scale_consent", -1)
        set(v) = prefs.edit().putInt("scale_consent", v).apply()

    @Synchronized
    fun addSpo2(list: List<Spo2Entry>) {
        val ids = list.map { it.id }.toSet()
        _spo2.value = (_spo2.value.filter { it.id !in ids } + list).sortedByDescending { it.t }
        saveList("spo2.json", _spo2.value) { it.toJson() }
    }

    @Synchronized
    fun deleteSpo2(id: String) {
        _spo2.value = _spo2.value.filter { it.id != id }
        saveList("spo2.json", _spo2.value) { it.toJson() }
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
        cacheDir = ctx.applicationContext.cacheDir
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

    /** Schlüssel für die Claude-API (Essensfotos). Bleibt nur auf dem Handy. */
    var claudeKey: String
        get() = prefs.getString("claude_key", "") ?: ""
        set(v) = prefs.edit().putString("claude_key", v.trim()).apply()

    var claudeModel: String
        get() = prefs.getString("claude_model", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        set(v) = prefs.edit().putString("claude_model", v).apply()

    var lastImport: Long
        get() = prefs.getLong("last_import", 0L)
        set(v) = prefs.edit().putLong("last_import", v).apply()
}
