import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名。keystore.properties 只存在于打包者本机（已在 .gitignore 里），
// 开源仓库里没有它——此时 release 会产出未签名 APK，debug 照常能装。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 版本号。默认写死在这里，CI 打 tag 时用 -PpaperinkVersion=1.2.3 覆盖，
// 这样「tag 里的版本」和「APK 里的版本」永远是同一个东西，不会对不上。
val paperinkVersion = (project.findProperty("paperinkVersion") as String?) ?: "1.0.0"

// 版本号转成单调递增的 versionCode：1.2.3 → 102。
// 商店和系统都靠它判断「新版本」，必须随版本号一起涨。
val paperinkVersionCode = paperinkVersion.split(".")
    .mapNotNull { it.toIntOrNull() }
    .take(2)
    .let { if (it.size == 2) it[0] * 100 + it[1] else 1 }

android {
    namespace = "io.github.moevis.paperink"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.moevis.paperink"
        minSdk = 26
        targetSdk = 36
        versionCode = paperinkVersionCode
        versionName = paperinkVersion
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 调试包带 .dev 后缀：可以和手机上的正式包并排装着，免得为了试一次
            // 调试版把正式包（连同里面的稿子和设置）卸掉——两者的签名本来就不一样，
            // 覆盖安装必然失败。
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有 keystore.properties 就用正式签名；没有就退回 debug 签名。
            //
            // 退回 debug 签名是为了让 CI 在没配签名密钥时也能出一个「能直接装」的
            // 包——未签名的 APK 系统不让装，那条流水线就等于白跑。
            // 代价是 debug 签名的包和以后正式签名的包互相装不上（签名不一致），
            // 所以正式发布前记得把签名密钥配到 Actions Secrets 里。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        // 内置示例只有一份，就放在仓库根的 samples/，直接当 asset 根目录用。
        // 以前是复制一份到 assets/docs 再打包，两边迟早会改歪一个。
        getByName("main").assets.srcDir(rootProject.file("samples"))
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")

    // Markdown 解析。渲染是自己写的（见 render/PrintRenderer.kt），
    // commonmark 只负责把文本切成块和行内元素，没有 Android 依赖。
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    // 公式排版：JLaTeXMath 的 Android 移植。分式、根号、上下标、数学轴线、
    // 衬线斜体全部由引擎排，我们不自己算盒模型。
    // 协议是 GPL-2.0 **带 Classpath 例外**，可以被本 App 链接而不传染；
    // 但 APK 里确实带着这份 GPL 代码，README 的「第三方」一节写明了。
    implementation("ru.noties:jlatexmath-android:0.2.0")

    // 图标：Lucide（ISC），24 栅格 / 2 描边，线条笔触跟正文最接近。
    // 没用 material-icons-extended：那个 aar 有 34MB，且全是实心字形。
    implementation("com.composables:icons-lucide:1.1.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// compileSdk 36 + AGP 8.13 这组版本只能配 Compose 1.10.0；再新的 activity
// 或传递依赖会把 compose 顶到 1.12，而 1.12 要求 compileSdk 37。
val composeLock = setOf(
    "androidx.compose.runtime",
    "androidx.compose.ui",
    "androidx.compose.foundation",
    "androidx.compose.animation",
)
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group in composeLock &&
            requested.version != null && requested.version != "1.10.0"
        ) {
            useVersion("1.10.0")
        }
    }
}
