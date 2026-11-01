package io.github.moevis.paperink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Printer
import io.github.moevis.paperink.R
import io.github.moevis.paperink.bt.Link

/**
 * 顶栏：品牌 + 打印按钮 + 连接胶囊。
 *
 * 它跟页面内容没有关系——换页签、滚稿子、调参数都不动它——所以单独成文件，
 * [RootScreen] 只留下骨架和状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    link: Link,
    /** 有稿子、且没在忙别的事，打印按钮才可点。 */
    canPrint: Boolean,
    onPrint: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    TopAppBar(
        title = {
            // 品牌图标放左上角，标题跟在右边。桌面图标是系统的，这里得自己摆一份，
            // 不然 App 里一点自己的痕迹都没有，像个网页套壳。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_brand),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
            }
        },
        actions = {
            // 打印是唯一的主操作，直接放右上角。
            // 没连接时就别摆一颗按不动的按钮占位——点胶囊连上，它自己会出来。
            if (link.connected) {
                Button(
                    onClick = onPrint,
                    enabled = canPrint,
                    modifier = Modifier.height(36.dp),
                    // 加了图标之后文字左右各收一点，按钮别比原来胖
                    contentPadding = PaddingValues(start = 12.dp, end = 15.dp),
                ) {
                    Icon(
                        Lucide.Printer,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.action_print), fontSize = 13.sp)
                }
                Spacer(Modifier.width(2.dp))
            }
            ConnectChip(link.connected, link.name, link.battery, onOpenSheet)
        },
    )
}
