package de.endurofit.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// ---------- Nährstoffe ----------

/**
 * [nrv] = Referenzmenge pro Tag (EU-NRV), [limit] = Obergrenze pro Tag, ab der gewarnt wird
 * (EFSA-Höchstmenge bzw. BfR-Empfehlung für Nahrungsergänzung), null = keine.
 */
enum class Nutrient(val label: String, val unit: String, val nrv: Double, val limit: Double?, val limitNote: String = "") {
    VIT_A("Vitamin A", "µg", 800.0, 3000.0),
    VIT_D("Vitamin D", "µg", 5.0, 100.0, "EFSA-Höchstmenge 100 µg"),
    VIT_E("Vitamin E", "mg", 12.0, 300.0),
    VIT_K("Vitamin K2", "µg", 75.0, null),
    VIT_C("Vitamin C", "mg", 80.0, null),
    B1("Vitamin B1", "mg", 1.1, null),
    B2("Vitamin B2", "mg", 1.4, null),
    B3("Niacin (B3)", "mg", 16.0, 900.0),
    B5("Pantothensäure (B5)", "mg", 6.0, null),
    B6("Vitamin B6", "mg", 1.4, 12.0, "EFSA-Höchstmenge 12 mg"),
    FOLATE("Folsäure", "µg", 200.0, 1000.0),
    B12("Vitamin B12", "µg", 2.5, null),
    BIOTIN("Biotin", "µg", 50.0, null),
    IRON("Eisen", "mg", 14.0, 6.0, "BfR: höchstens 6 mg/Tag aus Nahrungsergänzung"),
    MAGNESIUM("Magnesium", "mg", 375.0, 250.0, "BfR: höchstens 250 mg/Tag aus Nahrungsergänzung"),
    ZINC("Zink", "mg", 10.0, 25.0),
    Q10("Coenzym Q10", "mg", 0.0, null);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name }
    }
}

// ---------- Präparate ----------

enum class SuppSlot(val label: String, val emoji: String, val hour: Int, val minute: Int) {
    MORNING("Morgens", "🌅", 8, 0),
    MIDDAY("Mittags", "☀️", 12, 30),
    EVENING("Abends", "🌙", 21, 0);

    companion object {
        fun of(n: String?) = entries.firstOrNull { it.name == n } ?: MORNING
    }
}

/** Wofür ein Präparat steht – danach richten sich die Tipps. */
enum class SuppKind { MULTI, D3K2, MAGNESIUM, IRON, OTHER;
    companion object { fun of(n: String?) = entries.firstOrNull { it.name == n } ?: OTHER }
}

data class Supplement(
    val id: String,
    val name: String,
    val brand: String,
    val unit: String,               // „Tablette“, „Kapsel“
    val perUnit: Map<Nutrient, Double>,
    /** Einheiten pro Tag; 0.5 = alle 2 Tage eine */
    val perDay: Double,
    val slot: SuppSlot,
    val kind: SuppKind,
    val active: Boolean = true,
    val stock: Int = 0,
    val packSize: Int = 60,
    val howTo: String = "",
    val source: String = "",
    /** Nur nach ärztlichem Rat (z. B. Eisen bei Männern) – sonst schlägt die App es nicht vor */
    val onlyIfAdvised: Boolean = false,
    val advised: Boolean = false,
) {
    val everyDays: Int get() = if (perDay > 0 && perDay < 1) Math.round(1 / perDay).toInt() else 1
    val unitsPerDose: Int get() = if (perDay >= 1) perDay.toInt() else 1
    /** Vorrat reicht noch … Tage */
    val daysLeft: Int get() = if (perDay <= 0) 999 else (stock / perDay).toInt()
    val emoji: String get() = when (kind) {
        SuppKind.MULTI -> "🌿"; SuppKind.D3K2 -> "☀️"; SuppKind.MAGNESIUM -> "⚡"; SuppKind.IRON -> "🩸"; SuppKind.OTHER -> "💊"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("brand", brand); put("unit", unit)
        put("per", JSONObject().apply { perUnit.forEach { (k, v) -> put(k.name, v) } })
        put("perDay", perDay); put("slot", slot.name); put("kind", kind.name); put("active", active)
        put("stock", stock); put("pack", packSize); put("how", howTo); put("src", source)
        put("onlyAdv", onlyIfAdvised); put("adv", advised)
    }

    companion object {
        fun fromJson(o: JSONObject): Supplement {
            val per = HashMap<Nutrient, Double>()
            o.optJSONObject("per")?.let { p -> p.keys().forEach { k -> Nutrient.of(k)?.let { per[it] = p.optDouble(k) } } }
            return Supplement(
                o.getString("id"), o.optString("name"), o.optString("brand"), o.optString("unit", "Tablette"), per,
                o.optDouble("perDay", 1.0), SuppSlot.of(o.optString("slot")), SuppKind.of(o.optString("kind")),
                o.optBoolean("active", true), o.optInt("stock", 0), o.optInt("pack", 60), o.optString("how"),
                o.optString("src"), o.optBoolean("onlyAdv", false), o.optBoolean("adv", false),
            )
        }
    }
}

data class SuppIntake(val id: String, val t: Long, val suppId: String, val units: Int = 1) {
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("t", t); put("s", suppId); put("n", units) }
    companion object {
        fun fromJson(o: JSONObject) = SuppIntake(o.getString("id"), o.getLong("t"), o.getString("s"), o.optInt("n", 1))
    }
}

/** Deine Präparate vom Foto – Werte von den Herstellerseiten (Stand Oktober 2026). */
object SuppPresets {
    val all = listOf(
        Supplement(
            "zellplus", "ZellPlus Multivitamin", "naturalie", "Kapsel",
            mapOf(
                Nutrient.VIT_A to 400.0, Nutrient.VIT_D to 25.0, Nutrient.VIT_E to 12.0, Nutrient.VIT_K to 75.0,
                Nutrient.VIT_C to 85.0, Nutrient.B1 to 5.0, Nutrient.B2 to 10.0, Nutrient.B3 to 12.0, Nutrient.B6 to 8.0,
                Nutrient.FOLATE to 300.0, Nutrient.B12 to 120.0, Nutrient.BIOTIN to 300.0, Nutrient.B5 to 16.0, Nutrient.Q10 to 10.0,
            ),
            perDay = 1.0, slot = SuppSlot.MORNING, kind = SuppKind.MULTI, stock = 60, packSize = 60,
            howTo = "1 Kapsel morgens oder mittags zum Essen. 1–2 Std. Abstand zu anderen Präparaten. " +
                "Enthält außerdem Ashwagandha, Grüntee-, Ginseng-, Granatapfel-, Olivenblatt-Extrakt, Maca, Rhodiola, Resveratrol, Curcuma, Quercetin – keine Mineralstoffe.",
            source = "naturalie.de",
        ),
        Supplement(
            "d3k2", "Vitamin D3 2.000 IE + K2", "vitamaze", "Tablette",
            mapOf(Nutrient.VIT_D to 50.0, Nutrient.VIT_K to 100.0),
            perDay = 0.5, slot = SuppSlot.MORNING, kind = SuppKind.D3K2, stock = 240, packSize = 240,
            howTo = "Alle 2 Tage 1 Tablette zu einer Mahlzeit mit etwas Fett (D3 ist fettlöslich). Bei Blutverdünnern vorher Arzt fragen (K2).",
            source = "vitamaze / aponeo.de",
        ),
        Supplement(
            "magnesium", "Magnesium Komplex 7 Ultra", "Sunday Natural", "Kapsel",
            mapOf(Nutrient.MAGNESIUM to 200.0),
            perDay = 1.0, slot = SuppSlot.EVENING, kind = SuppKind.MAGNESIUM, stock = 240, packSize = 240,
            howTo = "200 mg je Kapsel aus 7 Formen (Oxid, Citrat, Malat, Lactat, Bisglycinat, L-Lysinat, L-Ascorbat). " +
                "Hersteller sagt 2 am Tag, das BfR rät zu höchstens 250 mg aus Nahrungsergänzung – 1 Kapsel abends reicht meist, nach harten Einheiten passt sie besonders.",
            source = "sunday.de",
        ),
        Supplement(
            "eisen", "Eisen + Vitamin C", "natural elements", "Tablette",
            mapOf(Nutrient.IRON to 20.0, Nutrient.VIT_C to 40.0),
            perDay = 1.0, slot = SuppSlot.MIDDAY, kind = SuppKind.IRON, stock = 240, packSize = 240,
            howTo = "20 mg Eisen (Bisglycinat) + 40 mg Vitamin C aus Acerola. Mit Wasser, mind. 2 Std. Abstand zu Magnesium, Kaffee, Tee und Milch. " +
                "Männer brauchen meist kein Eisen extra – zu viel Eisen schadet. Nur nehmen, wenn ein Bluttest (Ferritin) es zeigt.",
            source = "natural elements / shop-apotheke",
            onlyIfAdvised = true,
        ),
    )
}

// ---------- Auswertung & Tipps ----------

object Supps {
    data class Due(val supp: Supplement, val reason: String, val priority: Int)

    fun takenOn(intakes: List<SuppIntake>, suppId: String, day: Long): Int {
        val end = Calc.addDays(day, 1)
        return intakes.filter { it.suppId == suppId && it.t in day until end }.sumOf { it.units }
    }

    fun lastTaken(intakes: List<SuppIntake>, suppId: String): Long? = intakes.filter { it.suppId == suppId }.maxOfOrNull { it.t }

    /** Wird das Präparat heute vorgeschlagen? */
    fun relevant(s: Supplement) = s.active && (!s.onlyIfAdvised || s.advised)

    /** Ist es heute (noch) dran? */
    fun isDue(s: Supplement, intakes: List<SuppIntake>, day: Long): Boolean {
        if (!relevant(s)) return false
        if (takenOn(intakes, s.id, day) >= s.unitsPerDose) return false
        if (s.everyDays > 1) {
            val last = lastTaken(intakes, s.id) ?: return true
            val lastDay = Calc.dayStart(last)
            return lastDay <= Calc.addDays(day, -s.everyDays)
        }
        return true
    }

    /** Tipps für heute – mit Begründung, nach Wichtigkeit sortiert. */
    fun dueToday(supps: List<Supplement>, intakes: List<SuppIntake>, trainedToday: Boolean, sick: Boolean, now: Long = System.currentTimeMillis()): List<Due> {
        val day = Calc.dayStart(now)
        val month = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MONTH) + 1
        val darkSeason = month >= 10 || month <= 4
        val lastMg = supps.filter { it.kind == SuppKind.MAGNESIUM }.mapNotNull { lastTaken(intakes, it.id) }.maxOrNull()
        return supps.filter { isDue(it, intakes, day) }.map { s ->
            when (s.kind) {
                SuppKind.MULTI -> Due(s, if (sick) "Bei Infekt besonders sinnvoll – B-Vitamine, C und D fürs Immunsystem." else "Deine Basis für Vitamine – am besten zum Frühstück.", 2)
                SuppKind.D3K2 -> Due(
                    s,
                    if (darkSeason) "Oktober bis April macht die Haut kaum Vitamin D – heute ist wieder deine D3-Tablette dran."
                    else "Im Sommer optional – wenn du viel draußen bist, reicht die Sonne oft.",
                    if (darkSeason) 3 else 0,
                )
                SuppKind.MAGNESIUM -> Due(
                    s,
                    if (trainedToday) "Heute trainiert – Magnesium gleicht verschwitzte Mineralien aus und hilft gegen Krämpfe."
                    else "Abends 1 Kapsel – gut für Muskeln und Schlaf.",
                    if (trainedToday) 3 else 1,
                )
                SuppKind.IRON -> {
                    val gap = lastMg != null && now - lastMg < 2 * 3_600_000L
                    Due(s, if (gap) "Noch etwas warten: unter 2 Std. nach Magnesium wird Eisen schlecht aufgenommen." else "Laut Arzt/Bluttest – mit Wasser, ohne Kaffee oder Milch.", 2)
                }
                SuppKind.OTHER -> Due(s, s.howTo.ifBlank { "Laut deinem Plan heute dran." }, 1)
            }
        }.sortedByDescending { it.priority }
    }

    /** Nährstoffe eines Tages aus allen Einnahmen. */
    fun nutrientsOn(supps: List<Supplement>, intakes: List<SuppIntake>, day: Long): Map<Nutrient, Double> {
        val end = Calc.addDays(day, 1)
        val byId = supps.associateBy { it.id }
        val out = HashMap<Nutrient, Double>()
        intakes.filter { it.t in day until end }.forEach { i ->
            byId[i.suppId]?.perUnit?.forEach { (n, v) -> out[n] = (out[n] ?: 0.0) + v * i.units }
        }
        return out
    }

    /** Durchschnitt pro Tag über [days] Tage – zeigt deinen „Vitamin-Haushalt“. */
    fun averageNutrients(supps: List<Supplement>, intakes: List<SuppIntake>, days: Int, now: Long = System.currentTimeMillis()): Map<Nutrient, Double> {
        val today = Calc.dayStart(now)
        val sum = HashMap<Nutrient, Double>()
        for (d in 0 until days) {
            nutrientsOn(supps, intakes, Calc.addDays(today, -d)).forEach { (n, v) -> sum[n] = (sum[n] ?: 0.0) + v }
        }
        return sum.mapValues { it.value / days }
    }

    fun fmt(n: Nutrient, v: Double): String =
        Calc.fmtNum(v, if (v < 10) 1 else 0) + " " + n.unit

    // ---------- JSON-Helfer ----------
    fun listToJson(list: List<Supplement>) = JSONArray().apply { list.forEach { put(it.toJson()) } }
}
