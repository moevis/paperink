package io.github.moevis.paperink.bt

import java.util.UUID

/**
 * Paperang / 喵喵机的 BLE 服务号与特征号。
 *
 * 一代机（P1/P2）和二代机（P3 MAX）共用 49535343 这一族服务，只是可写特征
 * 换了名字；再往后的一族（ff00/ff02）整套服务号都换了，但写法还是 GATT 透传。
 */
object PaperangProfile {

    /** 一代机的服务号。 */
    val SVC: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")

    /** 一代机的写特征。 */
    val CH_WRITE: UUID = UUID.fromString("49535343-6daa-4d02-abf6-19569aca69fe")

    /** 另一代机用的写特征（同一族服务，换了个名字）。 */
    val CH_WRITE_ALT: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")

    /** 通知特征（仅作记录，实际靠特征属性找可通知的那一个）。 */
    val CH_NOTIFY: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")

    /** 二代机（P3 MAX 等）换成了 ff00/ff02 这一族。 */
    val FF00: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val FF02: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    /** 通知使能描述符（CCCD）。 */
    val CLIENT_CFG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 经典蓝牙 SPP 的服务号。 */
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
}

/**
 * 名字里带这些词的多半是打印机。
 *
 * BLE 广播里没有统一的「我是打印机」标志位，只能靠 device name 猜；猜不出的
 * 一律要用户手动切「显示全部」才出现。
 */
fun looksLikePrinter(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return n.contains("paperang") || n.contains("miaomiao") || n.contains("喵喵") ||
        n.contains("printer") || n.contains("pp-") || n.startsWith("p1") || n.startsWith("p2")
}
