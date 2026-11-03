plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aggapp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aggapp.app"
        minSdk = 26
        targetSdk = 34
        // 手动维护：覆盖安装时按需递增
        versionCode = 10
        versionName = "1.1.0"
    }
    buildToolsVersion = "34.0.0"

    buildTypes {
        release {
            isMinifyEnabled = false
            // 与调试包用同一签名，保证覆盖安装签名一致
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // 调试包独立包名，与 release 包共存（调试日志导出/全量请求日志仅 debug 包开启）
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // APK 文件名：广油驿站青春版拼音缩写 + 版本号，如 gyyz-v1.1.0.apk；debug 包 gyyz-debug-v1.1.0.apk
    applicationVariants.all {
        val debugSuffix = if (buildType.name == "debug") "-debug" else ""
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "gyyz${debugSuffix}-v${versionName}.apk"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    implementation(project(":platform:api"))
    implementation(project(":platform:pdd"))
    implementation(project(":platform:taobao"))
    implementation(project(":platform:jd"))
    implementation(project(":platform:sf"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    implementation("androidx.webkit:webkit:1.11.0") // WebViewAssetLoader：极验页需 https origin（file:// origin 为 null 会被 gt4.js 拒绝）
    implementation("io.coil-kt:coil-compose:2.6.0") // 验证题图片选项（收货地址截图等）异步加载
    implementation("androidx.browser:browser:1.8.0") // Chrome Custom Tabs：PDD 验证页反 WebView 渲染隐藏，必须用真 Chrome 管线显示
}

