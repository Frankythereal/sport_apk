package de.endurofit.data

/**
 * Liest FIT-Dateien (das Format, das die Suunto-App beim Export eines Trainings erzeugt).
 * Bewusst ohne Fremdbibliothek: es werden nur die Werte gelesen, die die App braucht.
 */
object Fit {

    /** Sekunden zwischen 1970-01-01 und dem FIT-Nullpunkt 1989-12-31 (UTC). */
    private const val FIT_EPOCH_OFFSET = 631_065_600L

    data class Point(val t: Long, val lat: Double, val lon: Double, val alt: Double?)

    data class Activity(
        val start: Long,
        val end: Long,
        val sport: Int,
        val subSport: Int,
        val kcal: Double?,
        val distanceM: Double?,
        val ascentM: Double?,
        val hr: List<HrSample>,
        val track: List<Point>,
    )

    class FitException(msg: String) : Exception(msg)

    private class FieldDef(val num: Int, val size: Int, val baseType: Int)

    private class Def(val global: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val devBytes: Int) {
        val totalSize = fields.sumOf { it.size } + devBytes
    }

    private class SessionAcc {
        var start: Long? = null
        var elapsedS: Double? = null
        var kcal: Double? = null
        var sport: Int? = null
        var subSport: Int? = null
        var distance: Double? = null
        var ascent: Double? = null
    }

    fun isFit(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()

    fun parse(bytes: ByteArray): Activity {
        if (!isFit(bytes)) throw FitException("Das ist keine FIT-Datei.")

        val records = ArrayList<LongArray>() // [t, hr, latRaw, lonRaw, altCm]
        val sessions = ArrayList<SessionAcc>()
        var fileCreated: Long? = null
        var firstTs: Long? = null
        var lastTs = 0L

        var pos = 0
        while (pos + 12 <= bytes.size && isFitAt(bytes, pos)) {
            val headerSize = bytes[pos].toInt() and 0xFF
            val dataSize = u32le(bytes, pos + 4)
            var p = pos + headerSize
            val end = minOf(bytes.size, (pos + headerSize + dataSize).toInt())
            val defs = HashMap<Int, Def>()

            while (p < end) {
                val h = bytes[p].toInt() and 0xFF
                p++
                if (h and 0x80 != 0) {
                    // Datensatz mit komprimiertem Zeitstempel
                    val local = (h shr 5) and 0x03
                    val offset = (h and 0x1F).toLong()
                    lastTs = if (offset >= (lastTs and 0x1F)) (lastTs and 0x1F.toLong().inv()) + offset
                    else (lastTs and 0x1F.toLong().inv()) + offset + 0x20
                    val d = defs[local] ?: throw FitException("Beschädigte Datei (fehlende Definition).")
                    if (p + d.totalSize > end) break
                    val vals = readFields(bytes, p, d)
                    p += d.totalSize
                    handle(d.global, vals, lastTs, records, sessions)?.let { fileCreated = fileCreated ?: it }
                    if (firstTs == null) firstTs = lastTs
                } else if (h and 0x40 != 0) {
                    // Definition
                    val local = h and 0x0F
                    val hasDev = h and 0x20 != 0
                    if (p + 5 > end) break
                    val big = bytes[p + 1].toInt() == 1
                    val global = if (big) u16be(bytes, p + 2) else u16le(bytes, p + 2)
                    val n = bytes[p + 4].toInt() and 0xFF
                    p += 5
                    val fields = ArrayList<FieldDef>(n)
                    repeat(n) {
                        fields.add(FieldDef(bytes[p].toInt() and 0xFF, bytes[p + 1].toInt() and 0xFF, bytes[p + 2].toInt() and 0xFF))
                        p += 3
                    }
                    var devBytes = 0
                    if (hasDev) {
                        val nd = bytes[p].toInt() and 0xFF
                        p++
                        repeat(nd) {
                            devBytes += bytes[p + 1].toInt() and 0xFF
                            p += 3
                        }
                    }
                    defs[local] = Def(global, big, fields, devBytes)
                } else {
                    // normaler Datensatz
                    val local = h and 0x0F
                    val d = defs[local] ?: throw FitException("Beschädigte Datei (fehlende Definition).")
                    if (p + d.totalSize > end) break
                    val vals = readFields(bytes, p, d)
                    p += d.totalSize
                    vals[253]?.let {
                        lastTs = it
                        if (firstTs == null) firstTs = it
                    }
                    handle(d.global, vals, lastTs, records, sessions)?.let { fileCreated = fileCreated ?: it }
                }
            }
            // Nächste Datei im selben Container (selten)
            pos = (pos + headerSize + dataSize + 2).toInt()
        }

        val hr = ArrayList<HrSample>()
        val track = ArrayList<Point>()
        for (r in records) {
            val t = toMillis(r[0])
            if (r[1] in 1..250) hr.add(HrSample(t, r[1].toInt()))
            if (r[2] != NO && r[3] != NO) {
                val lat = r[2] * (180.0 / 2147483648.0)
                val lon = r[3] * (180.0 / 2147483648.0)
                if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    track.add(Point(t, lat, lon, if (r[4] != NO) r[4] / 100.0 else null))
                }
            }
        }
        hr.sortBy { it.t }
        track.sortBy { it.t }

        val s = sessions.firstOrNull()
        val start = s?.start?.let { toMillis(it) }
            ?: records.minOfOrNull { it[0] }?.let { toMillis(it) }
            ?: fileCreated?.let { toMillis(it) }
            ?: throw FitException("Die Datei enthält keine Trainingsdaten.")
        val lastRecord = records.maxOfOrNull { it[0] }?.let { toMillis(it) }
        val end = s?.elapsedS?.let { start + (it * 1000).toLong() }
            ?: lastRecord
            ?: start
        if (records.isEmpty() && s == null) throw FitException("Die Datei enthält keine Trainingsdaten.")

        return Activity(
            start = start,
            end = maxOf(end, lastRecord ?: end),
            sport = s?.sport ?: 0,
            subSport = s?.subSport ?: 0,
            kcal = sessions.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum(),
            distanceM = sessions.mapNotNull { it.distance }.takeIf { it.isNotEmpty() }?.sum(),
            ascentM = sessions.mapNotNull { it.ascent }.takeIf { it.isNotEmpty() }?.sum(),
            hr = hr.distinctBy { it.t },
            track = track.distinctBy { it.t },
        )
    }

    private const val NO = Long.MIN_VALUE

    /** Wertet eine Nachricht aus. Gibt bei file_id die Erstellungszeit zurück. */
    private fun handle(
        global: Int, v: Map<Int, Long>, ts: Long,
        records: MutableList<LongArray>, sessions: MutableList<SessionAcc>,
    ): Long? {
        when (global) {
            0 -> return v[4] // file_id.time_created
            20 -> { // record
                val t = v[253] ?: ts
                if (t <= 0) return null
                val alt = v[78]?.let { (it * 20 - 50_000) } // enhanced_altitude: /5 - 500 m -> cm
                    ?: v[2]?.let { (it * 20 - 50_000) }
                records.add(longArrayOf(t, v[3] ?: NO, v[0] ?: NO, v[1] ?: NO, alt ?: NO))
            }
            18 -> { // session
                val s = SessionAcc()
                s.start = v[2]
                s.elapsedS = (v[7] ?: v[8])?.let { it / 1000.0 }
                s.kcal = v[11]?.toDouble()
                s.sport = v[5]?.toInt()
                s.subSport = v[6]?.toInt()
                s.distance = v[9]?.let { it / 100.0 }
                s.ascent = v[22]?.toDouble()
                sessions.add(s)
            }
        }
        return null
    }

    private val WANTED = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 22, 78, 253)

    private fun readFields(b: ByteArray, start: Int, d: Def): HashMap<Int, Long> {
        val out = HashMap<Int, Long>()
        var p = start
        for (f in d.fields) {
            if (f.num in WANTED) {
                readValue(b, p, f, d.bigEndian)?.let { out[f.num] = it }
            }
            p += f.size
        }
        return out
    }

    /** Liest einen Ganzzahlwert; ungültige Werte (FIT-"invalid") ergeben null. */
    private fun readValue(b: ByteArray, p: Int, f: FieldDef, big: Boolean): Long? {
        val type = f.baseType and 0x1F
        val (width, signed, invalid) = when (type) {
            0, 2, 13 -> Triple(1, false, 0xFFL)
            1 -> Triple(1, true, 0x7FL)
            10 -> Triple(1, false, 0L)
            3 -> Triple(2, true, 0x7FFFL)
            4 -> Triple(2, false, 0xFFFFL)
            11 -> Triple(2, false, 0L)
            5 -> Triple(4, true, 0x7FFFFFFFL)
            6 -> Triple(4, false, 0xFFFFFFFFL)
            12 -> Triple(4, false, 0L)
            else -> return null // Text, Kommazahlen, 64 Bit: hier nicht gebraucht
        }
        if (f.size < width) return null
        var raw = 0L
        for (i in 0 until width) {
            val byte = b[p + i].toLong() and 0xFF
            raw = if (big) (raw shl 8) or byte else raw or (byte shl (8 * i))
        }
        if (raw == invalid) return null
        if (signed) {
            val bits = width * 8
            if (raw and (1L shl (bits - 1)) != 0L) raw -= (1L shl bits)
        }
        return raw
    }

    private fun isFitAt(b: ByteArray, pos: Int): Boolean =
        b[pos + 8] == '.'.code.toByte() && b[pos + 9] == 'F'.code.toByte() &&
            b[pos + 10] == 'I'.code.toByte() && b[pos + 11] == 'T'.code.toByte()

    private fun u16le(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun u16be(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32le(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
            ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)

    private fun toMillis(fitSeconds: Long): Long = (fitSeconds + FIT_EPOCH_OFFSET) * 1000L

    /** Name der Sportart auf Deutsch (FIT-Sportart-Nummern). */
    fun sportName(sport: Int, subSport: Int): String = when {
        sport == 10 && subSport == 20 -> "Krafttraining"
        else -> when (sport) {
            1 -> "Laufen"; 2 -> "Radfahren"; 4 -> "Fitnessgerät"; 5 -> "Schwimmen"
            10 -> "Training"; 11 -> "Gehen"; 12 -> "Langlauf"; 13 -> "Ski alpin"
            15 -> "Rudern"; 16 -> "Bergsteigen"; 17 -> "Wandern"; 21 -> "E-Bike"
            22 -> "Motorrad"; 31 -> "Klettern"; 47 -> "Boxen"
            else -> "Suunto-Training"
        }
    }

    fun sessionType(sport: Int, subSport: Int): SessionType = when {
        sport == 22 -> SessionType.ENDURO
        sport == 10 || sport == 4 -> SessionType.GYM
        else -> SessionType.OTHER
    }
}
