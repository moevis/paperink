# 公式引擎：JLaTeXMath 靠名字查表加载符号和字体，R8 改名会直接让公式变成空白。
# 整个包都不混淆最省心（它的字节数本来也不大）。
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }

# Compose 在 release 下也要求保留行号，否则崩溃栈全是 ???。
-keepattributes SourceFile,LineNumberTable
