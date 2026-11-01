package io.github.moevis.paperink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * 浮层的皮：一整块圆角卡片，就一层。
 *
 * 早先用 DropdownMenu，它的菜单容器自带底色，里面又套一层分段控件的灰底，
 * 两层不同色、不同圆角叠在一起，看着脏。现在容器只有这一层。
 */
@Composable
internal fun PanelCard(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        // 跟底部工具栏同一套「surface + 主色微调」，浮层才不会在旁边显得是另一种灰
        color = cs.surfaceColorAtElevation(6.dp),
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f)),
    ) {
        content()
    }
}

/**
 * 参数面板的定位：摆在锚点正上方并水平居中，上方放不下才翻到下面（工具栏贴底，基本走不到）。
 * 水平方向夹在窗口内，最左/最右那两格的面板不会被裁掉半个。
 */
internal class AboveAnchorPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val above = anchorBounds.top - gapPx - popupContentSize.height
        val y = if (above >= marginPx) above else anchorBounds.bottom + gapPx
        return IntOffset(x, y)
    }
}

/**
 * 长按保存的定位：卡片水平居中于长按点、落在手指**上方**，上面放不下才翻到下面。
 *
 * 一开始想摆在按点右下（像右键菜单），但卡片宽 160dp，屏幕中点往右就放不下，
 * 于是几乎每次长按都会翻到左边——锚点在中点却弹出在左侧，莫名其妙。居中更稳。
 *
 * [point] 是窗口坐标：预览图在滚动容器里，Popup 的 anchorBounds 会随滚动漂，
 * 所以这里干脆不用它。最后一律夹进窗口，戳在纸的边角时按钮也不会被裁掉半个。
 */
internal class AtPointPositionProvider(
    private val point: Offset,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val px = point.x.roundToInt()
        val py = point.y.roundToInt()
        val x = (px - popupContentSize.width / 2)
            .coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
        var y = py - gapPx - popupContentSize.height
        if (y < marginPx) y = py + gapPx
        y = y.coerceIn(marginPx, (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        return IntOffset(x, y)
    }
}
