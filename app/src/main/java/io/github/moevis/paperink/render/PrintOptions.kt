package io.github.moevis.paperink.render

import android.graphics.Bitmap

/**
 * 打印参数。宽度单位是「点」= 热敏头像素。
 * P3 MAX 是三档纸自适应的 300dpi 机型：57mm→576、68mm→720、79mm→864，
 * 具体取哪档由打印机自报的纸型表（`PrinterSession.paperSizes`）决定，偏移也走那张表。
 */
data class PrintOptions(
    val widthDots: Int = 576,
    val marginDots: Int = 18,
    /** 正文字号，单位是打印点。26 点在 300dpi 上约 2.2mm，一行排得下 30 来个汉字。 */
    val bodySize: Float = 26f,
    /**
     * 独行公式的字号，单位是**正文 em 的倍数**（行内公式不受它影响，始终跟正文同字号）。
     * 单独可调是因为公式一放大就顶掉两行正文的观感，而正文该多大是另一回事。
     */
    val mathEm: Float = 1.45f,
    /**
     * 行距系数，乘在每个块自带的行高比上（正文 1.4、标题/代码 1.3、表格 1.28）。
     * 只动「行与行之间」，段间距单独算，所以拉紧行距不会让段落挤成一坨。
     */
    val lineEm: Float = 1f,
    val threshold: Int = 148,
    val dilate: Boolean = false,
    val maxDots: Int = 12000,
    /** 热敏头分辨率，只用来把行数换算成纸长显示。P3 MAX 是 300dpi。 */
    val dpi: Int = 300,
)

class PrintResult(
    /** 1:1 的二值图，直接拿来做「打印预览」。 */
    val bitmap: Bitmap,
    val rows: Int,
    val bytesPerRow: Int,
    /** 已按行打包好的位图数据（1 = 黑，MSB 在左），可直接喂给协议层。 */
    val packed: ByteArray,
    val truncated: Boolean,
    /** 这张纸实际有多长，毫米。 */
    val lengthMm: Float,
)
