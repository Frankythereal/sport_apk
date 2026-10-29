package de.endurofit.polar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import de.endurofit.data.Calc
import de.endurofit.data.CardioLoad
import de.endurofit.data.DayHrBuilder
import de.endurofit.data.Fit
import de.endurofit.data.HrSample
import de.endurofit.data.Recovery
import de.endurofit.data.Repo
import de.endurofit.data.SessionType
import de.endurofit.data.SleepEntry
import de.endurofit.health.FitImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Polar AccessLink – Polars offizielle, kostenlose Schnittstelle.
 * Liefert, was Polar nicht an Health Connect gibt: Puls rund um die Uhr, Nightly Recharge
 * (HRV, Nacht-Puls, Atmung), Schlaf-Score, Cardio Load und Trainings als FIT-Datei mit GPS.
 *
 * Einrichtung: auf admin.polaraccesslink.com mit dem Polar-Konto einen "API client" anlegen,
 * Redirect-URL [REDIRECT] eintragen, Client-ID und Secret in der App eintragen.
 */
object PolarApi {
    private const val TAG = "PolarApi"
    const val SOURCE = "polar-api"
    const val PORT = 5000
    const val REDIRECT = "http://localhost:$PORT/oauth2_callback"
    private const val API = "https://www.polaraccesslink.com"
    private const val AUTH = "https://flow.polar.com/oauth2/authorization"
    private const val TOKEN = "https://polarremote.com/v2/oauth2/token"

    class PolarException(msg: String) : Exception(msg)

    val connected: Boolean get() = Repo.polarToken.isNotBlank()

    // ---------- Fehlersuche: letzte Antworten von Polar ----------

    private val debug = StringBuilder()
    private fun log(line: String) {
        Log.d(TAG, line)
        if (debug.length < 60_000) debug.append(line).append('\n')
    }

    fun debugFile(): File = File(Repo.cacheDirPath(), "polar_rohdaten.txt")

    // ---------- Anmeldung ----------

    /** Läuft unabhängig vom Bildschirm weiter, auch wenn du zwischendurch im Browser bist. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Statusmeldung für die Oberfläche. */
    val status = MutableStateFlow<String?>(null)
    val waiting = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val syncing = MutableStateFlow(false)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var loginJob: Job? = null

    fun refreshConnected() { isConnected.value = connected }

    /**
     * Öffnet die Polar-Anmeldung im Browser. Polar leitet danach auf localhost:5000 zurück,
     * wo die App kurz lauscht. Klappt das nicht ("Seite nicht erreichbar"), kann die Adresse
     * aus dem Browser in die App eingefügt werden – siehe [finishWithPasted].
     */
    fun startLogin(ctx: Context) {
        val id = Repo.polarClientId
        val secret = Repo.polarSecret
        if (id.isBlank() || secret.isBlank()) { status.value = "❌ Bitte zuerst Client-ID und Client-Secret eintragen."; return }
        loginJob?.cancel()
        closeServer()
        waiting.value = true
        status.value = "Browser öffnet sich – bei Polar anmelden und zustimmen, dann zurück zu EnduroFit."
        val appCtx = ctx.applicationContext
        loginJob = scope.launch {
            val srv = try {
                ServerSocket(PORT).apply { soTimeout = 10 * 60_000 }
            } catch (e: Exception) {
                null
            }
            server = srv
            val url = "$AUTH?response_type=code&client_id=${enc(id)}&redirect_uri=${enc(REDIRECT)}&scope=accesslink.read_all"
            withContext(Dispatchers.Main) {
                appCtx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (srv == null) {
                status.value = "Nach der Polar-Anmeldung die Adresse aus der Adresszeile des Browsers kopieren und unten einfügen."
                return@launch
            }
            val code = try { listen(srv) } catch (_: Exception) { null } finally { closeServer() }
            if (code != null) finish(code)
        }
    }

    /** Adresse (oder nur den Code) aus dem Browser einfügen, falls die Weiterleitung nicht ankam. */
    fun finishWithPasted(text: String) {
        val code = codeFrom(text)
        if (code == null) { status.value = "❌ In dem Text ist kein Anmeldecode. Kopiere die ganze Adresse, die mit http://localhost:5000/oauth2_callback?code=… beginnt."; return }
        loginJob?.cancel()
        closeServer()
        loginJob = scope.launch { finish(code) }
    }

    fun codeFrom(text: String): String? {
        val t = text.trim()
        Regex("""[?&]code=([^&\s#]+)""").find(t)?.let { return URLDecoder.decode(it.groupValues[1], "UTF-8") }
        // Nur der Code selbst (ohne Adresse)
        return t.takeIf { it.isNotEmpty() && !it.contains(' ') && !it.contains('/') && it.length >= 8 }
    }

    private fun closeServer() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    /** Wartet auf die Weiterleitung von Polar und gibt den Code zurück. */
    private fun listen(srv: ServerSocket): String? {
        while (true) {
            val sock = try { srv.accept() } catch (e: SocketTimeoutException) { return null }
            sock.use { s ->
                s.soTimeout = 10_000
                val line = s.getInputStream().bufferedReader().readLine() ?: ""
                val path = line.split(" ").getOrNull(1) ?: ""
                val isCallback = path.startsWith("/oauth2_callback")
                val code = if (isCallback) codeFrom(path) else null
                val html = when {
                    code != null -> "<h2>✅ Fast fertig!</h2><p>Du kannst jetzt zu EnduroFit zurückkehren.</p>"
                    isCallback -> "<h2>EnduroFit</h2><p>Polar hat die Anmeldung abgelehnt.</p>"
                    else -> "<h2>EnduroFit</h2>"
                }
                val bytes = ("<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'></head>" +
                    "<body style='font-family:sans-serif;padding:24px'>$html</body></html>").toByteArray(Charsets.UTF_8)
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                if (isCallback) return code
            }
        }
    }

    private suspend fun finish(code: String) {
        waiting.value = false
        status.value = "Verbinde mit Polar …"
        try {
            exchange(code)
            isConnected.value = true
            status.value = "✅ Verbunden – hole die letzten 28 Tage …"
            syncing.value = true
            val r = sync(null, 28)
            status.value = describe(r)
        } catch (e: Exception) {
            status.value = "❌ ${e.message}"
        } finally {
            syncing.value = false
            refreshConnected()
        }
    }

    fun describe(r: Result): String =
        "✅ Trainings: ${r.trainings} · 24/7-Puls: ${r.hrDays} Tage · Erholung: ${r.recoveries} · Schlaf: ${r.nights} · Belastung: ${r.cardio}" +
            if (r.errors.isNotEmpty()) "\n⚠️ " + r.errors.joinToString("\n⚠️ ") else ""

    /** Code gegen Zugangsschlüssel tauschen und Nutzer registrieren. */
    private fun exchange(code: String) {
        val id = Repo.polarClientId
        val secret = Repo.polarSecret
        val basic = Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)
        val (tc, tb) = http(
            "POST", TOKEN, "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}",
            "application/x-www-form-urlencoded", "Basic $basic"
        )
        log("TOKEN $tc ${String(tb).take(300).replace(Regex("\"access_token\"\\s*:\\s*\"[^\"]+\""), "\"access_token\":\"…\"")}")
        if (tc !in 200..299) {
            throw PolarException(
                if (tc == 400) "Der Anmeldecode ist abgelaufen oder wurde schon benutzt – bitte „Mit Polar verbinden“ nochmal tippen."
                else "Anmeldung fehlgeschlagen ($tc). Stimmen Client-ID, Secret und Redirect-URL?"
            )
        }
        val tj = JSONObject(String(tb))
        Repo.polarToken = tj.getString("access_token")
        Repo.polarUserId = tj.opt("x_user_id")?.toString() ?: ""

        // Nutzer bei der Schnittstelle registrieren (409 = schon registriert)
        val (rc, rb) = http("POST", "$API/v3/users", JSONObject().put("member-id", "endurofit-${Repo.polarUserId}").toString(), "application/json")
        log("REGISTER $rc ${String(rb).take(300)}")
        if (rc !in 200..299 && rc != 409) {
            Repo.polarToken = ""
            throw PolarException("Registrierung bei Polar fehlgeschlagen ($rc).")
        }
    }

    /** Synchronisieren im Hintergrund (für Knöpfe in der Oberfläche). */
    fun syncInBackground(ctx: Context, days: Int) {
        if (syncing.value) return
        val appCtx = ctx.applicationContext
        scope.launch {
            syncing.value = true
            status.value = "Hole Daten von Polar …"
            status.value = try { describe(sync(appCtx, days)) } catch (e: Exception) { "❌ ${e.message}" }
            syncing.value = false
            refreshConnected()
        }
    }

    fun logout() {
        Repo.polarToken = ""
        Repo.polarUserId = ""
        isConnected.value = false
        status.value = null
    }

    // ---------- Synchronisieren ----------

    data class Result(
        val trainings: Int, val hrDays: Int, val recoveries: Int, val nights: Int, val cardio: Int,
        val errors: List<String>,
    )

    suspend fun sync(ctx: Context?, days: Int = 7): Result = withContext(Dispatchers.IO) {
        if (!connected) throw PolarException("Nicht mit Polar verbunden.")
        val errors = ArrayList<String>()
        fun <T> step(name: String, block: () -> T): T? = try {
            block()
        } catch (e: PolarException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, name, e); errors.add("$name: ${e.message}"); null
        }

        val rec = step("Nightly Recharge") { syncRecharge() } ?: 0
        val nights = step("Schlaf") { syncSleep() } ?: 0
        val cardio = step("Cardio Load") { syncCardio() } ?: 0
        val hr = step("24/7-Puls") { syncContinuousHr(days) } ?: 0
        val ex = step("Trainings") { syncExercises() } ?: 0

        Repo.polarLastSync = System.currentTimeMillis()
        if (debug.length > 50_000) debug.setLength(0)
        try { debugFile().writeText(debug.toString()) } catch (_: Exception) {}
        Result(ex, hr, rec, nights, cardio, errors)
    }

    private fun syncRecharge(): Int {
        val (c, b) = get("/v3/users/nightly-recharge")
        if (c == 204 || c == 404) return 0
        val o = JSONObject(String(b))
        val arr = o.optJSONArray("recharges") ?: return 0
        val list = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val day = dayOf(r.getString("date"))
            Recovery(
                day = day,
                hrv = r.optDoubleOrNull("heart_rate_variability_avg"),
                nightHr = r.optDoubleOrNull("heart_rate_avg")?.toInt(),
                breathing = r.optDoubleOrNull("breathing_rate_avg"),
                ansCharge = r.optDoubleOrNull("ans_charge"),
                status = r.optDoubleOrNull("nightly_recharge_status")?.toInt(),
                sleepScore = Repo.recovery.value[day]?.sleepScore,
            )
        }
        Repo.putRecovery(list)
        return list.size
    }

    private fun syncSleep(): Int {
        val (c, b) = get("/v3/users/sleep")
        if (c == 204 || c == 404) return 0
        val arr = JSONObject(String(b)).optJSONArray("nights") ?: return 0
        val entries = ArrayList<SleepEntry>()
        val scores = ArrayList<Recovery>()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            val start = OffsetDateTime.parse(n.getString("sleep_start_time")).toInstant().toEpochMilli()
            val end = OffsetDateTime.parse(n.getString("sleep_end_time")).toInstant().toEpochMilli()
            val dateKey = n.optString("date").ifBlank { null }
            entries.add(
                SleepEntry(
                    id = "polar_" + (dateKey ?: end.toString()),
                    start = start, end = end, source = "fi.polar.polarflow",
                    deepMs = n.optLong("deep_sleep") * 1000,
                    lightMs = n.optLong("light_sleep") * 1000,
                    remMs = n.optLong("rem_sleep") * 1000,
                    awakeMs = n.optLong("total_interruption_duration") * 1000,
                )
            )
            n.optDoubleOrNull("sleep_score")?.let { sc ->
                val day = Calc.dayStart(end)
                val old = Repo.recovery.value[day]
                scores.add(old?.copy(sleepScore = sc.toInt()) ?: Recovery(day, null, null, null, null, null, sc.toInt()))
            }
        }
        // Polar-Schlaf ersetzt den aus Health Connect für dieselbe Nacht (hat Phasen und Score)
        val nightsDone = entries.map { Calc.dayStart(it.end) }.toSet()
        Repo.sleep.value.filter { it.id.startsWith("hc_") && Calc.dayStart(it.end) in nightsDone }.forEach { Repo.deleteSleep(it.id) }
        Repo.saveSleepBatch(entries)
        if (scores.isNotEmpty()) Repo.putRecovery(scores)
        return entries.size
    }

    private fun syncCardio(): Int {
        val (c, b) = get("/v3/users/cardio-load/")
        if (c == 204 || c == 404) return 0
        val txt = String(b).trim()
        val arr = if (txt.startsWith("[")) JSONArray(txt) else JSONObject(txt).let { it.optJSONArray("cardio_load") ?: it.optJSONArray("data") } ?: return 0
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val date = o.optString("date")
            if (date.isBlank()) return@mapNotNull null
            CardioLoad(
                day = dayOf(date),
                load = o.optDouble("cardio_load", 0.0),
                strain = o.optDouble("strain", 0.0),
                tolerance = o.optDouble("tolerance", 0.0),
                ratio = o.optDouble("cardio_load_ratio", 0.0),
                status = o.optString("cardio_load_status"),
            )
        }
        Repo.putCardio(list)
        return list.size
    }

    private fun syncContinuousHr(days: Int): Int {
        val today = LocalDate.now()
        val out = ArrayList<de.endurofit.data.DayHr>()
        for (i in 0 until days) {
            val date = today.minusDays(i.toLong())
            val (c, b) = get("/v3/users/continuous-heart-rate/$date")
            if (c == 204 || c == 404 || b.isEmpty()) continue
            val o = JSONObject(String(b))
            val arr = o.optJSONArray("heart_rate_samples") ?: continue
            val day = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val samples = (0 until arr.length()).mapNotNull { k ->
                val s = arr.getJSONObject(k)
                val time = s.optString("sample_time")
                if (time.isBlank()) return@mapNotNull null
                val t = date.atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                HrSample(t, s.optInt("heart_rate"))
            }
            val night = Repo.recovery.value[day]?.nightHr
            DayHrBuilder.build(day, samples, night, night != null, SOURCE, requireFullDay = false)?.let { out.add(it) }
        }
        if (out.isNotEmpty()) Repo.replaceDayHr(out)
        return out.size
    }

    /** Trainings der letzten 30 Tage als FIT-Datei laden (mit Puls und GPS). */
    private fun syncExercises(): Int {
        val (c, b) = get("/v3/exercises")
        if (c == 204 || c == 404) return 0
        val arr = JSONArray(String(b))
        // Schon geladene Trainings nicht jedes Mal neu herunterladen
        val doneFile = File(Repo.baseDirPath(), "polar_done.txt")
        val done = (if (doneFile.exists()) doneFile.readLines() else emptyList()).toMutableSet()
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val id = e.optString("id")
            if (id.isBlank() || id in done) continue
            val sport = (e.optString("detailed_sport_info") + " " + e.optString("sport")).lowercase()
            val type = when {
                Regex("motor|enduro|moto|trial").containsMatchIn(sport) -> SessionType.ENDURO
                Regex("strength|gym|core|fitness").containsMatchIn(sport) -> SessionType.GYM
                else -> null
            }
            val title = when (type) {
                SessionType.ENDURO -> "Enduro"
                SessionType.GYM -> "Krafttraining"
                else -> prettySport(e.optString("detailed_sport_info").ifBlank { e.optString("sport") })
            }
            val (fc, fb) = get("/v3/exercises/$id/fit", accept = "*/*", logBody = false)
            if (fc in 200..299 && Fit.isFit(fb)) {
                FitImport.save(Fit.parse(fb), type, title)
                done.add(id)
                n++
            } else {
                log("FIT $id nicht verfügbar ($fc)")
            }
        }
        doneFile.writeText(done.joinToString("\n"))
        return n
    }

    // ---------- Hilfsfunktionen ----------

    private fun prettySport(s: String): String =
        s.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Training" }

    private fun dayOf(date: String): Long =
        LocalDate.parse(date.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(path: String, accept: String = "application/json", logBody: Boolean = true): Pair<Int, ByteArray> {
        val r = http("GET", API + path, null, null, accept = accept)
        log("GET $path → ${r.first}" + if (logBody) "\n" + String(r.second).take(2500) else " (${r.second.size} Bytes)")
        if (r.first == 401 || r.first == 403) {
            throw PolarException("Polar hat den Zugriff abgelehnt (${r.first}). Bitte in EnduroFit neu mit Polar verbinden.")
        }
        return r
    }

    private fun http(
        method: String, url: String, body: String?, contentType: String?,
        auth: String? = null, accept: String = "application/json",
    ): Pair<Int, ByteArray> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", accept)
            setRequestProperty("Authorization", auth ?: "Bearer ${Repo.polarToken}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/json")
            }
        }
        return try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            code to bytes
        } catch (e: java.io.IOException) {
            throw PolarException("Keine Verbindung zu Polar: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
