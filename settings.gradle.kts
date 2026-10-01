pluginManagement {
    includeBuild("tools/release-history-generator")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

includeBuild("tools/breed-catalog")
includeBuild("tools/weight-references")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WeighTogether"
include(":app", ":core")
