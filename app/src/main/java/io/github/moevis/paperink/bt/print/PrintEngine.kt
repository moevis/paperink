package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.Kind
import io.github.moevis.paperink.bt.PrinterSession

/**
 * 发一整页位图：按探测出来的协议族挑一条打印流程。
 *
 * 四条流程差别很大（有的按行发、有的按包发、有的要包一层 lattice 包裹），
 * 但对外面都是「给我一页 1=黑 的位图，你把纸打出来」。
 */
internal object PrintEngine {

    suspend fun print(
        session: PrinterSession,
        packed: ByteArray,
        rows: Int,
        bytesPerRow: Int,
        feedDots: Int,
        chunkDelayMs: Long,
        onProgress: (Float) -> Unit,
    ) {
        when {
            session.kind == Kind.CAT ->
                printCat(session, packed, rows, bytesPerRow, feedDots, chunkDelayMs, onProgress)

            session.kind == Kind.ESCPOS ->
                printEscPosRaster(session, packed, rows, bytesPerRow, onProgress)

            session.a5Mode ->
                printA5(session, packed, bytesPerRow, feedDots, chunkDelayMs, onProgress)

            else ->
                printPaperang(session, packed, bytesPerRow, feedDots, chunkDelayMs, onProgress)
        }
    }
}
