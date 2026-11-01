package io.github.moevis.paperink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Bold
import com.composables.icons.lucide.Droplet
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Rows3
import com.composables.icons.lucide.Ruler
import com.composables.icons.lucide.Sigma
import com.composables.icons.lucide.Type
import kotlin.math.roundToInt

/**
 * 底部参数栏。每格只显示「标签 + 当前值」，点一下在格子上方原地浮出选项，
 * 选完（或点别处）就收回——参数不再常驻吃掉半个屏幕。
 *
 * 它本身也是浮在预览上的一块圆角卡（不通栏贴边），所以稿子能一直滚到它身后。
 */
@Composable
internal fun ParamBar(
    modifier: Modifier = Modifier,
    sizeIndex: Int, onSize: (Int) -> Unit,
    widthIndex: Int, onWidth: (Int) -> Unit,
    mathIndex: Int, onMath: (Int) -> Unit,
    lineIndex: Int, onLine: (Int) -> Unit,
    threshold: Int, onThreshold: (Int) -> Unit,
    dilate: Boolean, onDilate: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceColorAtElevation(4.dp),
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ParamCell("字号", Presets.sizeLabels[sizeIndex], Lucide.Type, Modifier.weight(1f)) { done ->
                ChoiceRow(Presets.sizeLabels, sizeIndex) { onSize(it); done() }
            }
            ParamCell("纸宽", Presets.widthLabels[widthIndex], Lucide.Ruler, Modifier.weight(1f)) { done ->
                // 毫米是纸的买法，点数是喂给打印头的实际列数——换纸为什么整篇会重排，
                // 靠这行小字才解释得通。跟着选项一起摆，比在顶部常驻一句"864 点"有用。
                ChoiceRow(
                    labels = Presets.widthLabels,
                    selected = widthIndex,
                    fontSize = 12.sp,
                    subLabels = Array(Presets.widths.size) { "${Presets.widths[it]} 点" },
                ) { onWidth(it); done() }
            }
            // 只影响独行的 $$…$$；行内公式跟正文同号，不跟着变
            ParamCell("公式", Presets.sizeLabels[mathIndex], Lucide.Sigma, Modifier.weight(1f)) { done ->
                ChoiceRow(Presets.sizeLabels, mathIndex) { onMath(it); done() }
            }
            ParamCell("行距", Presets.lineLabels[lineIndex], Lucide.Rows3, Modifier.weight(1f)) { done ->
                ChoiceRow(Presets.lineLabels, lineIndex) { onLine(it); done() }
            }
            ParamCell("浓度", "$threshold", Lucide.Droplet, Modifier.weight(1f)) {
                // 竖着摆：面板要窄不要宽，横滑块会把弹出框撑成一条长条。
                // 数值压在滑块**顶上**（原来在右边）——滑块本身就是「上浓下淡」的刻度，
                // 数值贴在它头上，眼睛不用在横向来回扫，拖动时也只盯一个点。
                Column(
                    // 数字挪到顶上之后卡片只剩「一个数字 + 一根轨道」，别让它瘦成一条。
                    // 左右各 14dp 把宽度撑到约 72dp，和高度 158dp 大约是 1:2.2。
                    Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "$threshold",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    VerticalSlider(
                        value = threshold.toFloat(),
                        range = 70f..230f,
                        onValueChange = { onThreshold(it.roundToInt()) },
                        height = 124.dp,
                        width = 44.dp,
                    )
                }
            }
            // 加粗是开关，点一下直接切，不弹面板
            ToggleCell(
                "加粗", if (dilate) "开" else "关",
                on = dilate,
                modifier = Modifier.weight(1f),
                icon = Lucide.Bold,
            ) { onDilate(!dilate) }
        }
    }
}

/** 参数栏里带浮层的一格：整格可点，浮层锚在「字」上，所以弹出位置就在这一格正上方。 */
@Composable
private fun ParamCell(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    panel: @Composable (done: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val provider = remember(density) {
        AboveAnchorPositionProvider(
            gapPx = with(density) { 8.dp.roundToPx() },
            marginPx = with(density) { 10.dp.roundToPx() },
        )
    }
    CellBox(label, value, active = open, modifier = modifier, icon = icon, onClick = { open = true }) {
        if (open) {
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                PanelCard { panel { open = false } }
            }
        }
    }
}

/** 开关格（加粗这种）：点一下直接切，没有浮层。 */
@Composable
private fun ToggleCell(
    label: String,
    value: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    CellBox(label, value, active = on, modifier = modifier, icon = icon, onClick = onClick)
}

/**
 * 一格的皮：上半行「图标 + 参数名」、下半行当前值，整格可点（手指不用精确戳到字）。
 * [overlay] 里放浮层——它挂在「内容大小」的那层上，锚点才不会跟着整格一起变宽。
 *
 * 图标**不替换**文字，只摆在文字左边当视觉锚点：六格并排时眼睛先抓到字形轮廓，
 * 比逐格读两个汉字快。图标跟参数名同色（都在暗一档），当前值才是亮的那个。
 */
@Composable
private fun CellBox(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // 六格并排横向没有余量：系统字体调到 1.3 倍，13sp 的「79mm」就会撑出格子被裁成「79m」。
    // 这里把放大系数封顶在 1.15——工具条的可读性让步于「别把当前值截掉」，正文和弹出的
    // 选项面板仍完整跟随系统设置。sp 会再乘一次 fontScale，所以先把它除回去。
    val fontScale = LocalDensity.current.fontScale
    val k = fontScale.coerceAtMost(1.15f) / fontScale
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box {
            Column(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(2.5.dp))
                    }
                    Text(
                        label,
                        fontSize = (10f * k).sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    value,
                    fontSize = (13f * k).sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) cs.primary else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            overlay()
        }
    }
}

/**
 * 面板里的一排选项：选中的铺主色底，其余纯文字，没有第二层底色。
 *
 * [subLabels] 是每项下面的那行小字，目前只有纸宽用：毫米和点数一上一下摆在一起。
 * 不写成「面板底下单独一行说明当前值」——面板点完就收，那行字等再打开才看得到，
 * 三项的点数摊开才能互相比较。
 */
@Composable
private fun ChoiceRow(
    labels: Array<String>,
    selected: Int,
    fontSize: TextUnit = 13.sp,
    subLabels: Array<String>? = null,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) cs.primary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 15.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    l,
                    fontSize = fontSize,
                    // 两行文字都得自己定行高：不写就继承了正文的 24sp，行距比字高还大，
                    // 「57mm」和「576 点」看着像两句不相干的话。
                    lineHeight = fontSize * 1.25f,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) cs.onPrimary else cs.onSurface,
                )
                subLabels?.getOrNull(i)?.let { sub ->
                    Spacer(Modifier.height(1.dp))
                    Text(
                        sub,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        color = if (on) cs.onPrimary.copy(alpha = 0.78f) else cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 竖向滑块。Material 只有横的 Slider，而浓度面板要窄不要宽，就照同一套视觉自己做：
 * 上端最浓、下端最淡，按下即跳到该点，之后跟着手指走。
 */
@Composable
private fun VerticalSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    width: Dp = 44.dp,
) {
    val cs = MaterialTheme.colorScheme
    val knobPx = with(LocalDensity.current) { 14.dp.toPx() }
    var trackH by remember { mutableStateOf(1f) }
    val latest = rememberUpdatedState(onValueChange)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)

    // 手指 y → 数值。轨道两端各留半个钮，钮心才够得着上下限。
    fun valueAt(y: Float): Float {
        val travel = (trackH - knobPx).coerceAtLeast(1f)
        val f = 1f - ((y - knobPx / 2f) / travel).coerceIn(0f, 1f)
        return range.start + f * span
    }

    Canvas(
        modifier
            .width(width)
            .height(height)
            .onSizeChanged { trackH = it.height.toFloat() }
            .pointerInput(range) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    latest.value(valueAt(down.position.y))
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        latest.value(valueAt(ch.position.y))
                        ch.consume()
                    }
                }
            }
    ) {
        val cx = size.width / 2f
        val top = knobPx / 2f
        val bottom = size.height - knobPx / 2f
        val cy = bottom - frac * (bottom - top)
        val tw = 4.dp.toPx()
        drawRoundRect(
            color = cs.surfaceVariant,
            topLeft = Offset(cx - tw / 2f, top),
            size = Size(tw, (bottom - top).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(tw / 2f),
        )
        drawRoundRect(
            color = cs.primary,
            topLeft = Offset(cx - tw / 2f, cy),
            size = Size(tw, (bottom - cy).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(tw / 2f),
        )
        drawCircle(cs.primary.copy(alpha = 0.12f), radius = 12.dp.toPx(), center = Offset(cx, cy))
        drawCircle(cs.primary, radius = 6.dp.toPx(), center = Offset(cx, cy))
    }
}
