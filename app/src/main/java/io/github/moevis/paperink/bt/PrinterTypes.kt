package io.github.moevis.paperink.bt

import android.bluetooth.BluetoothDevice

/** 打印链路用哪套传输，由 [PrinterClient] 探测决定。 */
enum class Transport { BLE, SPP }

/** 当前连接状态。UI 只看这个对象。 */
data class Link(
    val connected: Boolean = false,
    val name: String? = null,
    val transport: Transport? = null,
    val battery: Int? = null,
)

/** 扫到（或从配对记录里翻到）的一台设备。 */
data class Found(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
    val transport: Transport,
)
