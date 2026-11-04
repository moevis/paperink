package io.github.moevis.paperink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import io.github.moevis.paperink.R
import io.github.moevis.paperink.bt.print.PrintEngine
import io.github.moevis.paperink.bt.print.PrintJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 打印机连接器：对外只暴露「找设备 / 连上 / 打印」三件事。
 *
 * 底下分成四块，各管各的：
 *  - [BluetoothScanner] 找附近的设备（BLE 广播 + 系统配对记录）
 *  - [PrinterSession] 一次连接的状态与收发中转（[GattLink] / [SppLink] 是两条腿）
 *  - [ProtocolProbe] 认协议、握手、问纸型
 *  - `bt/print/` 四种协议各自的打印流程
 *
 * 这里只留编排：先连哪条腿、打印时把活交给谁。认机器与握手已经沉到
 * [ProtocolProbe.identify]——那是「协议」的事，不是「连接」的事。
 */
@SuppressLint("MissingPermission")
class PrinterClient(private val ctx: Context) {

    companion object {
        private const val TAG = "Paperang"

        /** 指定串口时重试几次。这类机器第一次连常失败，多半是上一个 socket 还没散干净。 */
        private const val SPP_ATTEMPTS = 3
        private const val SPP_RETRY_GAP_MS = 900L
    }

    private val bluetooth = BluetoothScanner(ctx)
    private val session = PrinterSession(ctx)
    private val ioMutex = Mutex()

    /** 最近一次连成的地址，由 App 层持久化。 */
    var lastAddress: String? = null

    /** 当前连接状态。UI 只看这个。 */
    val link: StateFlow<Link> = session.link

    /** 扫到的设备。 */
    val found: StateFlow<List<Found>> = bluetooth.found

    val scanner: BluetoothAdapter? get() = bluetooth.scanner

    val enabled: Boolean get() = bluetooth.enabled

    fun startScan(all: Boolean = false) = bluetooth.startScan(all)

    fun stopScan() = bluetooth.stopScan()

    /** 已配对的喵喵机（系统配对记录里按名字找），用于开机自动重连。 */
    fun bondedPrinter(): Found? = bluetooth.bondedPrinter()

    fun bondedDevices(): List<Found> = bluetooth.bondedDevices()

    /** 按 MAC 直连（绕过 bondedDevices 的限制）。 */
    suspend fun connectAddress(addr: String, prefer: Transport = Transport.SPP): Boolean {
        val d = bluetooth.deviceByAddress(addr) ?: return false
        return connect(d, prefer)
    }

    /**
     * 连一台打印机。
     *
     * @param prefer 强制走某条腿。SPP 只有老机型认，BLE 只有新机型认；
     *   传 SPP 表示「只认串口」——这类机型降级到 BLE 等于白连。
     */
    suspend fun connect(device: BluetoothDevice, prefer: Transport? = null): Boolean =
        // 整段都在 IO 线程：连 socket 是阻塞调用（打印机不在线时能卡十几秒），
        // 识别协议的握手也是。调用它的地方常常是 LaunchedEffect / rememberCoroutineScope，
        // 那些都是主线程协程——不切线程就会喂出一个「应用无响应」。
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                disconnectQuiet()
                val name = try { device.name } catch (e: SecurityException) { null } ?: device.address

                /** 接通一条链路，接着认协议、握手。握手失败也照样算连通——能读状态也是连着。 */
                suspend fun linkUp(transport: Transport): Boolean {
                    val up = if (transport == Transport.BLE) {
                        session.gatt.connect(device)
                    } else {
                        session.spp.connect(device)
                    }
                    if (!up) return false
                    session.setLink(Link(true, name, transport, null))
                    lastAddress = device.address
                    ProtocolProbe.identify(session, transport)
                    return true
                }

                // 指定串口就只认串口：这类机型只有 SPP 能打印，降级到 BLE 等于白连
                if (prefer == Transport.SPP) {
                    repeat(SPP_ATTEMPTS) { i ->
                        if (linkUp(Transport.SPP)) return@withLock true
                        if (i < SPP_ATTEMPTS - 1) delay(SPP_RETRY_GAP_MS)
                    }
                    Log.w(TAG, "SPP $SPP_ATTEMPTS 次都失败，退回 BLE（只能读状态，不能打印）")
                }

                for (t in listOf(Transport.BLE, Transport.SPP)) {
                    if (linkUp(t)) return@withLock true
                }
                session.setLink(Link())
                false
            }
        }

    fun disconnect() {
        disconnectQuiet()
        session.setLink(Link())
    }

    /** 发一整页位图。 */
    suspend fun printImage(
        job: PrintJob,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (session.link.value.transport == null) {
            return@withContext Result.failure(
                IOException(ctx.getString(R.string.err_not_connected))
            )
        }
        try {
            PrintEngine.print(session, job, onProgress)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 给定渲染点数，返回该纸型下数据在热敏头上的起始偏移；查不到就 0。 */
    fun offsetFor(widthDots: Int): Int = ProtocolProbe.offsetFor(session, widthDots)

    fun close() {
        stopScan()
        disconnect()
    }

    private fun disconnectQuiet() {
        session.gatt.close()
        session.spp.close()
    }
}
