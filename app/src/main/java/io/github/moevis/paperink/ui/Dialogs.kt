package io.github.moevis.paperink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.moevis.paperink.R

/**
 * 示例选择框。列出内置讲义，点一条就灌进编辑器。
 */
@Composable
internal fun SamplesDialog(onPick: (Sample) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.action_samples),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                BUILT_IN.forEach { smp ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(smp) }
                            .padding(vertical = 11.dp)
                    ) {
                        Text(stringResource(smp.title), fontSize = 15.sp)
                        Text(
                            stringResource(smp.desc),
                            fontSize = 11.5.sp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 进度框：一条进度条 + 一句「别关打印机」的提醒。
 *
 * 没有取消按钮也没法划掉——正在做的事要么做完要么报错，半途而废会留下一张
 * 印了一半的纸。
 */
@Composable
internal fun TaskProgressDialog(label: String, progress: Float, note: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(label) },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    note,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
