package io.github.moevis.paperink.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import io.github.moevis.paperink.R

/**
 * 编辑页：一整块文本框 + 底部两个「起稿方式」的入口。
 *
 * 不用 Material 的 TextField：它自带一圈描边和内边距，屏幕上会多出两条竖线，
 * 而这里需要的是「一整张纸」，不是「一个输入框」。
 */
@Composable
internal fun EditorPane(
    md: String,
    onChange: (String) -> Unit,
    onImport: () -> Unit,
    onSamples: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 键盘弹出时整块往上让：不开这个，底部那两个入口会藏在输入法底下
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = md,
                onValueChange = onChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                    color = cs.onSurface,
                ),
            )
            // 空稿时才出现的起步提示。粘贴/复制走系统自带的文本选择菜单（长按即出），字数不显示。
            if (md.isEmpty()) {
                Text(
                    stringResource(R.string.editor_empty_title) + "\n\n" +
                        stringResource(R.string.editor_empty_hint),
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        // 两个入口以前挂在右上角的 ⋯ 菜单里，可那菜单一共就这两项：为它单设一颗键、
        // 还得点两下才到位。它们本来就只跟编辑有关，放在编辑器里才对。
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorAction(stringResource(R.string.action_import), Lucide.FileUp, onImport)
            EditorAction(stringResource(R.string.action_samples), Lucide.Sparkles, onSamples)
        }
    }
}

/** 编辑器底部的入口：一颗描边小药丸，不抢正文的视觉重量。 */
@Composable
private fun EditorAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(13.5.dp),
            tint = cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.5.sp, color = cs.onSurfaceVariant)
    }
}
