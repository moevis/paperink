package io.github.moevis.paperink.ui

import android.content.Context
import android.widget.Toast

/**
 * 内置示例：仓库根 `samples/` 下的 md，构建时直接当 asset 打包（见 app/build.gradle.kts），
 * 从编辑器底部的「示例」点一下灌进编辑器。
 *
 * 它们同时也是这个 App 的功能说明：一份讲幂运算，一份讲分数，一份把公式的重活
 * （分式、根号、嵌套、上标里的分式）挨个排一遍——纸上对不对，一眼就看得出。
 */
internal data class Sample(val title: String, val desc: String, val path: String)

internal val BUILT_IN = listOf(
    Sample("幂运算的性质", "三个名字、七条性质、零次与负指数", "powers.md"),
    Sample("分数的计算", "倒数、乘除、加减、约分、带分数", "fractions.md"),
    Sample("公式排版自检", "分式、根式、上下标、嵌套公式", "formulas.md"),
)

/** 读内置示例灌进编辑器；读不到就提示一声，别把编辑器清空。 */
internal fun loadSample(ctx: Context, path: String, onLoad: (String) -> Unit) {
    val t = runCatching {
        ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
    if (t.isNullOrBlank()) Toast.makeText(ctx, "读不到这份资料", Toast.LENGTH_SHORT).show()
    else onLoad(t)
}
