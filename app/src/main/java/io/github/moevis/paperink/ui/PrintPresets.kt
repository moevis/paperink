package io.github.moevis.paperink.ui

/**
 * 参数栏里的三档取值。数值喂给排版器，标签只负责让人看懂。
 *
 * 纸宽对得上 P3 MAX 固件自报的规格：300dpi 热敏头，57mm→576 点、68mm→720 点、79mm→864 点。
 * 毫米是纸的买法，点数是打印头的实际列数——表格挤不挤得下、条码扫不扫得出，
 * 都由点数决定，不由毫米决定。
 */
internal object Presets {

    /** 正文字号，单位是打印点。300dpi 下 26 点约 2.2mm，接近小票机的正常字高。 */
    val bodySizes = floatArrayOf(22f, 26f, 31f)
    val sizeLabels = arrayOf("小", "中", "大")

    val widths = intArrayOf(576, 720, 864)
    val widthLabels = arrayOf("57mm", "68mm", "79mm")

    /**
     * 独行公式字号（正文 em 的倍数）。行内公式不跟着变，始终跟正文同号。
     * 1.6546 是老版本写死的值，「大」档就是它——等号两横等粗在那儿验过。
     */
    val mathEms = floatArrayOf(1.30f, 1.50f, 1.6546f)

    /** 行距系数，乘在各块自带的行高比上。 */
    val lineEms = floatArrayOf(0.88f, 1f, 1.12f)
    val lineLabels = arrayOf("紧", "中", "松")
}
