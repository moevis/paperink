package io.github.moevis.paperink.bt.print

import android.util.Log
import io.github.moevis.paperink.bt.Protocol
import io.github.moevis.paperink.bt.ProtocolProbe
import io.github.moevis.paperink.bt.PrinterSession
import io.github.moevis.paperink.bt.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A5 帧族打印（P3 MAX）。流程对齐官方 App：
 *   printStart(05 19) → N×(05 1B 带 pid/宽/偏移/数据) → printFinish(05 1A) → feed(05 16)。
 * 每包必须按行对齐，包大小取「打印机自报 maxLen − 2 行」的最大整数行。
 */
internal suspend fun printA5(
    session: PrinterSession,
    packed: ByteArray,
    bytesPerRow: Int,
    feedDots: Int,
    chunkDelayMs: Long,
    onProgress: (Float) -> Unit,
) {
    val rowBytes = maxOf(1, bytesPerRow)
    // 单包上限取两家的较小值：固件自报的 maxLen（扣两行 + 帧头 CRC 余量），以及
    // 走 BLE 时 MTU 折出来的空间——writeCharacteristic 一次只能塞 MTU-3 字节，
    // 超了写不进去（帧头/CRC 约 20 字节，留 32 当余量）。
    val ble = session.link.value.transport == Transport.BLE
    val mtuRoom = if (ble) session.gatt.mtu - 3 - 32 else Int.MAX_VALUE
    val room = minOf(session.devMaxLen - 2 * rowBytes - 32, mtuRoom).coerceAtLeast(rowBytes)
    val chunk = (room / rowBytes) * rowBytes
    val total = (packed.size + chunk - 1) / chunk
    // 57mm 纸在 864 点的热敏头上是居中装着的，数据得从第 144 点开始铺，否则整页左移
    val offset = ProtocolProbe.offsetFor(session, rowBytes * 8)
    Log.i(
        "Paperang",
        "A5 打印 ${packed.size}B 每行${rowBytes}B 包$chunk 偏移$offset 共$total 包 ble=$ble mtu=${session.gatt.mtu}"
    )

    session.writeFrame(Protocol.A5.printStart(), await = false)
    delay(80)

    var off = 0
    var pid = 0
    while (off < packed.size) {
        val n = minOf(chunk, packed.size - off)
        val part = packed.copyOfRange(off, off + n)
        val before = session.a5Ack.get()
        session.writeFrame(Protocol.A5.printData(part, rowBytes, offset, pid), await = false)
        off += n
        pid++
        onProgress(pid.toFloat() / (total + 1).toFloat())
        // 逐包背压：等着打印机把这包收下的应答，长文档才不会把固件缓冲灌爆；
        // 万一这版固件不回应答，超时后按原延时继续，不让整单卡死
        val acked = withTimeoutOrNull(600) {
            while (session.a5Ack.get() == before) delay(4)
            true
        } == true
        if (!acked && chunkDelayMs > 0) delay(chunkDelayMs)
    }

    session.writeFrame(Protocol.A5.printFinish(), await = false)
    delay(150)
    if (feedDots > 0) {
        session.writeFrame(Protocol.A5.feedLine(feedDots.coerceIn(0, 0xFFFF)), await = false)
    }
    delay(200)
    onProgress(1f)
}
