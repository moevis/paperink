package io.github.moevis.paperink.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.moevis.paperink.R

/**
 * 内置示例：仓库根 `samples/` 下的 md，构建时直接当 asset 打包（见 app/build.gradle.kts），
 * 从编辑器底部的「示例」点一下灌进编辑器。
 *
 * 它们同时也是这个 App 的功能说明：一份讲幂运算，一份讲分数，一份把公式的重活
 * （分式、根号、嵌套、上标里的分式）挨个排一遍——纸上对不对，一眼就看得出。
 *
 * 标题与副标题用资源 id，不写死字符串：中文界面显示中文，英文界面显示英文。
 */
internal data class Sample(@StringRes val title: Int, @StringRes val desc: Int, val file: String)

internal val BUILT_IN = listOf(
    Sample(R.string.sample_powers_title, R.string.sample_powers_desc, "powers.md"),
    Sample(R.string.sample_fractions_title, R.string.sample_fractions_desc, "fractions.md"),
    Sample(R.string.sample_formulas_title, R.string.sample_formulas_desc, "formulas.md"),
)

/**
 * 读内置示例灌进编辑器；读不到就提示一声，别把编辑器清空。
 *
 * 讲义是中文写的，英文界面下换成 `en/` 那份英文的；万一英文文件不在（老包），
 * 退回中文那份，总比什么都不显示强。
 */
internal fun loadSample(ctx: Context, file: String, onLoad: (String) -> Unit) {
    val zh = ctx.resources.configuration.locales[0].language == "zh"
    val path = if (zh) file else "en/$file"
    val t = runCatching {
        ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
    }.recoverCatching {
        ctx.assets.open(file).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
    if (t.isNullOrBlank()) {
        Toast.makeText(ctx, ctx.getString(R.string.sample_read_failed), Toast.LENGTH_SHORT).show()
    } else {
        onLoad(t)
    }
}
