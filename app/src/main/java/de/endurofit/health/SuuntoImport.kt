package de.endurofit.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import de.endurofit.data.Calc
import de.endurofit.data.DaySteps
import de.endurofit.data.HrSample
import de.endurofit.data.Repo
import de.endurofit.data.SOURCE_SUUNTO
import de.endurofit.data.Session
import de.endurofit.data.SessionType
import de.endurofit.data.SleepEntry
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Holt Daten der Suunto-App aus Health Connect: Trainings (mit Puls und Kalorien),
 * Schritte und Schlaf.
 * Voraussetzung: In der Suunto-App ist "In Health Connect speichern" aktiviert.
 */
object SuuntoImport {
    private const val TAG = "SuuntoImport"

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(ctx: Context): Boolean {
        if (!available(ctx)) return false
        val granted = HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    private fun isSuunto(pkg: String) = pkg.contains("suunto", true) || pkg.startsWith("com.stt.android")

    data class Result(
        val imported: Int,
        val updated: Int,
        val skippedOtherApps: Int,
        val stepDays: Int,
        val nights: Int,
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
        Repo.lastImport = System.currentTimeMillis()
        return Result(imported, updated, skipped, stepDays, nights)
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
            if (onlySuunto && !isSuunto(pkg)) { skipped++; continue }

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
            val guessedType = when (rec.exerciseType) {
                ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> SessionType.GYM
                else -> SessionType.OTHER
            }
            Repo.saveSession(
                Session(
                    id = id,
                    // Was du selbst geändert hast (Art, Name, Notiz), bleibt erhalten
                    type = existing?.type ?: guessedType,
                    title = existing?.title ?: (rec.title?.takeIf { it.isNotBlank() } ?: "Suunto-Aktivität"),
                    start = rec.startTime.toEpochMilli(),
                    end = rec.endTime.toEpochMilli(),
                    source = SOURCE_SUUNTO,
                    samples = samples,
                    markers = existing?.markers ?: emptyList(),
                    sets = existing?.sets ?: emptyList(),
                    kcal = kcal,
                    note = existing?.note ?: "",
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
     * Liefern mehrere Apps Schritte (z. B. Handy und Uhr), wird pro Tag nur eine Quelle genommen.
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
            if (onlySuunto && !isSuunto(pkg)) continue
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
                compareByDescending<Map.Entry<String, DoubleArray>> { isSuunto(it.key) }.thenByDescending { it.value[0] }
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
                compareByDescending<SleepSessionRecord> { isSuunto(it.metadata.dataOrigin.packageName) }
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
}
