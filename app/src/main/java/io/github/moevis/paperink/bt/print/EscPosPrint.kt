package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.EscPos
import io.github.moevis.paperink.bt.PrinterSession
import kotlinx.coroutines.delay

/**
 * ESC/POS 光栅打印（Phomemo / Marklife 一族，ff00/ff02 通道）。
 *
 * 要点全部来自 sgrankin/phomemo 的实测文档：
 *  - 必须 Write Without Response，否则长传输会中途卡死
 *  - 发送前先 1B 40，再 1F 11 02 04；两步之间留一点时间
 *  - 位图用 GS v 0，整幅高度一次发，不分 255 行块
 *  - 每包之间要限速，发太快打印头只打几行就停
 */
internal object EscPosPrint : PrintProtocol {

    /** 文档里实测的上限：再大打印头就跟不上了。 */
    private const val MAX_CHUNK = 182

    /** 一包之间至少留这么久，快过它打印头只打几行就停。 */
    private const val CHUNK_GAP_MS = 26L

    override suspend fun print(
        session: PrinterSession,
        job: PrintJob,
        onProgress: (Float) -> Unit,
    ) {
        val chunk = minOf(MAX_CHUNK, maxOf(20, session.maxPayload))

        writeRaw(session, EscPos.init())
        delay(80)
        writeRaw(session, EscPos.vendorInit(0x04))
        delay(80)

        writeRaw(session, EscPos.rasterHeader(job.bytesPerRow, job.rows))
        delay(40)

        val total = job.packed.size
        var off = 0
        while (off < total) {
            val n = minOf(chunk, total - off)
            writeRaw(session, job.packed.copyOfRange(off, off + n))
            off += n
            onProgress(progressOf(off, total))
            delay(CHUNK_GAP_MS)
        }

        delay(120)
        writeRaw(session, EscPos.feedLines(4))
        // 固件在最后一个字节后约 2 秒才走完纸，提前断开最后几行会不打
        delay(1_800)
        onProgress(1f)
    }

    /** 不走帧格式的裸写：ESC/POS 是纯字节流，没有包头包尾。 */
    private suspend fun writeRaw(session: PrinterSession, data: ByteArray) {
        for (c in EscPos.chunk(data, maxOf(20, session.maxPayload))) {
            session.writeFrame(c, await = false)
            delay(2)
        }
    }
}
