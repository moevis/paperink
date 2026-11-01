package io.github.moevis.paperink.render

import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.moevis.paperink.md.Align
import io.github.moevis.paperink.md.Block
import io.github.moevis.paperink.md.Inl
import io.github.moevis.paperink.md.plain
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 表格排版。
 *
 * 表格是所有块里最讲究的一个：先把每列的「自然宽度」量出来，再按内容宽压缩；
 * 压不下就退回纯比例，宁可挤也不能让表格出血（热敏纸宽度是死的）。
 * 单元格里的文字各自断行，行高取该行最高的那格。
 */
internal fun LayoutBuilder.table(t: Block.Table) {
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
                    inlineSpan(inl, 13.6f, s, forceBold = ri == 0),
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
