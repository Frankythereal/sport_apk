package de.endurofit.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import de.endurofit.data.Calc
import de.endurofit.data.Fit
import de.endurofit.data.Repo
import de.endurofit.data.SOURCE_SUUNTO
import de.endurofit.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Importiert Trainings, die in der Suunto-App als FIT-Datei exportiert wurden –
 * entweder über "Teilen → EnduroFit" oder über die Dateiauswahl.
 */
object FitImport {

    /** Dateien, die per "Teilen" an die App geschickt wurden und noch importiert werden müssen. */
    val pending = MutableStateFlow<List<Uri>>(emptyList())

    data class Result(val sessions: List<Session>, val errors: List<String>)

    fun urisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
    }

    private fun fileName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Datei"
    } catch (_: Exception) {
        uri.lastPathSegment ?: "Datei"
    }

    suspend fun import(ctx: Context, uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        val ok = ArrayList<Session>()
        val errors = ArrayList<String>()
        for (uri in uris) {
            val name = fileName(ctx, uri)
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Fit.FitException("Datei lässt sich nicht öffnen.")
                if (!Fit.isFit(bytes)) {
                    throw Fit.FitException(
                        if (name.endsWith(".gpx", true)) "GPX enthält zu wenig Daten – bitte in der Suunto-App „FIT“ wählen."
                        else "Keine FIT-Datei."
                    )
                }
                ok.add(save(Fit.parse(bytes)))
            } catch (e: Exception) {
                errors.add("$name: ${e.message ?: "unbekannter Fehler"}")
            }
        }
        Result(ok, errors)
    }

    private fun save(a: Fit.Activity): Session {
        val profile = Repo.profile.value
        val id = "fit_" + (a.start / 1000)
        val existing = Repo.session(id)
        val kcal = a.kcal?.takeIf { it > 0 } ?: Calc.sessionKcal(a.hr, profile)
        val s = Session(
            id = id,
            // Was du selbst geändert hast (Art, Name, Notiz, Sätze), bleibt bei erneutem Import erhalten
            type = existing?.type ?: Fit.sessionType(a.sport, a.subSport),
            title = existing?.title ?: Fit.sportName(a.sport, a.subSport),
            start = a.start,
            end = a.end,
            source = SOURCE_SUUNTO,
            samples = a.hr,
            markers = existing?.markers ?: emptyList(),
            sets = existing?.sets ?: emptyList(),
            kcal = kcal,
            note = existing?.note ?: "",
            track = a.track,
            distanceM = a.distanceM,
            ascentM = a.ascentM,
        )
        // Dasselbe Training, das schon über Health Connect kam, wird ersetzt
        Repo.sessions.value
            .filter { it.id.startsWith("hc_") && Calc.overlaps(it, s) }
            .forEach { Repo.deleteSession(it.id) }
        Repo.saveSession(s)
        return s
    }
}
