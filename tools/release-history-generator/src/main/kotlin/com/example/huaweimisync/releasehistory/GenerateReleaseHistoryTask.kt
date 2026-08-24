package com.example.huaweimisync.releasehistory

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
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
    @get:Input abstract val generatorSchemaVersion: Property<Int>
    @get:Input abstract val gitMetadata: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fragments: ConfigurableFileCollection

    @get:OutputDirectory abstract val kotlinOutputDirectory: DirectoryProperty
    @get:OutputDirectory abstract val resourceOutputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val repositoryRoot = project.rootProject.projectDir.toPath()
        val dirty = git(repositoryRoot, "status", "--porcelain", "--untracked-files=no").trim()
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
                baseline,
            )
            val source = kotlinOutputDirectory.file(
                "com/example/huaweimisync/changelog/AppReleaseHistory.kt",
            ).get().asFile.toPath()
            val resource = resourceOutputDirectory.file("raw/app_release_history.json").get().asFile.toPath()
            Files.createDirectories(source.parent)
            Files.createDirectories(resource.parent)
            Files.writeString(source, CanonicalOutput.kotlinSource(history, "com.example.huaweimisync.changelog"))
            Files.writeString(resource, CanonicalOutput.json(history))
        } catch (exception: GenerationException) {
            throw GradleException("Cannot generate ${flavor.get()} release history: ${exception.message}", exception)
        }
    }

    private fun git(root: java.nio.file.Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("git ${arguments.joinToString(" ")} failed: ${error.trim()}")
        return output
    }
}
