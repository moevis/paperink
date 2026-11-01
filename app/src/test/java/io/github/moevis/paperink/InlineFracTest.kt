package io.github.moevis.paperink

import io.github.moevis.paperink.render.inlineFracAsSlash
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行内分式改斜杠的钉子。
 *
 * 起因：`fractions.md` 里 `$\frac{2}{3}$` 印出来分子分母只有正文的七成大，
 * 而且把所在行的行高从 24 点顶到 34 点。改法是渲染前把行内 `\frac` 换成
 * `a/b`。这里钉住改写规则——尤其是「哪些必须加括号」和「带分数不能粘成
 * 一个数」，这两类错了会静默印出错误的数学表达式，比排版丑严重得多。
 */
class InlineFracTest {

    private fun f(tex: String) = inlineFracAsSlash(tex)

    @Test
    fun simpleFrac() {
        assertEquals("2/3", f("\\frac{2}{3}"))
        assertEquals("1/n", f("\\frac{1}{n}"))
        assertEquals("-3/2", f("-\\frac{3}{2}"))
        assertEquals("a/b", f("\\frac{a}{b}"))
        assertEquals("0/5", f("\\frac{0}{5}"))
    }

    @Test
    fun fractionWithoutFracUntouched() {
        assertEquals("a^b = N", f("a^b = N"))
        assertEquals("\\sqrt{2}", f("\\sqrt{2}"))
        assertEquals("8^{2/3}", f("8^{2/3}"))
    }

    @Test
    fun parensWhenOperatorsPresent() {
        assertEquals("(1+2)/(3+4)", f("\\frac{1+2}{3+4}"))
        assertEquals("(a \\times c)/(b \\times d)", f("\\frac{a \\times c}{b \\times d}"))
        assertEquals("(a \\div k)/(b \\div k)", f("\\frac{a \\div k}{b \\div k}"))
        assertEquals("(-1)/2", f("\\frac{-1}{2}"))
        // 嵌套：内层先成 a/b，外层再括起来，避免 a/b/c 的歧义
        assertEquals("(a/b)/c", f("\\frac{\\frac{a}{b}}{c}"))
    }

    @Test
    fun mixedFracKeepsParens() {
        assertEquals("1/2 + 1/3 = 5/6", f("\\frac{1}{2} + \\frac{1}{3} = \\frac{5}{6}"))
    }

    @Test
    fun mixedNumberKeepsSpace() {
        // 2 又 1/3：绝不能粘成 21/3
        assertEquals("2 1/3", f("2\\frac{1}{3}"))
        assertEquals("2 1/3", f("2 \\frac{1}{3}"))
    }

    @Test
    fun dfracIsRespected() {
        // 显式要行内大分数的写法不拦
        assertEquals("\\dfrac{2}{3}", f("\\dfrac{2}{3}"))
    }

    @Test
    fun nestedGroupsAndEscapes() {
        assertEquals("(x^{2}+1)/n", f("\\frac{x^{2}+1}{n}"))
        // 参数里的字面花括号不能搅乱配对；本身无歧义，不加多余括号
        assertEquals("\\{a\\}/b", f("\\frac{\\{a\\}}{b}"))
    }

    @Test
    fun brokenFracLeftAlone() {
        // 缺参数的坏公式原样返回，交给 TeXFormula 兜底/报错
        assertEquals("\\frac{2}", f("\\frac{2}"))
        assertEquals("\\frac", f("\\frac"))
    }
}
