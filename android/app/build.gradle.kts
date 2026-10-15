import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
/*
 * Android SDK 位置。
 *
 * 三级兜底：local.properties（Android Studio 自己写）→ 环境变量 → 常见默认位置。
 * 最后那一档在开源版里改成了相对通用的写法 ——
 * 写死某台机器的绝对路径带着用户名，对别人既没用也不合适。
 */
val sdkDir: String = localProps.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/AppData/Local/Android/Sdk"

android {
    namespace = "com.notedrop.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notedrop.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-demo"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

// 构建环境信息：写入 BuildConfig，方便 App 内显示“是否已配置签名 / 如何构建”
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = signingProps.getProperty("storeFile") != null

android.buildTypes.named("release") {
    if (hasSigning) {
        signingConfig = android.signingConfigs.create("release") {
            storeFile = rootProject.file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
}

android.defaultConfig {
    buildConfigField("String", "SDK_DIR", "\"${sdkDir.replace("\\", "/")}\"")
    buildConfigField("boolean", "HAS_RELEASE_SIGNING", hasSigning.toString())
}

android.buildFeatures.buildConfig = true

android.defaultConfig {
    /*
      ABI 过滤。

      MediaPipe 的 native 库同时带了 arm64 / armv7 / x86 / x86_64 四套，
      不限制的话 APK 会从 13MB 涨到 77MB —— 而真机只用得到其中一套。

      保留 arm64-v8a（2016 年后的主流手机）和 armeabi-v7a（老旧设备），
      去掉 x86 系列（只有模拟器用）。

      不用 splits 拆多包：那样会生成好几个 APK，
      用户不知道该装哪个。保持单包更省心。
    */
    ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
}

// 用 lint 的 Release 检查跑一遍，能在没有设备的情况下抓出
// 资源引用错误、Manifest 问题、API 等级误用等静态缺陷。
android.lint {
    abortOnError = true
    checkReleaseBuilds = true
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // OkHttp 同时提供 HTTP 与 WebSocket，一个依赖覆盖两种需求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    /*
      贴纸抠图**不再需要任何 ML 依赖**。

      ## 为什么把 MediaPipe 删掉

      原来用 MediaPipe `deeplab_v3` 做自动抠图。那是**语义分割**模型，
      只认识 PASCAL VOC 那 20 类（人、车、猫、狗、瓶子…），
      输出的是"这块像素属于哪一类"，不是"哪块是主体"。

      所以拿普通照片或截图给它，结果基本是随机的。
      用户实测"几乎全部失败"—— 这是方案选错，不是调参能救的。

      现在换成**颜色键（魔棒）**：用户点哪儿，就去掉哪儿颜色相近的连片区。
      它不需要认识任何东西，只比颜色，所以白底图、截图、手写字几乎百发百中，
      而且结果完全确定、可撤销、可调容差。

      ## 体积

      删掉这个依赖后 APK 从 39.2MB 降到 38.1MB —— 只少了 1.1MB。

      原因是之前已经加了 ABI 过滤（下面 defaultConfig 里那段），
      MediaPipe 的 native 库那时就只剩 arm64 一套了，本来就不是大头。
      我原先以为能省 25MB，是记错了，这里更正一下。

      顺带把 assets/ml/deeplab_v3.tflite（2.78MB，仅这个依赖用得到）
      挪去了 ref/deprecated-ml/，没有删除，需要时可以挪回来。
    */
}
