package com.example.huaweimisync.releasehistory

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project

class ReleaseHistoryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
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
                    configured.generatorSchemaVersion.set(1)
                    configured.gitMetadata.set(project.providers.exec {
                        it.commandLine(
                            "git", "-C", project.rootDir, "log", "--first-parent",
                            "--format=%H%x00%s", "HEAD", "--", ".release-notes", ".release-history",
                        )
                    }.standardOutput.asText)
                    configured.baselineFile.set(project.rootProject.layout.projectDirectory.file(".release-history/baseline.yaml"))
                    configured.fragments.from(project.rootProject.fileTree(".release-notes") {
                        it.include("*.yaml")
                    })
                    configured.kotlinOutputDirectory.set(project.layout.buildDirectory.dir("generated/releaseHistory/${variant.name}/kotlin"))
                    configured.resourceOutputDirectory.set(project.layout.buildDirectory.dir("generated/releaseHistory/${variant.name}/res"))
                }
                variant.sources.java?.addGeneratedSourceDirectory(task, GenerateReleaseHistoryTask::kotlinOutputDirectory)
                variant.sources.res?.addGeneratedSourceDirectory(task, GenerateReleaseHistoryTask::resourceOutputDirectory)
            }
        }
    }
}
