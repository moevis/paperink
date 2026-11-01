package io.github.moevis.paperink.file

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * 把排好的位图存成 PNG。
 *
 * 落在应用私有的外部目录（`Android/data/<包名>/files/print/`），不需要任何存储权限，
 * 卸载 App 时跟着一起清掉。要进相册就得走 MediaStore，那是另一套逻辑。
 */
internal fun savePng(ctx: Context, bmp: Bitmap): File {
    val dir = File(ctx.getExternalFilesDir(null), "print")
    dir.mkdirs()
    val f = File(dir, "print-${System.currentTimeMillis()}.png")
    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f
}
