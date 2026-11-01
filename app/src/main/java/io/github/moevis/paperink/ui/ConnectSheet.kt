package io.github.moevis.paperink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bluetooth
import com.composables.icons.lucide.BluetoothConnected
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Printer
import com.composables.icons.lucide.Unplug
import io.github.moevis.paperink.bt.Found
import io.github.moevis.paperink.bt.Link
import io.github.moevis.paperink.bt.PrinterClient
import io.github.moevis.paperink.bt.Transport

/** 连上了是绿的，没连是灰的——全 App 只有这一个绿。 */
private val ConnectedGreen = Color(0xFF3BA55D)

/**
 * 顶栏右侧的连接胶囊。它既是状态显示，也是唯一的连接入口：
 * 点开就是设备列表，断开也藏在那个面板里。不在别处再放一颗「断开连接」按钮——
 * 那会让「没连上也能点断开」变成一个成立的操作。
 */
@Composable
internal fun ConnectChip(
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
        // 既表态又表意——绿色=连着，灰色=没连，比圆点更能让人想到「点它去连打印机」。
        Icon(
            if (connected) Lucide.BluetoothConnected else Lucide.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (connected) ConnectedGreen else cs.outline,
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

/**
 * 选打印机：底部弹出的面板。扫描、已配对设备、连接状态、断开连接都收在这一个面板里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectSheet(
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
            ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
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
                    Box(Modifier.size(7.dp).background(ConnectedGreen, CircleShape))
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
