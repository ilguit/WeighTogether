package com.example.huaweimisync.releasehistory

class ReleaseHistoryGenerator(
    private val repository: GitRepository,
    private val fragmentParser: FragmentParser = FragmentParser(),
) {
    fun generate(
        headRevision: String,
        currentVersion: String,
        flavor: ReleaseFlavor,
        baseline: ReleaseHistoryBaseline? = null,
    ): GeneratedHistory {
        val head = repository.resolve(headRevision)
        val tags = repository.reachableAnnotatedApkTags(head)
        baseline?.let {
            if (!repository.isFirstParentAncestor(it.boundaryCommit, head)) {
                throw GenerationException("Baseline boundary ${it.boundaryCommit} is not on the first-parent history of $head")
            }
        }
        val headTags = tags.filter { it.commitSha == head }
        if (headTags.any { it.version != currentVersion }) {
            throw GenerationException(
                "HEAD $head is tagged as ${headTags.joinToString { it.version }}, not current version $currentVersion",
            )
        }
        val taggedPoints = tags.filter { it.commitSha != head }
            .filter { baseline == null || repository.isFirstParentAncestor(baseline.boundaryCommit, it.commitSha) }
            .map { ReleasePoint(it.version, it.commitSha) }
        val points = listOf(ReleasePoint(currentVersion, head)) + taggedPoints
        val releases = points.mapIndexed { index, point ->
            val base = points.getOrNull(index + 1)?.commitSha ?: baseline?.boundaryCommit
            generateRelease(point, base, flavor)
        }
        return GeneratedHistory(releases + baseline.orEmpty())
    }

    private fun generateRelease(point: ReleasePoint, exclusiveBase: String?, flavor: ReleaseFlavor): GeneratedRelease {
        val issues = extractIssues(repository.firstParentCommits(point.commitSha, exclusiveBase).map { it.subject })
        val fragments = repository.changedFragmentPaths(point.commitSha, exclusiveBase)
            .map { fragmentParser.parse(it, repository.readFile(point.commitSha, it)) }
        val fragmentsByIssue = fragments.groupBy { it.issue }

        val missing = issues.filterNot { fragmentsByIssue.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw GenerationException("Release ${point.version} has issue(s) without a changed fragment: " +
                missing.joinToString { "#$it" })
        }
        val extra = fragmentsByIssue.keys.filterNot { it in issues }.sorted()
        if (extra.isNotEmpty()) {
            throw GenerationException("Release ${point.version} has changed fragment(s) without a matching issue: " +
                extra.joinToString { "#$it" })
        }
        val conflicts = fragmentsByIssue.filterValues { it.size > 1 }.keys.sorted()
        if (conflicts.isNotEmpty()) {
            throw GenerationException("Release ${point.version} has multiple fragments for issue(s): " +
                conflicts.joinToString { "#$it" })
        }
        val changes = issues.mapNotNull { issue ->
            val fragment = fragmentsByIssue.getValue(issue).single()
            if (!fragment.userVisible || !fragment.appliesTo(flavor)) null
            else ReleaseChange(issue, requireNotNull(fragment.text))
        }
        return GeneratedRelease(point.version, point.commitSha, changes)
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

    private fun ReleaseHistoryBaseline?.orEmpty(): List<GeneratedRelease> =
        this?.releases.orEmpty().map { it.release }

    private companion object {
        val ISSUE_SUFFIX = Regex("\\(#([1-9][0-9]*)\\)(?=\\s*$)")
        val MERGE_PULL_REQUEST = Regex("^Merge pull request #([1-9][0-9]*)\\b")
    }
}
