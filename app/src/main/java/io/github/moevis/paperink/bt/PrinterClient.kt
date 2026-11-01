package io.github.moevis.paperink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import io.github.moevis.paperink.R
import io.github.moevis.paperink.bt.print.PrintEngine
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
 *  - [ProtocolProbe] 认协议、A5 握手、问纸型
 *  - `bt/print/` 四种协议各自的打印流程
 *
 * 这里只留编排：先连哪条腿、连上之后按什么协议握手、打印时走哪条流程。
 */
@SuppressLint("MissingPermission")
class PrinterClient(private val ctx: Context) {

    companion object {
        private const val TAG = "Paperang"

        /** 猫猫机单行字节数（384 点 / 8），老机型固定这个宽度。 */
        const val ROW_BYTES = 48
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
        ioMutex.withLock {
            disconnectQuiet()
            val name = try { device.name } catch (e: SecurityException) { null } ?: device.address

            /** 腿接上了，接下来认协议、握手。 */
            suspend fun switchedOn(t: Transport) {
                session.setLink(Link(true, name, t, null))
                lastAddress = device.address
                session.kind = if (t == Transport.BLE) {
                    ProtocolProbe.detect(session)
                } else {
                    Kind.PAPERANG
                }

                /** 问一句 A5 版本：答话的就是 A5 机（P3 MAX 属于这一类）。 */
                suspend fun adoptA5(): Boolean {
                    if (!ProtocolProbe.handshakeA5(session)) return false
                    session.kind = Kind.PAPERANG
                    session.a5Mode = true
                    session.devMaxLen = ProtocolProbe.queryMaxLen(session)
                    ProtocolProbe.readSupportSize(session)
                    ProtocolProbe.readTpInfo(session)
                    Log.i(
                        TAG,
                        "A5 握手成功 fw=${session.fwVersion} maxLen=${session.devMaxLen} " +
                            "paper=${session.paperSizes} tp=${session.tpInfo}"
                    )
                    return true
                }

                if (session.kind == Kind.PAPERANG) {
                    if (!adoptA5()) {
                        // 老机型：复位 CRC 密钥（payload = 0 XOR standardKey），之后帧 CRC 初值为 0
                        runCatching { session.writeFrame(Protocol.setCrcKeyFrame()) }
                        delay(150)
                        runCatching {
                            session.writeFrame(Protocol.frame(Protocol.Cmd.GET_BAT_STATUS, Protocol.u8(1)))
                        }
                    }
                }

                // detect() 在谁都不答话时按「可写特征所属服务」猜协议，ff00 会被猜成
                // ESC/POS；而 P3 MAX 恰恰就用 ff00/ff02 这一族，于是整台 A5 机被当成
                // ESC/POS，打出去的一千多包光栅帧它一句不认（实测：走完进度条，一张
                // 纸没出）。所以猜完之后一律再问一句 A5，答话的以 A5 为准。
                if (session.kind != Kind.PAPERANG && adoptA5()) {
                    Log.w(TAG, "特征先验猜错，补测到 A5，已改按 A5 处理")
                }
            }

            // 指定串口就只认串口：这类机型只有 SPP 能打印，降级到 BLE 等于白连
            if (prefer == Transport.SPP) {
                repeat(3) { i ->
                    if (session.spp.connect(device)) {
                        switchedOn(Transport.SPP)
                        return@withLock true
                    }
                    if (i < 2) delay(900)
                }
                Log.w(TAG, "SPP 三次都失败，退回 BLE（只能读状态，不能打印）")
            }

            for (t in listOf(Transport.BLE, Transport.SPP)) {
                val ok = if (t == Transport.BLE) session.gatt.connect(device) else session.spp.connect(device)
                if (ok) {
                    switchedOn(t)
                    return@withLock true
                }
            }
            session.setLink(Link())
            false
        }

    fun disconnect() {
        disconnectQuiet()
        session.setLink(Link())
    }

    /**
     * 发一整页位图。
     * @param packed PrintRenderer 打好的位图数据（1=黑），每行 [bytesPerRow] 字节
     * @param rows 行数
     */
    suspend fun printImage(
        packed: ByteArray,
        rows: Int,
        bytesPerRow: Int,
        feedDots: Int = 260,
        chunkDelayMs: Long = 10,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (session.link.value.transport == null) {
            return@withContext Result.failure(
                IOException(ctx.getString(R.string.err_not_connected))
            )
        }
        try {
            PrintEngine.print(
                session, packed, rows, bytesPerRow, feedDots, chunkDelayMs, onProgress
            )
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
