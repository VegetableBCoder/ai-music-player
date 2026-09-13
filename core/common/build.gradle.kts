plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aimusic.player.common"
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Phase 1 这批类是纯 Kotlin（无 Android import），全部走 JVM 单测
    testImplementation(project(":core:testing"))
}
