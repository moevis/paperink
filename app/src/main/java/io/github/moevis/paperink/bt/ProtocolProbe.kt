package io.github.moevis.paperink.bt

import android.util.Log
import kotlin.math.abs

/**
 * 认协议 + 握手。
 *
 * 打印机连上了不一定会说话（有的机器只认自己的握手包），所以分两步：
 *  - [detect] 挨个问一句，谁答话就是谁；
 *  - [handshakeA5] 用 A5 版本查询再确认一遍——P3 MAX 那一族的特征号跟老机型不一样，
 *    光看特征容易猜成 ESC/POS，结果打了一千多个包它一句不认。
 */
internal object ProtocolProbe {

    private const val TAG = "Paperang"

    /** 问不到单包上限时按官方 P3 MAX 的值来。 */
    private const val FALLBACK_MAX_LEN = 1944

    /** 探测器：先问猫猫机状态，再问 Paperang 电量，谁答话就是谁。 */
    suspend fun detect(session: PrinterSession): Kind {
        val r1 = session.exchange(CatProtocol.frame(CatProtocol.Cmd.GET_STATE, byteArrayOf(0)), 1_500)
        if (r1 != null && r1.size >= 2 && r1[0] == 0x51.toByte() && r1[1] == 0x78.toByte()) {
            Log.i(TAG, "协议识别：猫猫机 (0x51 0x78)")
            session.exchange(CatProtocol.frame(CatProtocol.Cmd.GET_INFO, byteArrayOf(0)), 800)
            return Kind.CAT
        }
        val r2 = session.exchange(Protocol.frame(Protocol.Cmd.GET_BAT_STATUS, Protocol.u8(1)), 1_000)
        if (r2 != null && r2.isNotEmpty() && r2[0] == 0x02.toByte()) {
            Log.i(TAG, "协议识别：Paperang (0x02 … 0x03)")
            return Kind.PAPERANG
        }
        // 都不答话，就按服务特征先验猜一个
        return when (session.gatt.writeServiceUuid) {
            PaperangProfile.SVC -> Kind.PAPERANG
            PaperangProfile.FF00 -> Kind.ESCPOS
            else -> Kind.CAT
        }
    }

    /** 用 A5 协议版本查询做握手：答话即说明是 A5 帧族。 */
    suspend fun handshakeA5(session: PrinterSession): Boolean {
        val raw = session.exchange(Protocol.A5.protoVersion(), 1_200) ?: return false
        if (raw.isEmpty() || raw[0] != 0xA5.toByte()) return false
        val ver = Protocol.A5.parse(raw, raw.size)
            .firstOrNull { it.cmd == Protocol.A5.CMD_PROTO_VERSION } ?: return false
        session.fwVersion = ver.text(1)
        return true
    }

    /** 问打印机单包最大字节数（P3 MAX 回 1944）。 */
    suspend fun queryMaxLen(session: PrinterSession): Int {
        val raw = session.exchange(Protocol.A5.maxLen(), 1_000) ?: return FALLBACK_MAX_LEN
        val n = Protocol.A5.parse(raw, raw.size)
            .firstOrNull { it.cmd == Protocol.A5.CMD_MAX_LEN }?.int(1)
        return n?.takeIf { it in 128..16384 } ?: FALLBACK_MAX_LEN
    }

    /**
     * 问支持的纸型。回包是一段 JSON：`{"TPSizeInfo":[{"PaperWidth":57,"HotSpot":576,"OffsetStart":144}, …]}`
     * 拿到后就知道每种纸该渲染多宽、数据在热敏头上从第几点开始铺。
     */
    suspend fun readSupportSize(session: PrinterSession) {
        val raw = session.exchange(Protocol.A5.supportSize(), 1_200)
        val json = raw?.let { Protocol.A5.parse(it, it.size) }
            ?.firstOrNull { it.cmd == Protocol.A5.SUPPORT_SIZE }?.text(1)
        if (json.isNullOrBlank()) {
            Log.w(TAG, "纸型查询无回包")
            return
        }
        val list = Protocol.A5.parseSizeInfo(json)
        if (list.isNotEmpty()) session.paperSizes = list
        Log.i(TAG, "纸型表 $json")
    }

    /**
     * 问设备信息（`05 01`）。回包是 JSON，里面 `PaperType` 说明装的是哪种纸
     * （官方逻辑里 2 = 卡片纸），`TPCurrentSize` 是卡扣读到的当前纸宽 mm。
     */
    suspend fun readTpInfo(session: PrinterSession) {
        val raw = session.exchange(Protocol.A5.deviceInfo(), 1_200)
        val json = raw?.let { Protocol.A5.parse(it, it.size) }
            ?.firstOrNull { it.cmd == Protocol.A5.DEV_INFO }?.text(1)
        if (json.isNullOrBlank()) {
            Log.w(TAG, "设备信息无回包")
            return
        }
        session.tpInfo = Protocol.A5.parseTpInfo(json)
        Log.i(TAG, "设备信息 $json")
    }

    /** 给定渲染点数，返回该纸型下数据在热敏头上的起始偏移；查不到就 0。 */
    fun offsetFor(session: PrinterSession, widthDots: Int): Int {
        val s = session.paperSizes
        if (s.isEmpty()) return 0
        return (s.minByOrNull { abs(it.hotSpot - widthDots) } ?: return 0).offsetStart
    }
}
