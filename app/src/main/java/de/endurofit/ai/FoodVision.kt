package de.endurofit.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import de.endurofit.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Schätzt Kalorien und Nährwerte aus einem Essensfoto mit der Claude-API.
 * Funktioniert auch mit Fotos von Nährwerttabellen auf Verpackungen.
 */
object FoodVision {

    /** Foto, das aus einer anderen App (z. B. Galerie) an EnduroFit geteilt wurde. */
    val sharedImage = MutableStateFlow<Uri?>(null)

    val MODELS = listOf(
        "claude-sonnet-5-5" to "Genauer (Sonnet) · ca. 1 Cent pro Foto",
        "claude-haiku-5-5" to "Günstiger (Haiku) · ca. 0,1 Cent pro Foto",
    )

    data class Item(val name: String, val grams: Double, val kcal: Double, val protein: Double, val carbs: Double, val fat: Double)

    data class Result(
        val isFood: Boolean,
        val kind: String,
        val name: String,
        val items: List<Item>,
        val kcal: Double,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val liquidMl: Int,
        val confidence: String,
        val note: String,
    )

    class VisionException(msg: String) : Exception(msg)

    private const val SYSTEM = """Du bist Ernährungsberater und schätzt Nährwerte aus Fotos für eine Fitness-App eines Hard-Enduro-Fahrers.
Regeln:
- Erkenne alle Lebensmittel und Getränke auf dem Foto und schätze die Portionsgröße in Gramm realistisch anhand von Teller, Besteck, Händen und Verpackungen.
- Rechne mit üblichen Nährwerten (z. B. Bundeslebensmittelschlüssel / Packungsangaben). Denk an versteckte Kalorien wie Öl, Butter, Soßen und Dressing.
- Ist eine Nährwerttabelle oder Verpackung zu sehen, lies die Werte ab und rechne auf die abgebildete oder angegebene Menge um.
- Gib bei Getränken die Flüssigkeitsmenge in ml an (Wasser, Tee, Kaffee, Saft, Softdrinks, Milch …), sonst 0.
- Schreibe alle Namen auf Deutsch, kurz und verständlich.
- Ist kein Essen zu sehen, setze ist_essen auf false und alle Werte auf 0.
- Antworte ausschließlich über das Werkzeug mahlzeit_erfassen."""

    private fun schema(): JSONObject {
        fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
        val item = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("name", JSONObject().put("type", "string"))
                .put("menge_g", num("geschätzte Menge in Gramm (bei Getränken ml)"))
                .put("kcal", num("Kilokalorien"))
                .put("eiweiss_g", num("Eiweiß in g"))
                .put("kohlenhydrate_g", num("Kohlenhydrate in g"))
                .put("fett_g", num("Fett in g")))
            .put("required", JSONArray(listOf("name", "menge_g", "kcal", "eiweiss_g", "kohlenhydrate_g", "fett_g")))
        return JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("ist_essen", JSONObject().put("type", "boolean"))
                .put("art", JSONObject().put("type", "string").put("enum", JSONArray(listOf("mahlzeit", "snack", "getraenk", "naehrwerttabelle", "kein_essen"))))
                .put("name", JSONObject().put("type", "string").put("description", "kurzer Name der Mahlzeit, z. B. „Spaghetti Bolognese mit Parmesan“"))
                .put("zutaten", JSONObject().put("type", "array").put("items", item))
                .put("kcal", num("Summe Kilokalorien"))
                .put("eiweiss_g", num("Summe Eiweiß in g"))
                .put("kohlenhydrate_g", num("Summe Kohlenhydrate in g"))
                .put("fett_g", num("Summe Fett in g"))
                .put("fluessigkeit_ml", num("Trinkmenge in ml, 0 wenn kein Getränk"))
                .put("sicherheit", JSONObject().put("type", "string").put("enum", JSONArray(listOf("hoch", "mittel", "niedrig"))))
                .put("hinweis", JSONObject().put("type", "string").put("description", "kurzer Hinweis, z. B. was schwer zu schätzen war")))
            .put("required", JSONArray(listOf("ist_essen", "art", "name", "zutaten", "kcal", "eiweiss_g", "kohlenhydrate_g", "fett_g", "fluessigkeit_ml", "sicherheit")))
    }

    /** Lädt das Bild, dreht es richtig (EXIF) und verkleinert es auf max. 1280 px. */
    fun loadJpegBase64(ctx: Context, uri: Uri): String {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = 1280.0 / max(w, h)
            if (scale < 1) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun analyze(ctx: Context, uri: Uri, hint: String): Result = withContext(Dispatchers.IO) {
        val key = Repo.claudeKey
        if (key.isBlank()) throw VisionException("Bitte zuerst im Profil deinen Claude-API-Schlüssel eintragen.")
        val image = try {
            loadJpegBase64(ctx, uri)
        } catch (e: Exception) {
            throw VisionException("Das Foto lässt sich nicht öffnen.")
        }

        val text = buildString {
            append("Schätze die Nährwerte für das, was auf dem Foto zu sehen ist.")
            if (hint.isNotBlank()) append(" Zusatzinfo von mir: ").append(hint.trim())
        }
        val body = JSONObject()
            .put("model", Repo.claudeModel)
            .put("max_tokens", 1500)
            .put("system", SYSTEM)
            .put("tools", JSONArray().put(JSONObject()
                .put("name", "mahlzeit_erfassen")
                .put("description", "Erfasst die geschätzten Nährwerte der Mahlzeit auf dem Foto.")
                .put("input_schema", schema())))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", "mahlzeit_erfassen"))
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg").put("data", image)))
                    .put(JSONObject().put("type", "text").put("text", text)))))

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("content-type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(resp).getJSONObject("error").optString("message") } catch (_: Exception) { "" }
                throw VisionException(
                    when {
                        code == 401 -> "Der API-Schlüssel ist ungültig. Bitte im Profil prüfen."
                        msg.contains("credit", true) -> "Dein Claude-API-Guthaben ist aufgebraucht. Unter console.anthropic.com aufladen."
                        code == 429 -> "Zu viele Anfragen – bitte kurz warten."
                        code == 529 || code >= 500 -> "Der Dienst ist gerade überlastet – bitte gleich nochmal versuchen."
                        else -> "Fehler $code: $msg"
                    }
                )
            }
            parse(JSONObject(resp))
        } catch (e: VisionException) {
            throw e
        } catch (e: java.io.IOException) {
            throw VisionException("Keine Verbindung zum Internet.")
        } finally {
            conn.disconnect()
        }
    }

    /** Kurzer Test, ob Schlüssel und Modell funktionieren. Gibt null zurück, wenn alles passt, sonst die Fehlermeldung. */
    suspend fun test(): String? = withContext(Dispatchers.IO) {
        val key = Repo.claudeKey
        if (key.isBlank()) return@withContext "Kein Schlüssel eingetragen."
        val body = JSONObject()
            .put("model", Repo.claudeModel)
            .put("max_tokens", 5)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Antworte nur mit OK.")))
        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("content-type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) null else {
                val resp = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val msg = try { JSONObject(resp).getJSONObject("error").optString("message") } catch (_: Exception) { "" }
                when {
                    code == 401 -> "Schlüssel ungültig."
                    msg.contains("credit", true) -> "Kein Guthaben – unter console.anthropic.com aufladen."
                    else -> "Fehler $code: $msg"
                }
            }
        } catch (e: java.io.IOException) {
            "Keine Verbindung zum Internet."
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(resp: JSONObject): Result {
        val content = resp.optJSONArray("content") ?: throw VisionException("Leere Antwort.")
        var input: JSONObject? = null
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "tool_use") input = c.optJSONObject("input")
        }
        val o = input ?: throw VisionException("Die Antwort war unvollständig – bitte nochmal versuchen.")
        val arr = o.optJSONArray("zutaten") ?: JSONArray()
        val items = (0 until arr.length()).map {
            val z = arr.getJSONObject(it)
            Item(
                z.optString("name"), z.optDouble("menge_g", 0.0), z.optDouble("kcal", 0.0),
                z.optDouble("eiweiss_g", 0.0), z.optDouble("kohlenhydrate_g", 0.0), z.optDouble("fett_g", 0.0),
            )
        }
        fun total(key: String, f: (Item) -> Double) = o.optDouble(key, Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: items.sumOf(f)
        return Result(
            isFood = o.optBoolean("ist_essen", true),
            kind = o.optString("art", "mahlzeit"),
            name = o.optString("name").ifBlank { items.joinToString(", ") { it.name } },
            items = items,
            kcal = total("kcal") { it.kcal },
            protein = total("eiweiss_g") { it.protein },
            carbs = total("kohlenhydrate_g") { it.carbs },
            fat = total("fett_g") { it.fat },
            liquidMl = o.optDouble("fluessigkeit_ml", 0.0).toInt().coerceAtLeast(0),
            confidence = o.optString("sicherheit", "mittel"),
            note = o.optString("hinweis", ""),
        )
    }
}
