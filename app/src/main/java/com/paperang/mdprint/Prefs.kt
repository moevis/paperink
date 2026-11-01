package com.paperang.mdprint

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("paper_print", Context.MODE_PRIVATE)

    var text: String
        get() = sp.getString("text", "").orEmpty()
        set(v) = sp.edit().putString("text", v).apply()

    /** 上次连成的打印机地址，用于启动时直连（MIUI 下 bondedDevices 不可靠）。 */
    var lastAddress: String
        get() = sp.getString("last_addr", "").orEmpty()
        set(v) = sp.edit().putString("last_addr", v).apply()

    var threshold: Int
        get() = sp.getInt("threshold", 148)
        set(v) = sp.edit().putInt("threshold", v).apply()

    var dilate: Boolean
        get() = sp.getBoolean("dilate", false)
        set(v) = sp.edit().putBoolean("dilate", v).apply()

    /** 0=57mm(576) 1=68mm(720) 2=79mm(864)，对应 P3 MAX 固件自报的三档纸 */
    var widthIndex: Int
        get() = sp.getInt("width", 2).coerceIn(0, 2)
        set(v) = sp.edit().putInt("width", v).apply()

    /** 0=小 1=中 2=大 */
    var sizeIndex: Int
        get() = sp.getInt("size", 1)
        set(v) = sp.edit().putInt("size", v).apply()

    /** 独行公式字号，0=小 1=中 2=大（跟正文字号各自独立） */
    var mathIndex: Int
        get() = sp.getInt("math_size", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("math_size", v.coerceIn(0, 2)).apply()

    /** 行距，0=紧 1=中 2=松 */
    var lineIndex: Int
        get() = sp.getInt("line_gap", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("line_gap", v.coerceIn(0, 2)).apply()
}
