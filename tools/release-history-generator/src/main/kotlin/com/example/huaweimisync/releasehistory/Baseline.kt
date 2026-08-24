package com.example.huaweimisync.releasehistory

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

data class ReleaseHistoryBaseline(
    val boundaryCommit: String,
    val releases: List<GeneratedRelease>,
)

object BaselineParser {
    fun parse(input: String): ReleaseHistoryBaseline {
        val root = Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
            .loadFromString(input) as? Map<*, *> ?: fail("document must be a mapping")
        if (root.keys != setOf("schemaVersion", "boundaryCommit", "releases")) fail("unexpected fields")
        if (root["schemaVersion"] != 1) fail("schemaVersion must be 1")
        val boundary = root["boundaryCommit"] as? String ?: fail("boundaryCommit must be a SHA")
        if (!Regex("[0-9a-f]{40}").matches(boundary)) fail("boundaryCommit must be a full lowercase SHA")
        val releases = (root["releases"] as? List<*>)?.map { raw ->
            val release = raw as? Map<*, *> ?: fail("release must be a mapping")
            if (release.keys != setOf("version", "changes")) fail("release has unexpected fields")
            val version = release["version"] as? String ?: fail("release version must be a string")
            val changes = (release["changes"] as? List<*>)?.map { changeRaw ->
                val change = changeRaw as? Map<*, *> ?: fail("change must be a mapping")
                if (change.keys != setOf("issue", "text")) fail("change has unexpected fields")
                val issue = change["issue"] as? Int ?: fail("change issue must be an integer")
                val text = change["text"] as? String ?: fail("change text must be a string")
                ReleaseChange(issue, text)
            } ?: fail("release changes must be a list")
            GeneratedRelease(version, boundary, changes)
        } ?: fail("releases must be a list")
        if (releases.isEmpty() || releases.map { it.version }.toSet().size != releases.size) {
            fail("releases must contain unique versions")
        }
        return ReleaseHistoryBaseline(boundary, releases)
    }

    private fun fail(message: String): Nothing = throw GenerationException("release history baseline: $message")
}
