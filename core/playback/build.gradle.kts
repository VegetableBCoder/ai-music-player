plugins {
    alias(libs.plugins.android.library)
    // @Module / @Provides 需要 KSP + Hilt 插件
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aimusic.player.playback"
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
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 直接用 MusicDatabase 的 DAO（`:core:data` 用 implementation 引 Room，不传导过来）——
    // 不显式加会报 "Cannot access 'RoomDatabase' which is a supertype of 'MusicDatabase'"
    implementation(libs.androidx.room.runtime)

    // PlaybackModule 用 @ApplicationScope 装配 NoopPlaybackController
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
