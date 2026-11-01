package io.github.moevis.paperink.bt

/**
 * 打印机说的是哪一种协议。
 *
 * 以前这是 `kind` 加 `a5Mode` 两个字段，能拼出「ESCPOS 且 a5Mode」这种根本不存在的
 * 组合，打印时还得记住「先看 kind 再看 a5Mode」的优先级。收成一个密封类型之后，
 * 四种机器各是一个值：打印流程按它分派，`when` 少一支编译器就报错。
 *
 * 连上之前是 null（还没认出来），认出来之后整场连接不变。
 */
internal sealed interface DeviceProtocol {

    /** 老机型：0x02 私有帧，分带 CRC32，每包 5 行。 */
    data object Paperang02 : DeviceProtocol

    /** A5 帧族（P3 MAX）：`05` 开头的定长帧，打图前要握手、问纸型与单包上限。 */
    data object A5 : DeviceProtocol

    /** 猫猫机：一行一条命令，打印前后用 lattice 包裹，否则纸不动。 */
    data object Cat : DeviceProtocol

    /** ESC/POS 光栅（Phomemo / Marklife 一族，`ff00`/`ff02` 通道）。 */
    data object EscPos : DeviceProtocol
}
