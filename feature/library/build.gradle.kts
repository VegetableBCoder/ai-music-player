plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aimusic.player.library"
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
    // 02 §2：feature 只允许依赖 core:*，且 feature 之间禁止互相依赖
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:llm"))
    implementation(project(":core:playback"))
    implementation(project(":core:ui"))
}
