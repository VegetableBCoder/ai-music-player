plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aimusic.player.llm"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 02 §2：core:llm 只依赖 core:common。分类/标签清单由调用方以参数传入，
    // 保证 LLM 层可独立测试。
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
