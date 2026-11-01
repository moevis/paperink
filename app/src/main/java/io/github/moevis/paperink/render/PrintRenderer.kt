package io.github.moevis.paperink.render

import android.graphics.Bitmap
import android.graphics.Color
import io.github.moevis.paperink.md.Doc
import kotlin.math.min

/**
 * Markdown → 热敏位图。
 *
 * 三步，各归一处：
 *  1. [LayoutBuilder] 排出绘制指令（只管位置，不碰像素）；
 *  2. [Rasterizer] 分带画出来、降采样、过阈值、按行打包；
 *  3. 这里把打包结果回填成预览图——预览必须跟真正发出的字节完全一致，
 *     否则「看着对、打出来不对」没法查。
 */
object PrintRenderer {

    fun render(doc: Doc, opt: PrintOptions): PrintResult {
        val b = LayoutBuilder(opt.widthDots * Rasterizer.SS, Rasterizer.SS, opt)
        b.build(doc)

        val maxSrc = opt.maxDots * Rasterizer.SS
        val truncated = b.height > maxSrc
        val raster = Rasterizer.rasterize(b.items, opt, min(b.height, maxSrc))

        return PrintResult(
            bitmap = preview(raster, opt.widthDots),
            rows = raster.rows,
            bytesPerRow = raster.bytesPerRow,
            packed = raster.data,
            truncated = truncated,
            lengthMm = raster.rows * 25.4f / opt.dpi,
        )
    }

    /**
     * 把打包好的字节还原成一张黑白图。
     *
     * 这里刻意不解码原始画布，而是从 [Raster.data] 逐位还原：预览与真正发出去的
     * 字节是同一份数据，中间不存在「另一条渲染路径」可以走歪。
     */
    private fun preview(raster: Raster, w: Int): Bitmap {
        val out = Bitmap.createBitmap(w, raster.rows, Bitmap.Config.ARGB_8888)
        val px = IntArray(w)
        for (row in 0 until raster.rows) {
            val base = row * raster.bytesPerRow
            for (x in 0 until w) {
                val byte = raster.data[base + (x ushr 3)].toInt()
                val on = (byte shr (7 - (x and 7))) and 1 == 1
                px[x] = if (on) Color.BLACK else Color.WHITE
            }
            out.setPixels(px, 0, w, 0, row, w, 1)
        }
        return out
    }
}
