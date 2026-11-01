package io.github.moevis.paperink.bt.print

import io.github.moevis.paperink.bt.DeviceProtocol
import io.github.moevis.paperink.bt.PrinterSession

/**
 * 发一整页位图：按认出来的协议挑一条打印流程。
 *
 * 四条流程差别很大（有的按行发、有的按包发、有的要包一层 lattice 包裹），
 * 但对外面都是同一件事：给我一页 1=黑 的位图，你把纸打出来。
 *
 * 用 `when` 而不是查表，是为了让「新加一种协议」变成编译错误，而不是静默走到
 * 某条默认分支上打出乱码。
 */
internal object PrintEngine {

    suspend fun print(
        session: PrinterSession,
        job: PrintJob,
        onProgress: (Float) -> Unit,
    ) {
        protocolFor(session.protocol).print(session, job, onProgress)
    }

    /**
     * 还没认出来时按老机型 0x02 帧走——它的回包最少，是「认不出来也碰巧能打」
     * 概率最高的一条。
     */
    private fun protocolFor(protocol: DeviceProtocol?): PrintProtocol = when (protocol) {
        DeviceProtocol.A5 -> A5Print
        DeviceProtocol.Cat -> CatPrint
        DeviceProtocol.EscPos -> EscPosPrint
        DeviceProtocol.Paperang02, null -> PaperangPrint
    }
}
