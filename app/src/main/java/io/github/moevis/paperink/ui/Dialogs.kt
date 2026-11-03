package io.github.moevis.paperink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.moevis.paperink.R

/**
 * 示例选择框。列出内置讲义，点一条就灌进编辑器。
 *
 * 右上角可以切讲义语言：界面语言和「想打哪一份」不是一回事，
 * 中文界面里也会有人想拿英文讲义去试排版。
 */
@Composable
internal fun SamplesDialog(onPick: (Sample, SampleLang) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var lang by remember { mutableStateOf(defaultSampleLang(ctx)) }
    // 换语言的上下文只用来取标题和副标题，文件另走 loadSample。
    val res = remember(lang) { ctx.withLang(lang).resources }
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.action_samples),
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                LangToggle(lang = lang, onPick = { lang = it })
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                BUILT_IN.forEach { smp ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(smp, lang) }
                            .padding(vertical = 11.dp)
                    ) {
                        Text(res.getString(smp.title), fontSize = 15.sp)
                        Text(
                            res.getString(smp.desc),
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
 * 「中 / EN」小开关。两个词各自用本语言写——语言选择器都这么干，
 * 界面是英文时也不该把「中」翻成 "Chinese"。
 */
@Composable
private fun LangToggle(lang: SampleLang, onPick: (SampleLang) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp)
    ) {
        LangChip("中", lang == SampleLang.Zh) { onPick(SampleLang.Zh) }
        LangChip("EN", lang == SampleLang.En) { onPick(SampleLang.En) }
    }
}

@Composable
private fun LangChip(text: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) cs.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) cs.onPrimary else cs.onSurfaceVariant,
        )
    }
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
