package com.example.huaweimisync.releasehistory

class ReleaseHistoryGenerator(
    private val repository: GitRepository,
    private val fragmentParser: FragmentParser = FragmentParser(),
) {
    fun generate(
        headRevision: String,
        currentVersion: String,
        flavor: ReleaseFlavor,
        mode: ReleaseHistoryMode,
        baseline: ReleaseHistoryBaseline? = null,
    ): GeneratedHistory = preflight(headRevision, currentVersion, flavor, mode, baseline).history

    fun preflight(
        headRevision: String,
        currentVersion: String,
        flavor: ReleaseFlavor,
        mode: ReleaseHistoryMode,
        baseline: ReleaseHistoryBaseline? = null,
    ): ReleasePreflight {
        val head = repository.resolve(headRevision)
        parseVersion(currentVersion)
        if (repository.isShallow()) {
            throw GenerationException(
                "Release range root..$head cannot be verified because the local Git history is shallow or " +
                    "incomplete; run 'git fetch --unshallow --tags' (or 'git fetch --force --tags origin' " +
                    "after fetching full history) and retry",
            )
        }
        val tags = repository.reachableAnnotatedApkTags(head)
        baseline?.let {
            if (!repository.isFirstParentAncestor(it.boundaryCommit, head)) {
                throw GenerationException("Baseline boundary ${it.boundaryCommit} is not on the first-parent history of $head")
            }
        }
        val headTags = tags.filter { it.commitSha == head }
        if (headTags.any { it.version != currentVersion }) {
            throw GenerationException(
                "Release range ${rangeName(tags.firstOrNull { it.commitSha != head }?.commitSha, head)}: " +
                    "HEAD $head is tagged as ${headTags.joinToString { it.version }}, not current version " +
                    "$currentVersion; set versionName to the HEAD tag version or remove the incorrect tag",
            )
        }
        val previousTag = tags.firstOrNull { it.commitSha != head }
        val baselinePreviousRelease = baseline?.releases?.firstOrNull()?.release
        when (mode) {
            ReleaseHistoryMode.BUILD -> {
                val latestTag = tags.firstOrNull()
                if (latestTag != null && compareVersions(currentVersion, latestTag.version) < 0) {
                    throw GenerationException(
                        "Build at $head uses versionName $currentVersion, which is older than latest release tag " +
                            "${latestTag.name}; restore at least versionName ${latestTag.version}",
                    )
                }
                if (tags.isEmpty() && baselinePreviousRelease != null &&
                    compareVersions(currentVersion, baselinePreviousRelease.version) < 0
                ) {
                    throw GenerationException(
                        "Build at $head uses versionName $currentVersion, which is older than newest baseline " +
                            "release ${baselinePreviousRelease.version}; restore at least versionName " +
                            baselinePreviousRelease.version,
                    )
                }
            }
            ReleaseHistoryMode.RELEASE -> {
                previousTag?.let {
                    if (compareVersions(currentVersion, it.version) <= 0) {
                        throw GenerationException(
                            "Release range ${rangeName(it.commitSha, head)}: versionName $currentVersion must be newer " +
                                "than previous release tag ${it.name}; increment versionName",
                        )
                    }
                }
                if (tags.isEmpty() && baselinePreviousRelease != null &&
                    compareVersions(currentVersion, baselinePreviousRelease.version) <= 0
                ) {
                    throw GenerationException(
                        "Release range ${rangeName(baseline.boundaryCommit, head)}: versionName $currentVersion " +
                            "must be newer than newest baseline release ${baselinePreviousRelease.version}; " +
                            "increment versionName",
                    )
                }
                repository.annotatedApkTags()
                    .firstOrNull { it.version == currentVersion && it.commitSha != head }
                    ?.let {
                        throw GenerationException(
                            "Release range ${rangeName(previousTag?.commitSha, head)}: versionName $currentVersion is " +
                                "already used by ${it.name} at ${it.commitSha}; choose a new versionName",
                        )
                    }
            }
        }
        val taggedPoints = tags
            .filter { baseline == null || repository.isFirstParentAncestor(baseline.boundaryCommit, it.commitSha) }
            .map { ReleasePoint(it.version, it.commitSha) }
        val points = when (mode) {
            ReleaseHistoryMode.BUILD -> taggedPoints
            ReleaseHistoryMode.RELEASE -> {
                if (headTags.isEmpty()) listOf(ReleasePoint(currentVersion, head)) + taggedPoints
                else taggedPoints
            }
        }
        val ranges = points.mapIndexed { index, point ->
            val base = points.getOrNull(index + 1)?.commitSha ?: baseline?.boundaryCommit
            inspectRange(point, base, tags.firstOrNull { it.commitSha == base })
        }
        val releases = ranges.mapIndexed { index, range ->
            generateRelease(points[index], range, flavor)
        }
        val oldestGeneratedVersion = points.lastOrNull()?.version
        val baselineHistory = baseline.orEmpty().filter {
            oldestGeneratedVersion == null || compareVersions(it.version, oldestGeneratedVersion) < 0
        }
        val latestChanges = if (mode == ReleaseHistoryMode.BUILD) {
            val latestBoundary = tags.firstOrNull()?.commitSha ?: baseline?.boundaryCommit
            if (latestBoundary == head) {
                emptyList()
            } else {
                val latestRange = inspectRange(
                    ReleasePoint(currentVersion, head),
                    latestBoundary,
                    tags.firstOrNull { it.commitSha == latestBoundary },
                )
                generateChanges(latestRange, flavor)
            }
        } else {
            emptyList()
        }
        val history = GeneratedHistory(releases + baselineHistory, latestChanges)
        if (mode == ReleaseHistoryMode.RELEASE && history.releases.firstOrNull()?.version != currentVersion) {
            throw GenerationException(
                "Release range ${ranges.firstOrNull()?.displayName ?: rangeName(previousTag?.commitSha, head)}: " +
                    "versionName $currentVersion does not match " +
                    "the newest generated history entry; regenerate release history",
            )
        }
        return ReleasePreflight(head, previousTag, ranges.firstOrNull(), currentVersion, history)
    }

    private fun inspectRange(point: ReleasePoint, exclusiveBase: String?, previousTag: ApkTag?): ReleaseRange {
        val issues = extractIssues(
            repository.commits(point.commitSha, exclusiveBase).asReversed().map { it.subject },
        )
        val fragments = repository.changedFragmentPaths(point.commitSha, exclusiveBase)
            .map { fragmentParser.parse(it, repository.readFile(point.commitSha, it)) }
        val fragmentsByIssue = fragments.groupBy { it.issue }
        val range = rangeName(exclusiveBase, point.commitSha)

        val missing = issues.filterNot { fragmentsByIssue.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw GenerationException("Release range $range has issue(s) without a changed fragment: " +
                missing.joinToString { "#$it" } + "; add one fragment for each listed issue")
        }
        val extra = fragmentsByIssue
            .filter { (issue, fragments) -> issue !in issues && fragments.any { it.userVisible } }
            .keys
            .sorted()
        if (extra.isNotEmpty()) {
            throw GenerationException("Release range $range has changed fragment(s) without a matching issue: " +
                extra.joinToString { "#$it" } + "; remove each fragment or add the matching issue to a commit subject")
        }
        val conflicts = fragmentsByIssue.filterValues { it.size > 1 }.keys.sorted()
        if (conflicts.isNotEmpty()) {
            throw GenerationException("Release range $range has multiple fragments for issue(s): " +
                conflicts.joinToString { "#$it" } + "; keep exactly one fragment per issue")
        }
        return ReleaseRange(point.commitSha, previousTag, range, issues, fragments)
    }

    private fun generateRelease(point: ReleasePoint, range: ReleaseRange, flavor: ReleaseFlavor): GeneratedRelease {
        return GeneratedRelease(point.version, point.commitSha, generateChanges(range, flavor))
    }

    private fun generateChanges(range: ReleaseRange, flavor: ReleaseFlavor): List<ReleaseChange> {
        val fragmentsByIssue = range.fragments.associateBy { it.issue }
        return range.issues.mapNotNull { issue ->
            val fragment = fragmentsByIssue.getValue(issue)
            if (!fragment.userVisible || !fragment.appliesTo(flavor)) null
            else ReleaseChange(issue, requireNotNull(fragment.text))
        }
    }

    internal fun extractIssues(subjects: List<String>): List<Int> {
        val result = linkedSetOf<Int>()
        subjects.forEach { subject ->
            ISSUE_SUFFIX.findAll(subject).forEach { result += it.groupValues[1].toInt() }
            MERGE_PULL_REQUEST.find(subject)?.let { result += it.groupValues[1].toInt() }
        }
        return result.toList()
    }

    private data class ReleasePoint(val version: String, val commitSha: String)

    private fun rangeName(exclusiveBase: String?, head: String): String = exclusiveBase?.let { "$it..$head" }
        ?: "root..$head"

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = parseVersion(left)
        val rightParts = parseVersion(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun parseVersion(version: String): List<Int> = version.split('.').map { part ->
        part.toIntOrNull() ?: throw GenerationException(
            "Release version '$version' is not numeric dotted notation; use a versionName such as 0.1.10",
        )
    }

    private fun ReleaseHistoryBaseline?.orEmpty(): List<GeneratedRelease> =
        this?.releases.orEmpty().map { it.release }

    private companion object {
        val ISSUE_SUFFIX = Regex("\\(#([1-9][0-9]*)\\)(?=\\s*$)")
        val MERGE_PULL_REQUEST = Regex("^Merge pull request #([1-9][0-9]*)\\b")
    }
}
