plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // @HiltViewModel 需要 KSP + Hilt 插件
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aimusic.player.mine"
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
    implementation(project(":core:data"))
    implementation(project(":core:llm"))
    implementation(project(":core:playback"))
    implementation(project(":core:ui"))
    // 应用内目录浏览器要直接列目录（02 §2 的落地补充（3e））
    implementation(project(":core:storage"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // 扫描页的渲染测试（09 §8、11 §8.3）跑真机 androidTest，与 Room 测试同一个决定。
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
