plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // @HiltViewModel 需要 KSP + Hilt 插件
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // VM 单测（JVM）+ Content 渲染测试（真机 androidTest），与 feature:mine 同一套做法。
    // :core:testing 已用 api 暴露 junit / truth / mockk / coroutines-test。
    testImplementation(project(":core:testing"))
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // createComposeRule 需要一个宿主 Activity，由它提供（只在 debug 变体里）
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
