package io.github.moevis.paperink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.min

/** 打包好的一页：行数、每行字节数，以及 1=黑、MSB 在左 的数据。 */
internal class Raster(
    val rows: Int,
    val bytesPerRow: Int,
    val data: ByteArray,
)

/**
 * 位图化：把排版指令画到离屏画布上，降采样、过阈值，再按行打包成热敏机能吃的字节。
 *
 * 分带画是为了省内存——一张 864×12000 的 ARGB 画布要 40MB，切成 512 点高的带
 * 只占它的二十分之一。跨带的指令会被相邻两条带各画一次，重叠部分自然对上。
 */
internal object Rasterizer {

    /**
     * 超采样倍数。排版在 2 倍分辨率上做，降采样时 13 点小字的边缘才不会出现锯齿断笔。
     * 排版那边必须用同一个数（见 [LayoutBuilder] 的构造参数）。
     */
    const val SS = 2

    /** 一条带多高（超采样后的点数）。 */
    private const val BAND_DOTS = 512

    /**
     * @param items 排版指令
     * @param heightSrc 要画的源高度（超采样点数），已按 [PrintOptions.maxDots] 截断
     */
    fun rasterize(items: List<Laid>, opt: PrintOptions, heightSrc: Int): Raster {
        val w = opt.widthDots
        val bpr = w / 8
        val rows = ceil(heightSrc.toFloat() / SS).toInt()
        val packed = ByteArray(bpr * rows)
        val scale = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = false
            isDither = false
        }
        val black = BooleanArray(w)
        var rowIdx = 0
        var y = 0
        while (y < heightSrc) {
            val bandSrc = min(BAND_DOTS * SS, heightSrc - y)
            val src = Bitmap.createBitmap(opt.widthDots * SS, bandSrc, Bitmap.Config.ARGB_8888)
            val c = Canvas(src)
            c.drawColor(Color.WHITE)
            c.translate(0f, -y.toFloat())
            for (l in items) {
                if (l.y >= y + bandSrc) continue
                if (l.y + l.h <= y) continue
                l.draw(c)
            }
            val bandDots = ceil(bandSrc.toFloat() / SS).toInt()
            val dst = Bitmap.createBitmap(w, bandDots, Bitmap.Config.ARGB_8888)
            val dc = Canvas(dst)
            dc.drawColor(Color.WHITE)
            dc.drawBitmap(src, Rect(0, 0, src.width, bandSrc), Rect(0, 0, w, bandDots), scale)
            src.recycle()

            val px = IntArray(w * bandDots)
            dst.getPixels(px, 0, w, 0, 0, w, bandDots)
            for (r in 0 until bandDots) {
                if (rowIdx >= rows) break
                val base = r * w
                for (x in 0 until w) {
                    val v = px[base + x]
                    // 降采样后仍是 ARGB，取亮度做阈值（阈值越小越黑）
                    val luma = ((v shr 16 and 0xFF) * 77 +
                        (v shr 8 and 0xFF) * 151 + (v and 0xFF) * 28) shr 8
                    black[x] = luma < opt.threshold
                }
                if (opt.dilate) dilate(black)
                packRow(black, packed, rowIdx * bpr, bpr)
                rowIdx++
            }
            dst.recycle()
            y += bandSrc
        }
        return Raster(rows, bpr, packed)
    }

    /** 左右各膨胀一格。热敏纸上的细笔画容易断，加压一档用这个补。 */
    private fun dilate(row: BooleanArray) {
        val src = row.copyOf()
        for (i in row.indices) {
            row[i] = src[i] || (i > 0 && src[i - 1]) || (i < row.size - 1 && src[i + 1])
        }
    }

    /** 一行像素 → 每字节 8 点、MSB 在左。 */
    private fun packRow(row: BooleanArray, out: ByteArray, off: Int, bpr: Int) {
        for (b in 0 until bpr) {
            var v = 0
            for (k in 0 until 8) {
                v = v shl 1
                if (row[b * 8 + k]) v = v or 1
            }
            out[off + b] = v.toByte()
        }
    }
}
