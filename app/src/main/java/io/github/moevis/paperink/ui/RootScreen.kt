package io.github.moevis.paperink.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import io.github.moevis.paperink.Prefs
import io.github.moevis.paperink.R
import io.github.moevis.paperink.bt.PrinterClient
import io.github.moevis.paperink.bt.Transport
import io.github.moevis.paperink.bt.print.PrintJob
import io.github.moevis.paperink.file.Incoming
import io.github.moevis.paperink.file.savePng
import io.github.moevis.paperink.md.MarkdownParser
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
 *
 * 拆出去的：对话框在 [Dialogs.kt]，文件导入在 [TextImport.kt]，参数条在 [ParamBar.kt]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(client: PrinterClient, prefs: Prefs, incoming: State<Incoming?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val link by client.link.collectAsState()

    // 回调里不能再调 stringResource（那要在组合作用域里），固定文案先在这里备好。
    // 带参数的提示在回调里用 ctx.getString 取——Activity 的资源跟着应用语言走。
    val msgWriteSomething = stringResource(R.string.msg_write_something)
    val msgConnectFirst = stringResource(R.string.msg_connect_first)
    val msgTypesetting = stringResource(R.string.msg_typesetting)
    val msgPrintDone = stringResource(R.string.msg_print_done)
    val msgDisconnected = stringResource(R.string.msg_disconnected)
    val msgConnectFailed = stringResource(R.string.msg_connect_failed)
    val msgKeepPrinterOn = stringResource(R.string.msg_keep_printer_on)
    val undoLabel = stringResource(R.string.action_undo)

    // 外面送进来的那一份：首次进来直接当初始文本，别先闪一下上次的稿子。
    val firstIn = remember { incoming.value }
    var md by remember {
        mutableStateOf((firstIn as? Incoming.Text)?.text?.takeIf { it.isNotBlank() } ?: prefs.text)
    }
    var tab by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(prefs.printSettings()) }
    val opts = settings.toOptions()
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
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (r == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    // App 已经开着时又从别处分享/打开一份：换掉正文，并给一次撤销——
    // 整篇覆盖不可逆，而编辑器只有自动保存。
    LaunchedEffect(incoming.value?.id) {
        when (val inc = incoming.value) {
            null -> Unit
            // 读不到要先说一句：冷启动时它就是 firstIn，跳过「是否首次」会把提示一起跳掉。
            is Incoming.Failed -> say(ctx.getString(R.string.msg_read_failed))
            is Incoming.Text -> {
                if (inc.id == firstIn?.id) return@LaunchedEffect
                val was = md
                md = inc.text
                result = null
                tab = 0
                val msg = if (inc.name.isNullOrBlank()) {
                    ctx.getString(R.string.msg_imported, inc.text.length)
                } else {
                    ctx.getString(R.string.msg_imported_named, inc.name, inc.text.length)
                }
                sayUndo(msg) { md = was; result = null }
            }
        }
    }

    // 导入文件：.txt / .md 都是纯文本，读进来灌进编辑器，顺便作废已排好的位图。
    val importFile = rememberTextImporter { res ->
        when (res) {
            is ImportResult.Ok -> {
                val was = md
                md = res.text
                result = null
                val msg = if (res.name.isNullOrBlank()) {
                    ctx.getString(R.string.msg_imported, res.text.length)
                } else {
                    ctx.getString(R.string.msg_imported_named, res.name, res.text.length)
                }
                if (was.isBlank()) say(msg) else sayUndo(msg) { md = was; result = null }
            }

            ImportResult.ReadFailed -> say(ctx.getString(R.string.msg_read_failed))
            ImportResult.TooBig -> say(ctx.getString(R.string.msg_too_big))
            ImportResult.Empty -> say(ctx.getString(R.string.msg_empty_file))
        }
    }

    /** 要连着打印机才能做的事，没连就先弹连接面板。 */
    fun requireLink(): Boolean {
        if (link.connected) return true
        sheet = true
        say(msgConnectFirst)
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
        if (md.isBlank()) { say(msgWriteSomething); return }
        if (!requireLink()) return
        runTask(msgTypesetting) {
            val r = ensureRendered()
            task = Task(0f, ctx.getString(R.string.msg_sending_lines, r.rows))
            val res = client.printImage(PrintJob(r.packed, r.rows, r.bytesPerRow)) { p ->
                task = Task(p, ctx.getString(R.string.msg_printing, (p * 100).roundToInt()))
            }
            say(res.fold({ msgPrintDone }, { ctx.getString(R.string.msg_print_failed, it.message) }))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AppTopBar(
                link = link,
                canPrint = md.isNotBlank() && task == null,
                onPrint = { print() },
                onOpenSheet = { sheet = true },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                listOf(
                    stringResource(R.string.tab_edit),
                    stringResource(R.string.tab_preview),
                ).forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> EditorPane(
                    md = md,
                    onChange = { md = it },
                    onImport = { importFile() },
                    onSamples = { samples = true },
                )
                else -> PrintPane(
                    result = result,
                    rendering = rendering,
                    desc = stringResource(R.string.desc_blocks, doc.blocks.size),
                    settings = settings,
                    onSettings = { settings = it; prefs.store(it) },
                    onSavePng = {
                        runTask(ctx.getString(R.string.msg_saving)) {
                            val f = savePng(ctx, ensureRendered().bitmap)
                            say(ctx.getString(R.string.msg_saved_to, f.absolutePath))
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
                say(msgDisconnected)
            },
            onPick = { f, t ->
                sheet = false
                scope.launch {
                    task = Task(0f, ctx.getString(R.string.msg_connecting, f.name))
                    val ok = client.connect(f.device, t)
                    task = null
                    say(
                        if (ok) ctx.getString(R.string.msg_connected, f.name)
                        else msgConnectFailed
                    )
                }
            },
        )
    }

    if (samples) {
        SamplesDialog(
            onPick = { smp, lang ->
                samples = false
                loadSample(ctx, smp.file, lang) { md = it; result = null; tab = 0 }
            },
            onDismiss = { samples = false },
        )
    }

    task?.let { j ->
        TaskProgressDialog(
            label = j.label,
            progress = j.progress,
            note = msgKeepPrinterOn,
        )
    }
}
