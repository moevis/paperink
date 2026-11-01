package com.paperang.mdprint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.paperang.mdprint.bt.PrinterClient
import com.paperang.mdprint.ui.PaperTheme

class MainActivity : ComponentActivity() {

    private lateinit var client: PrinterClient
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        client = PrinterClient(applicationContext)
        prefs = Prefs(this)
        // 调试/深链用：adb ... -e addr XX:XX:XX:XX:XX:XX 可直接指定打印机
        intent?.getStringExtra("addr")?.takeIf { it.isNotBlank() }?.let {
            prefs.lastAddress = it
        }
        val incoming = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
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

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }
}
