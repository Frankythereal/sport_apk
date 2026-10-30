package de.endurofit.scale

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.endurofit.data.Repo
import de.endurofit.data.WeightEntry
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar
import java.util.UUID
import kotlin.random.Random

/**
 * Verbindung zur Beurer BF 720 (und anderen Waagen mit dem Bluetooth-Standard für Waagen):
 *
 * - Weight Scale Service (0x181D) liefert das Gewicht,
 * - Body Composition Service (0x181B) Fett, Muskeln, Wasser, Knochen,
 * - User Data Service (0x181C) verwaltet die Personen auf der Waage. EnduroFit legt dort beim
 *   ersten Mal einen eigenen Platz mit Zugangscode an und meldet sich danach damit an.
 * - Beurer-Zusatzdienst (0xFFFF) für Aktivitätslevel, Kürzel und „jetzt messen“.
 *
 * Gespeicherte Messungen, die die Waage ohne Handy gemacht hat, schickt sie nach der Anmeldung mit.
 */
@SuppressLint("MissingPermission")
object BeurerScale {
    private const val TAG = "BeurerScale"

    private fun u16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    private val SVC_WEIGHT = u16(0x181D)
    private val CHR_WEIGHT = u16(0x2A9D)
    private val SVC_BODY = u16(0x181B)
    private val CHR_BODY = u16(0x2A9C)
    private val SVC_USER = u16(0x181C)
    private val CHR_UCP = u16(0x2A9F)
    private val CHR_CHANGE_INC = u16(0x2A99)
    private val CHR_BIRTH = u16(0x2A85)
    private val CHR_GENDER = u16(0x2A8C)
    private val CHR_HEIGHT = u16(0x2A8E)
    private val SVC_TIME = u16(0x1805)
    private val CHR_TIME = u16(0x2A2B)
    private val SVC_BEURER = u16(0xFFFF)
    private val CHR_ACTIVITY = u16(0x0004)
    private val CHR_INITIALS = u16(0x0002)
    private val CHR_MEASURE = u16(0x0006)
    private val CCCD = u16(0x2902)

    enum class Phase { IDLE, SEARCHING, CONNECTING, CONNECTED, DONE, ERROR }

    val phase = MutableStateFlow(Phase.IDLE)
    val status = MutableStateFlow<String?>(null)
    /** Wiegungen dieser Verbindung */
    val received = MutableStateFlow(0)
    /** Zuletzt gemessenes Gewicht (auch Zwischenwert) */
    val liveKg = MutableStateFlow<Double?>(null)

    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private var registering = false
    private var pendingConsent = -1
    /** Messungen dieser Sitzung, nach Zeitstempel */
    private val pending = LinkedHashMap<Long, WeightEntry>()
    private var lastWeightKey: Long? = null

    val busy get() = phase.value == Phase.SEARCHING || phase.value == Phase.CONNECTING || phase.value == Phase.CONNECTED

    // ------------------------------------------------------------------ Start / Stopp

    /** Waage suchen und verbinden. Die Waage muss wach sein – einmal kurz draufstellen. */
    fun start(ctx: Context) {
        if (busy) return
        appCtx = ctx.applicationContext
        received.value = 0
        liveKg.value = null
        pending.clear()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth ist ausgeschaltet.")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return fail("Bluetooth-Suche nicht verfügbar.")
        phase.value = Phase.SEARCHING
        status.value = "Stell dich kurz auf die Waage, damit sie aufwacht …"
        val known = Repo.scaleAddress
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = (result.scanRecord?.deviceName ?: d.name ?: "")
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                val match = if (known != null) d.address == known
                else name.contains("BF", true) && (name.contains("720") || name.contains("105") || name.contains("BF7", true)) ||
                    uuids.any { it == SVC_WEIGHT || it == SVC_BODY || it == SVC_USER }
                if (!match || phase.value != Phase.SEARCHING) return
                stopScan()
                Repo.scaleAddress = d.address
                if (name.isNotBlank()) Repo.scaleName = name
                connect(d)
            }

            override fun onScanFailed(errorCode: Int) {
                fail("Bluetooth-Suche fehlgeschlagen ($errorCode).")
            }
        }
        scanCb = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            return fail("Bluetooth-Suche nicht erlaubt: ${e.message}")
        }
        // Nach 60 Sekunden aufgeben
        handler.postDelayed({
            if (phase.value == Phase.SEARCHING) {
                stopScan()
                fail("Waage nicht gefunden. Stell dich drauf, bis sie etwas anzeigt, und versuch es nochmal.")
            }
        }, 60_000)
    }

    fun stop() {
        stopScan()
        finish(null)
    }

    /** Waage vergessen – beim nächsten Mal wird ein neuer Platz angelegt. */
    fun forget() {
        stop()
        Repo.scaleAddress = null
        Repo.scaleName = null
        Repo.scaleUserIndex = -1
        Repo.scaleConsent = -1
        phase.value = Phase.IDLE
        status.value = null
    }

    private fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        try {
            appCtx?.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        finish(msg, error = true)
    }

    private fun finish(msg: String?, error: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        saveAll()
        try {
            gatt?.disconnect(); gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        ops.clear(); opBusy = false
        registering = false
        phase.value = if (error) Phase.ERROR else if (received.value > 0) Phase.DONE else Phase.IDLE
        status.value = msg ?: if (received.value > 0) "✓ ${received.value} Wiegung(en) übernommen." else null
    }

    // ------------------------------------------------------------------ Verbindung

    private fun connect(d: BluetoothDevice) {
        val ctx = appCtx ?: return
        phase.value = Phase.CONNECTING
        status.value = "Verbinde mit ${Repo.scaleName ?: "der Waage"} …"
        handler.post {
            gatt = d.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        handler.postDelayed({ if (phase.value == Phase.CONNECTING) fail("Verbindung kam nicht zustande. Waage noch einmal wecken und erneut versuchen.") }, 25_000)
    }

    /** Schließt die Verbindung, wenn eine Weile nichts mehr kommt. */
    private fun idleTimeout(ms: Long = 30_000) {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, ms)
    }

    private val idleRunnable = Runnable { if (phase.value == Phase.CONNECTED) finish(null) }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    phase.value = Phase.CONNECTED
                    status.value = "Verbunden – richte die Waage ein …"
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && g == gatt) {
                    finish(null)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            handler.post { setup(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            handler.post { next() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (st != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Schreiben ${c.uuid} fehlgeschlagen: $st")
            handler.post { next() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            handler.post { onData(c.uuid, copy) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = c.value?.copyOf() ?: return
                handler.post { onData(c.uuid, v) }
            }
        }
    }

    // ------------------------------------------------------------------ Befehle nacheinander

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (!opBusy) next()
    }

    private fun next() {
        opBusy = false
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            if (op()) { opBusy = true; return }
        }
    }

    private fun chr(svc: UUID, c: UUID) = gatt?.getService(svc)?.getCharacteristic(c)

    private fun subscribe(svc: UUID, c: UUID) = enqueue {
        val g = gatt ?: return@enqueue false
        val ch = chr(svc, c) ?: return@enqueue false
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return@enqueue false
        val v = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun write(svc: UUID, c: UUID, data: ByteArray) = enqueue {
        val g = gatt ?: return@enqueue false
        val ch = chr(svc, c) ?: return@enqueue false
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, data, type) == BluetoothGatt.GATT_SUCCESS
        else {
            @Suppress("DEPRECATION")
            ch.writeType = type
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    // ------------------------------------------------------------------ Ablauf

    private fun setup(g: BluetoothGatt) {
        if (g.getService(SVC_WEIGHT) == null && g.getService(SVC_BODY) == null) {
            fail("Das Gerät ist keine unterstützte Waage.")
            return
        }
        subscribe(SVC_WEIGHT, CHR_WEIGHT)
        subscribe(SVC_BODY, CHR_BODY)
        subscribe(SVC_USER, CHR_UCP)
        write(SVC_TIME, CHR_TIME, timeBytes())
        val idx = Repo.scaleUserIndex
        val consent = Repo.scaleConsent
        if (idx >= 0 && consent >= 0) {
            status.value = "Melde dich an der Waage an …"
            sendConsent(idx, consent)
        } else if (g.getService(SVC_USER) != null) {
            registering = true
            pendingConsent = Random.nextInt(1, 10_000)
            status.value = "Lege dich als neue Person auf der Waage an …"
            write(SVC_USER, CHR_UCP, byteArrayOf(0x01, lo(pendingConsent), hi(pendingConsent)))
        } else {
            status.value = "Jetzt auf die Waage stellen …"
        }
        idleTimeout(60_000)
    }

    private fun sendConsent(idx: Int, consent: Int) =
        write(SVC_USER, CHR_UCP, byteArrayOf(0x02, idx.toByte(), lo(consent), hi(consent)))

    private fun onData(uuid: UUID, v: ByteArray) {
        when (uuid) {
            CHR_UCP -> onControlPoint(v)
            CHR_WEIGHT -> parseWeight(v)
            CHR_BODY -> parseBody(v)
        }
    }

    private fun onControlPoint(v: ByteArray) {
        if (v.size < 3 || (v[0].toInt() and 0xFF) != 0x20) return
        val req = v[1].toInt() and 0xFF
        val result = v[2].toInt() and 0xFF
        when (req) {
            0x01 -> { // neue Person
                if (result == 0x01 && v.size >= 4) {
                    val idx = v[3].toInt() and 0xFF
                    Repo.scaleUserIndex = idx
                    Repo.scaleConsent = pendingConsent
                    sendConsent(idx, pendingConsent)
                } else {
                    registering = false
                    fail(
                        if (result == 0x04) "Auf der Waage sind alle Personen-Plätze belegt. Lösche in der Beurer-App eine Person und versuch es nochmal."
                        else "Die Waage hat die Anmeldung abgelehnt (Code $result)."
                    )
                }
            }
            0x02 -> { // Anmeldung mit Zugangscode
                when (result) {
                    0x01 -> {
                        if (registering) {
                            registering = false
                            writeUserData()
                            status.value = "Angelegt ✓ – stell dich jetzt barfuß auf die Waage für die erste Messung."
                        } else {
                            status.value = "Angemeldet ✓ – jetzt barfuß auf die Waage stellen."
                        }
                        // Messung anstoßen (Beurer)
                        write(SVC_BEURER, CHR_MEASURE, byteArrayOf(0x00))
                        idleTimeout(60_000)
                    }
                    0x05 -> {
                        // Zugangscode passt nicht mehr (z. B. Waage zurückgesetzt) → neu anlegen
                        Repo.scaleUserIndex = -1
                        Repo.scaleConsent = -1
                        registering = true
                        pendingConsent = Random.nextInt(1, 10_000)
                        status.value = "Anmeldung abgelaufen – lege dich neu an …"
                        write(SVC_USER, CHR_UCP, byteArrayOf(0x01, lo(pendingConsent), hi(pendingConsent)))
                    }
                    else -> fail("Anmeldung an der Waage fehlgeschlagen (Code $result).")
                }
            }
        }
    }

    /** Alter, Größe und Geschlecht aus dem Profil – die Waage braucht sie für Fett/Muskeln. */
    private fun writeUserData() {
        val p = Repo.profile.value
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR) - p.age
        write(SVC_USER, CHR_BIRTH, byteArrayOf(lo(year), hi(year), 1, 1))
        write(SVC_USER, CHR_GENDER, byteArrayOf(if (p.male) 0 else 1))
        write(SVC_USER, CHR_HEIGHT, byteArrayOf(lo(p.heightCm), hi(p.heightCm)))
        write(SVC_BEURER, CHR_ACTIVITY, byteArrayOf(4)) // 1–5, 4 = sehr aktiv
        write(SVC_BEURER, CHR_INITIALS, "ENF".toByteArray())
        write(SVC_USER, CHR_CHANGE_INC, byteArrayOf(1, 0, 0, 0))
    }

    // ------------------------------------------------------------------ Messwerte lesen

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16le(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun lo(v: Int) = (v and 0xFF).toByte()
    private fun hi(v: Int) = ((v shr 8) and 0xFF).toByte()
    private fun mass(raw: Int, kg: Boolean) = if (kg) raw * 0.005 else raw * 0.01 * 0.45359237

    private fun time(b: ByteArray, o: Int): Long? {
        val y = u16le(b, o)
        if (y < 2000) return null
        return Calendar.getInstance().apply {
            clear(); set(y, u8(b, o + 2) - 1, u8(b, o + 3), u8(b, o + 4), u8(b, o + 5), u8(b, o + 6))
        }.timeInMillis
    }

    /** Messung gehört zu uns? (Ohne Personenangabe nehmen wir sie an.) */
    private fun mine(idx: Int?) = idx == null || idx == Repo.scaleUserIndex

    private fun parseWeight(v: ByteArray) {
        if (v.size < 3) return
        val flags = u8(v, 0)
        val kg = flags and 0x01 == 0
        var o = 1
        val raw = u16le(v, o); o += 2
        if (raw == 0xFFFF) return // „Messung ungültig“
        var t: Long? = null
        if (flags and 0x02 != 0 && v.size >= o + 7) { t = time(v, o); o += 7 }
        var idx: Int? = null
        if (flags and 0x04 != 0 && v.size > o) { idx = u8(v, o).takeIf { it != 0xFF }; o += 1 }
        val w = mass(raw, kg)
        liveKg.value = w
        if (!mine(idx) || w < 20) return
        val key = t ?: System.currentTimeMillis()
        val e = pending[key]
        pending[key] = (e ?: WeightEntry("scale_${key / 1000}", key, w, source = "scale")).copy(kg = w)
        lastWeightKey = key
        received.value = pending.size
        status.value = "⚖️ ${de.endurofit.data.Calc.fmtNum(w, 1)} kg empfangen – Körperwerte folgen …"
        idleTimeout(12_000)
    }

    private fun parseBody(v: ByteArray) {
        if (v.size < 4) return
        val flags = u16le(v, 0)
        val kg = flags and 0x0001 == 0
        var o = 2
        val fat = u16le(v, o) * 0.1; o += 2
        var t: Long? = null
        if (flags and 0x0002 != 0) { t = time(v, o); o += 7 }
        var idx: Int? = null
        if (flags and 0x0004 != 0) { idx = u8(v, o).takeIf { it != 0xFF }; o += 1 }
        if (flags and 0x0008 != 0) o += 2 // Grundumsatz
        var musclePct: Double? = null
        if (flags and 0x0010 != 0) { musclePct = u16le(v, o) * 0.1; o += 2 }
        if (flags and 0x0020 != 0) o += 2 // Muskelmasse
        if (flags and 0x0040 != 0) o += 2 // fettfreie Masse
        var softLean: Double? = null
        if (flags and 0x0080 != 0) { softLean = mass(u16le(v, o), kg); o += 2 }
        var waterKg: Double? = null
        if (flags and 0x0100 != 0) { waterKg = mass(u16le(v, o), kg); o += 2 }
        if (flags and 0x0200 != 0) o += 2 // Impedanz
        var weight: Double? = null
        if (flags and 0x0400 != 0 && v.size >= o + 2) { weight = mass(u16le(v, o), kg); o += 2 }
        if (!mine(idx)) return

        val key = t ?: lastWeightKey ?: System.currentTimeMillis()
        val base = pending[key] ?: weight?.let { WeightEntry("scale_${key / 1000}", key, it, source = "scale") } ?: return
        val w = weight ?: base.kg
        val valid = fat in 1.0..70.0 // ohne Fußkontakt (Socken) liefert die Waage 0
        pending[key] = base.copy(
            kg = w,
            fatPct = if (valid) fat else base.fatPct,
            musclePct = musclePct?.takeIf { valid && it > 0 } ?: base.musclePct,
            waterPct = waterKg?.takeIf { valid && it > 0 && w > 0 }?.let { it / w * 100 } ?: base.waterPct,
            boneKg = if (valid && softLean != null && softLean > 0) (w * (1 - fat / 100) - softLean).takeIf { it in 0.5..8.0 } else base.boneKg,
        )
        received.value = pending.size
        saveAll()
        status.value = "✓ ${de.endurofit.data.Calc.fmtNum(w, 1)} kg" + (if (valid) " · Fett ${de.endurofit.data.Calc.fmtNum(fat, 1)} %" else " – für Körperfett barfuß wiegen")
        idleTimeout(8_000)
    }

    private fun saveAll() {
        if (pending.isEmpty()) return
        Repo.addWeights(pending.values.toList())
    }

    private fun timeBytes(): ByteArray {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val dow = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        return byteArrayOf(
            lo(y), hi(y), (c.get(Calendar.MONTH) + 1).toByte(), c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(), c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte(),
            dow.toByte(), 0, 0,
        )
    }
}
