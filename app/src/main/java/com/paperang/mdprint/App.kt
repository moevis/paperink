package com.paperang.mdprint

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Bluetooth
import com.composables.icons.lucide.BluetoothConnected
import com.composables.icons.lucide.Bold
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Droplet
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Printer
import com.composables.icons.lucide.Rows3
import com.composables.icons.lucide.Ruler
import com.composables.icons.lucide.Sigma
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Unplug
import com.paperang.mdprint.bt.Found
import com.paperang.mdprint.bt.Link
import com.paperang.mdprint.bt.PrinterClient
import com.paperang.mdprint.bt.Transport
import com.paperang.mdprint.md.MarkdownParser
import com.paperang.mdprint.render.PrintOptions
import com.paperang.mdprint.render.PrintResult
import com.paperang.mdprint.render.PrintRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.roundToInt

private data class PrintJob(val progress: Float, val label: String)

// 正文字号单位是打印点；P3 MAX 300dpi 下 26 点约 2.2mm，接近小票机正常字高
private val SIZES = floatArrayOf(22f, 26f, 31f)
// P3 MAX 是 300dpi 的热敏头（864 点），三档纸由固件自报：57mm→576、68mm→720、79mm→864
private val WIDTHS = intArrayOf(576, 720, 864)
private val WIDTH_LABELS = arrayOf("57mm", "68mm", "79mm")
private val SIZE_LABELS = arrayOf("小", "中", "大")
// 独行公式字号（正文 em 的倍数）。行内公式不受影响，始终跟正文同号。
// 1.6546 是老版本写死的值，「大」档就是它，等号两横等粗在那里验过。
private val MATH_EMS = floatArrayOf(1.30f, 1.50f, 1.6546f)
// 行距系数，乘在各块自带的行高比上（见 PrintOptions.lineEm）
private val LINE_EMS = floatArrayOf(0.88f, 1f, 1.12f)
private val LINE_LABELS = arrayOf("紧", "中", "松")

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
        widthDots = WIDTHS[widthIndex],
        // 边距按纸宽等比缩放，宽纸不会显得贴边
        marginDots = WIDTHS[widthIndex] / 32,
        bodySize = SIZES[sizeIndex],
        mathEm = MATH_EMS[mathIndex],
        lineEm = LINE_EMS[lineIndex],
        threshold = threshold,
        dilate = dilate,
    )
    val doc = remember(md) { MarkdownParser.parse(md) }

    var result by remember { mutableStateOf<PrintResult?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<PrintJob?>(null) }
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
    fun runJob(label: String, block: suspend () -> Unit) {
        scope.launch {
            job = PrintJob(0f, label)
            try {
                block()
            } finally {
                job = null
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
    DisposableEffect(job != null) {
        view.keepScreenOn = job != null
        onDispose { view.keepScreenOn = false }
    }

    fun print() {
        if (md.isBlank()) { say("先写点东西"); return }
        if (!requireLink()) return
        runJob("正在排版") {
            val r = ensureRendered()
            job = PrintJob(0f, "正在发送 ${r.rows} 行")
            val res = client.printImage(r.packed, r.rows, r.bytesPerRow) { p ->
                job = PrintJob(p, "正在打印 ${(p * 100).roundToInt()}%")
            }
            say(
                res.fold({ "打印完成，撕纸吧" }, { "打印失败：${it.message}" })
            )
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
                            enabled = md.isNotBlank() && job == null,
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
                        runJob("正在保存") {
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
                    job = PrintJob(0f, "正在连接 ${f.name}")
                    val ok = client.connect(f.device, t)
                    job = null
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

    job?.let { j ->
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

@Composable
private fun ConnectChip(
    connected: Boolean,
    name: String?,
    battery: Int?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        // 右侧留白跟标题的左内边距（16dp）对齐：⋯ 菜单撤掉后，胶囊就成了最后一个元素
        Modifier
            .padding(end = 12.dp)
            .background(cs.surfaceVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 原来是一颗 7dp 的绿点：只说了「连/没连」，没说「这是什么」。换成蓝牙图标后
        // 既表态又表意——绿色=连着，灰色=没连，比圆点更能让人想到"点它去连打印机"。
        Icon(
            if (connected) Lucide.BluetoothConnected else Lucide.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (connected) Color(0xFF3BA55D) else cs.outline,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            when {
                !connected -> "未连接"
                else -> buildString {
                    append(name?.take(12) ?: "已连接")
                    battery?.let { append(" · $it%") }
                }
            },
            fontSize = 12.5.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditorPane(
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
                    "在这里粘贴 Markdown…\n\n支持标题、列表、表格、代码块、引用。\n底部可以导入文本或 Markdown 文件，或直接开一份示例。",
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        // 两个「起稿方式」的入口。以前挂在右上角 ⋯ 菜单里，可菜单一共就这两项，
        // 为它单设一颗键、还得点两下才到位；它们本来就只跟编辑有关，放在编辑器里才对。
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorAction("导入文件", Lucide.FileUp, onImport)
            EditorAction("示例", Lucide.Sparkles, onSamples)
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

@Composable
private fun PrintPane(
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
                    // 点数跟纸宽绑死（57mm→576、68mm→720、79mm→864），放在这儿等于
                    // 天天重复同一句由别处决定的话。它挪到纸宽面板里当辅助信息了，
                    // 这里只说"打完有多长"——真正会变、真正要看的那个数。
                    if (result == null) "$desc · 排版中"
                    else buildString {
                        append("${result.rows} 行 · ")
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
                        // 半透明在这儿行不通——"浮起来"靠形状和阴影表达，颜色必须压实。
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

/**
 * 底部参数栏。每格只显示「标签 + 当前值」，点一下在格子上方原地浮出选项，
 * 选完（或点别处）就收回——参数不再常驻吃掉半个屏幕。
 *
 * 它本身也是浮在预览上的一块圆角卡（不再通栏贴边），所以稿子能一直滚到它身后。
 */
@Composable
private fun ParamBar(
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
            ParamCell("字号", SIZE_LABELS[sizeIndex], Lucide.Type, Modifier.weight(1f)) { done ->
                ChoiceRow(SIZE_LABELS, sizeIndex) { onSize(it); done() }
            }
            ParamCell("纸宽", WIDTH_LABELS[widthIndex], Lucide.Ruler, Modifier.weight(1f)) { done ->
                // 毫米是纸的买法，点数是喂给打印头的实际列数——换纸为什么整篇会重排，
                // 靠这行小字才解释得通。跟着选项一起摆，比在顶部常驻一句"864 点"有用。
                ChoiceRow(
                    labels = WIDTH_LABELS,
                    selected = widthIndex,
                    fontSize = 12.sp,
                    subLabels = Array(WIDTHS.size) { "${WIDTHS[it]} 点" },
                ) { onWidth(it); done() }
            }
            // 只影响独行的 $$…$$；行内公式跟正文同号，不跟着变
            ParamCell("公式", SIZE_LABELS[mathIndex], Lucide.Sigma, Modifier.weight(1f)) { done ->
                ChoiceRow(SIZE_LABELS, mathIndex) { onMath(it); done() }
            }
            ParamCell("行距", LINE_LABELS[lineIndex], Lucide.Rows3, Modifier.weight(1f)) { done ->
                ChoiceRow(LINE_LABELS, lineIndex) { onLine(it); done() }
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

/**
 * 浮层定位：摆在锚点正上方并水平居中，上方放不下才翻到下面（工具栏贴底，基本走不到）。
 * 水平方向夹在窗口内，最左/最右那两格的面板不会被裁掉半个。
 */
private class AboveAnchorPositionProvider(
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
 * 浮层定位：卡片水平居中于长按点、落在手指**上方**，上面放不下才翻到下面。
 * 一开始想摆在按点右下（像右键菜单），但卡片宽 160dp，屏幕中点往右就放不下，
 * 于是几乎每次长按都会翻到左边——锚点在中点却弹出在左侧，莫名其妙。居中更稳。
 *
 * [point] 是窗口坐标：预览图在滚动容器里，Popup 的 anchorBounds 会随滚动漂，
 * 所以这里干脆不用它。最后一律夹进窗口，戳在纸的边角时按钮也不会被裁掉半个。
 */
private class AtPointPositionProvider(
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

/**
 * 浮层的皮：一整块圆角卡片，就一层。
 * 早先用 DropdownMenu，它的菜单容器自带底色，里面又套一层分段控件的灰底，
 * 两层不同色、不同圆角叠在一起，看着脏。现在容器只有这一层。
 */
@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        // 跟底部工具栏同一套「surface + 主色微调」，浮层才不会在旁边显得是另一种灰
        color = cs.surfaceColorAtElevation(6.dp),
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f)),
    ) {
        content()
    }
}

/**
 * 面板里的一排选项：选中的铺主色底，其余纯文字，没有第二层底色。
 *
 * [subLabels] 是每项下面的那行小字，目前只有纸宽用：毫米和点数一上一下摆在一起。
 * 不写成"面板底下单独一行说明当前值"——面板点完就收，那行字等再打开才看得到，
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

/**
 * 浮层定位：摆在锚点正上方并水平居中，上方放不下才翻到下面（工具栏贴底，基本走不到）。
 * 水平方向夹在窗口内，最左/最右那两格的面板不会被裁掉半个。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectSheet(
    client: PrinterClient,
    link: Link,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
    onPick: (Found, Transport) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val found by client.found.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) {
            client.startScan(all)
            scanning = true
        } else {
            Toast.makeText(ctx, "没有蓝牙权限就没法扫描", Toast.LENGTH_LONG).show()
        }
    }

    fun beginScan() {
        if (client.scanner == null) {
            Toast.makeText(ctx, "这台设备没有蓝牙", Toast.LENGTH_LONG).show()
            return
        }
        if (!client.enabled) {
            Toast.makeText(ctx, "请先打开蓝牙", Toast.LENGTH_LONG).show()
            return
        }
        val need = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = need.filter {
            ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
            return
        }
        client.startScan(all)
        scanning = true
    }

    LaunchedEffect(all) {
        beginScan()
    }
    DisposableEffect(Unit) {
        onDispose { client.stopScan() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择打印机", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    all = !all
                    client.startScan(all)
                }) { Text(if (all) "只看喵喵机" else "显示全部") }
            }
            // 连接状态与「断开连接」都归这里：没连时这一行根本不存在，
            // 也就不会出现「没连上还能点断开」这种不成立的操作。
            if (link.connected) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.55f))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(7.dp).background(Color(0xFF3BA55D), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        link.name ?: "已连接",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDisconnect) {
                        Icon(
                            Lucide.Unplug,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = cs.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("断开连接", fontSize = 13.sp, color = cs.error)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (found.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().height(96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (scanning) "正在扫描…" else "没找到设备",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant,
                    )
                }
                val bonded = remember { client.bondedDevices() }
                if (bonded.isNotEmpty()) {
                    Text("已配对设备", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                    // 面板放不下太多行：配对记录多于两三台时底下几行会顶出屏幕点不到
                    // （P3 MAX 恰好排在第五个），所以这里独立滚动，别让它够不着。
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(bonded, key = { it.device.address }) { f ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(f, Transport.SPP) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DeviceGlyph(f.name)
                                Spacer(Modifier.width(10.dp))
                                Text(f.name, fontSize = 14.sp)
                            }
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                    items(found, key = { it.device.address }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(f, Transport.BLE) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DeviceGlyph(f.name, size = 18.dp)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 15.sp)
                                Text(
                                    f.device.address + if (f.rssi != 0) "  ·  ${f.rssi} dBm" else "",
                                    fontSize = 11.5.sp,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/**
 * 设备行首的图标：认得出的喵喵机给打印机，其余给蓝牙。
 * 「显示全部」时列表里混着一堆无名 BLE 设备，给每一行都画打印机等于撒谎。
 */
@Composable
private fun DeviceGlyph(name: String, size: Dp = 16.dp) {
    Icon(
        if (PrinterClient.looksLikePrinter(name)) Lucide.Printer else Lucide.Bluetooth,
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 内置资料：打进 APK 的 assets/docs 下的 md，从编辑器底部的「示例」里点一下直接灌进编辑器。
 * 内容源是仓库根的 `materials/`，改完那边要同步复制到 assets/docs（文件名用 ASCII 名）。
 */
private data class Sample(val title: String, val desc: String, val path: String)

private val BUILT_IN = listOf(
    Sample("幂运算的性质", "三个名字、七条性质、零次与负指数", "docs/powers.md"),
    Sample("分数的计算", "倒数、乘除、加减、约分、带分数", "docs/fractions.md"),
)

private fun readAsset(ctx: Context, path: String): String? = runCatching {
    ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
}.getOrNull()

/**
 * 导入文件时认哪些 MIME。
 *
 * text 那一支覆盖 .txt 和认得 .md 的机型；`application/octet-stream` 是给那些把 .md
 * 报成「二进制未知」的文件管理器的——只留 text 的话，这类机器上 .md 会灰掉选不中。
 * （注意：Kotlin 的块注释可嵌套，注释里不能写带斜杠星号的通配符，会把注释吃掉。）
 */
private val IMPORT_MIME_TYPES = arrayOf(
    "text/*",
    "application/octet-stream",
    "application/x-markdown",
)

/** 4 MB 纯文本约两百万字，一页 100cm 也印不完；再大只会把排版卡死。 */
private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

/** 读到上限就停手——不能先 `readBytes()` 再判大小，那会儿内存已经吃进去了。 */
internal fun readCapped(input: InputStream, cap: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        out.write(chunk, 0, n)
        if (out.size() > cap) break
    }
    return out.toByteArray()
}

/**
 * 纯文本解码：BOM → 严格 UTF-8 → GB18030 三级。
 *
 * 中文 .txt 十有八九是 Windows 上存的 GBK，直接按 UTF-8 读会满屏「锟斤拷」；
 * 而 UTF-8 又必须先**严格**试一遍才知道该不该退——宽松解码永不报错，只会默默塞 U+FFFD。
 */
internal fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    val strict = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
    strict.getOrNull()?.let { return it }
    return runCatching { String(bytes, Charset.forName("GB18030")) }
        .getOrDefault(String(bytes, Charsets.UTF_8))
}

/** 问 SAF 要文件名。有的 provider 不给 DISPLAY_NAME，退回路径尾段。 */
private fun displayName(ctx: Context, uri: Uri): String? =
    runCatching {
        ctx.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

/** 读内置示例灌进编辑器；读不到就提示一声，别把编辑器清空。 */
private fun loadSample(ctx: Context, path: String, onLoad: (String) -> Unit) {
    val t = readAsset(ctx, path)
    if (t.isNullOrBlank()) Toast.makeText(ctx, "读不到这份资料", Toast.LENGTH_SHORT).show()
    else onLoad(t)
}

private fun savePng(ctx: Context, bmp: Bitmap): File {
    val dir = File(ctx.getExternalFilesDir(null), "print")
    dir.mkdirs()
    val f = File(dir, "print-${System.currentTimeMillis()}.png")
    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f
}
