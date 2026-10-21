package de.endurofit.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import de.endurofit.data.Calc
import de.endurofit.data.HrSample
import de.endurofit.data.Repo
import de.endurofit.data.SOURCE_SUUNTO
import de.endurofit.data.Session
import de.endurofit.data.SessionType
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Holt Aktivitäten der Suunto-App aus Health Connect.
 * Voraussetzung: In der Suunto-App ist "In Health Connect speichern" aktiviert.
 */
object SuuntoImport {

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(ctx: Context): Boolean {
        if (!available(ctx)) return false
        val granted = HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    private fun isSuunto(pkg: String) = pkg.contains("suunto", true) || pkg.startsWith("com.stt.android")

    data class Result(val imported: Int, val updated: Int, val skippedOtherApps: Int)

    suspend fun import(ctx: Context, days: Int = 30): Result {
        val client = HealthConnectClient.getOrCreate(ctx)
        val end = Instant.now()
        val start = end.minus(days.toLong(), ChronoUnit.DAYS)
        val onlySuunto = Repo.onlySuunto
        val profile = Repo.profile.value

        val records = ArrayList<ExerciseSessionRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = token,
                )
            )
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

            val range = TimeRangeFilter.between(rec.startTime, rec.endTime)
            val origin = setOf(DataOrigin(pkg))

            val hr = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, range, dataOriginFilter = origin)
            ).records
            val samples = hr.flatMap { r -> r.samples.map { HrSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) } }
                .sortedBy { it.t }
                .distinctBy { it.t }

            var kcal = client.readRecords(
                ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range, dataOriginFilter = origin)
            ).records.sumOf { it.energy.inKilocalories }
            if (kcal <= 0.0) {
                kcal = client.readRecords(
                    ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range, dataOriginFilter = origin)
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
            val session = Session(
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
            Repo.saveSession(session)
            if (existing == null) imported++ else updated++
        }
        Repo.lastImport = System.currentTimeMillis()
        return Result(imported, updated, skipped)
    }
}
