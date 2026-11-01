package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.Protocol
import io.github.moevis.paperink.bt.PrinterSession
import kotlinx.coroutines.delay

/**
 * 老机型：0x02 私有帧，每包 5 行。
 *
 * 帧格式见 [Protocol]。位图按行对齐切分，一行都不能断——断行会在纸上撕开。
 */
internal suspend fun printPaperang(
    session: PrinterSession,
    packed: ByteArray,
    bytesPerRow: Int,
    feedDots: Int,
    chunkDelayMs: Long,
    onProgress: (Float) -> Unit,
) {
    val chunk = bytesPerRow * 5
    runCatching { session.writeFrame(Protocol.frame(Protocol.Cmd.SET_PAPER_TYPE, Protocol.u8(0))) }
    val frames = Protocol.imageFrames(packed, bytesPerRow, chunk)
    var sent = 0
    for (f in frames) {
        session.writeFrame(f)
        sent++
        onProgress(sent.toFloat() / (frames.size + 1))
        if (chunkDelayMs > 0) delay(chunkDelayMs)
    }
    session.writeFrame(
        Protocol.frame(Protocol.Cmd.FEED_LINE, Protocol.u16le(feedDots.coerceIn(0, 0xFFFF)))
    )
    onProgress(1f)
}
