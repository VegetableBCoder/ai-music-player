plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // 注意：AGP 9 起不再需要 org.jetbrains.kotlin.android 插件（内置 Kotlin 支持）
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ---------------------------------------------------------------------------
// 模块依赖守卫
//
// 文档 02 §2 要求"严格单向依赖，禁止反向依赖与 feature 间依赖"。口头约定必然
// 会被违反，所以落成可执行的检查：每个模块一份"允许依赖的白名单"，出现白名单
// 外的 project(...) 依赖就直接让构建失败。
//
// 放在 projectsEvaluated 里（配置期）而不是做成独立任务，是为了让每次构建都
// 自动守着，不依赖人记得手动跑。
// ---------------------------------------------------------------------------

/** feature 模块只允许依赖这些 core 模块（02 §2 明确不含 :core:storage） */
val featureAllowedCore = setOf(
    ":core:ui", ":core:playback", ":core:data", ":core:llm", ":core:common",
)

/** 组装根（:app）可以依赖全部 core 模块 */
val appAllowedCore = featureAllowedCore + ":core:storage"

val allFeatureModules = setOf(
    ":feature:library", ":feature:search", ":feature:player", ":feature:mine",
)

val moduleDependencyRules: Map<String, Set<String>> = mapOf(
    // 叶子：不依赖任何本工程模块
    ":core:common" to emptySet(),
    ":core:testing" to emptySet(),

    ":core:storage" to setOf(":core:common"),
    ":core:data" to setOf(":core:storage", ":core:common"),
    ":core:llm" to setOf(":core:common"),
    ":core:playback" to setOf(":core:data", ":core:common"),
    // 注：02 §2 未规定 core:ui 的依赖。先只允许 core:common；等 Phase 8 真正需要
    // PlaybackUiState / 实体模型时再显式放宽，避免现在凭空猜。
    ":core:ui" to setOf(":core:common"),

    // feature 之间禁止互相依赖
    ":feature:library" to featureAllowedCore,
    ":feature:search" to featureAllowedCore,
    ":feature:player" to featureAllowedCore,
    ":feature:mine" to featureAllowedCore,

    ":app" to appAllowedCore + allFeatureModules,
)

gradle.projectsEvaluated {
    val violations = mutableListOf<String>()

    moduleDependencyRules.forEach { (modulePath, allowed) ->
        val target = rootProject.project(modulePath)

        val declared = target.configurations
            // 测试配置不受生产依赖规则约束（例如 testImplementation(project(":core:testing")))
            .filterNot { it.name.contains("test", ignoreCase = true) }
            .flatMap { config -> config.dependencies.filterIsInstance<ProjectDependency>() }
            .map { it.path }
            .filter { it != modulePath }
            .distinct()
            .sorted()

        declared.filterNot { it in allowed }.forEach { bad ->
            violations += "  ✗ $modulePath → $bad" +
                "（允许：${allowed.sorted().joinToString(", ").ifEmpty { "无" }}）"
        }
    }

    if (violations.isNotEmpty()) {
        throw GradleException(
            "模块依赖违反 docs/技术方案/02-详细设计总纲.md §2 的单向约定：\n" +
                violations.joinToString("\n"),
        )
    }
}

// ---------------------------------------------------------------------------
// instrumented 测试名不得含空格
//
// Kotlin 反引号名会变成方法名，而 DEX 040（minSdk ≥ 30）之前不允许方法名带空格。
// 本项目 minSdk = 26，带空格会让 D8 在 dexBuilderDebugAndroidTest 阶段失败，而且报错
// 被 AGP 吞成一句 “Failed to process: ...dirs”，只能加 --stacktrace 才看得到真因。
// 这个坑踩了两次，所以落成配置期检查（见 docs/技术方案/01 §2.2 第 15 条）。
// ---------------------------------------------------------------------------
gradle.projectsEvaluated {
    val backtickFun = Regex("""fun\s+`([^`]*)`""")
    val offending = mutableListOf<String>()

    rootProject.allprojects.forEach { target ->
        val androidTestDir = target.file("src/androidTest")
        if (androidTestDir.isDirectory) {
            androidTestDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    backtickFun.findAll(file.readText()).forEach { match ->
                        val name = match.groupValues[1]
                        if (name.contains(' ')) {
                            offending += "  ✗ ${target.path} ${file.name}: `fun $name`"
                        }
                    }
                }
        }
    }

    if (offending.isNotEmpty()) {
        throw GradleException(
            "instrumented 测试名含空格会令 D8 dex 失败（DEX 040 之前不允许）。请把空格改成 `_`：\n" +
                offending.joinToString("\n"),
        )
    }
}
