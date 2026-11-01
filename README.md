<div align="center">

<img src="docs/icon.png" width="96" alt="纸印">

# 纸印 · paperink

**把 Markdown 打到热敏纸上。**

粘贴一段 Markdown，本地排成位图，蓝牙直接发给打印头。
不需要官方 App，不联网，不登录。

<img src="docs/screenshots/editor.png" width="240" alt="编辑">
<img src="docs/screenshots/preview.png" width="240" alt="打印预览">
<img src="docs/screenshots/samples.png" width="240" alt="内置示例">

</div>

## 能做什么

- **编辑即预览**：粘贴、导入 `.txt` / `.md`，右边立刻看到打印出来的样子
- **真的排版**：标题、列表、表格、代码块、引用、粗体斜体、分隔线
- **公式**：行内 `$…$` 与独立 `$$…$$`，分式、根号、上下标由 JLaTeXMath 真排，不是图片
- **按纸调**：57 / 68 / 79 mm 三档纸宽，字号、行距、浓度、加粗四档可分调
- **长按存图**：在预览上长按，把当前位图存成 PNG
- **只申请蓝牙权限**：没有 `INTERNET`，稿子不会离开这台机器

## 支持的机器

在 **Paperang P3 MAX**（300dpi，79mm / 864 点）上验证过。协议层内置四套并自动识别：

| 协议 | 用在哪 |
|---|---|
| Paperang（老协议） | P3 系列，`0x02 … 0x03` 帧 |
| CatProtocol | 喵喵机，`0x51 0x78` 帧 |
| A5 | 换过主控的批次，MTU 与握手不同 |
| ESC/POS 光栅 | 通用热敏机，`GS v 0` |

别的型号大概率能出纸，但没实测过。

## 构建

需要 JDK 17 与 Android SDK 36（`compileSdk = 36`，`minSdk = 26`）。

```bash
git clone https://github.com/moevis/paperink.git && cd paperink
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 23 项单元测试
```

装到自己的机器上：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 与 `~/.gradle/gradle.properties` 里的 JDK 路径都由本机决定，
不要提交。发版签名靠仓库根的 `keystore.properties`（同样不入库），没有它时 release 产出未签名包：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## 代码怎么走

```
Markdown 文本
   │  md/MarkdownParser.kt      切成块与行内元素，不碰排版
   ▼
Doc
   │  render/PrintRenderer.kt   按点数算行、画字、二值化
   │  render/JlmMath.kt         公式交给 JLaTeXMath
   ▼
单色位图（576 / 720 / 864 点宽）
   │  bt/PrinterClient.kt       连机器、认协议、分包发
   ▼
热敏打印机
```

| 目录 | 干什么 |
|---|---|
| `bt/` | 蓝牙连接、协议识别、四套打印指令 |
| `md/` | Markdown → `Doc`，纯 JVM，可单测 |
| `render/` | `Doc` → 单色位图；公式、表格、分页都在这里 |
| `ui/` | Compose 界面，一个文件管一屏 |
| `file/` | 导入文件的编码探测、PNG 导出 |
| `samples/` | 三个内置示例，构建时直接当 asset 打包 |

## 已知限制

- 只在 P3 MAX 上跑通过，其余机型没有实机验证
- **行内分式排成 `a/b`**：TeX 的 text style 会把分子分母压到 0.43em，热敏纸 300dpi 二值化后直接糊掉。
  想要上下堆叠，写 `\dfrac{}{}`，那个不会被改写
- 行高比正文字高一截的情况仍存在（数学字体的斜杠有下降部）
- 保存的 PNG 在应用私有目录，没进相册
- 界面只有中文

## 许可

代码以 **GPL-3.0** 发布，全文见 [LICENSE](LICENSE)。选它是因为公式引擎 JLaTeXMath 本身就是 GPL 系，
整个作品跟着走最省心。

第三方组件：

| 组件 | 协议 |
|---|---|
| [JLaTeXMath-Android](https://github.com/noties/jlatexmath-android) | GPL-2.0 **或更高**，带 Classpath 例外 |
| [commonmark-java](https://github.com/commonmark/commonmark-java) | BSD-2-Clause |
| [Lucide](https://lucide.dev) | ISC |
| AndroidX / Jetpack Compose | Apache-2.0 |
