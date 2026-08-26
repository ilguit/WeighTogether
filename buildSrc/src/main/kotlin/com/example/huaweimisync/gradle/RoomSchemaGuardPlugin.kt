package com.example.huaweimisync.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyRoomSchemaVersionTask : DefaultTask() {
    @get:InputFile
    abstract val databaseSource: RegularFileProperty

    @get:InputDirectory
    abstract val schemaDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val source = databaseSource.get().asFile.readText()
        val databaseAnnotation = Regex(
            pattern = """@Database\s*\((.*?)\)\s*abstract\s+class\s+AppDatabase""",
            option = RegexOption.DOT_MATCHES_ALL,
        ).find(source) ?: throw GradleException(
            "Cannot determine the Room version: AppDatabase @Database annotation was not found.",
        )
        val declaredVersion = Regex("""\bversion\s*=\s*(\d+)""")
            .find(databaseAnnotation.groupValues[1])
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: throw GradleException(
                "Cannot determine the Room version: @Database must declare a numeric version.",
            )

        val highestSchemaVersion = schemaDirectory.get().asFile
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .maxOrNull()
            ?: throw GradleException(
                "Cannot verify the Room version: no numeric schema snapshots were found in " +
                    "${schemaDirectory.get().asFile}.",
            )

        if (declaredVersion < highestSchemaVersion) {
            throw GradleException(
                "AppDatabase version $declaredVersion is below the highest committed Room schema " +
                    "version $highestSchemaVersion. Restore or advance the @Database version.",
            )
        }
        if (declaredVersion > highestSchemaVersion) {
            throw GradleException(
                "AppDatabase version $declaredVersion does not match the highest committed Room " +
                    "schema version $highestSchemaVersion. Commit the matching schema snapshot.",
            )
        }
    }
}

class RoomSchemaGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val verification = project.tasks.register(
            "verifyRoomSchemaVersion",
            VerifyRoomSchemaVersionTask::class.java,
        ) {
            group = "verification"
            description = "Verifies that AppDatabase and the latest committed Room schema use the same version."
            databaseSource.convention(
                project.layout.projectDirectory.file(
                    "src/main/kotlin/com/example/huaweimisync/data/AppDatabase.kt",
                ),
            )
            schemaDirectory.convention(
                project.layout.projectDirectory.dir(
                    "schemas/com.example.huaweimisync.data.AppDatabase",
                ),
            )
        }

        project.tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
            dependsOn(verification)
        }
    }
}
