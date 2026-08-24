pluginManagement {
    includeBuild("tools/release-history-generator")
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
        maven("https://developer.huawei.com/repo/")
    }
}

rootProject.name = "HuaweiMiSync"
include(":app", ":core")
