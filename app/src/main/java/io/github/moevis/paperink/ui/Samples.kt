package io.github.moevis.paperink.ui

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.moevis.paperink.R
import java.util.Locale

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
 * 讲义的语言。跟界面语言是两件事：界面是中文的人，也可能想把英文那份打出来。
 *
 * 中文那份放在 `samples/` 下，英文那份放在 `samples/en/` 下，两份都在包里。
 */
internal enum class SampleLang(val tag: String) { Zh("zh"), En("en") }

/** 界面是中文就默认中文讲义，其余一律英文——跟以前的行为对得上。 */
internal fun defaultSampleLang(ctx: Context): SampleLang =
    if (ctx.resources.configuration.locales[0].language == "zh") SampleLang.Zh else SampleLang.En

/**
 * 换成指定语言的资源上下文，只为了取标题和副标题。
 *
 * 直接用 `stringResource` 拿到的是界面语言那份，切了语言按钮标题不动会很怪，
 * 所以绕一层：同一套资源按 `lang` 重新解析一次。
 */
internal fun Context.withLang(lang: SampleLang): Context {
    val cfg = Configuration(resources.configuration)
    cfg.setLocale(Locale(lang.tag))
    return createConfigurationContext(cfg)
}

/**
 * 读内置示例灌进编辑器；读不到就提示一声，别把编辑器清空。
 *
 * 选英文但文件不在（老包）时退回中文那份，总比什么都不显示强。
 */
internal fun loadSample(
    ctx: Context,
    file: String,
    lang: SampleLang,
    onLoad: (String) -> Unit,
) {
    val prefer = if (lang == SampleLang.Zh) file else "en/$file"
    val t = runCatching {
        ctx.assets.open(prefer).use { it.readBytes().toString(Charsets.UTF_8) }
    }.recoverCatching {
        ctx.assets.open(file).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
    if (t.isNullOrBlank()) {
        Toast.makeText(ctx, ctx.getString(R.string.sample_read_failed), Toast.LENGTH_SHORT).show()
    } else {
        onLoad(t)
    }
}
