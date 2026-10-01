package com.palixander.weightogether.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RoomSchemaGuardPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `matching declared and committed versions pass`() {
        val project = fixture(declaredVersion = 8, schemaVersions = listOf(1, 8))

        val result = run(project, "verifyRoomSchemaVersion")

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyRoomSchemaVersion")?.outcome)
    }

    @Test
    fun `declared version below committed schema fails clearly`() {
        val project = fixture(declaredVersion = 7, schemaVersions = listOf(7, 8))

        val failure = runAndFail(project, "verifyRoomSchemaVersion")

        assertTrue(failure.message.orEmpty().contains("version 7 is below the highest committed Room schema version 8"))
    }

    @Test
    fun `declared version above committed schema fails clearly`() {
        val project = fixture(declaredVersion = 9, schemaVersions = listOf(7, 8))

        val failure = runAndFail(project, "verifyRoomSchemaVersion")

        assertTrue(failure.message.orEmpty().contains("version 9 does not match the highest committed Room schema version 8"))
    }

    @Test
    fun `assemble lifecycle invokes guard through preBuild`() {
        val project = fixture(declaredVersion = 9, schemaVersions = listOf(8))

        val failure = runAndFail(project, "assemble")

        assertTrue(failure.message.orEmpty().contains("version 9 does not match"))
    }

    @Test
    fun `check lifecycle invokes guard`() {
        val project = fixture(declaredVersion = 7, schemaVersions = listOf(8))

        val failure = runAndFail(project, "check")

        assertTrue(failure.message.orEmpty().contains("version 7 is below"))
    }

    private fun fixture(declaredVersion: Int, schemaVersions: List<Int>): File {
        val project = temporaryFolder.newFolder()
        project.resolve("settings.gradle.kts").writeText("rootProject.name = \"guard-test\"\n")
        project.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.palixander.weightogether.room-schema-guard")
            }

            tasks.register("preBuild")
            tasks.register("assemble") { dependsOn("preBuild") }
            tasks.register("check")
            """.trimIndent(),
        )
        project.resolve("src/main/kotlin/com/palixander/weightogether/data").mkdirs()
        project.resolve("src/main/kotlin/com/palixander/weightogether/data/AppDatabase.kt").writeText(
            "@Database(entities = [], version = $declaredVersion) abstract class AppDatabase\n",
        )
        val schemas = project.resolve("schemas/com.palixander.weightogether.data.AppDatabase")
        schemas.mkdirs()
        schemaVersions.forEach { schemas.resolve("$it.json").writeText("{}\n") }
        return project
    }

    private fun run(project: File, task: String) = runner(project, task).build()

    private fun runAndFail(project: File, task: String): UnexpectedBuildFailure =
        try {
            runner(project, task).build()
            error("Expected Gradle build to fail")
        } catch (failure: UnexpectedBuildFailure) {
            failure
        }

    private fun runner(project: File, task: String) = GradleRunner.create()
        .withProjectDir(project)
        .withArguments(task, "--stacktrace")
        .withPluginClasspath()
}
