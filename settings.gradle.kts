pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-music-player"

// 模块划分见 docs/技术方案/02-详细设计总纲.md §2
include(":app")

include(":core:common")
include(":core:storage")
include(":core:data")
include(":core:llm")
include(":core:playback")
include(":core:ui")
include(":core:testing")

include(":feature:library")
include(":feature:search")
include(":feature:player")
include(":feature:mine")
