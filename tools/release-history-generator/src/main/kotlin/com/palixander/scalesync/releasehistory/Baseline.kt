package com.palixander.scalesync.releasehistory

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

data class ReleaseHistoryBaseline(
    val boundaryCommit: String,
    val releases: List<BootstrapRelease>,
)

object BaselineParser {
    fun parse(input: String): ReleaseHistoryBaseline {
        val root = Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
            .loadFromString(input) as? Map<*, *> ?: fail("document must be a mapping")
        if (root.keys != setOf("schemaVersion", "boundaryCommit", "releases")) fail("unexpected fields")
        if (root["schemaVersion"] != 2) fail("schemaVersion must be 2")
        val boundary = root["boundaryCommit"] as? String ?: fail("boundaryCommit must be a SHA")
        if (!Regex("[0-9a-f]{40}").matches(boundary)) fail("boundaryCommit must be a full lowercase SHA")
        val releases = (root["releases"] as? List<*>)?.map { raw ->
            val release = raw as? Map<*, *> ?: fail("release must be a mapping")
            if (release.keys != setOf("version", "evidence", "changes")) fail("release has unexpected fields")
            val version = release["version"] as? String ?: fail("release version must be a string")
            if (!VERSION.matches(version)) fail("release version must use numeric dotted notation")
            val evidence = parseEvidence(release["evidence"])
            val changes = (release["changes"] as? List<*>)?.map { changeRaw ->
                val change = changeRaw as? Map<*, *> ?: fail("change must be a mapping")
                if (change.keys != setOf("issue", "text")) fail("change has unexpected fields")
                val issue = change["issue"] as? Int ?: fail("change issue must be an integer")
                if (issue <= 0) fail("change issue must be positive")
                val text = change["text"] as? String ?: fail("change text must be a string")
                if (text.isBlank()) fail("change text must not be blank")
                ReleaseChange(issue, text)
            } ?: fail("release changes must be a list")
            if (changes.map { it.issue }.toSet().size != changes.size) fail("release changes must use unique issues")
            BootstrapRelease(GeneratedRelease(version, boundary, changes), evidence)
        } ?: fail("releases must be a list")
        if (releases.isEmpty() || releases.map { it.release.version }.toSet().size != releases.size) {
            fail("releases must contain unique versions")
        }
        if (releases.zipWithNext().any { (newer, older) -> compareVersions(newer.release.version, older.release.version) <= 0 }) {
            fail("releases must be ordered newest-first")
        }
        return ReleaseHistoryBaseline(boundary, releases)
    }

    private fun parseEvidence(raw: Any?): HistoricalBoundaryEvidence {
        val evidence = raw as? Map<*, *> ?: fail("release evidence must be a mapping")
        val status = when (evidence["status"]) {
            "confirmed" -> HistoricalBoundaryStatus.CONFIRMED
            "unknown" -> HistoricalBoundaryStatus.UNKNOWN
            else -> fail("release evidence status must be confirmed or unknown")
        }
        val expectedKeys = when (status) {
            HistoricalBoundaryStatus.CONFIRMED -> setOf("status", "boundaryCommit", "source")
            HistoricalBoundaryStatus.UNKNOWN -> setOf("status", "candidateCommit", "source")
        }
        if (evidence.keys != expectedKeys) fail("release evidence has unexpected or inconsistent fields")
        val boundaryCommit = evidence["boundaryCommit"] as? String
        val candidateCommit = evidence["candidateCommit"] as? String
        listOfNotNull(boundaryCommit, candidateCommit).forEach {
            if (!SHA.matches(it)) fail("release evidence commit must be a full lowercase SHA")
        }
        val source = evidence["source"] as? String ?: fail("release evidence source must be a string")
        if (source.isBlank()) fail("release evidence source must not be blank")
        return HistoricalBoundaryEvidence(status, boundaryCommit, candidateCommit, source)
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map(String::toInt)
        val rightParts = right.split('.').map(String::toInt)
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun fail(message: String): Nothing = throw GenerationException("release history baseline: $message")

    private val SHA = Regex("[0-9a-f]{40}")
    private val VERSION = Regex("[0-9]+(?:\\.[0-9]+)+")
}
