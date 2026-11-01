package io.github.moevis.paperink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 竖向滑块。Material 只有横的 Slider，而浓度面板要窄不要宽，就照同一套视觉自己做：
 * 上端最浓、下端最淡，按下即跳到该点，之后跟着手指走。
 */
@Composable
internal fun VerticalSlider(
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
