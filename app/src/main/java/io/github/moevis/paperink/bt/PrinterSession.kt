package io.github.moevis.paperink.bt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次连接会话：链路状态 + 设备能力 + 收发中转。
 *
 * 分工上它管的是「现在连的是什么、打印机有什么脾气」：
 *  - [link] 是 UI 唯一看的连接状态；
 *  - [kind] / [a5Mode] / [devMaxLen] 等是握手之后才知道的设备能力；
 *  - [writeFrame] 是唯一的出口，BLE 与 SPP 走各自的链路，但外面看到的是同一个接口；
 *  - [exchange] 是「发一帧、等一条回包」，探测协议、问版本、问纸型都走它。
 */
internal class PrinterSession(ctx: Context) {

    companion object {
        private const val TAG = "Paperang"
    }

    private val _link = MutableStateFlow(Link())
    val link: StateFlow<Link> = _link

    /** BLE 链路。 */
    val gatt = GattLink(ctx)

    /** 经典蓝牙链路。 */
    val spp = SppLink()

    /** 探测出来的协议族。 */
    @Volatile
    var kind: Kind = Kind.NONE

    /** 握手确认走 A5 帧族（P3 MAX 实测就是它），打印流程与 0x02 私有帧完全不同。 */
    @Volatile
    var a5Mode: Boolean = false

    /** A5 单包最大字节数，由打印机自报（P3 MAX 实测 1944）。 */
    @Volatile
    var devMaxLen: Int = 1944

    /** 打印机自报的固件协议版本，例如 01.03.08。 */
    @Volatile
    var fwVersion: String? = null

    /** 支持的纸型表（`05 28` 回包）。空表示机型没报，按偏移 0 处理。 */
    @Volatile
    var paperSizes: List<Protocol.A5.PaperSize> = emptyList()

    /** 打印机自报的 TPInfo（`05 01`）：装的是哪种纸、纸宽多少 mm。没答话就是 null。 */
    @Volatile
    var tpInfo: Protocol.A5.TpInfo? = null

    /** 猫猫机说「暂停」了（0xAE 回包），打印时按它等一等。 */
    @Volatile
    var paused: Boolean = false

    /** A5 打印应答计数（收到一条 group=5 的回包就 +1），用于逐包背压。 */
    val a5Ack = AtomicInteger(0)

    private var replyGate: CompletableDeferred<ByteArray>? = null

    init {
        gatt.onBytes = { consume(it) }
        spp.onBytes = { consume(it) }
    }

    fun setLink(l: Link) {
        _link.value = l
    }

    fun setBattery(pct: Int) {
        _link.value = _link.value.copy(battery = pct)
    }

    /**
     * 收到任意字节的入口。三种协议的回包混在同一条流上，得挨个认：
     *  A5 的打印应答（用来做背压）、猫猫机的状态、Paperang 0x02 的电量。
     */
    private fun consume(value: ByteArray) {
        Log.i(TAG, "rx " + value.joinToString(" ") { "%02X".format(it) })
        // 打印时的流控：打印机每收一包就回一条 05 1B 应答，靠它决定何时发下一包
        if (a5Mode && value.isNotEmpty() && value[0] == 0xA5.toByte()) {
            for (r in Protocol.A5.parse(value, value.size)) {
                if (r.group == Protocol.A5.GROUP_PRINT) a5Ack.incrementAndGet()
            }
        }
        replyGate?.complete(value)
        val cat = CatProtocol.parse(value, value.size)
        if (cat != null && cat.first == 0xAE) {
            paused = cat.second.firstOrNull()?.toInt() == 1
            Log.i(TAG, "cat flow paused=$paused")
            return
        }
        if (cat != null) {
            val st = CatProtocol.Status(cat.second.firstOrNull()?.toInt()?.and(0xFF) ?: 0)
            Log.i(TAG, "cat reply cmd=0x${Integer.toHexString(cat.first)} ${st.text()}")
        }
        val replies = Protocol.parseReplies(value, value.size)
        for (r in replies) {
            if (r.cmd == Protocol.Cmd.GET_BAT_STATUS && r.payload.isNotEmpty()) {
                setBattery(r.payload[0].toInt() and 0xFF)
            }
        }
    }

    /**
     * 发一帧、等一条回包（原始字节）。超时或写失败就返回 null。
     * 探协议、问版本、问单包上限、问纸型都走这一条路，别各自复制一套 gate。
     */
    suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
        val gate = CompletableDeferred<ByteArray>()
        replyGate = gate
        // BLE 的往返比 SPP 慢一个数量级（实测同一句 A5 版本查询：SPP 约 30ms、
        // BLE 约 2s），照 SPP 的 1 秒档等必然错过回包、把 A5 机误判成别的协议。
        val wait = if (_link.value.transport == Transport.BLE) timeoutMs * 3 else timeoutMs
        return try {
            writeFrame(frame, await = false)
            withTimeoutOrNull(wait) { gate.await() }
        } catch (e: Exception) {
            null
        } finally {
            replyGate = null
        }
    }

    /** 唯一的发送出口。SPP 是纯字节流，BLE 走 GATT 特征。 */
    suspend fun writeFrame(frame: ByteArray, await: Boolean = true) {
        val t = _link.value.transport ?: throw IOException("未连接")
        if (t == Transport.SPP) {
            spp.write(frame)
            return
        }
        gatt.write(frame, await)
    }
}
