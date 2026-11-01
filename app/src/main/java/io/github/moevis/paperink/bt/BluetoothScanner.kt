package io.github.moevis.paperink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 找设备。只管「附近有哪些打印机」，不碰连接，连接归 [PrinterClient]。
 *
 * BLE 广播没有「我是打印机」的标志位，能认出来的全靠设备名，所以「显示全部」
 * 这个开关是必需的——不然用户看不到那些名字被改过的机器。
 */
@SuppressLint("MissingPermission")
class BluetoothScanner(private val ctx: Context) {

    companion object {
        private const val TAG = "Paperang"
    }

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found

    private var scanning = false
    private var showAll = false

    val scanner: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val enabled: Boolean get() = scanner?.isEnabled == true

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = try { dev.name } catch (e: SecurityException) { null } ?: result.scanRecord?.deviceName
            val label = name?.trim().orEmpty()
            if (!showAll && !looksLikePrinter(label) && !advertises(result)) return
            val shown = label.ifBlank { "未命名设备" }
            val item = Found(dev, shown, result.rssi, Transport.BLE)
            val list = _found.value.filterNot { it.device.address == dev.address } + item
            _found.value = list.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "scan failed $errorCode")
        }
    }

    /** 广播里带了我们那个服务号的，多半是喵喵机，哪怕名字被改过。 */
    private fun advertises(r: ScanResult): Boolean {
        val rec = r.scanRecord ?: return false
        return rec.serviceUuids?.any { it.uuid == PaperangProfile.SVC } == true
    }

    fun startScan(all: Boolean = false) {
        showAll = all
        val adapter = scanner ?: return
        if (!adapter.isEnabled) return
        _found.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            adapter.bluetoothLeScanner?.startScan(null, settings, scanCb)
            scanning = true
        } catch (e: SecurityException) {
            Log.w(TAG, "no scan permission", e)
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: SecurityException) {
            // 权限被撤，忽略
        }
    }

    /** 已配对的喵喵机（系统配对记录里按名字找），用于开机自动重连。 */
    fun bondedPrinter(): Found? = try {
        scanner?.bondedDevices?.firstOrNull { d ->
            val n = d.name.orEmpty().lowercase()
            n.contains("paperang") || n.contains("miaomiao") || n.contains("喵喵")
        }?.let { Found(it, it.name?.trim().orEmpty(), 0, Transport.SPP) }
    } catch (e: SecurityException) {
        null
    }

    fun bondedDevices(): List<Found> = try {
        scanner?.bondedDevices.orEmpty().map {
            Found(it, it.name?.trim().orEmpty().ifBlank { it.address }, 0, Transport.SPP)
        }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** 按 MAC 直连（绕过 bondedDevices 的限制）。 */
    fun deviceByAddress(addr: String): BluetoothDevice? = try {
        scanner?.getRemoteDevice(addr)
    } catch (e: Exception) {
        Log.w(TAG, "getRemoteDevice 失败 $addr", e)
        null
    }
}
