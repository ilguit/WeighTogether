package com.palixander.scalesync.releasehistory

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
    fun `build mode keeps tagged releases and exposes work after the newest tag separately`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Release feature (#1)")
        val taggedRelease = git.head()
        git.annotatedTag("apk/0.1.1")
        git.fragment(2, "latest", true, "Последнее улучшение")
        git.commit("Add latest improvement (#2)")

        val result = ReleaseHistoryGenerator(GitRepository(directory))
            .preflight("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)

        assertEquals(listOf("0.1.1"), result.history.releases.map { it.version })
        assertEquals(taggedRelease, result.history.releases.single().commitSha)
        assertEquals(listOf(1), result.history.releases.single().changes.map { it.issue })
        assertEquals(listOf(2), result.history.latestChanges.map { it.issue })
        assertEquals("root..$taggedRelease", result.range!!.displayName)
    }

    @Test
    fun `build mode allows unchanged version after its release tag`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Release feature (#1)")
        val taggedRelease = git.head()
        git.annotatedTag("apk/0.1.1")
        git.fragment(2, "ordinary-build", false, "Подготовка обычной сборки")
        git.commit("Prepare another build (#2)")

        val result = ReleaseHistoryGenerator(GitRepository(directory))
            .preflight("HEAD", "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)

        assertEquals(listOf("0.1.1"), result.history.releases.map { it.version })
        assertEquals(taggedRelease, result.history.releases.single().commitSha)
        assertTrue(result.history.latestChanges.isEmpty())
    }

    @Test
    fun `build latest changes are newest first and filtered by visibility and flavor`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Release feature (#1)")
        git.annotatedTag("apk/0.1.1")
        git.fragment(2, "older", true, "Более раннее улучшение")
        git.commit("Add older improvement (#2)")
        git.fragment(3, "technical", false, "Служебная подготовка")
        git.commit("Prepare internals (#3)")
        git.fragment(4, "enterprise", true, "Изменение предприятия", listOf("huaweiEnterprise"))
        git.commit("Add enterprise improvement (#4)")
        git.fragment(5, "newer", true, "Самое новое улучшение")
        git.commit("Add newest improvement (#5)")

        val personal = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)
        val enterprise = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.1", ReleaseFlavor.HUAWEI_ENTERPRISE, ReleaseHistoryMode.BUILD)

        assertEquals(listOf(5, 2), personal.latestChanges.map { it.issue })
        assertEquals(listOf(5, 4, 2), enterprise.latestChanges.map { it.issue })
        assertEquals(listOf(1), personal.releases.single().changes.map { it.issue })
    }

    @Test
    fun `release mode leaves latest changes empty and keeps candidate only in release history`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Release feature (#1)")
        git.annotatedTag("apk/0.1.1")
        git.fragment(2, "candidate", true, "Новое улучшение")
        git.commit("Add release candidate (#2)")

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)

        assertTrue(history.latestChanges.isEmpty())
        assertEquals(listOf(2), history.releases.first().changes.map { it.issue })
    }

    @Test
    fun `tagged releases keep legacy order while an untagged candidate is newest first`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "older-release", true, "Раннее изменение выпуска")
        git.commit("Add older release change (#1)")
        git.fragment(2, "newer-release", true, "Позднее изменение выпуска")
        git.commit("Add newer release change (#2)")
        git.annotatedTag("apk/0.1.1")
        git.fragment(3, "older-candidate", true, "Раннее изменение кандидата")
        git.commit("Add older candidate change (#3)")
        git.fragment(4, "newer-candidate", true, "Позднее изменение кандидата")
        git.commit("Add newer candidate change (#4)")

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)

        assertEquals(listOf(4, 3), history.releases[0].changes.map { it.issue })
        assertEquals(listOf(1, 2), history.releases[1].changes.map { it.issue })
    }

    @Test
    fun `build mode rejects a version older than the latest release`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Release feature (#1)")
        git.annotatedTag("apk/0.1.2")
        git.file("README.md", "ordinary build")
        git.commit("Prepare another build (#2)")

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(directory))
                .preflight("HEAD", "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)
        }

        assertTrue(error.message!!.contains("older than latest release tag apk/0.1.2"))
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
    fun `build without release tags rejects version older than newest baseline release`() {
        val (git, baseline) = repositoryWithBaseline()

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(directory))
                .preflight("HEAD", "0.1.4", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD, baseline)
        }

        assertTrue(error.message!!.contains("older than newest baseline release 0.1.5"))
        assertTrue(error.message!!.contains("restore at least versionName 0.1.5"))
        assertEquals(git.head(), runGit(directory, "rev-parse", "HEAD").trim())
    }

    @Test
    fun `build without release tags allows version equal to newest baseline release`() {
        val (_, baseline) = repositoryWithBaseline()

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.5", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD, baseline)

        assertEquals(listOf("0.1.5"), history.releases.map { it.version })
    }

    @Test
    fun `release without tags rejects versions equal to or older than newest baseline release`() {
        val (git, baseline) = repositoryWithBaseline(withCandidateFragment = true)

        listOf("0.1.5", "0.1.4").forEach { version ->
            val error = assertThrows(GenerationException::class.java) {
                ReleaseHistoryGenerator(GitRepository(directory))
                    .preflight("HEAD", version, ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE, baseline)
            }

            assertTrue(error.message!!.contains("${baseline.boundaryCommit}..${git.head()}"), version)
            assertTrue(error.message!!.contains("must be newer than newest baseline release 0.1.5"), version)
            assertTrue(error.message!!.contains("increment versionName"), version)
        }
    }

    @Test
    fun `release without tags adds a newer version before unique baseline history`() {
        val (git, baseline) = repositoryWithBaseline(withCandidateFragment = true)

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.6", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE, baseline)

        assertEquals(listOf("0.1.6", "0.1.5"), history.releases.map { it.version })
        assertEquals(history.releases.map { it.version }.distinct(), history.releases.map { it.version })
        assertEquals(git.head(), history.releases.first().commitSha)
        assertEquals(listOf(30), history.releases.first().changes.map { it.issue })
    }

    @Test
    fun `reachable tag takes precedence over baseline without duplicate or out of order history`() {
        val git = TestGit(directory)
        git.init()
        git.file("README.md", "legacy")
        git.commit("Legacy history")
        val boundary = git.head()
        git.fragment(29, "tagged", true, "Тегированный выпуск")
        git.commit("Add tagged release (#29)")
        git.annotatedTag("apk/0.1.4")
        git.fragment(30, "candidate", true, "Новый выпуск")
        git.commit("Add release candidate (#30)")
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

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.5", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE, baseline)

        assertEquals(listOf("0.1.5", "0.1.4"), history.releases.map { it.version })
        assertEquals(listOf(30, 29), history.releases.flatMap { release -> release.changes.map { it.issue } })
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
    fun `release mode rejects version not newer than previous release with actionable range`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "first", true, "Первое изменение")
        git.commit("First task (#1)")
        val previous = git.head()
        git.annotatedTag("apk/0.1.2")
        git.fragment(2, "second", false, "Техническое изменение")
        git.commit("Second task (#2)")

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(directory))
                .preflight("HEAD", "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
        }

        assertTrue(error.message!!.contains("$previous..${git.head()}"))
        assertTrue(error.message!!.contains("increment versionName"))
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
                .preflight(mainHead, "0.1.2", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.RELEASE)
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
    fun `includes issue fragments introduced by merged branch commits`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "base", true, "Первое изменение")
        git.commit("Base task (#1)")
        git.annotatedTag("apk/0.1.0")
        git.branch("side")
        git.file("README.md", "main")
        git.commit("Prepare integration")
        git.checkout("side")
        git.fragment(2, "side", true, "Боковое изменение")
        git.commit("Side task (#2)")
        git.checkout("main")
        git.mergeNoFastForward("side", "Merge side branch")

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)

        assertEquals(listOf(2), history.latestChanges.map { it.issue })
    }

    @Test
    fun `allows technical carryover fragment changed after its released issue`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Released task (#1)")
        git.annotatedTag("apk/0.1.0")
        git.fragment(1, "released", false, "Изменение уже вошло в выпущенную версию")
        git.fragment(2, "current", true, "Текущее изменение")
        git.commit("Current task (#2)")

        val history = ReleaseHistoryGenerator(GitRepository(directory))
            .generate("HEAD", "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)

        assertEquals(listOf(2), history.latestChanges.map { it.issue })
    }

    @Test
    fun `rejects unmatched technical fragment whose issue was not previously released`() {
        val git = TestGit(directory)
        git.init()
        git.fragment(1, "released", true, "Выпущенное изменение")
        git.commit("Released task (#1)")
        git.annotatedTag("apk/0.1.0")
        git.fragment(2, "unmatched", false, "Новое техническое изменение")
        git.commit("Commit without issue")

        val error = assertThrows(GenerationException::class.java) {
            ReleaseHistoryGenerator(GitRepository(directory))
                .generate("HEAD", "0.1.1", ReleaseFlavor.PERSONAL, ReleaseHistoryMode.BUILD)
        }

        assertTrue(error.message!!.contains("#2"))
        assertTrue(error.message!!.contains("without a matching issue"))
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
            releases = listOf(
                GeneratedRelease("0.1.2", "abc", listOf(ReleaseChange(25, "Кавычка \" и \\ $ знак"))),
            ),
            latestChanges = listOf(ReleaseChange(45, "Последнее улучшение")),
        )
        val firstJson = CanonicalOutput.json(history)
        assertEquals(firstJson, CanonicalOutput.json(history))
        assertTrue(firstJson.endsWith("\n"))
        assertTrue(firstJson.contains("\"latestChanges\""))
        assertTrue(firstJson.indexOf("\"issue\": 45") < firstJson.indexOf("\"releases\""))
        assertTrue(firstJson.contains("Кавычка \\\" и \\\\"))
        val firstKotlin = CanonicalOutput.kotlinSource(history, "example.generated")
        assertEquals(firstKotlin, CanonicalOutput.kotlinSource(history, "example.generated"))
        assertTrue(firstKotlin.contains("val latestChanges: List<ReleaseChange>"))
        assertTrue(firstKotlin.indexOf("issueNumber = 45") < firstKotlin.indexOf("val releases"))
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
            listOf(2, 1),
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

    private fun repositoryWithBaseline(withCandidateFragment: Boolean = false): Pair<TestGit, ReleaseHistoryBaseline> {
        val git = TestGit(directory)
        git.init()
        git.file("README.md", "legacy")
        git.commit("Legacy history")
        val boundary = git.head()
        if (withCandidateFragment) git.fragment(30, "baseline-fallback", true, "Новый выпуск")
        else git.file("README.md", "ordinary build")
        git.commit(if (withCandidateFragment) "Add release fallback (#30)" else "Prepare ordinary build")
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
        return git to baseline
    }

    private fun runGit(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $error" }
        return output
    }
}
