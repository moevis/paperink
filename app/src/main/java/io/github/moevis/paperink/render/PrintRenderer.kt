package io.github.moevis.paperink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import io.github.moevis.paperink.md.Doc
import kotlin.math.ceil
import kotlin.math.min

/**
 * Markdown → 热敏位图。
 *
 * 先在 2 倍分辨率上排版（supersample），再降采样 + 阈值，这样 13 点的小字
 * 边缘不会出现锯齿状断笔；不支持灰度，热敏只有黑/白。
 *
 * 三步：
 *  1. [LayoutBuilder] 排出绘制指令（只管位置，不碰像素）；
 *  2. 分带把指令画到离屏画布上，降采样成目标宽度，亮度过阈值就是黑；
 *  3. 按行打包成 1=黑、MSB 在左的字节流，顺手用同一份数据回填预览图——
 *     预览必须跟真正发出的字节完全一致，否则「看着对、打出来不对」没法查。
 */
object PrintRenderer {

    private const val SS = 2
    private const val BAND_DOTS = 512

    fun render(doc: Doc, opt: PrintOptions): PrintResult {
        val b = LayoutBuilder(opt.widthDots * SS, SS, opt)
        b.build(doc)
        val totalSrc = b.height
        val maxSrc = opt.maxDots * SS
        val truncated = totalSrc > maxSrc
        val srcH = if (truncated) maxSrc else totalSrc
        val rows = ceil(srcH.toFloat() / SS).toInt()
        val w = opt.widthDots
        val bpr = w / 8

        val out = Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888)
        val packed = ByteArray(bpr * rows)
        val dstCanvas = Canvas(out)
        val scalePaint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = false
            isDither = false
        }

        val black = BooleanArray(w)
        var rowIdx = 0
        var y = 0
        while (y < srcH) {
            val bandSrc = min(BAND_DOTS * SS, srcH - y)
            val src = Bitmap.createBitmap(opt.widthDots * SS, bandSrc, Bitmap.Config.ARGB_8888)
            val c = Canvas(src)
            c.drawColor(Color.WHITE)
            c.translate(0f, -y.toFloat())
            for (l in b.items) {
                if (l.y >= y + bandSrc) continue
                if (l.y + l.h <= y) continue
                l.draw(c)
            }
            val bandDots = ceil(bandSrc.toFloat() / SS).toInt()
            val dst = Bitmap.createBitmap(w, bandDots, Bitmap.Config.ARGB_8888)
            val dc = Canvas(dst)
            dc.drawColor(Color.WHITE)
            dc.drawBitmap(src, Rect(0, 0, src.width, bandSrc), Rect(0, 0, w, bandDots), scalePaint)
            src.recycle()

            val px = IntArray(w * bandDots)
            dst.getPixels(px, 0, w, 0, 0, w, bandDots)

            for (r in 0 until bandDots) {
                if (rowIdx >= rows) break
                val base = r * w
                for (x in 0 until w) {
                    val v = px[base + x]
                    // 2x 降采样后仍是 ARGB，取亮度做阈值（阈值越小越黑）
                    val luma = (((v shr 16 and 0xFF) * 77 + (v shr 8 and 0xFF) * 151 + (v and 0xFF) * 28) shr 8)
                    black[x] = luma < opt.threshold
                }
                if (opt.dilate) dilateRow(black)
                packRow(black, packed, rowIdx * bpr, bpr)
                rowIdx++
            }
            dst.recycle()
            y += bandSrc
        }

        dstCanvas.drawColor(Color.WHITE)
        // 把打包结果回填成预览图（保持与真正发出的数据完全一致）
        val rowPixels = IntArray(w)
        for (r in 0 until rows) {
            val o = r * bpr
            for (x in 0 until w) {
                val byte = packed[o + (x ushr 3)].toInt()
                val on = (byte shr (7 - (x and 7))) and 1 == 1
                rowPixels[x] = if (on) Color.BLACK else Color.WHITE
            }
            out.setPixels(rowPixels, 0, w, 0, r, w, 1)
        }

        return PrintResult(out, rows, bpr, packed, truncated, rows * 25.4f / opt.dpi)
    }

    private fun dilateRow(row: BooleanArray) {
        val src = row.copyOf()
        for (i in row.indices) {
            row[i] = src[i] || (i > 0 && src[i - 1]) || (i < row.size - 1 && src[i + 1])
        }
    }

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
