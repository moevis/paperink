package io.github.moevis.paperink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Printer
import io.github.moevis.paperink.Prefs
import io.github.moevis.paperink.bt.PrinterClient
import io.github.moevis.paperink.bt.Transport
import io.github.moevis.paperink.file.IMPORT_MIME_TYPES
import io.github.moevis.paperink.file.MAX_IMPORT_BYTES
import io.github.moevis.paperink.file.decodeText
import io.github.moevis.paperink.file.displayName
import io.github.moevis.paperink.file.readCapped
import io.github.moevis.paperink.file.savePng
import io.github.moevis.paperink.md.MarkdownParser
import io.github.moevis.paperink.render.PrintOptions
import io.github.moevis.paperink.render.PrintResult
import io.github.moevis.paperink.render.PrintRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 正在进行的一件事：进度条 + 说明文字。null 表示此刻没有在忙。 */
private data class Task(val progress: Float, val label: String)

/**
 * 整个 App 的状态都在这里，往下分成两页和一个面板。
 *
 * 为什么不用 ViewModel：这个界面的全部状态就是「一段文字 + 六个参数 + 一份排版结果」。
 * 进程一死就该从 SharedPreferences 恢复，没有需要跨配置变更存活的东西，
 * 引入 ViewModel 只会多一层样板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(client: PrinterClient, prefs: Prefs, incoming: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val link by client.link.collectAsState()

    var md by remember {
        mutableStateOf(incoming?.takeIf { it.isNotBlank() } ?: prefs.text)
    }
    var tab by remember { mutableStateOf(0) }
    var sizeIndex by remember { mutableStateOf(prefs.sizeIndex) }
    var mathIndex by remember { mutableStateOf(prefs.mathIndex) }
    var lineIndex by remember { mutableStateOf(prefs.lineIndex) }
    var widthIndex by remember { mutableStateOf(prefs.widthIndex) }
    var threshold by remember { mutableStateOf(prefs.threshold) }
    var dilate by remember { mutableStateOf(prefs.dilate) }

    val opts = PrintOptions(
        widthDots = Presets.widths[widthIndex],
        // 边距按纸宽等比缩放，宽纸不会显得贴边
        marginDots = Presets.widths[widthIndex] / 32,
        bodySize = Presets.bodySizes[sizeIndex],
        mathEm = Presets.mathEms[mathIndex],
        lineEm = Presets.lineEms[lineIndex],
        threshold = threshold,
        dilate = dilate,
    )
    val doc = remember(md) { MarkdownParser.parse(md) }

    var result by remember { mutableStateOf<PrintResult?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Task?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    fun say(m: String) {
        scope.launch { snack.showSnackbar(m) }
    }

    /** 带「撤销」的提示。导入会整篇覆盖，编辑器又只有自动保存——没有这一步，
     *  选错文件就等于原稿没了，而它值一次后悔的机会。 */
    fun sayUndo(m: String, onUndo: () -> Unit) {
        scope.launch {
            val r = snack.showSnackbar(
                message = m,
                actionLabel = "撤销",
                duration = SnackbarDuration.Long,
            )
            if (r == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    // 导入文件：.txt / .md 都是纯文本，读进来灌进编辑器，顺便作废已排好的位图。
    // 一个入口吃两种格式——它们本质是同一种东西，分两个按钮只会让人先想「我这是哪种」。
    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(ctx, uri)
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_IMPORT_BYTES) }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            say("读不出这个文件")
            return@rememberLauncherForActivityResult
        }
        if (bytes.size > MAX_IMPORT_BYTES) {
            say("文件超过 4 MB，一卷纸印不完，先拆开吧")
            return@rememberLauncherForActivityResult
        }
        val text = decodeText(bytes)
        if (text.isBlank()) {
            say("这个文件里没有能打印的字")
            return@rememberLauncherForActivityResult
        }
        val was = md
        md = text
        result = null
        val msg = if (name.isNullOrBlank()) "已导入 ${text.length} 字" else "已导入 $name（${text.length} 字）"
        if (was.isBlank()) say(msg) else sayUndo(msg) { md = was; result = null }
    }

    /** 要连着打印机才能做的事，没连就先弹连接面板。 */
    fun requireLink(): Boolean {
        if (link.connected) return true
        sheet = true
        say("先连上打印机")
        return false
    }

    /** 跑一件带进度条的事，结束（含异常）一定收掉进度条。 */
    fun runTask(label: String, block: suspend () -> Unit) {
        scope.launch {
            task = Task(0f, label)
            try {
                block()
            } finally {
                task = null
            }
        }
    }

    /** 拿当前的排版结果，没有就现排一份。 */
    suspend fun ensureRendered(): PrintResult =
        result ?: withContext(Dispatchers.Default) { PrintRenderer.render(doc, opts) }
            .also { result = it }

    // 启动时静默重连上次用过的喵喵机，省得每次手动点
    LaunchedEffect(Unit) {
        if (link.connected) return@LaunchedEffect
        client.lastAddress = prefs.lastAddress
        val f = client.bondedPrinter()
        val ok = when {
            f != null -> client.connect(f.device, Transport.SPP)
            prefs.lastAddress.isNotBlank() -> client.connectAddress(prefs.lastAddress)
            else -> false
        }
        if (ok) prefs.lastAddress = client.lastAddress ?: prefs.lastAddress
    }

    // 编辑后的结果作废，切到打印预览再重排（tab 必须进 key，否则只是切标签不会触发）
    LaunchedEffect(doc, opts, tab) {
        result = null
        if (tab == 1) {
            delay(80)
            rendering = true
            result = withContext(Dispatchers.Default) { PrintRenderer.render(doc, opts) }
            rendering = false
        }
    }

    LaunchedEffect(md) { prefs.text = md }

    // 打印时别让屏幕灭掉
    DisposableEffect(task != null) {
        view.keepScreenOn = task != null
        onDispose { view.keepScreenOn = false }
    }

    fun print() {
        if (md.isBlank()) { say("先写点东西"); return }
        if (!requireLink()) return
        runTask("正在排版") {
            val r = ensureRendered()
            task = Task(0f, "正在发送 ${r.rows} 行")
            val res = client.printImage(r.packed, r.rows, r.bytesPerRow) { p ->
                task = Task(p, "正在打印 ${(p * 100).roundToInt()}%")
            }
            say(res.fold({ "打印完成，撕纸吧" }, { "打印失败：${it.message}" }))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("纸印", fontWeight = FontWeight.Bold) },
                actions = {
                    // 打印是唯一的主操作，直接放右上角。底栏那行「已连接 XXX / 已就绪」
                    // 跟顶栏胶囊说的是同一件事，整条删掉了。
                    // 没连接时就别摆一颗按不动的按钮占位——点胶囊连上，它自己会出来。
                    if (link.connected) {
                        Button(
                            onClick = { print() },
                            enabled = md.isNotBlank() && task == null,
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
                            Text("打印", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    ConnectChip(link.connected, link.name, link.battery) { sheet = true }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                listOf("编辑", "打印预览").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> EditorPane(
                    md = md,
                    onChange = { md = it },
                    onImport = { importFile.launch(IMPORT_MIME_TYPES) },
                    onSamples = { samples = true },
                )
                else -> PrintPane(
                    result = result,
                    rendering = rendering,
                    desc = "${doc.blocks.size} 个块",
                    threshold = threshold,
                    onThreshold = { threshold = it; prefs.threshold = it },
                    dilate = dilate,
                    onDilate = { dilate = it; prefs.dilate = it },
                    sizeIndex = sizeIndex,
                    onSize = { sizeIndex = it; prefs.sizeIndex = it },
                    mathIndex = mathIndex,
                    onMath = { mathIndex = it; prefs.mathIndex = it },
                    lineIndex = lineIndex,
                    onLine = { lineIndex = it; prefs.lineIndex = it },
                    widthIndex = widthIndex,
                    onWidth = { widthIndex = it; prefs.widthIndex = it },
                    onSavePng = {
                        runTask("正在保存") {
                            val f = savePng(ctx, ensureRendered().bitmap)
                            say("已存到 ${f.absolutePath}")
                        }
                    },
                )
            }
        }
    }

    if (sheet) {
        ConnectSheet(
            client = client,
            link = link,
            onDismiss = { sheet = false },
            // 断开是「收工」而不是「换一台」：面板跟着收起，顶部胶囊变回未连接，
            // 一眼就能确认状态真的变了。还想连别的再点一次胶囊。
            onDisconnect = {
                sheet = false
                client.disconnect()
                say("已断开连接")
            },
            onPick = { f, t ->
                sheet = false
                scope.launch {
                    task = Task(0f, "正在连接 ${f.name}")
                    val ok = client.connect(f.device, t)
                    task = null
                    say(if (ok) "已连接 ${f.name}" else "连接失败，试着唤醒打印机再重试")
                }
            },
        )
    }

    if (samples) {
        val cs = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { samples = false },
            title = { Text("示例", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    BUILT_IN.forEach { smp ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    samples = false
                                    loadSample(ctx, smp.path) { md = it; result = null; tab = 0 }
                                }
                                .padding(vertical = 11.dp)
                        ) {
                            Text(smp.title, fontSize = 15.sp)
                            Text(smp.desc, fontSize = 11.5.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { samples = false }) { Text("取消") } },
        )
    }

    task?.let { j ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(j.label) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { j.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "保持打印机开着，别切到别的 App。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}
