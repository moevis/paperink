package io.github.moevis.paperink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import io.github.moevis.paperink.render.PrintResult

/**
 * 打印预览页。整页只有一样东西是「内容」：那张位图。
 * 其余全是浮层——顶部一行尺寸、底部一排参数，滚动时让路，不占排版宽度。
 */
@Composable
internal fun PrintPane(
    result: PrintResult?,
    rendering: Boolean,
    desc: String,
    threshold: Int,
    onThreshold: (Int) -> Unit,
    dilate: Boolean,
    onDilate: (Boolean) -> Unit,
    sizeIndex: Int,
    onSize: (Int) -> Unit,
    mathIndex: Int,
    onMath: (Int) -> Unit,
    lineIndex: Int,
    onLine: (Int) -> Unit,
    widthIndex: Int,
    onWidth: (Int) -> Unit,
    onSavePng: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    // 长按预览图 → 在原地弹一个「保存图片」。位置记窗口坐标（不是图片内坐标），
    // 因为浮层是独立的窗口，跟滚动容器不共用一个坐标系，滚动时也不会跟着漂。
    var saveAt by remember { mutableStateOf<Offset?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }      // 预览图左上角（窗口坐标）
    var pressLocal by remember { mutableStateOf(Offset.Zero) }  // 按下点（图片内坐标）
    val haptics = LocalHapticFeedback.current
    // 长按没有可点的反馈，靠这一下震动告诉用户「抓到了」
    val pressSource = remember { MutableInteractionSource() }

    // 两个浮层都跟着滚动让路：往下看稿子时收起，一往上翻就回来。
    // 默认可见——稿子不满一屏时根本滚不动，等于常驻。
    var barsShown by remember { mutableStateOf(true) }

    LaunchedEffect(scroll) {
        // 上一次的值放在协程里就够了，不必做成 State 再去触发重组
        var last = scroll.value
        snapshotFlow { scroll.value }.collect { v ->
            val d = v - last
            last = v
            // 手指抖动 / 惯性刚起步时会来回跳一两个像素，低于阈值不判方向
            if (d > 3) barsShown = false
            else if (d < -3 || v == 0) barsShown = true
        }
    }

    // 浮层不占布局，得自己把内容垫开：顶部让开信息条，底部让开工具栏，
    // 否则稿子的第一行和最后一行永远压在浮层底下。
    var barHeight by remember { mutableStateOf(58.dp) }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .clipToBounds()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(48.dp))
                when {
                    rendering -> Column(
                        Modifier.fillMaxWidth().padding(top = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                    result == null -> Text(
                        "生成中…",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 30.dp),
                    )
                    else -> Image(
                        bitmap = result.bitmap.asImageBitmap(),
                        contentDescription = "打印预览",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { origin = it.positionInWindow() }
                            // 只旁观按下点，不消费事件——消费了 down，整块预览就再也滚不动了
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        if (e.type == PointerEventType.Press) {
                                            e.changes.firstOrNull()?.let { pressLocal = it.position }
                                        }
                                    }
                                }
                            }
                            .border(1.dp, cs.outlineVariant)
                            .background(Color.White)
                            .combinedClickable(
                                interactionSource = pressSource,
                                // 点一下预览什么也不做，铺一层模糊的涟漪只会碍眼
                                indication = null,
                                onLongClickLabel = "保存图片",
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    saveAt = origin + pressLocal
                                },
                                onClick = {},
                            ),
                    )
                }
                Spacer(Modifier.height(barHeight + 12.dp))
            }
        }

        // 「这份稿子有多长」——一行小字压在预览顶上，不再单独占一行高度
        Box(Modifier.align(Alignment.TopCenter)) {
            AnimatedVisibility(
                visible = barsShown,
                enter = slideInVertically(animationSpec = tween(220)) { -it } + fadeIn(tween(160)),
                exit = slideOutVertically(animationSpec = tween(220)) { -it } + fadeOut(tween(160)),
            ) {
                Text(
                    // 只报「打完有多长」这一个数。曾经还并排显示「1335 行」，那是位图高度
                    // 按 300dpi 折算的点行数——二十来行的稿子也报四位数，读者无从校核。
                    // 点数跟纸宽绑死（57mm→576、68mm→720、79mm→864），也挪到纸宽面板里了。
                    if (result == null) "$desc · 排版中"
                    else buildString {
                        append("%.1f cm".format(result.lengthMm / 10f))
                        if (result.truncated) append(" · 已截断")
                    },
                    fontSize = 11.5.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(50))
                        // 试过 0.78 / 0.88 / 0.94 / 0.96：白纸上的黑字只要透出 4%，
                        // 在深灰底上就是 10 级灰度差，字形看得一清二楚，糊成一团脏点。
                        // 半透明在这儿行不通——「浮起来」靠形状和阴影表达，颜色必须压实。
                        .background(cs.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = barsShown,
                enter = slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(160)),
            ) {
                ParamBar(
                    modifier = Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                    sizeIndex = sizeIndex, onSize = onSize,
                    widthIndex = widthIndex, onWidth = onWidth,
                    mathIndex = mathIndex, onMath = onMath,
                    lineIndex = lineIndex, onLine = onLine,
                    threshold = threshold, onThreshold = onThreshold,
                    dilate = dilate, onDilate = onDilate,
                )
            }
        }

        // 长按预览图浮出的那一块：一个按钮，点下去就把当前位图存成 PNG。
        // 点别处、按返回键都收起（Popup 要 focusable 才吃得到这两件事）。
        saveAt?.let { at ->
            val provider = remember(at, density) {
                AtPointPositionProvider(
                    point = at,
                    gapPx = with(density) { 12.dp.roundToPx() },
                    marginPx = with(density) { 10.dp.roundToPx() },
                )
            }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { saveAt = null },
                properties = PopupProperties(focusable = true),
            ) {
                PanelCard {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                saveAt = null
                                onSavePng()
                            }
                            .padding(start = 13.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Lucide.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "保存图片",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurface,
                        )
                        if (result != null) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${result.bitmap.width}×${result.bitmap.height} PNG",
                                fontSize = 10.5.sp,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
