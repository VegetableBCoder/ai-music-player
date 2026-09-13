plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aimusic.player.testing"
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

// 公共测试夹具与假实现（FakeStorageSource、假 LLM provider 等），
// 供各模块以 testImplementation(project(":core:testing")) 引入。
// 因此这里用 api 把测试库一并暴露出去。
dependencies {
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.mockk)
    api(libs.truth)
}
