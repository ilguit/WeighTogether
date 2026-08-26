package com.example.huaweimisync.releasehistory

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyReleaseMetadataTask : DefaultTask() {
    @get:Input abstract val headSha: Property<String>
    @get:Input abstract val currentVersion: Property<String>
    @get:Input abstract val flavor: Property<String>
    @get:Input abstract val mode: Property<ReleaseHistoryMode>
    @get:Input abstract val trackedWorktreeState: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val dirty = trackedWorktreeState.get().trim()
        if (dirty.isNotEmpty()) {
            throw GradleException(
                "Release metadata preflight requires a clean tracked worktree; commit or stash tracked changes:\n$dirty",
            )
        }
        try {
            val repositoryRoot = project.rootProject.projectDir.toPath()
            ReleaseHistoryGenerator(GitRepository(repositoryRoot)).preflight(
                headSha.get(),
                currentVersion.get(),
                ReleaseFlavor.fromId(flavor.get()),
                mode.get(),
                BaselineParser.parse(baselineFile.get().asFile.readText()),
            )
        } catch (exception: GenerationException) {
            throw GradleException("Release metadata preflight failed: ${exception.message}", exception)
        }
    }
}
