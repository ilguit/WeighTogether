package com.palixander.scalesync.releasehistory

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

@CacheableTask
abstract class GenerateReleaseHistoryTask : DefaultTask() {
    @get:Input abstract val headSha: Property<String>
    @get:Input abstract val currentVersion: Property<String>
    @get:Input abstract val flavor: Property<String>
    @get:Input abstract val mode: Property<ReleaseHistoryMode>
    @get:Input abstract val generatorSchemaVersion: Property<Int>
    @get:Input abstract val gitMetadata: Property<String>
    @get:Input abstract val apkTagMetadata: Property<String>
    @get:Input abstract val trackedWorktreeState: Property<String>
    @get:Input abstract val trackedFragmentMetadata: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    @get:OutputDirectory abstract val kotlinOutputDirectory: DirectoryProperty
    @get:OutputDirectory abstract val resourceOutputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val repositoryRoot = project.rootProject.projectDir.toPath()
        val dirty = trackedWorktreeState.get().trim()
        if (dirty.isNotEmpty()) {
            throw GradleException(
                "Release history generation requires a clean tracked worktree; commit or stash tracked changes:\n$dirty",
            )
        }
        try {
            val baseline = BaselineParser.parse(baselineFile.get().asFile.readText())
            val history = ReleaseHistoryGenerator(GitRepository(repositoryRoot)).generate(
                headSha.get(),
                currentVersion.get(),
                ReleaseFlavor.fromId(flavor.get()),
                mode.get(),
                baseline,
            )
            val source = kotlinOutputDirectory.file(
                "com/palixander/scalesync/changelog/AppReleaseHistory.kt",
            ).get().asFile.toPath()
            val resource = resourceOutputDirectory.file("raw/app_release_history.json").get().asFile.toPath()
            Files.createDirectories(source.parent)
            Files.createDirectories(resource.parent)
            Files.writeString(source, CanonicalOutput.kotlinSource(history, "com.palixander.scalesync.changelog"))
            Files.writeString(resource, CanonicalOutput.json(history))
        } catch (exception: GenerationException) {
            throw GradleException("Cannot generate ${flavor.get()} release history: ${exception.message}", exception)
        }
    }
}
