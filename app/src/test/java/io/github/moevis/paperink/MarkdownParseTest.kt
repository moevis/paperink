package io.github.moevis.paperink

import io.github.moevis.paperink.md.Block
import io.github.moevis.paperink.md.Inl
import io.github.moevis.paperink.md.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析层的回归钉子。
 *
 * 起因是打印稿上「例 1」没有加粗，而「底数」「1. 负号…」都正常——
 * 这类「某几处 Markdown 标记被吞掉」的问题肉眼只能靠一张张出纸发现，
 * 所以在解析层钉住：同样的写法必须解析出同样的结构。
 */
class MarkdownParseTest {

    private fun para(md: String): List<Inl> {
        val doc = MarkdownParser.parse(md)
        val b = doc.blocks.first()
        return when (b) {
            is Block.Para -> b.inl
            is Block.Item -> b.inl
            is Block.Heading -> b.inl
            else -> error("不是文字块：$b")
        }
    }

    /** 递归找出所有 Inl.B（加粗）里的文字。 */
    private fun bolds(l: List<Inl>, out: MutableList<String> = ArrayList()): List<String> {
        for (i in l) {
            if (i is Inl.B) {
                out += i.c.joinToString("") { c ->
                    when (c) {
                        is Inl.T -> c.s
                        is Inl.B -> c.c.filterIsInstance<Inl.T>().joinToString("") { it.s }
                        else -> ""
                    }
                }
                bolds(i.c, out)
            }
        }
        return out
    }

    @Test
    fun boldSurvivesCommonShapes() {
        // 每种写法单独一段，避免互相干扰
        val cases = listOf(
            "**例 1** 化简 x",
            "**例1** 化简 x",
            "**例 A** 化简 x",
            "**底数** a",
            "**1. 负号在括号外**",
            "**4. \$a^0 = 1\$ 有前提**",
            "**例 2** 计算 \$(2x^2y)^3\$",
        )
        for (c in cases) {
            val b = bolds(para(c))
            assertTrue("这段没解析出加粗：$c", b.isNotEmpty())
        }
    }

    @Test
    fun inlineMathBeforeDashSurvives() {
        // 打印稿里「——\$a^b = N\$ 等价于…」的第一个公式整段消失过
        val inl = para("真数是对数里的说法——\$a^b = N\$ 等价于 \$b = \\log_a N\$。")
        val texs = inl.filterIsInstance<Inl.M>().map { it.tex }
        assertEquals(2, texs.size)
        assertEquals("a^b = N", texs[0])
    }
}
