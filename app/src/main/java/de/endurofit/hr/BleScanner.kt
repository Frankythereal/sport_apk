package de.endurofit.hr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

/** Sucht Pulssensoren in der Nähe. */
@SuppressLint("MissingPermission")
class BleScanner(private val ctx: Context) {

    data class Found(val address: String, val name: String, val rssi: Int, val isHr: Boolean)

    private var cb: ScanCallback? = null

    fun bluetoothOn(): Boolean =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun start(onFound: (Found) -> Unit): Boolean {
        stop()
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return false
        val hrUuid = ParcelUuid(HrService.HR_SERVICE)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = result.device.name ?: record?.deviceName ?: return
                val isHr = record?.serviceUuids?.contains(hrUuid) == true
                val looksLikeSensor = isHr || name.contains("Polar", true) || name.contains("HR", false)
                if (!looksLikeSensor) return
                onFound(Found(result.device.address, name, result.rssi, isHr))
            }
        }
        cb = callback
        return try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
            true
        } catch (e: Exception) {
            cb = null
            false
        }
    }

    fun stop() {
        val c = cb ?: return
        try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c)
        } catch (_: Exception) {
        }
        cb = null
    }
}
