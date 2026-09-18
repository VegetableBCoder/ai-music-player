plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aimusic.player.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    // `09 §4.3` 的三件套是**无状态**组件，签名里就是 `SongListItem` / `SongFilter` 这些类型。
    // 在 `:core:ui` 里重定义一套 UI 模型再在 feature 层映射，与 `06 §3.1`「不做两套模型映射」冲突 ——
    // 故按 `02 §2` 落地补充(3e) 放开本依赖（原排 Phase 8，前移至 Phase 5）。
    implementation(project(":core:data"))

    // 设计系统模块对外暴露 Compose，feature 模块无需重复声明
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    // 行内图标（多选顶栏的关闭、批量条动作）—— 组件签名里就有 ImageVector，故 api 暴露
    api(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // CollectEvents 用 repeatOnLifecycle + LocalLifecycleOwner（09 §4.6）
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 多选态 / 面板裁剪是纯 Kotlin 逻辑，用 JVM 单测钉住（`09 §8`）
    testImplementation(project(":core:testing"))
}
