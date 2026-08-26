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
    fun `build mode keeps baseline and omits an untagged head`() {
        val git = TestGit(directory)
        git.init()
        git.file("README.md", "legacy")
        git.commit("Legacy history")
        val boundary = git.head()
        git.file("README.md", "work after the last release")
        git.commit("Unreleased work without release metadata")
        val baselineRelease = GeneratedRelease("0.1.5", boundary, emptyList())
        val baseline = ReleaseHistoryBaseline(
            boundary,
            listOf(
                BootstrapRelease(
                    baselineRelease,
                    HistoricalBoundaryEvidence(
                        HistoricalBoundaryStatus.CONFIRMED,
                        boundaryCommit = boundary,
                        candidateCommit = null,
                        source = "test",
                    ),
                ),
            ),
        )

        val result = ReleaseHistoryGenerator(GitRepository(directory))
            .preflight("HEAD", "0.1.6", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD, baseline)

        assertEquals(listOf(baselineRelease), result.history.releases)
        assertEquals(null, result.range)
    }

    @Test
    fun `build mode includes tagged releases but ignores work after the newest tag`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Release feature (#1)")
        val taggedRelease = git.head()
        git.annotatedTag("apk/0.1.1")
        git.file("README.md", "unreleased")
        git.commit("Unreleased work without fragment (#2)")

        val result = ReleaseHistoryGenerator(GitRepository(directory))
            .preflight("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)

        assertEquals(listOf("0.1.1"), result.history.releases.map { it.version })
        assertEquals(taggedRelease, result.history.releases.single().commitSha)
        assertEquals(listOf(1), result.history.releases.single().changes.map { it.issue })
        assertEquals("root..$taggedRelease", result.range!!.displayName)
    }

    @Test
    fun `release mode includes and validates an untagged candidate`() {
        val git = TestGit(directory)
        git.init()
        git.file("README.md", "missing fragment")
        git.commit("Candidate work (#30)")

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(directory))
                .generate("HEAD", "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
        }

        assertTrue(error.message!!.contains("#30"))
        assertTrue(error.message!!.contains("without a changed fragment"))
    }

    @Test
    fun `parses supported release history modes and rejects unknown values`() {
        assertEquals(ReleaseHistoryMode.BUILD, ReleaseHistoryMode.fromId("build"))
        assertEquals(ReleaseHistoryMode.RELEASE, ReleaseHistoryMode.fromId("release"))

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryMode.fromId("candidate")
        }
        assertTrue(error.message!!.contains("expected one of: build, release"))
    }

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
            listOf(
                BootstrapRelease(
                    GeneratedRelease("0.1.5", boundary, listOf(ReleaseChange(3, "Старое изменение"))),
                    HistoricalBoundaryEvidence(
                        HistoricalBoundaryStatus.UNKNOWN,
                        boundaryCommit = null,
                        candidateCommit = boundary,
                        source = "test candidate",
                    ),
                ),
            ),
        )

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.7", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE, baseline)

        assertEquals(listOf("0.1.7", "0.1.5"), history.releases.map { it.version })
        assertEquals(listOf(25), history.releases.first().changes.map { it.issue })
    }

    @Test
    fun `rejects shallow history even when baseline boundary is available and tags are missing`() {
        val source = TestGit(directory.resolve("source"))
        source.init()
        source.file("README.md", "legacy")
        source.commit("Legacy history")
        val boundary = source.head()
        source.annotatedTag("apk/0.1.5")
        source.fragment(26, "preflight", false, "Проверка выпуска")
        source.commit("Add release preflight (#26)")

        val shallow = directory.resolve("shallow")
        runGit(
            directory,
            "clone",
            "--quiet",
            "--no-tags",
            "--depth=2",
            source.root.toUri().toString(),
            shallow.toString(),
        )
        val baseline = ReleaseHistoryBaseline(
            boundary,
            listOf(
                BootstrapRelease(
                    GeneratedRelease("0.1.5", boundary, emptyList()),
                    HistoricalBoundaryEvidence(
                        HistoricalBoundaryStatus.CONFIRMED,
                        boundaryCommit = boundary,
                        candidateCommit = null,
                        source = "test",
                    ),
                ),
            ),
        )

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(shallow))
                .preflight("HEAD", "0.1.6", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD, baseline)
        }

        assertTrue(error.message!!.contains("root..${runGit(shallow, "rev-parse", "HEAD").trim()}"))
        assertTrue(error.message!!.contains("shallow or incomplete"))
        assertTrue(error.message!!.contains("git fetch --unshallow --tags"))
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
            .generate("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
        assertEquals(listOf("0.1.2", "0.1.1"), personal.releases.map { it.version })
        assertEquals(head, personal.releases[0].commitSha)
        assertEquals(listOf(3), personal.releases[0].changes.map { it.issue })
        assertEquals(firstRelease, personal.releases[1].commitSha)
        assertEquals(listOf(1), personal.releases[1].changes.map { it.issue })

        val enterprise = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.2", ReleaseFlavor.HUAWEI_ENTERPRISE, ReleaseHistoryMode.RELEASE)
        assertTrue(enterprise.releases[0].changes.isEmpty())
    }

    @Test
    fun `both modes rebuild an already tagged head without duplicating the release`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(25, "history", true, "История версий")
        git.commit("Generate history (#25)")
        git.annotatedTag("apk/0.1.7")

        ReleaseHistoryMode.entries.forEach { mode ->
            val history = ReleaseHistoryGenerator(GitRepository(directory))
                .generate(git.head(), "0.1.7", ReleaseFlavor.PERSONAL, mode)

            assertEquals(listOf("0.1.7"), history.releases.map { it.version }, mode.id)
            assertEquals(listOf(25), history.releases.single().changes.map { it.issue }, mode.id)
        }
    }

    @Test
    fun `preflight returns the resolved release range and metadata`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "first", true, "Первое изменение")
        git.commit("First task (#1)")
        val previous = git.head()
        git.annotatedTag("apk/0.1.1")
        git.fragment(2, "second", false, "Техническое изменение")
        git.commit("Second task (#2)")

        val result = ReleaseHistoryGenerator(GitRepository(directory))
            .preflight("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)

        assertEquals(git.head(), result.headSha)
        assertEquals("apk/0.1.1", result.previousTag?.name)
        val range = requireNotNull(result.range)
        assertEquals("$previous..${git.head()}", range.displayName)
        assertEquals(listOf(2), range.issues)
        assertEquals(listOf(2), range.fragments.map { it.issue })
        assertEquals("0.1.2", result.history.releases.first().version)
    }

    @Test
    fun `both modes reject version not newer than previous release with actionable range`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "first", true, "Первое изменение")
        git.commit("First task (#1)")
        val previous = git.head()
        git.annotatedTag("apk/0.1.2")
        git.fragment(2, "second", false, "Техническое изменение")
        git.commit("Second task (#2)")

        ReleaseHistoryMode.entries.forEach { mode ->
            val error = assertThrows(GenerationException::class.java) {
                ReleaseHistoryGenerator(GitRepository(directory))
                    .preflight("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, mode)
            }

            assertTrue(error.message!!.contains("$previous..${git.head()}"), mode.id)
            assertTrue(error.message!!.contains("increment versionName"), mode.id)
        }
    }

    @Test
    fun `rejects current version used by an annotated tag on another local branch`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "first", true, "Первое изменение")
        git.commit("First task (#1)")
        git.branch("release-side")
        git.fragment(2, "main", false, "Главное изменение")
        git.commit("Main task (#2)")
        val mainHead = git.head()
        git.checkout("release-side")
        git.fragment(3, "side", false, "Боковое изменение")
        git.commit("Side task (#3)")
        git.annotatedTag("apk/0.1.2")
        val occupiedSha = git.head()
        git.checkout("main")

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(directory))
                .preflight(mainHead, "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)
        }

        assertTrue(error.message!!.contains("apk/0.1.2"))
        assertTrue(error.message!!.contains(occupiedSha))
        assertTrue(error.message!!.contains("choose a new versionName"))
    }

    @Test
    fun `rejects baseline boundary that is only a merged ancestor`() {
        val git = TestGit(directory)
        git.init()
        git.file("README.md", "base")
        git.commit("Base")
        git.branch("side")
        git.file("README.md", "main")
        git.commit("Main")
        git.checkout("side")
        git.file("side.txt", "side")
        git.commit("Side boundary")
        val sideBoundary = git.head()
        git.checkout("main")
        git.mergeNoFastForward("side", "Merge side")

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(directory)).generate(
                "HEAD",
                "0.1.7",
                ReleaseFlavor.PERSONAL,
                ReleaseHistoryMode.BUILD,
                ReleaseHistoryBaseline(sideBoundary, emptyList()),
            )
        }
        assertTrue(error.message!!.contains("first-parent"))
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
            ReleaseHistoryGenerator(GitRepository(missing.root))
                .generate("HEAD", "1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
        }
        assertTrue(missingError.message!!.contains("#7"))

        val extra = TestGit(directory.resolve("extra"))
        extra.init()
        extra.fragment(7, "extra", true, "Лишнее изменение")
        extra.commit("Commit without issue")
        val extraError = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(extra.root))
                .generate("HEAD", "1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
        }
        assertTrue(extraError.message!!.contains("#7"))

        val conflict = TestGit(directory.resolve("conflict"))
        conflict.init()
        conflict.fragment(7, "first", true, "Первое изменение")
        conflict.fragment(7, "second", true, "Второе изменение")
        conflict.commit("Conflicting notes (#7)")
        val conflictError = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(conflict.root))
                .generate("HEAD", "1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
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

    @Test
    fun `tracked fragment input ignores untracked files and changes with committed fragments`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "first", true, "Первое изменение")
        git.commit("Add first change (#1)")
        val repository = GitRepository(directory)
        val firstHead = git.head()
        val firstInput = repository.trackedFragmentMetadata(firstHead)
        val firstOutput = ReleaseHistoryGenerator(repository)
            .generate(firstHead, "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)

        git.fragment(99, "untracked", true, "Не должно попасть в историю")

        assertEquals(firstInput, repository.trackedFragmentMetadata(firstHead))
        assertEquals(
            firstOutput,
            ReleaseHistoryGenerator(repository)
                .generate(firstHead, "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE),
        )

        git.delete(".release-notes/99-untracked.yaml")
        git.fragment(2, "second", true, "Второе изменение")
        git.commit("Add second change (#2)")
        val secondHead = git.head()

        assertTrue(repository.trackedFragmentMetadata(secondHead) != firstInput)
        assertEquals(
            listOf(1, 2),
            ReleaseHistoryGenerator(repository)
                .generate(secondHead, "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
                .releases.single().changes.map { it.issue },
        )
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

        fun delete(path: String) {
            Files.delete(root.resolve(path))
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

    private fun runGit(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $error" }
        return output
    }
}
