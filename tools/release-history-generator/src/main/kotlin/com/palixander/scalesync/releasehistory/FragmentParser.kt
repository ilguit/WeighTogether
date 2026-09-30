package com.palixander.scalesync.releasehistory

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

class FragmentParser {
    private val settings = LoadSettings.builder()
        .setLabel("release-note fragment")
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(0)
        .build()
    private val yaml = Load(settings)
    private val parser = Parse(settings)

    fun parse(path: String, input: String, historical: Boolean = false): ReleaseNoteFragment {
        val fileIssue = FILE_NAME.matchEntire(path.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
            ?: fail(path, "filename must match <positive-issue>-<lowercase-slug>.yaml")
        val document = try {
            if (parser.parseString(input).any { it is AliasEvent }) fail(path, "YAML aliases are not allowed")
            yaml.loadAllFromString(input).toList().singleOrNull()
                ?: fail(path, "must contain exactly one YAML document")
        } catch (exception: GenerationException) {
            throw exception
        } catch (exception: YamlEngineException) {
            fail(path, "invalid YAML: ${exception.message}", exception)
        }
        val raw = document as? Map<*, *> ?: fail(path, "document must be a mapping")
        if (raw.keys.any { it !is String }) fail(path, "mapping keys must be strings")
        @Suppress("UNCHECKED_CAST")
        val values = raw as Map<String, Any?>
        val unknown = values.keys - ALLOWED_KEYS
        if (unknown.isNotEmpty()) fail(path, "unknown field(s): ${unknown.sorted().joinToString()}")

        val issue = when (val value = values["issue"]) {
            is Int -> value
            is Long -> value.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            else -> null
        } ?: fail(path, "required field 'issue' must be a positive integer")
        if (issue <= 0) fail(path, "required field 'issue' must be a positive integer")
        if (issue != fileIssue) fail(path, "field 'issue' ($issue) must match filename issue ($fileIssue)")
        val visible = values["userVisible"] as? Boolean
            ?: fail(path, "required field 'userVisible' must be a boolean")
        val suppressReleasedChange = when (val value = values["suppressReleasedChange"]) {
            null -> if (values.containsKey("suppressReleasedChange")) {
                fail(path, "field 'suppressReleasedChange' must be a boolean")
            } else {
                false
            }
            is Boolean -> value
            else -> fail(path, "field 'suppressReleasedChange' must be a boolean")
        }
        val text = values["text"] as? String
        val reason = values["reason"] as? String
        if (visible) {
            if (text.isNullOrBlank()) fail(path, "field 'text' is required for a user-visible fragment")
            if (values.containsKey("reason")) fail(path, "field 'reason' is forbidden when 'userVisible' is true")
            if (values.containsKey("suppressReleasedChange")) {
                fail(path, "field 'suppressReleasedChange' is forbidden when 'userVisible' is true")
            }
        } else {
            if (reason.isNullOrBlank()) fail(path, "field 'reason' is required for a technical fragment")
            if (values.containsKey("text")) fail(path, "field 'text' is forbidden when 'userVisible' is false")
        }
        val flavors = when (val value = values["flavors"]) {
            null -> emptySet()
            is List<*> -> {
                if (value.isEmpty() || value.any { it !is String }) {
                    fail(path, "field 'flavors' must be a non-empty string list")
                }
                value.map {
                    val id = it as String
                    if (!historical) ReleaseFlavor.fromId(id)
                    id
                }.toSet().also {
                    if (it.size != value.size) fail(path, "field 'flavors' must not contain duplicates")
                }
            }
            else -> fail(path, "field 'flavors' must be a non-empty string list")
        }
        return ReleaseNoteFragment(path, issue, visible, text, reason, flavors, suppressReleasedChange)
    }

    private fun fail(path: String, message: String, cause: Throwable? = null): Nothing =
        throw GenerationException("$path: $message", cause)

    private companion object {
        val FILE_NAME = Regex("([1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*\\.yaml")
        val ALLOWED_KEYS = setOf(
            "issue",
            "userVisible",
            "text",
            "reason",
            "flavors",
            "suppressReleasedChange",
        )
    }
}
