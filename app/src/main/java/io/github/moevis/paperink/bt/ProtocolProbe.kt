package io.github.moevis.paperink.bt

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 认协议 + 握手。
 *
 * 打印机连上了不一定会说话（有的机器只认自己的握手包），所以分两步：
 *  - [detect] 挨个问一句，谁答话就是谁；
 *  - [adoptA5] 用 A5 版本查询再确认一遍——P3 MAX 那一族的特征号跟老机型一样，
 *    光看特征会被猜成 ESC/POS，结果打了一千多个包它一句不认。
 *
 * 对外只有 [identify] 一个入口：链路接上之后调它，返回这台机器该按哪种协议打。
 */
internal object ProtocolProbe {

    private const val TAG = "Paperang"

    /** 问不到单包上限时按官方 P3 MAX 的值来。 */
    private const val FALLBACK_MAX_LEN = 1944

    /**
     * 链路刚接上：认机器、握手、把能力问齐，最后把结论写回 [PrinterSession.protocol]。
     *
     * 串口上只可能是老机型或 A5（两者都认 `0x02` 帧），不用挨个问；BLE 上才要探测。
     */
    suspend fun identify(session: PrinterSession, transport: Transport): DeviceProtocol {
        val guess = if (transport == Transport.BLE) detect(session) else DeviceProtocol.Paperang02
        session.protocol = guess

        if (adoptA5(session)) {
            if (guess != DeviceProtocol.Paperang02) {
                Log.w(TAG, "特征先验猜成 $guess，补测到 A5，已改按 A5 处理")
            }
            session.protocol = DeviceProtocol.A5
            return DeviceProtocol.A5
        }

        if (guess == DeviceProtocol.Paperang02) {
            // 老机型：复位 CRC 密钥（payload = 0 XOR standardKey），之后帧 CRC 初值为 0
            runCatching { session.writeFrame(Protocol.setCrcKeyFrame()) }
            delay(150)
            runCatching {
                session.writeFrame(Protocol.frame(Protocol.Cmd.GET_BAT_STATUS, Protocol.u8(1)))
            }
        }
        return guess
    }

    /** 探测器：先问猫猫机状态，再问 Paperang 电量，谁答话就是谁。 */
    private suspend fun detect(session: PrinterSession): DeviceProtocol {
        val r1 = session.exchange(CatProtocol.frame(CatProtocol.Cmd.GET_STATE, byteArrayOf(0)), 1_500)
        if (r1 != null && r1.size >= 2 && r1[0] == 0x51.toByte() && r1[1] == 0x78.toByte()) {
            Log.i(TAG, "协议识别：猫猫机 (0x51 0x78)")
            session.exchange(CatProtocol.frame(CatProtocol.Cmd.GET_INFO, byteArrayOf(0)), 800)
            return DeviceProtocol.Cat
        }
        val r2 = session.exchange(Protocol.frame(Protocol.Cmd.GET_BAT_STATUS, Protocol.u8(1)), 1_000)
        if (r2 != null && r2.isNotEmpty() && r2[0] == 0x02.toByte()) {
            Log.i(TAG, "协议识别：Paperang (0x02 … 0x03)")
            return DeviceProtocol.Paperang02
        }
        // 都不答话，就按服务特征先验猜一个
        return when (session.gatt.writeServiceUuid) {
            PaperangProfile.SVC -> DeviceProtocol.Paperang02
            PaperangProfile.FF00 -> DeviceProtocol.EscPos
            else -> DeviceProtocol.Cat
        }
    }

    /**
     * 用 A5 协议版本查询做握手：答话即说明是 A5 帧族。
     * 成功之后顺手把单包上限、纸型表、设备信息问齐——这几样只有 A5 机才答。
     */
    private suspend fun adoptA5(session: PrinterSession): Boolean {
        if (!handshakeA5(session)) return false
        session.devMaxLen = queryMaxLen(session)
        readSupportSize(session)
        readTpInfo(session)
        Log.i(
            TAG,
            "A5 握手成功 fw=${session.fwVersion} maxLen=${session.devMaxLen} " +
                "paper=${session.paperSizes} tp=${session.tpInfo}"
        )
        return true
    }

    private suspend fun handshakeA5(session: PrinterSession): Boolean {
        val raw = session.exchange(Protocol.A5.protoVersion(), 1_200) ?: return false
        if (raw.isEmpty() || raw[0] != 0xA5.toByte()) return false
        val ver = Protocol.A5.parse(raw, raw.size)
            .firstOrNull { it.cmd == Protocol.A5.CMD_PROTO_VERSION } ?: return false
        session.fwVersion = ver.text(1)
        return true
    }

    /** 问打印机单包最大字节数（P3 MAX 回 1944）。 */
    private suspend fun queryMaxLen(session: PrinterSession): Int {
        val raw = session.exchange(Protocol.A5.maxLen(), 1_000) ?: return FALLBACK_MAX_LEN
        val n = Protocol.A5.parse(raw, raw.size)
            .firstOrNull { it.cmd == Protocol.A5.CMD_MAX_LEN }?.int(1)
        return n?.takeIf { it in 128..16384 } ?: FALLBACK_MAX_LEN
    }

    /**
     * 问支持的纸型。回包是一段 JSON：`{"TPSizeInfo":[{"PaperWidth":57,"HotSpot":576,"OffsetStart":144}, …]}`
     * 拿到后就知道每种纸该渲染多宽、数据在热敏头上从第几点开始铺。
     */
    private suspend fun readSupportSize(session: PrinterSession) {
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
    private suspend fun readTpInfo(session: PrinterSession) {
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
