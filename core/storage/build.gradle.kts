plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aimusic.player.storage"
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
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    // 3a 这批（路径规范化 / 白名单 / 目录遍历 / 权限决策）都是纯逻辑，全部走 JVM 单测
    testImplementation(project(":core:testing"))
}
