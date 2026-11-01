package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.CatProtocol
import io.github.moevis.paperink.bt.Protocol
import io.github.moevis.paperink.bt.PrinterSession
import kotlinx.coroutines.delay

/** 猫猫机加热能量，官方默认值。 */
private const val CAT_ENERGY = 7500

/**
 * 猫猫机：一行一条命令，print 前后要发 lattice 包裹，否则纸不动。
 * 官方流程是 state → dpi → speed → energy → apply → update → start → rows → end → feed。
 */
internal suspend fun printCat(
    session: PrinterSession,
    packed: ByteArray,
    rows: Int,
    bytesPerRow: Int,
    feedDots: Int,
    chunkDelayMs: Long,
    onProgress: (Float) -> Unit,
) {
    session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_QUALITY, byteArrayOf(0x32)))
    session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_SPEED, byteArrayOf(0x20)))
    session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_ENERGY, Protocol.u16le(CAT_ENERGY)))
    session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.APPLY_ENERGY, byteArrayOf(0x01)))
    session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.UPDATE_DEVICE, byteArrayOf(0x00)))
    session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.LATTICE_START_END, CatProtocol.LATTICE_START))

    val scratch = ByteArray(bytesPerRow)
    for (r in 0 until rows) {
        val off = r * bytesPerRow
        val rle = CatProtocol.rleRow(packed, off, bytesPerRow)
        val f = if (rle != null) {
            CatProtocol.frame(CatProtocol.Cmd.ROW_RLE, rle)
        } else {
            CatProtocol.rowFrame(packed, off, bytesPerRow, scratch)
        }
        session.writeFrame(f, await = false)
        while (session.paused) delay(20)
        onProgress((r + 1).toFloat() / (rows + 2))
        if (chunkDelayMs > 0) delay(chunkDelayMs)
    }

    session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.LATTICE_START_END, CatProtocol.LATTICE_END))
    session.writeFrame(
        CatProtocol.frame(CatProtocol.Cmd.FEED, Protocol.u16le(feedDots.coerceIn(0, 0xFFFF)))
    )
    onProgress(1f)
}
