package de.endurofit.hr

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import de.endurofit.MainActivity
import de.endurofit.R
import java.util.UUID

/**
 * Hält die Bluetooth-Verbindung zur Polar Verity Sense (Standard-Pulsprofil),
 * auch wenn der Bildschirm aus ist.
 */
@SuppressLint("MissingPermission")
class HrService : Service() {

    companion object {
        private const val TAG = "HrService"
        const val ACTION_CONNECT = "de.endurofit.CONNECT"
        const val ACTION_DISCONNECT = "de.endurofit.DISCONNECT"
        const val EXTRA_ADDRESS = "address"
        private const val CHANNEL = "hr"
        private const val NOTIF_ID = 1

        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun connect(ctx: Context, address: String) {
            val i = Intent(ctx, HrService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_ADDRESS, address)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun disconnect(ctx: Context) {
            if (HrState.conn.value == HrState.Conn.IDLE) return
            ctx.startService(Intent(ctx, HrService::class.java).setAction(ACTION_DISCONNECT))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var wantConnected = false
    private var lastNotifUpdate = 0L
    private var reconnectTries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startInForeground("Verbinde mit Sensor …")
                val addr = intent.getStringExtra(EXTRA_ADDRESS)
                if (addr == null) { stopEverything(); return START_NOT_STICKY }
                address = addr
                wantConnected = true
                reconnectTries = 0
                connectGatt()
            }
            ACTION_DISCONNECT -> stopEverything()
            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun stopEverything() {
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        HrState.conn.value = HrState.Conn.IDLE
        HrState.bpm.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        HrState.conn.value = HrState.Conn.IDLE
        HrState.bpm.value = 0
        super.onDestroy()
    }

    // ---------- Benachrichtigung ----------

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pulssensor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("EnduroFit")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Benachrichtigung fehlgeschlagen", e)
        }
    }

    // ---------- Bluetooth ----------

    private fun connectGatt() {
        closeGatt()
        val addr = address ?: return
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                HrState.error.value = "Bluetooth ist ausgeschaltet."
                scheduleReconnect()
                return
            }
            val device = adapter.getRemoteDevice(addr)
            HrState.deviceName.value = device.name ?: addr
            HrState.conn.value = if (reconnectTries == 0) HrState.Conn.CONNECTING else HrState.Conn.RECONNECTING
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "Verbindung fehlgeschlagen", e)
            HrState.error.value = "Verbindung fehlgeschlagen: ${e.message}"
            scheduleReconnect()
        }
    }

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectTries++
        HrState.conn.value = HrState.Conn.RECONNECTING
        HrState.bpm.value = 0
        updateNotification("Sensor getrennt – verbinde neu …")
        val delay = (2_000L * reconnectTries).coerceAtMost(15_000L)
        handler.postDelayed({ if (wantConnected) connectGatt() }, delay)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post {
                    if (g == gatt) {
                        closeGatt()
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                HrState.error.value = "Dieses Gerät sendet keinen Puls. Ist die Verity Sense eingeschaltet?"
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD)
            if (d != null) handler.post { enableNotify(g, d) }
            reconnectTries = 0
            HrState.error.value = null
            HrState.conn.value = HrState.Conn.CONNECTED
            updateNotification("Verbunden mit ${HrState.deviceName.value ?: "Sensor"}")
        }

        // Ab Android 13
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == HR_MEASUREMENT) onHr(value)
        }

        // Bis Android 12
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == HR_MEASUREMENT) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { onHr(it) }
            }
        }
    }

    private fun enableNotify(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            enableNotifyLegacy(g, d)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifyLegacy(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    /** Wertet das Standard-Pulsformat aus (Bluetooth Heart Rate Measurement). */
    private fun onHr(v: ByteArray) {
        if (v.size < 2) return
        val flags = v[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 != 0) {
            if (v.size < 3) return
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        } else {
            v[1].toInt() and 0xFF
        }
        if (bpm <= 0 || bpm > 250) return
        HrState.bpm.value = bpm
        HrState.lastBeatAt.value = System.currentTimeMillis()
        Recorder.addSample(bpm)

        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate > 5_000) {
            lastNotifUpdate = now
            val rec = if (Recorder.live.value != null) " · Aufnahme läuft" else ""
            updateNotification("♥ $bpm bpm$rec")
        }
    }
}
