package de.endurofit.data

/**
 * Liest Kalorien und Nährwerte aus beliebigem Text, z. B. einer ChatGPT-Antwort,
 * einer Nährwerttabelle oder einer Notiz – ganz ohne KI.
 */
object FoodText {

    data class Parsed(
        val name: String,
        val kcal: Double?,
        val protein: Double?,
        val carbs: Double?,
        val fat: Double?,
    ) {
        val found: Boolean get() = kcal != null || protein != null || carbs != null || fat != null
    }

    /** Text zum Kopieren für ChatGPT, damit die Antwort sicher erkannt wird. */
    const val CHATGPT_PROMPT = """Schätze bitte die Nährwerte für mein Essen (Beschreibung oder Foto folgt). Rechne versteckte Kalorien wie Öl, Butter und Soßen mit ein. Beende deine Antwort immer mit genau diesem Block:

GESAMT
Name: <kurzer Name des Gerichts>
kcal: <Zahl>
Eiweiß: <Zahl> g
Kohlenhydrate: <Zahl> g
Fett: <Zahl> g

Mein Essen:"""

    private val NUM = """\d+(?:[.,]\d+)*"""
    private val RANGE = """($NUM)(?:[ \t]*(?:-|–|—|bis)[ \t]*($NUM))?"""
    private val APPROX = """(?:ca\.?|circa|etwa|ungefähr|rund|~|≈|±)?[ \t]*"""

    private val totalLine = Regex("""(gesamt|summe|total|insgesamt|zusammen)""", RegexOption.IGNORE_CASE)

    private fun num(s: String): Double? {
        val t = s.trim()
        // deutsche Tausenderpunkte: 1.200
        if (Regex("""\d{1,3}(\.\d{3})+""").matches(t)) return t.replace(".", "").toDoubleOrNull()
        return t.replace(',', '.').toDoubleOrNull()
    }

    private fun rangeValue(m: MatchResult, g1: Int, g2: Int): Double? {
        val a = num(m.groupValues[g1]) ?: return null
        val b = m.groupValues.getOrNull(g2)?.takeIf { it.isNotEmpty() }?.let { num(it) }
        return if (b != null && b > a) (a + b) / 2 else a
    }

    private data class Hit(val pos: Int, val value: Double)

    /** Alle Treffer für einen Nährwert: "35 g Eiweiß" oder "Eiweiß: 35 g". */
    private fun hits(text: String, words: String, unitBefore: String, exclude: Regex? = null): List<Hit> {
        val out = ArrayList<Hit>()
        // Zahl vor dem Wort: "35 g Eiweiß", "650 kcal"
        Regex("""$APPROX$RANGE[ \t]*$unitBefore[ \t]*(?:$words)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            if (exclude != null && exclude.containsMatchIn(text.substring(m.range.first, minOf(text.length, m.range.last + 12)))) return@forEach
            rangeValue(m, 1, 2)?.let { out.add(Hit(m.range.first, it)) }
        }
        // Wort vor der Zahl: "Eiweiß: 35 g", "Kalorien: ca. 650"
        Regex("""(?:$words)\w*[ \t]*(?:\([^)\n]*\))?[ \t]*[:=\-–]?[ \t]*$APPROX$RANGE""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            if (exclude != null && exclude.containsMatchIn(m.value)) return@forEach
            // "Energie: 2720 kJ" nicht als kcal werten
            val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 5)).trim().lowercase()
            if (after.startsWith("kj")) return@forEach
            rangeValue(m, 1, 2)?.let { v -> if (out.none { kotlin.math.abs(it.pos - m.range.first) < 30 }) out.add(Hit(m.range.first, v)) }
        }
        return out.sortedBy { it.pos }
    }

    private fun pick(text: String, list: List<Hit>): Double? {
        if (list.isEmpty()) return null
        // Gibt es einen Gesamt-Block, gilt der erste Wert danach
        val totals = totalLine.findAll(text).map { it.range.first }.toList()
        for (t in totals.reversed()) {
            list.firstOrNull { it.pos >= t }?.let { return it.value }
        }
        if (list.size == 1) return list.first().value
        // Mehrere Einzelposten ohne Gesamtzeile: zusammenzählen
        return list.sumOf { it.value }
    }

    fun parse(raw: String): Parsed {
        val text = raw.replace("**", "").replace("*", "").replace(" ", " ")

        val kcal = pick(text, hits(text, "kcal|kalorien|kilokalorien|cal\\b|calories", "", Regex("kj", RegexOption.IGNORE_CASE)))
        val protein = pick(text, hits(text, "eiweiß|eiweiss|protein", "g?"))
        val carbs = pick(text, hits(text, "kohlenhydrate|kohlenhydrat|kh\\b|carbs|carbohydrates", "g?"))
        val fat = pick(text, hits(text, "fett(?!säure|sauer)|fat\\b", "g?", Regex("fettsäure|gesättigt", RegexOption.IGNORE_CASE)))

        val name = Regex("""(?:name|gericht|mahlzeit|essen)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
            .findAll(text).lastOrNull()?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("<") }
            ?: text.lines().map { it.trim().trimStart('#', '-', '•', ' ').trim() }
                .firstOrNull { it.isNotEmpty() && it.count { c -> c.isDigit() } < 3 && it.length in 3..60 && !totalLine.containsMatchIn(it) && !it.endsWith(":") }
            ?: "Mahlzeit"

        return Parsed(name.take(60), kcal, protein, carbs, fat)
    }
}
