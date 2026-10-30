package de.endurofit.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import de.endurofit.data.Calc
import de.endurofit.data.DayHr
import de.endurofit.data.DayHrBuilder
import de.endurofit.data.Fit
import de.endurofit.data.DaySteps
import de.endurofit.data.HrSample
import de.endurofit.data.Repo
import de.endurofit.data.SOURCE_SUUNTO
import de.endurofit.data.Session
import de.endurofit.data.SessionType
import de.endurofit.data.SleepEntry
import de.endurofit.data.Spo2Entry
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Holt Daten der Uhr (Polar, Suunto, Garmin …) aus Health Connect: Trainings (mit Puls, Kalorien, GPS),
 * Schritte und Schlaf.
 * Voraussetzung: In der App der Uhr ist die Health-Connect-Verbindung eingeschaltet.
 */
object SuuntoImport {
    private const val TAG = "SuuntoImport"

    /** GPS-Strecken anderer Apps lesen */
    const val ROUTE_PERMISSION = "android.permission.health.READ_EXERCISE_ROUTES"

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class),
        ROUTE_PERMISSION,
    )

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(ctx: Context): Boolean {
        if (!available(ctx)) return false
        val granted = HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    /** Mindestens eine Berechtigung erteilt – dann lohnt sich der Import im Hintergrund. */
    suspend fun anyPermission(ctx: Context): Boolean {
        if (!available(ctx)) return false
        return HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().any { it in PERMISSIONS }
    }

    private fun isSuunto(pkg: String) = pkg.contains("suunto", true) || pkg.startsWith("com.stt.android")
    fun isPolar(pkg: String) = pkg.startsWith("fi.polar")
    private fun isGarmin(pkg: String) = pkg.startsWith("com.garmin")

    /** Daten von einer Sportuhr (nicht vom Handy) – werden bevorzugt. */
    fun isWatch(pkg: String) = isSuunto(pkg) || isPolar(pkg) || isGarmin(pkg) || pkg.contains("coros", true)

    data class Result(
        val imported: Int,
        val updated: Int,
        val skippedOtherApps: Int,
        val stepDays: Int,
        val nights: Int,
        val spo2: Int,
        val stepSource: String?,
        val hrDays: Int,
    )

    suspend fun import(ctx: Context, days: Int = 30): Result {
        val client = HealthConnectClient.getOrCreate(ctx)
        val end = Instant.now()
        val start = end.minus(days.toLong(), ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)
        val onlySuunto = Repo.onlySuunto

        val (imported, updated, skipped) = try {
            importSessions(ctx, client, range, onlySuunto)
        } catch (e: Exception) {
            Log.w(TAG, "Trainings-Import fehlgeschlagen", e); Triple(0, 0, 0)
        }
        val stepDays = try {
            importSteps(ctx, client, start, end, onlySuunto)
        } catch (e: Exception) {
            Log.w(TAG, "Schritte-Import fehlgeschlagen", e); 0
        }
        val nights = try {
            importSleep(ctx, client, range)
        } catch (e: Exception) {
            Log.w(TAG, "Schlaf-Import fehlgeschlagen", e); 0
        }
        val spo2 = try {
            importSpo2(ctx, client, range)
        } catch (e: Exception) {
            Log.w(TAG, "SpO2-Import fehlgeschlagen", e); 0
        }
        val hrDays = try {
            importDayHr(ctx, client, start, end)
        } catch (e: Exception) {
            Log.w(TAG, "Tagespuls-Import fehlgeschlagen", e); 0
        }
        try {
            importWeight(ctx, client, range)
        } catch (e: Exception) {
            Log.w(TAG, "Gewicht-Import fehlgeschlagen", e)
        }
        Repo.lastImport = System.currentTimeMillis()
        val todaySource = Repo.steps.value[Calc.dayStart(System.currentTimeMillis())]?.source
        return Result(imported, updated, skipped, stepDays, nights, spo2, todaySource, hrDays)
    }

    // ---------- Trainings ----------

    private suspend fun importSessions(
        ctx: Context, client: HealthConnectClient, range: TimeRangeFilter, onlySuunto: Boolean,
    ): Triple<Int, Int, Int> {
        val profile = Repo.profile.value
        val records = ArrayList<ExerciseSessionRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range, pageToken = token))
            records.addAll(resp.records)
            token = resp.pageToken
        } while (token != null)

        var imported = 0
        var updated = 0
        var skipped = 0
        for (rec in records) {
            val pkg = rec.metadata.dataOrigin.packageName
            if (pkg == ctx.packageName) continue
            if (onlySuunto && !isWatch(pkg)) { skipped++; continue }

            val r = TimeRangeFilter.between(rec.startTime, rec.endTime)
            val origin = setOf(DataOrigin(pkg))

            val hr = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, r, dataOriginFilter = origin)).records
            val samples = hr.flatMap { h -> h.samples.map { HrSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) } }
                .sortedBy { it.t }
                .distinctBy { it.t }

            var kcal = client.readRecords(
                ReadRecordsRequest(TotalCaloriesBurnedRecord::class, r, dataOriginFilter = origin)
            ).records.sumOf { it.energy.inKilocalories }
            if (kcal <= 0.0) {
                kcal = client.readRecords(
                    ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, r, dataOriginFilter = origin)
                ).records.sumOf { it.energy.inKilocalories }
            }
            if (kcal <= 0.0) kcal = Calc.sessionKcal(samples, profile)

            val id = "hc_" + rec.metadata.id
            val existing = Repo.session(id)
            val probe = Session(id, SessionType.OTHER, "", rec.startTime.toEpochMilli(), rec.endTime.toEpochMilli(), SOURCE_SUUNTO)
            if (Repo.sessions.value.any { it.id.startsWith("fit_") && Calc.overlaps(it, probe) }) {
                if (existing != null) Repo.deleteSession(id)
                continue
            }
            // GPS-Strecke (z. B. von Polar). Nur lesbar, solange die App offen ist.
            var track = try {
                when (val rr = rec.exerciseRouteResult) {
                    is ExerciseRouteResult.Data -> rr.exerciseRoute.route.map {
                        Fit.Point(it.time.toEpochMilli(), it.latitude, it.longitude, it.altitude?.inMeters)
                    }.sortedBy { it.t }
                    else -> emptyList()
                }
            } catch (_: Throwable) {
                emptyList()
            }
            if (track.isEmpty()) track = existing?.track ?: emptyList()
            val guessedType = guessType(rec.exerciseType, rec.title)
            Repo.saveSession(
                Session(
                    id = id,
                    // Was du selbst geändert hast (Art, Name, Notiz), bleibt erhalten
                    type = existing?.type ?: guessedType,
                    title = existing?.title ?: (rec.title?.takeIf { it.isNotBlank() } ?: (appLabel(ctx, pkg) + "-Training")),
                    start = rec.startTime.toEpochMilli(),
                    end = rec.endTime.toEpochMilli(),
                    source = SOURCE_SUUNTO,
                    samples = samples,
                    markers = existing?.markers ?: emptyList(),
                    sets = existing?.sets ?: emptyList(),
                    kcal = kcal,
                    note = existing?.note ?: "",
                    track = track,
                    distanceM = existing?.distanceM ?: Calc.trackDistance(track),
                    ascentM = existing?.ascentM,
                )
            )
            if (existing == null) imported++ else updated++
        }
        return Triple(imported, updated, skipped)
    }

    // ---------- Schritte ----------

    /**
     * Summiert die Schritte je Tag. Schritte während eines Trainings werden für den
     * Kalorienverbrauch herausgerechnet, damit nichts doppelt zählt.
     * Liefern mehrere Apps Schritte (z. B. Handy und Uhr), wird pro Tag nur eine Quelle genommen –
     * bevorzugt die Uhr, sonst die mit den meisten Schritten (z. B. Samsung Health vom Handy).
     */
    private suspend fun importSteps(
        ctx: Context, client: HealthConnectClient, start: Instant, end: Instant, onlySuunto: Boolean,
    ): Int {
        val records = ArrayList<StepsRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end), pageToken = token)
            )
            records.addAll(resp.records)
            token = resp.pageToken
        } while (token != null)

        val sessions = Repo.sessions.value
        // Tag -> Quelle -> (gesamt, außerhalb Training)
        val acc = HashMap<Long, HashMap<String, DoubleArray>>()
        for (r in records) {
            val pkg = r.metadata.dataOrigin.packageName
            if (pkg == ctx.packageName) continue
            val s = r.startTime.toEpochMilli()
            val e = r.endTime.toEpochMilli()
            val dur = max(1L, e - s)
            var overlap = 0L
            for (ses in sessions) {
                val o = min(e, ses.end) - max(s, ses.start)
                if (o > 0) overlap += o
            }
            val outsideFrac = 1.0 - (overlap.toDouble() / dur).coerceIn(0.0, 1.0)
            val day = Calc.dayStart(s)
            val arr = acc.getOrPut(day) { HashMap() }.getOrPut(pkg) { DoubleArray(2) }
            arr[0] += r.count.toDouble()
            arr[1] += r.count * outsideFrac
        }

        val list = acc.map { (day, bySource) ->
            val best = bySource.entries.sortedWith(
                compareByDescending<Map.Entry<String, DoubleArray>> { isWatch(it.key) }.thenByDescending { it.value[0] }
            ).first()
            DaySteps(day, best.value[0].roundToInt(), best.value[1].roundToInt(), best.key)
        }
        Repo.replaceSteps(Calc.dayStart(start.toEpochMilli()), Calc.dayStart(end.toEpochMilli()), list)
        return list.size
    }

    // ---------- Schlaf ----------

    /** Schlaf je Nacht. Gibt es mehrere Quellen, wird Suunto bevorzugt, sonst die längste Aufzeichnung. */
    private suspend fun importSleep(ctx: Context, client: HealthConnectClient, range: TimeRangeFilter): Int {
        val records = ArrayList<SleepSessionRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range, pageToken = token))
            records.addAll(resp.records)
            token = resp.pageToken
        } while (token != null)

        val byNight = records
            .filter { it.metadata.dataOrigin.packageName != ctx.packageName }
            .groupBy { Calc.dayStart(it.endTime.toEpochMilli()) }

        val entries = byNight.values.map { list ->
            val rec = list.sortedWith(
                compareByDescending<SleepSessionRecord> { isWatch(it.metadata.dataOrigin.packageName) }
                    .thenByDescending { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
            ).first()
            var deep = 0L; var light = 0L; var rem = 0L; var awake = 0L
            for (st in rec.stages) {
                val d = st.endTime.toEpochMilli() - st.startTime.toEpochMilli()
                when (st.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> deep += d
                    SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> light += d
                    SleepSessionRecord.STAGE_TYPE_REM -> rem += d
                    SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += d
                }
            }
            SleepEntry(
                id = "hc_" + rec.metadata.id,
                start = rec.startTime.toEpochMilli(),
                end = rec.endTime.toEpochMilli(),
                source = rec.metadata.dataOrigin.packageName,
                deepMs = deep, lightMs = light, remMs = rem, awakeMs = awake,
            )
        }
        // Importierte Nächte ersetzen ältere Importe derselben Nacht, eigene Einträge bleiben
        val nights = entries.map { Calc.dayStart(it.end) }.toSet()
        Repo.sleep.value
            .filter { it.id.startsWith("hc_") && Calc.dayStart(it.end) in nights && entries.none { e -> e.id == it.id } }
            .forEach { Repo.deleteSleep(it.id) }
        Repo.saveSleepBatch(entries)
        return entries.size
    }

    // ---------- Gewicht (z. B. aus der Beurer-App oder Polar Flow) ----------

    private suspend fun importWeight(ctx: Context, client: HealthConnectClient, range: TimeRangeFilter): Int {
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class) !in granted) return 0
        val weights = client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.WeightRecord::class, range)).records
            .filter { it.metadata.dataOrigin.packageName != ctx.packageName }
        if (weights.isEmpty()) return 0
        val fats = if (HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class) in granted)
            client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.BodyFatRecord::class, range)).records
        else emptyList()
        val list = weights.map { w ->
            val t = w.time.toEpochMilli()
            val fat = fats.minByOrNull { kotlin.math.abs(it.time.toEpochMilli() - t) }
                ?.takeIf { kotlin.math.abs(it.time.toEpochMilli() - t) < 10 * 60_000 }?.percentage?.value
            de.endurofit.data.WeightEntry("hc_" + w.metadata.id, t, w.weight.inKilograms, fatPct = fat, source = w.metadata.dataOrigin.packageName)
        }
        Repo.addWeights(list)
        return list.size
    }

    // ---------- Blutsauerstoff ----------

    private suspend fun importSpo2(ctx: Context, client: HealthConnectClient, range: TimeRangeFilter): Int {
        val list = ArrayList<Spo2Entry>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range, pageToken = token))
            resp.records.filter { it.metadata.dataOrigin.packageName != ctx.packageName }.forEach {
                list.add(Spo2Entry("hc_" + it.metadata.id, it.time.toEpochMilli(), it.percentage.value, it.metadata.dataOrigin.packageName))
            }
            token = resp.pageToken
        } while (token != null)
        if (list.isNotEmpty()) Repo.addSpo2(list)
        return list.size
    }

    // ---------- Herzfrequenz über den Tag ----------

    /**
     * Liest alle Pulswerte (nicht nur Trainings) und fasst sie je Tag zu 5-Minuten-Mittelwerten
     * zusammen. Ruhepuls: von der Uhr, wenn vorhanden – sonst die ruhigsten 30 Minuten des Tages.
     */
    private suspend fun importDayHr(ctx: Context, client: HealthConnectClient, start: Instant, end: Instant): Int {
        val range = TimeRangeFilter.between(start, end)
        // Quelle -> alle Werte
        val bySource = HashMap<String, ArrayList<HrSample>>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageToken = token))
            for (r in resp.records) {
                val pkg = r.metadata.dataOrigin.packageName
                if (pkg == ctx.packageName) continue
                val list = bySource.getOrPut(pkg) { ArrayList() }
                r.samples.forEach { list.add(HrSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt())) }
            }
            token = resp.pageToken
        } while (token != null)
        if (bySource.isEmpty()) return 0

        val resting = HashMap<Long, Int>()
        try {
            var t2: String? = null
            do {
                val resp = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range, pageToken = t2))
                // Polar schreibt hier nur den Ruhepuls aus den Profileinstellungen – kein Messwert, daher ignorieren
                resp.records.filter { val o = it.metadata.dataOrigin.packageName; o != ctx.packageName && !isPolar(o) }
                    .forEach { resting[Calc.dayStart(it.time.toEpochMilli())] = it.beatsPerMinute.toInt() }
                t2 = resp.pageToken
            } while (t2 != null)
        } catch (_: Exception) {
            // keine Berechtigung oder keine Daten – dann wird geschätzt
        }

        val days = bySource.values.flatten().map { Calc.dayStart(it.t) }.toSet()
        val out = ArrayList<DayHr>()
        for (day in days) {
            // Tage, die schon über die Polar-Schnittstelle (24/7-Puls) kamen, nicht überschreiben
            if (Repo.dayHr.value[day]?.source == de.endurofit.polar.PolarApi.SOURCE) continue
            val next = Calc.addDays(day, 1)
            // Pro Tag die Quelle mit den meisten Werten, Uhr bevorzugt
            val candidates = bySource.entries
                .map { it.key to it.value.filter { s -> s.t in day until next } }
                .filter { it.second.isNotEmpty() }
                .sortedWith(compareByDescending<Pair<String, List<HrSample>>> { isWatch(it.first) }.thenByDescending { it.second.size })
            val built = candidates.firstNotNullOfOrNull { (pkg, list) ->
                DayHrBuilder.build(day, list, resting[day], true, pkg)
            } ?: continue
            out.add(built)
        }
        Repo.replaceDayHr(out)
        return out.size
    }

    /** Sportart erkennen – Polar schreibt z. B. "Motorsport" nur in den Titel. */
    fun guessType(exerciseType: Int, title: String?): SessionType {
        val t = title.orEmpty().lowercase()
        return when {
            Regex("motor|enduro|moto|mx\\b|trial").containsMatchIn(t) -> SessionType.ENDURO
            Regex("kraft|strength|gym|fitness|hantel|weight|krafttraining").containsMatchIn(t) -> SessionType.GYM
            exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING ||
                exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> SessionType.GYM
            else -> SessionType.OTHER
        }
    }

    // ---------- Diagnose ----------

    data class SourceInfo(val label: String, val pkg: String, val count: Int, val last: Long)

    fun appLabel(ctx: Context, pkg: String): String = when {
        isSuunto(pkg) -> "Suunto"
        isPolar(pkg) -> "Polar"
        isGarmin(pkg) -> "Garmin"
        pkg == "com.sec.android.app.shealth" -> "Samsung Health"
        pkg == "com.google.android.apps.fitness" -> "Google Fit"
        pkg.startsWith("com.google.android.apps.healthdata") || pkg == "android" -> "Handy (Android)"
        else -> try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    /** Zeigt, welche App in den letzten 7 Tagen welche Daten an Health Connect geliefert hat. */
    suspend fun diagnose(ctx: Context): Map<String, List<SourceInfo>> {
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        val range = TimeRangeFilter.between(Instant.now().minus(7, ChronoUnit.DAYS), Instant.now())
        val out = LinkedHashMap<String, List<SourceInfo>>()

        suspend fun <T : androidx.health.connect.client.records.Record> check(
            name: String, type: kotlin.reflect.KClass<T>, time: (T) -> Instant,
        ) {
            if (HealthPermission.getReadPermission(type) !in granted) {
                out[name] = listOf(SourceInfo("keine Berechtigung", "", -1, 0)); return
            }
            try {
                val recs = client.readRecords(ReadRecordsRequest(type, range, pageSize = 1000)).records
                out[name] = recs.groupBy { it.metadata.dataOrigin.packageName }
                    .filterKeys { it != ctx.packageName }
                    .map { (pkg, l) -> SourceInfo(appLabel(ctx, pkg), pkg, l.size, l.maxOf { time(it).toEpochMilli() }) }
                    .sortedByDescending { it.count }
            } catch (e: Exception) {
                out[name] = listOf(SourceInfo("Fehler: ${e.message}", "", -1, 0))
            }
        }
        check("Trainings", ExerciseSessionRecord::class) { it.endTime }
        check("Schritte", StepsRecord::class) { it.endTime }
        check("Puls", HeartRateRecord::class) { it.endTime }
        check("Schlaf", SleepSessionRecord::class) { it.endTime }
        check("Blutsauerstoff", OxygenSaturationRecord::class) { it.time }
        return out
    }
}
