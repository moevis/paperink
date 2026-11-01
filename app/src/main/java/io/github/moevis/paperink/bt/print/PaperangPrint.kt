package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.Protocol
import io.github.moevis.paperink.bt.PrinterSession
import kotlinx.coroutines.delay

/**
 * 老机型：0x02 私有帧，每包 5 行。
 *
 * 帧格式见 [Protocol]。位图按行对齐切分，一行都不能断——断行会在纸上撕开。
 */
internal object PaperangPrint : PrintProtocol {

    private const val ROWS_PER_CHUNK = 5

    override suspend fun print(
        session: PrinterSession,
        job: PrintJob,
        onProgress: (Float) -> Unit,
    ) {
        val chunk = job.bytesPerRow * ROWS_PER_CHUNK
        runCatching { session.writeFrame(Protocol.frame(Protocol.Cmd.SET_PAPER_TYPE, Protocol.u8(0))) }
        val frames = Protocol.imageFrames(job.packed, job.bytesPerRow, chunk)
        var sent = 0
        for (f in frames) {
            session.writeFrame(f)
            sent++
            onProgress(progressOf(sent, frames.size + 1))
            if (job.chunkDelayMs > 0) delay(job.chunkDelayMs)
        }
        session.writeFrame(
            Protocol.frame(Protocol.Cmd.FEED_LINE, Protocol.u16le(job.feedDots.coerceIn(0, 0xFFFF)))
        )
        onProgress(1f)
    }
}
