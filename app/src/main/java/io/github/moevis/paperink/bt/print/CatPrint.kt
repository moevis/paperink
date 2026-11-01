package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.CatProtocol
import io.github.moevis.paperink.bt.Protocol
import io.github.moevis.paperink.bt.PrinterSession
import kotlinx.coroutines.delay

/**
 * 猫猫机：一行一条命令，print 前后要发 lattice 包裹，否则纸不动。
 * 官方流程是 state → dpi → speed → energy → apply → update → start → rows → end → feed。
 */
internal object CatPrint : PrintProtocol {

    /** 加热能量，官方默认值。 */
    private const val CAT_ENERGY = 7500

    override suspend fun print(
        session: PrinterSession,
        job: PrintJob,
        onProgress: (Float) -> Unit,
    ) {
        session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_QUALITY, byteArrayOf(0x32)))
        session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_SPEED, byteArrayOf(0x20)))
        session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.SET_ENERGY, Protocol.u16le(CAT_ENERGY)))
        session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.APPLY_ENERGY, byteArrayOf(0x01)))
        session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.UPDATE_DEVICE, byteArrayOf(0x00)))
        session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.LATTICE_START_END, CatProtocol.LATTICE_START))

        val scratch = ByteArray(job.bytesPerRow)
        for (r in 0 until job.rows) {
            val off = r * job.bytesPerRow
            val rle = CatProtocol.rleRow(job.packed, off, job.bytesPerRow)
            val f = if (rle != null) {
                CatProtocol.frame(CatProtocol.Cmd.ROW_RLE, rle)
            } else {
                CatProtocol.rowFrame(job.packed, off, job.bytesPerRow, scratch)
            }
            session.writeFrame(f, await = false)
            // 机器说「暂停」就停在这里等，它自己会恢复
            while (session.paused) delay(20)
            onProgress(progressOf(r + 1, job.rows + 2))
            if (job.chunkDelayMs > 0) delay(job.chunkDelayMs)
        }

        session.writeFrame(CatProtocol.frame(CatProtocol.Cmd.LATTICE_START_END, CatProtocol.LATTICE_END))
        session.writeFrame(
            CatProtocol.frame(CatProtocol.Cmd.FEED, Protocol.u16le(job.feedDots.coerceIn(0, 0xFFFF)))
        )
        onProgress(1f)
    }
}
