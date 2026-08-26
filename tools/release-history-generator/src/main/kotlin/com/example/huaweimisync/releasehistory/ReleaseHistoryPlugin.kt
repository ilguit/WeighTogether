package com.example.huaweimisync.releasehistory

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project

class ReleaseHistoryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            val releaseHistoryMode = project.providers.gradleProperty("releaseHistoryMode")
                .map(ReleaseHistoryMode::fromId)
                .orElse(ReleaseHistoryMode.BUILD)
            @Suppress("UNCHECKED_CAST")
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                val applicationVariant = variant as ApplicationVariant
                val flavor = variant.productFlavors.singleOrNull()?.second
                    ?: throw IllegalStateException("Release history requires exactly one product flavor for ${variant.name}")
                if (flavor !in setOf("personal", "huaweiEnterprise")) {
                    throw IllegalStateException("Unsupported release history flavor '$flavor' for ${variant.name}")
                }
                val capitalized = variant.name.replaceFirstChar(Char::uppercaseChar)
                val task = project.tasks.register(
                    "generate${capitalized}ReleaseHistory",
                    GenerateReleaseHistoryTask::class.java,
                )
                task.configure { configured ->
                    configured.group = "build"
                    configured.description = "Generates release history for ${variant.name}"
                    configured.headSha.set(project.providers.exec {
                        it.commandLine("git", "-C", project.rootDir, "rev-parse", "HEAD")
                    }.standardOutput.asText.map(String::trim))
                    val versionName = applicationVariant.outputs.singleOrNull()?.versionName
                        ?: throw IllegalStateException("Release history requires one output for ${variant.name}")
                    configured.currentVersion.set(versionName.map { it.substringBefore('-') })
                    configured.flavor.set(flavor)
                    configured.mode.set(releaseHistoryMode)
                    configured.generatorSchemaVersion.set(2)
                    configured.gitMetadata.set(project.providers.exec {
                        it.commandLine(
                            "git", "-C", project.rootDir, "log", "--first-parent",
                            "--format=%H%x00%s", "HEAD", "--", ".release-notes", ".release-history",
                        )
                    }.standardOutput.asText)
                    configured.apkTagMetadata.set(project.providers.exec {
                        it.commandLine(
                            "git", "-C", project.rootDir, "for-each-ref",
                            "--format=%(refname)%00%(objecttype)%00%(*objectname)", "refs/tags/apk/",
                        )
                    }.standardOutput.asText)
                    configured.trackedWorktreeState.set(project.providers.exec {
                        it.commandLine("git", "-C", project.rootDir, "status", "--porcelain", "--untracked-files=no")
                    }.standardOutput.asText)
                    configured.trackedFragmentMetadata.set(project.providers.exec {
                        it.commandLine(
                            "git", "-C", project.rootDir, "ls-tree", "-r", "--full-tree", "HEAD", "--",
                            ".release-notes",
                        )
                    }.standardOutput.asText)
                    configured.baselineFile.set(project.rootProject.layout.projectDirectory.file(".release-history/baseline.yaml"))
                    configured.kotlinOutputDirectory.set(project.layout.buildDirectory.dir("generated/releaseHistory/${variant.name}/kotlin"))
                    configured.resourceOutputDirectory.set(project.layout.buildDirectory.dir("generated/releaseHistory/${variant.name}/res"))
                }
                variant.sources.java?.addGeneratedSourceDirectory(task, GenerateReleaseHistoryTask::kotlinOutputDirectory)
                variant.sources.res?.addGeneratedSourceDirectory(task, GenerateReleaseHistoryTask::resourceOutputDirectory)

                if (variant.name == "personalDebug") {
                    val preflight = project.tasks.register(
                        "verifyPersonalDebugReleaseMetadata",
                        VerifyReleaseMetadataTask::class.java,
                    )
                    preflight.configure { configured ->
                        configured.group = "verification"
                        configured.description = "Verifies release metadata before producing the personal debug APK"
                        configured.headSha.set(task.flatMap(GenerateReleaseHistoryTask::headSha))
                        configured.currentVersion.set(task.flatMap(GenerateReleaseHistoryTask::currentVersion))
                        configured.flavor.set(task.flatMap(GenerateReleaseHistoryTask::flavor))
                        configured.mode.set(task.flatMap(GenerateReleaseHistoryTask::mode))
                        configured.trackedWorktreeState.set(task.flatMap(GenerateReleaseHistoryTask::trackedWorktreeState))
                        configured.baselineFile.set(task.flatMap(GenerateReleaseHistoryTask::baselineFile))
                    }
                    project.tasks.matching {
                        it.name == "packagePersonalDebug" || it.name == "assemblePersonalDebug"
                    }.configureEach {
                        it.dependsOn(preflight)
                    }
                }
            }
        }
    }
}
