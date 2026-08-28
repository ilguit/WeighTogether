package com.palixander.scalesync.releasehistory

object CanonicalOutput {
    fun json(history: GeneratedHistory): String = buildString {
        append("{\n  \"latestChanges\": [")
        history.latestChanges.forEachIndexed { changeIndex, change ->
            if (changeIndex > 0) append(',')
            append("\n    { \"issue\": ").append(change.issue)
                .append(", \"text\": ").append(jsonString(change.text)).append(" }")
        }
        if (history.latestChanges.isNotEmpty()) append('\n').append("  ")
        append("],\n  \"releases\": [")
        history.releases.forEachIndexed { releaseIndex, release ->
            if (releaseIndex > 0) append(',')
            append("\n    {\n")
            append("      \"version\": ").append(jsonString(release.version)).append(",\n")
            append("      \"commitSha\": ").append(jsonString(release.commitSha)).append(",\n")
            append("      \"changes\": [")
            release.changes.forEachIndexed { changeIndex, change ->
                if (changeIndex > 0) append(',')
                append("\n        { \"issue\": ").append(change.issue)
                    .append(", \"text\": ").append(jsonString(change.text)).append(" }")
            }
            if (release.changes.isNotEmpty()) append('\n').append("      ")
            append("]\n    }")
        }
        if (history.releases.isNotEmpty()) append('\n').append("  ")
        append("]\n}\n")
    }

    fun kotlinSource(history: GeneratedHistory, packageName: String): String = buildString {
        append("package ").append(packageName).append("\n\n")
        append("object AppReleaseHistory {\n")
        append("    val latestChanges: List<ReleaseChange> = listOf(")
        history.latestChanges.forEachIndexed { changeIndex, change ->
            if (changeIndex > 0) append(',')
            append("\n        ReleaseChange(issueNumber = ").append(change.issue)
                .append(", description = ").append(kotlinString(change.text)).append(')')
        }
        if (history.latestChanges.isNotEmpty()) append('\n').append("    ")
        append(")\n\n")
        append("    val releases: List<AppRelease> = listOf(")
        history.releases.forEachIndexed { releaseIndex, release ->
            if (releaseIndex > 0) append(',')
            append("\n        AppRelease(\n")
            append("            version = ").append(kotlinString(release.version)).append(",\n")
            append("            changes = listOf(")
            release.changes.forEachIndexed { changeIndex, change ->
                if (changeIndex > 0) append(',')
                append("\n                ReleaseChange(issueNumber = ").append(change.issue)
                    .append(", description = ").append(kotlinString(change.text)).append(')')
            }
            if (release.changes.isNotEmpty()) append('\n').append("            ")
            append(")\n        )")
        }
        if (history.releases.isNotEmpty()) append('\n').append("    ")
        append(")\n}\n")
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun kotlinString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
