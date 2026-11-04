package io.github.moevis.paperink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import io.github.moevis.paperink.bt.PrinterClient
import io.github.moevis.paperink.file.Incoming
import io.github.moevis.paperink.file.readIncoming
import io.github.moevis.paperink.ui.PaperTheme
import io.github.moevis.paperink.ui.RootScreen

class MainActivity : ComponentActivity() {

    private lateinit var client: PrinterClient
    private lateinit var prefs: Prefs

    /**
     * 外面送进来的内容（分享的文字/文件、双击打开的 .md）。
     *
     * 用 Compose 的 state 而不是一次性参数：App 已经开着的时候再从别处分享一次，
     * 走的是 [onNewIntent]，界面得跟着换掉——只读 onCreate 那一次就成了「点了没反应」。
     */
    private val incoming = mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        client = PrinterClient(applicationContext)
        prefs = Prefs(this)
        // 调试/深链用：adb ... -e addr XX:XX:XX:XX:XX:XX 可直接指定打印机
        intent?.getStringExtra("addr")?.takeIf { it.isNotBlank() }?.let {
            prefs.lastAddress = it
        }
        incoming.value = readIncoming(this, intent)
        setContent {
            PaperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootScreen(client, prefs, incoming)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIncoming(this, intent)?.let { incoming.value = it }
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }
}
