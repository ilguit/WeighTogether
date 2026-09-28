package com.palixander.scalesync

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourceContractTest {
    @Test
    fun `weighing reminder copy is translated in every supported locale`() {
        val defaults = readResources(File("src/main/res/values/strings.xml"))
        val reminderKeys = defaults.keys.filter(::isReminderResource).toSet()

        assertFalse("No default reminder resources found", reminderKeys.isEmpty())

        LOCALES.filterNot { it.directory == "values" }.forEach { locale ->
            val localized = readResources(File("src/main/res/${locale.directory}/strings.xml"))

            reminderKeys.forEach { key ->
                val default = defaults[key]
                val translation = localized[key]
                assertFalse("Missing default reminder resource: $key", default == null)
                assertFalse("Missing ${locale.tag} reminder resource: $key", translation == null)
                assertEquals(
                    "Format arguments differ for ${locale.tag}:$key",
                    default!!.arguments,
                    translation!!.arguments,
                )
                if ((locale.tag to key) !in IDENTICAL_TRANSLATION_EXCEPTIONS) {
                    assertFalse(
                        "Reminder resource is still English for ${locale.tag}:$key",
                        default.texts == translation.texts,
                    )
                }
            }
        }
    }

    @Test
    fun `localized resources expose matching keys formats and locale plural quantities`() {
        val defaultResources = LOCALIZABLE_RESOURCE_FILES.associateWith { fileName ->
            readResources(File("src/main/res/values/$fileName"))
        }

        LOCALES.forEach { locale ->
            LOCALIZABLE_RESOURCE_FILES.forEach { fileName ->
                val default = defaultResources.getValue(fileName)
                val localized = readResources(File("src/main/res/${locale.directory}/$fileName"))

                assertEquals("Resource keys differ for ${locale.tag} in $fileName", default.keys, localized.keys)
                default.forEach { (key, value) ->
                    val localizedValue = localized.getValue(key)
                    assertEquals("Resource type differs for ${locale.tag}:$key", value.type, localizedValue.type)
                    assertEquals(
                        "Format arguments differ for ${locale.tag}:$key",
                        value.arguments,
                        localizedValue.arguments,
                    )
                    if (value.type == "plurals") {
                        assertEquals(
                            "Plural quantities differ for ${locale.tag}:$key",
                            locale.pluralQuantities,
                            localizedValue.quantities,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `default reference copy contains no placeholder prose`() {
        val resources = readResources(File("src/main/res/values/reference_strings.xml"))

        resources.forEach { (key, value) ->
            value.texts.forEach { text ->
                assertFalse("Placeholder prose remains in $key: $text", PLACEHOLDER_PROSE.containsMatchIn(text))
            }
        }
    }

    private fun readResources(file: File): Map<String, ResourceContract> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return (0 until document.documentElement.childNodes.length)
            .mapNotNull { document.documentElement.childNodes.item(it) as? Element }
            .associate { element ->
                val texts = if (element.tagName == "plurals") {
                    (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
                } else {
                    listOf(element)
                }
                element.getAttribute("name") to ResourceContract(
                    type = element.tagName,
                    arguments = texts
                        .flatMap { FORMAT_ARGUMENT.findAll(it.textContent).map(MatchResult::value) }
                        .groupingBy { it }
                        .eachCount(),
                    quantities = texts.mapNotNull { it.getAttribute("quantity").takeIf(String::isNotEmpty) }.toSet(),
                    texts = texts.map { it.textContent.trim() },
                )
            }
    }

    private data class ResourceContract(
        val type: String,
        val arguments: Map<String, Int>,
        val quantities: Set<String>,
        val texts: List<String>,
    )

    private data class LocaleContract(
        val tag: String,
        val directory: String,
        val pluralQuantities: Set<String>,
    )

    private companion object {
        val LOCALIZABLE_RESOURCE_FILES = listOf(
            "account_routing_strings.xml",
            "audit_strings.xml",
            "pet_editor_strings.xml",
            "pet_measurement_strings.xml",
            "reference_strings.xml",
            "strings.xml",
            "ui_strings.xml",
        )
        val LOCALES = listOf(
            LocaleContract("en", "values", setOf("one", "other")),
            LocaleContract("ru", "values-ru", setOf("one", "few", "many", "other")),
            LocaleContract("de", "values-de", setOf("one", "other")),
            LocaleContract("fr", "values-fr", setOf("one", "other")),
            LocaleContract("it", "values-it", setOf("one", "other")),
            LocaleContract("uk", "values-uk", setOf("one", "few", "many", "other")),
            LocaleContract("be", "values-be", setOf("one", "few", "many", "other")),
            LocaleContract("ja", "values-ja", setOf("other")),
            LocaleContract("zh", "values-zh", setOf("other")),
        )
        val FORMAT_ARGUMENT = Regex("%\\d+\\$[a-zA-Z]")
        val PLACEHOLDER_PROSE =
            Regex("(?i)\\b(?:meaning|calculation|dependencies?|limitations?|warning|disclaimer|source)?\\s*information\\b")
        fun isReminderResource(key: String): Boolean =
            key.startsWith("reminder_") ||
                key.startsWith("weighing_reminder_") ||
                key.startsWith("notification_channel_weighing_")
        // “Alarm” is the idiomatic German UI term as well as the English source text.
        val IDENTICAL_TRANSLATION_EXCEPTIONS = setOf("de" to "reminder_alarm")
    }
}
