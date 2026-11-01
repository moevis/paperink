plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.paperang.mdprint"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.paperang.mdprint"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Markdown 解析用 commonmark（纯 Java，无 Android 依赖），渲染两边各自写。
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    // 公式排版：JLaTeXMath 的 Android 移植（GPL v2，自用无碍；若要上架需整个 App 开源）。
    // 分式/根号/上下标/数学轴线/衬线斜体全部由引擎排，替掉原来手写的盒模型
    // （MathBoxes / RuleSpan，已删）。字号 setSize 收像素（PIXELS_PER_POINT 默认 1.0）。
    implementation("ru.noties:jlatexmath-android:0.2.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 图标：Lucide（MIT，1500+ 个线条图标，24 栅格 / 2 描边）。
    // 为什么不用官方的：material-icons-extended 的 aar 有 34MB 且全是实心字形，
    // 跟这套偏「纸上铅字」的排版打架；material-icons-core 只有 40 个基础图标，
    // 打印机 / 蓝牙 / 文件这些一个都没有。Lucide 是线条描边，笔触跟正文最接近。
    implementation("com.composables:icons-lucide:1.1.0")
}

// 与 CFA 工程同样的约束：compileSdk 36 + AGP 8.13 只能配 compose 1.10.0，
// 新版的 activity / 传递依赖会把 compose 顶到 1.12（要求 compileSdk 37）。
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
