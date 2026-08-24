package com.example.huaweimisync.releasehistory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReleaseHistoryGeneratorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `uses baseline boundary when repository has no release tags`() {
        val git = TestGit(directory)
        git.init()
        git.file("README.md", "legacy")
        git.commit("Legacy history")
        val boundary = git.head()
        git.fragment(25, "generated-history", true, "Автоматическая история версий")
        git.commit("Generate release history (#25)")
        val baseline = ReleaseHistoryBaseline(
            boundary,
            listOf(GeneratedRelease("0.1.5", boundary, listOf(ReleaseChange(3, "Старое изменение")))),
        )

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.7", ReleaseFlavor.PERSONAL, baseline)

        assertEquals(listOf("0.1.7", "0.1.5"), history.releases.map { it.version })
        assertEquals(listOf(25), history.releases.first().changes.map { it.issue })
    }

    @Test
    fun `builds newest-first history from annotated first-parent tags`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "one", true, "Первое изменение")
        git.commit("Add first change (#1)")
        val firstRelease = git.head()
        git.annotatedTag("apk/0.1.1")

        git.fragment(2, "technical", false, "Служебное изменение")
        git.commit("Prepare internals (#2)")
        git.fragment(3, "personal", true, "Новое измерение", listOf("personal"))
        git.commit("Add measurement (#3)")
        val head = git.head()

        val personal = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.2", ReleaseFlavor.PERSONAL)
        assertEquals(listOf("0.1.2", "0.1.1"), personal.releases.map { it.version })
        assertEquals(head, personal.releases[0].commitSha)
        assertEquals(listOf(3), personal.releases[0].changes.map { it.issue })
        assertEquals(firstRelease, personal.releases[1].commitSha)
        assertEquals(listOf(1), personal.releases[1].changes.map { it.issue })

        val enterprise = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.2", ReleaseFlavor.HUAWEI_ENTERPRISE)
        assertTrue(enterprise.releases[0].changes.isEmpty())
    }

    @Test
    fun `ignores lightweight unreachable and merged-side tags`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "base", true, "Первое изменение")
        git.commit("Base task (#1)")
        git.lightweightTag("apk/9.9.9")
        git.annotatedTag("apk/0.1.0")
        git.branch("side")
        git.fragment(2, "main", true, "Главное изменение")
        git.commit("Main task (#2)")
        git.checkout("side")
        git.fragment(99, "side", true, "Боковое изменение")
        git.commit("Side task (#99)")
        git.annotatedTag("apk/8.8.8")
        git.checkout("main")
        git.mergeNoFastForward("side", "Merge side branch")

        val tags = GitRepository(directory).reachableAnnotatedApkTags("HEAD")
        assertEquals(listOf("apk/0.1.0"), tags.map { it.name })
    }

    @Test
    fun `extracts merge and task issues once in commit order`() {
        val generator = ReleaseHistoryGenerator(GitRepository(directory))
        assertEquals(
            listOf(12, 8),
            generator.extractIssues(
                listOf(
                    "Implement generator (#12)",
                    "Refine generator (#12)",
                    "Merge pull request #8 from topic",
                    "Do not treat bare #77 as a task",
                ),
            ),
        )
    }

    @Test
    fun `rejects missing extra and conflicting fragments`() {
        val missing = TestGit(directory.resolve("missing"))
        missing.init()
        missing.file("README.md", "x")
        missing.commit("Missing notes (#7)")
        val missingError = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(missing.root)).generate("HEAD", "1", ReleaseFlavor.PERSONAL)
        }
        assertTrue(missingError.message!!.contains("#7"))

        val extra = TestGit(directory.resolve("extra"))
        extra.init()
        extra.fragment(7, "extra", true, "Лишнее изменение")
        extra.commit("Commit without issue")
        val extraError = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(extra.root)).generate("HEAD", "1", ReleaseFlavor.PERSONAL)
        }
        assertTrue(extraError.message!!.contains("#7"))

        val conflict = TestGit(directory.resolve("conflict"))
        conflict.init()
        conflict.fragment(7, "first", true, "Первое изменение")
        conflict.fragment(7, "second", true, "Второе изменение")
        conflict.commit("Conflicting notes (#7)")
        val conflictError = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(conflict.root)).generate("HEAD", "1", ReleaseFlavor.PERSONAL)
        }
        assertTrue(conflictError.message!!.contains("multiple fragments"))
    }

    @Test
    fun `canonical outputs are stable and escape content`() {
        val history = GeneratedHistory(
            listOf(GeneratedRelease("0.1.2", "abc", listOf(ReleaseChange(25, "Кавычка \" и \\ $ знак")))),
        )
        val firstJson = CanonicalOutput.json(history)
        assertEquals(firstJson, CanonicalOutput.json(history))
        assertTrue(firstJson.endsWith("\n"))
        assertTrue(firstJson.contains("Кавычка \\\" и \\\\"))
        val firstKotlin = CanonicalOutput.kotlinSource(history, "example.generated")
        assertEquals(firstKotlin, CanonicalOutput.kotlinSource(history, "example.generated"))
        assertTrue(firstKotlin.contains("\\$ знак"))
        assertFalse(firstKotlin.contains("\r"))
    }

    private class TestGit(val root: Path) {
        fun init() {
            Files.createDirectories(root)
            run("init", "-b", "main")
            run("config", "user.name", "Test")
            run("config", "user.email", "test@example.com")
        }

        fun fragment(issue: Int, slug: String, visible: Boolean, message: String, flavors: List<String> = emptyList()) {
            val typeLine = if (visible) "text: $message" else "reason: $message"
            val flavorLines = if (flavors.isEmpty()) "" else "flavors:\n" + flavors.joinToString("") { "  - $it\n" }
            file(".release-notes/$issue-$slug.yaml", "issue: $issue\nuserVisible: $visible\n$typeLine\n$flavorLines")
        }

        fun file(path: String, contents: String) {
            val target = root.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, contents)
        }

        fun commit(subject: String) {
            run("add", ".")
            run("commit", "-m", subject)
        }

        fun head(): String = run("rev-parse", "HEAD").trim()
        fun annotatedTag(name: String) = run("tag", "-a", name, "-m", name)
        fun lightweightTag(name: String) = run("tag", name)
        fun branch(name: String) = run("branch", name)
        fun checkout(name: String) = run("checkout", name)
        fun mergeNoFastForward(name: String, message: String) = run("merge", "--no-ff", name, "-m", message)

        private fun run(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $error" }
            return output
        }
    }
}
