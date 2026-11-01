package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.Protocol
import io.github.moevis.paperink.bt.ProtocolProbe
import io.github.moevis.paperink.bt.PrinterSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A5 帧族打印（P3 MAX）。流程对齐官方 App：
 *   printStart(05 19) → N×(05 1B 带 pid/宽/偏移/数据) → printFinish(05 1A) → feed(05 16)。
 * 每包必须按行对齐，包大小取「打印机自报 maxLen − 2 行」的最大整数行。
 */
internal object A5Print : PrintProtocol {

    /** 帧头加 CRC 的余量，估着留的。 */
    private const val FRAME_OVERHEAD = 32

    /** 单包发出去之后最多等多久应答，超时就按兜底延时继续。 */
    private const val ACK_TIMEOUT_MS = 600L

    override suspend fun print(
        session: PrinterSession,
        job: PrintJob,
        onProgress: (Float) -> Unit,
    ) {
        val rowBytes = maxOf(1, job.bytesPerRow)
        // 单包上限取两家的较小值：固件自报的 maxLen（扣两行 + 帧头 CRC 余量），
        // 以及这条链路一次能写的字节数——BLE 受 MTU 限制，串口没有这个上限。
        // 这里问 [PrinterSession.maxPayload] 而不是直读 gatt.mtu：走串口时那个值是
        // BLE 链路留下的默认 23，会让分包莫名其妙缩到 20 字节。
        val room = minOf(
            session.devMaxLen - 2 * rowBytes - FRAME_OVERHEAD,
            session.maxPayload - FRAME_OVERHEAD,
        ).coerceAtLeast(rowBytes)
        val chunk = (room / rowBytes) * rowBytes
        val total = (job.packed.size + chunk - 1) / chunk
        // 57mm 纸在 864 点的热敏头上是居中装着的，数据得从第 144 点开始铺，否则整页左移
        val offset = ProtocolProbe.offsetFor(session, rowBytes * 8)
        logPrint(
            "A5 打印 ${job.packed.size}B 每行${rowBytes}B 包$chunk 偏移$offset " +
                "共$total 包 链路上限=${session.maxPayload}"
        )

        session.writeFrame(Protocol.A5.printStart(), await = false)
        delay(80)

        var off = 0
        var pid = 0
        while (off < job.packed.size) {
            val n = minOf(chunk, job.packed.size - off)
            val part = job.packed.copyOfRange(off, off + n)
            val before = session.a5Ack.get()
            session.writeFrame(Protocol.A5.printData(part, rowBytes, offset, pid), await = false)
            off += n
            pid++
            onProgress(progressOf(pid, total + 1))
            // 逐包背压：等着打印机把这包收下的应答，长文档才不会把固件缓冲灌爆；
            // 万一这版固件不回应答，超时后按原延时继续，不让整单卡死
            val acked = withTimeoutOrNull(ACK_TIMEOUT_MS) {
                while (session.a5Ack.get() == before) delay(4)
                true
            } == true
            if (!acked && job.chunkDelayMs > 0) delay(job.chunkDelayMs)
        }

        session.writeFrame(Protocol.A5.printFinish(), await = false)
        delay(150)
        if (job.feedDots > 0) {
            session.writeFrame(
                Protocol.A5.feedLine(job.feedDots.coerceIn(0, 0xFFFF)),
                await = false,
            )
        }
        delay(200)
        onProgress(1f)
    }
}
