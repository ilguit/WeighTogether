package com.palixander.scalesync.releasehistory

import java.nio.file.Path

class GitRepository(private val root: Path) {
    fun resolve(revision: String): String = git("rev-parse", "--verify", "$revision^{commit}").trim()

    fun isShallow(): Boolean = git("rev-parse", "--is-shallow-repository").trim() == "true"

    fun isFirstParentAncestor(ancestor: String, descendant: String): Boolean =
        git("rev-list", "--first-parent", descendant)
            .lineSequence()
            .any { it == ancestor }

    fun firstParentCommits(head: String, exclusiveBase: String? = null): List<GitCommit> {
        val range = exclusiveBase?.let { "$it..$head" } ?: head
        val output = git("log", "--first-parent", "--reverse", "--format=%H%x00%s", range)
        return parseCommits(output)
    }

    fun commits(head: String, exclusiveBase: String? = null): List<GitCommit> {
        val range = exclusiveBase?.let { "$it..$head" } ?: head
        val output = git("log", "--reverse", "--format=%H%x00%s", range)
        return parseCommits(output)
    }

    private fun parseCommits(output: String): List<GitCommit> {
        return output.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val values = line.split('\u0000', limit = 2)
            GitCommit(values[0], values.getOrElse(1) { "" })
        }.toList()
    }

    fun reachableAnnotatedApkTags(head: String): List<ApkTag> {
        val firstParent = git("rev-list", "--first-parent", head).lineSequence().filter { it.isNotBlank() }.toList()
        val position = firstParent.withIndex().associate { it.value to it.index }
        val tags = git(
            "for-each-ref",
            "--format=%(refname:short)%00%(objecttype)%00%(*objectname)",
            "refs/tags/apk/",
        ).lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val fields = line.split('\u0000')
            if (fields.size != 3 || fields[1] != "tag" || fields[2].isBlank()) return@mapNotNull null
            val version = APK_TAG.matchEntire(fields[0])?.groupValues?.get(1) ?: return@mapNotNull null
            ApkTag(fields[0], version, fields[2])
        }.filter { it.commitSha in position }
            .sortedWith(compareBy<ApkTag> { position.getValue(it.commitSha) }.thenBy { it.name })
            .toList()
        val duplicateCommit = tags.groupBy { it.commitSha }.values.firstOrNull { it.size > 1 }
        if (duplicateCommit != null) {
            throw GenerationException("Multiple annotated APK tags point to ${duplicateCommit.first().commitSha}: " +
                duplicateCommit.joinToString { it.name })
        }
        return tags
    }

    fun annotatedApkTags(): List<ApkTag> = git(
        "for-each-ref",
        "--format=%(refname:short)%00%(objecttype)%00%(*objectname)",
        "refs/tags/apk/",
    ).lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val fields = line.split('\u0000')
        if (fields.size != 3 || fields[1] != "tag" || fields[2].isBlank()) return@mapNotNull null
        val version = APK_TAG.matchEntire(fields[0])?.groupValues?.get(1) ?: return@mapNotNull null
        ApkTag(fields[0], version, fields[2])
    }.sortedBy { it.name }.toList()

    fun changedFragmentPaths(head: String, exclusiveBase: String?): List<String> {
        val paths = if (exclusiveBase == null) {
            git("ls-tree", "-r", "--name-only", head, "--", NOTES_DIRECTORY)
                .lineSequence().map { it.trim() }
        } else {
            git("diff", "--name-only", "--diff-filter=AM", exclusiveBase, head, "--", NOTES_DIRECTORY)
                .lineSequence().map { it.trim() }
        }
        return paths.filter { FRAGMENT_PATH.matches(it) }.sorted().toList()
    }

    fun readFile(revision: String, path: String): String = git("show", "$revision:$path")

    fun trackedFragmentMetadata(revision: String): String = git(
        "ls-tree",
        "-r",
        "--full-tree",
        revision,
        "--",
        NOTES_DIRECTORY,
    )

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val error = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        if (process.waitFor() != 0) {
            throw GenerationException("git ${arguments.joinToString(" ")} failed: ${error.trim()}")
        }
        return output
    }

    data class GitCommit(val sha: String, val subject: String)

    private companion object {
        const val NOTES_DIRECTORY = ".release-notes"
        val APK_TAG = Regex("apk/([^/]+)")
        val FRAGMENT_PATH = Regex("\\.release-notes/[1-9][0-9]*-[a-z0-9]+(?:-[a-z0-9]+)*\\.yaml")
    }
}
