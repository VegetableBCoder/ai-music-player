plugins {
    alias(libs.plugins.android.library)
    // Hilt：`StorageModule`（10 §5.4）要绑 StorageSource / MetadataReader / 权限判定
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aimusic.player.storage"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 3a 这批（路径规范化 / 白名单 / 目录遍历 / 权限决策）都是纯逻辑，全部走 JVM 单测
    testImplementation(project(":core:testing"))

    // MMR 的真实读取只能在真机上验证（夹具由 tools/gen-audio-fixtures.py 生成）
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
