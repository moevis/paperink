package io.github.moevis.paperink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import io.github.moevis.paperink.md.Align
import io.github.moevis.paperink.md.Block
import io.github.moevis.paperink.md.Doc
import io.github.moevis.paperink.md.Inl
import io.github.moevis.paperink.md.plain
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 打印参数。宽度单位是「点」= 热敏头像素。
 * P3 MAX 是三档纸自适应的 300dpi 机型：57mm→576、68mm→720、79mm→864，
 * 具体取哪档由 `PrinterClient.paperSizes` 决定，偏移也走那张表。
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

/**
 * Markdown → 热敏位图。
 *
 * 先在 2 倍分辨率上排版（supersample），再降采样 + 阈值，这样 13 点的小字
 * 边缘不会出现锯齿状断笔；不支持灰度，热敏只有黑/白。
 */
object PrintRenderer {

    private const val SS = 2
    private const val BAND_DOTS = 512

    fun render(doc: Doc, opt: PrintOptions): PrintResult {
        val b = Builder(opt.widthDots * SS, SS, opt)
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

    // ---------------------------------------------------------------- layout

    private class Laid(val y: Int, val h: Int, val draw: (Canvas) -> Unit)

    private class Builder(private val w: Int, private val s: Int, private val opt: PrintOptions) {
        private val m = opt.marginDots * s
        private val contentW = w - 2 * m

        /**
         * 版式缩放系数。下面所有行距、块间距的常数都是按 203dpi 小票机（正文 16.5 点）
         * 调的；P3 MAX 是 300dpi，同样的点数只有 2/3 的物理高度，所以统一乘这个系数放大。
         * 字号不乘它——字号一律写成「正文的倍数」（见 [em]），这样调字号时整套比例不乱。
         */
        private val k = opt.bodySize / 16.5f

        /** 按正文倍率取字号，单位是打印点。 */
        private fun em(mul: Float) = opt.bodySize * mul

        val items = ArrayList<Laid>()
        var height = 0
            private set

        /** 块间距：跟着版式缩放。 */
        private fun space(dots: Float) {
            height += (dots * k * s).roundToInt()
        }

        /** 绝对间距：边距这类物理量，不参与版式缩放。 */
        private fun spaceAbs(dots: Float) {
            height += (dots * s).roundToInt()
        }

        private fun add(h: Int, draw: (Canvas) -> Unit) {
            items += Laid(height, h, draw)
            height += h
        }

        private fun paint(
            size: Float,
            bold: Boolean = false,
            mono: Boolean = false,
        ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * s
            color = Color.BLACK
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.SANS_SERIF
            }
            isSubpixelText = true
        }

        private fun layout(
            cs: CharSequence,
            p: TextPaint,
            width: Int,
            firstIndent: Int = 0,
            restIndent: Int = 0,
            align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
            lh: Float = 1.4f,
        ): StaticLayout {
            // 悬挂缩进只能靠 LeadingMarginSpan；Builder.setIndents 要逐行数组，这里用不上
            val text: CharSequence = if (firstIndent > 0 || restIndent > 0) {
                val sb = if (cs is android.text.Spannable) cs else SpannableStringBuilder(cs)
                sb.setSpan(
                    LeadingMarginSpan.Standard(firstIndent, restIndent),
                    0, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb
            } else {
                cs
            }
            return StaticLayout.Builder
                .obtain(text, 0, text.length, p, max(1, width))
                .setIncludePad(false)
                // lh 是这一块自带的行高比（正文/标题/代码各不同），乘上用户的
                // 行距档位才是最终值。下限 1.02 是防手滑：行高比掉到 1 以下
                // 会让上下行叠在一起，字直接糊掉。
                .setLineSpacing(p.textSize * (max(1.02f, lh * opt.lineEm) - 1f), 1f)
                .setAlignment(align)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        }

        private fun drawLayout(l: StaticLayout, x: Int, extra: Int = 0) {
            val yy = height
            add(l.height + extra) { c ->
                c.save()
                c.translate(x.toFloat(), yy.toFloat())
                l.draw(c)
                c.restore()
            }
        }

        fun build(doc: Doc) {
            spaceAbs(opt.marginDots.toFloat())
            for (blk in doc.blocks) {
                when (blk) {
                    is Block.Heading -> heading(blk)
                    is Block.Para -> {
                        val p = paint(opt.bodySize)
                        drawLayout(layout(span(blk.inl, opt.bodySize, forceBold = false), p, contentW), m)
                        space(7f)
                    }
                    is Block.Item -> item(blk)
                    is Block.Quote -> quote(blk)
                    is Block.Code -> code(blk)
                    is Block.Math -> mathBlock(blk)
                    is Block.Rule -> rule()
                    is Block.Table -> table(blk)
                }
            }
            spaceAbs(opt.marginDots.toFloat())
        }

        private fun heading(h: Block.Heading) {
            // 倍率沿用 203dpi 时代的手调比例（26/22/19.5/17.5 对正文 16.5）
            val (size, before, after) = when (h.level) {
                1 -> Triple(em(1.58f), 6f, 8f)
                2 -> Triple(em(1.33f), 16f, 7f)
                3 -> Triple(em(1.18f), 14f, 5f)
                else -> Triple(em(1.06f), 12f, 4f)
            }
            space(before)
            val p = paint(size, bold = true)
            val l = layout(span(h.inl, size, forceBold = true), p, contentW, lh = 1.3f)
            val yTop = height
            add(l.height) { c ->
                c.save()
                c.translate(m.toFloat(), yTop.toFloat())
                l.draw(c)
                c.restore()
            }
            // 只有一级标题画通栏线；二级及以下曾经画 1/3 宽的短线，长度跟标题
            // 文字对不上、每节都挂一条，纯噪声，已去掉。
            if (h.level == 1) {
                space(5f)
                val yy = height
                add(max(1, s)) { c ->
                    val paint = Paint().apply { color = Color.BLACK; strokeWidth = 1.6f * k * s }
                    c.drawLine(
                        m.toFloat(), yy.toFloat() + 0.8f * s,
                        (m + contentW).toFloat(), yy.toFloat() + 0.8f * s, paint
                    )
                }
            }
            space(after)
        }

        private fun item(it: Block.Item) {
            val indent = m + it.depth * (15 * s)
            val p = paint(opt.bodySize)
            // 符号宽度按实际字宽算，否则「12.」这种两位数会被文字压住
            val markerW = if (it.marker == null) {
                10 * s
            } else {
                max(14 * s, (p.measureText(it.marker) + 4 * s).roundToInt())
            }
            val l = layout(
                span(it.inl, opt.bodySize), p,
                contentW - (indent - m), markerW, markerW
            )
            val yy = height
            add(l.height) { c ->
                c.save()
                c.translate(indent.toFloat(), yy.toFloat())
                if (it.marker != null) {
                    // 与布局首行基线对齐（includePad=false 时首行 top = baseline + fm.top）
                    c.drawText(it.marker, 1f * s, -p.fontMetrics.top, p)
                }
                l.draw(c)
                c.restore()
            }
            space(3.5f)
        }

        private fun quote(q: Block.Quote) {
            val bar = max(2, 2 * s)
            val x = m + s
            val inner = contentW - 12 * s
            val p = paint(opt.bodySize)
            val l = layout(span(q.inl, opt.bodySize), p, inner)
            val yTop = height
            add(l.height + 2) { c ->
                val pt = Paint().apply { color = Color.BLACK }
                c.drawRect(
                    x.toFloat(), yTop.toFloat() + 1f,
                    (x + bar).toFloat(), (yTop + l.height - 1f), pt
                )
                c.save()
                c.translate((x + 9 * s).toFloat(), yTop.toFloat())
                l.draw(c)
                c.restore()
            }
            space(7f)
        }

        private fun code(c: Block.Code) {
            space(4f)
            val size = em(0.84f)
            val p = paint(size, mono = true)
            val inner = contentW - 11 * s
            val text = SpannableStringBuilder(c.text.replace("\t", "    "))
            val l = layout(text, p, inner, lh = 1.3f)
            val yTop = height
            val bar = max(2, 2 * s)
            add(l.height + 4) { canv ->
                val pt = Paint().apply { color = Color.BLACK }
                canv.drawRect(
                    m.toFloat(), yTop.toFloat() + 1f,
                    (m + bar).toFloat(), (yTop + l.height - 1f), pt
                )
                canv.save()
                canv.translate((m + 9 * s).toFloat(), yTop.toFloat() + 1f)
                l.draw(canv)
                canv.restore()
            }
            space(7f)
        }

    /**
     * 独立公式：jlatexmath 排版（分式真叠、根号带横线、上下标按引擎的样式降级），
     * 轴线对齐由引擎的盒模型保证，不再手推。
     * 比正文略大一点，居中；宽度兜不住就整体等比缩，不让它出血。
     */
    private fun mathBlock(math: Block.Math) {
        space(8f)
        // 公式字号在预览页「公式」里三档可调（opt.mathEm），必须与 bodySize **线性**：
        // 曾经写成 em(1.05) * k * s，而 k = bodySize/16.5 本是笔画宽度单位，乘进来就成了
        // bodySize² —— 「大」档数学图标胀到正文 2 倍。
        // 另外：1.6546 是「大」档的老值，它让「中」正文（26 点）下还原成 86.04 内部像素，
        // 那一档等号两横等粗已在模拟器与真机逐像素验证过。改这三档数字后先看一眼等号：
        // 差 0.24 px 就够把相位翻掉，两横变一粗一细。
        val px = em(opt.mathEm) * s
        val icon = mathIcon(math.tex, px, display = true)
        if (icon == null) {
            // 解析失败别让整张纸打不出来：这段公式按纯文本排，至少看得见写了什么
            val p = paint(em(0.92f))
            drawLayout(layout(SpannableStringBuilder(math.tex), p, contentW), m)
            space(6f)
            return
        }
        val w = icon.iconWidth.toFloat()
        val h = icon.iconHeight.toFloat()
        val avail = contentW.toFloat()
        val sx = if (w > avail && w > 0f) avail / w else 1f
        val drawW = w * sx
        val x = m + (avail - drawW) / 2f
        val hh = ceil(h * sx).toInt()
        val yTop = height
        add(hh + 4) { c ->
            c.save()
            c.translate(x, yTop.toFloat() + 2f + (hh - h * sx) / 2f)
            if (sx < 1f) c.scale(sx, sx)
            paintIcon(c, icon, 0f, 0f)
            c.restore()
        }
        space(11f)
    }

        private fun rule() {
            space(6f)
            val yy = height
            val pt = Paint().apply { color = Color.BLACK; strokeWidth = 1.4f * k * s }
            add(max(1, s)) { c ->
                c.drawLine(m.toFloat(), yy.toFloat(), (w - m).toFloat(), yy.toFloat(), pt)
            }
            space(8f)
        }

        private fun table(t: Block.Table) {
            space(4f)
            val n = max(1, max(t.head.size, t.rows.maxOfOrNull { it.size } ?: 0))
            val padX = 4 * s
            val bodyP = paint(em(0.82f))
            val headP = paint(em(0.82f), bold = true)

            // 各列自然宽度 → 超宽就按比例压缩，再按最小份额兜底
            val nat = IntArray(n)
            fun measure(cells: List<List<Inl>>, p: TextPaint) {
                for (i in 0 until min(n, cells.size)) {
                    val tw = p.measureText(cells[i].plain()).roundToInt() + padX * 2
                    if (tw > nat[i]) nat[i] = tw
                }
            }
            measure(t.head, headP)
            t.rows.forEach { measure(it, bodyP) }
            val sum = nat.sum().coerceAtLeast(1)
            val colW = IntArray(n)
            if (sum <= contentW) {
                // 表格比较窄：给足自然宽度，剩下的补到最后一列前，保持左对齐观感
                for (i in 0 until n) colW[i] = nat[i]
                val extra = contentW - sum
                if (n > 0) colW[n - 1] += extra
            } else {
                var acc = 0
                for (i in 0 until n) {
                    colW[i] = max((contentW * nat[i].toFloat() / sum).roundToInt(), 34 * s)
                    acc += colW[i]
                }
                var diff = acc - contentW
                var i = n - 1
                while (diff > 0 && i >= 0) {
                    val take = min(diff, max(0, colW[i] - 34 * s))
                    colW[i] -= take
                    diff -= take
                    i--
                }
                if (colW.sum() > contentW && colW.sum() > 0) {
                    // 列太多兜不住最窄宽度时，退回纯比例，宁可挤不让表格出血
                    val s2 = colW.sum()
                    var total2 = 0
                    for (k in 0 until n) {
                        colW[k] = max(1, (contentW * colW[k].toFloat() / s2).roundToInt())
                        total2 += colW[k]
                    }
                    if (total2 < contentW) colW[n - 1] += contentW - total2
                }
            }

            val rowsAll = ArrayList<List<List<Inl>>>()
            rowsAll.add(t.head)
            rowsAll.addAll(t.rows)
            val laid = ArrayList<List<StaticLayout?>>()
            val heights = IntArray(rowsAll.size)
            for ((ri, cells) in rowsAll.withIndex()) {
                val ls = ArrayList<StaticLayout?>()
                var h = 0
                for (ci in 0 until n) {
                    val inl = cells.getOrNull(ci) ?: emptyList()
                    if (inl.isEmpty()) {
                        ls.add(null)
                        continue
                    }
                    val p = if (ri == 0) headP else bodyP
                    val align = when (t.aligns.getOrNull(ci) ?: Align.LEFT) {
                        Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                        Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                        Align.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    }
                    val l = layout(
                        span(inl, 13.6f, forceBold = ri == 0),
                        p, max(1, colW[ci] - padX * 2),
                        align = align, lh = 1.28f
                    )
                    ls.add(l)
                    h = max(h, l.height)
                }
                laid.add(ls)
                heights[ri] = h + 5 * s
            }

            val totalH = heights.sum() + 2 * s
            val yTop = height
            val lineP = Paint().apply { color = Color.BLACK; strokeWidth = 1.3f * s }
            val boxP = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 1.3f * s
            }
            val xs = IntArray(n + 1)
            var acc = m
            for (i in 0 until n) {
                xs[i] = acc
                acc += colW[i]
            }
            xs[n] = acc
            val tableW = colW.sum()

            add(totalH) { c ->
                var cy = yTop + 1f
                val half = 0.65f * s
                c.drawRect(
                    m + half, yTop + half,
                    (m + tableW) - half, (yTop + totalH - 1) - half, boxP
                )
                for ((ri, ls) in laid.withIndex()) {
                    if (ri > 0) {
                        c.drawLine(m.toFloat(), cy, (m + tableW).toFloat(), cy, lineP)
                    }
                    for (ci in 0 until n) {
                        val l = ls.getOrNull(ci) ?: continue
                        c.save()
                        c.translate((xs[ci] + padX).toFloat(), cy + 1.5f * s)
                        l.draw(c)
                        c.restore()
                    }
                    cy += heights[ri]
                }
            }
            space(9f)
        }

        // ------------------------------------------------------------ inline

        private fun span(inl: List<Inl>, size: Float, forceBold: Boolean = false): CharSequence {
            val sb = SpannableStringBuilder()
            appendInto(sb, inl, size, forceBold)
            return sb
        }

        private fun appendInto(sb: SpannableStringBuilder, inl: List<Inl>, size: Float, bold: Boolean) {
            for (i in inl) {
                val start = sb.length
                when (i) {
                    is Inl.T -> sb.append(i.s)
                    is Inl.B -> appendInto(sb, i.c, size, true)
                    is Inl.I -> {
                        appendInto(sb, i.c, size, bold)
                        sb.setSpan(
                            StyleSpan(Typeface.ITALIC), start, sb.length,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    is Inl.S -> {
                        appendInto(sb, i.c, size, bold)
                        sb.setSpan(
                            StrikethroughSpan(), start, sb.length,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    is Inl.C -> {
                        sb.append(i.s)
                        sb.setSpan(
                            monoSpan(), start, sb.length,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        sb.setSpan(
                            RelativeSizeSpan(0.92f), start, sb.length,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    is Inl.L -> appendInto(sb, i.c, size, bold)
                    is Inl.M -> {
                        // 公式排版外包给 jlatexmath：MathSpan 把公式基线对到文本
                        // 基线（跟 TeX 的行内公式一致）；解析失败退回原文，至少看得见。
                        val icon = mathIcon(i.tex, size * s, display = false)
                        if (icon == null) {
                            sb.append(i.tex)
                        } else {
                            val s0 = sb.length
                            // 占位**不能**用空格。StaticLayout 会把行尾空格当可裁的
                            // 尾部空白，公式一旦正好落在折行点，那一格连同公式一起被
                            // 裁掉——打印稿上「……说法——$a^b = N$ 等价于」整条公式凭空
                            // 消失过，同一句话换个长度折行位置就正常，极难肉眼定位。
                            // 改用 TeX 原文占位：不是空白就不会被当作尾部空白裁掉，
                            // 而 ReplacementSpan 本身保证折行不会切进公式内部。
                            sb.append(i.tex)
                            sb.setSpan(
                                MathSpan(icon), s0, sb.length,
                                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                if (bold) {
                    sb.setSpan(
                        StyleSpan(Typeface.BOLD), start, sb.length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        private fun monoSpan(): Any =
            if (Build.VERSION.SDK_INT >= 28) TypefaceSpan(Typeface.MONOSPACE)
            else @Suppress("DEPRECATION") TypefaceSpan("monospace")
    }
}
