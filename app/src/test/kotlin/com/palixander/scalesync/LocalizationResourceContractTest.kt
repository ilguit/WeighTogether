package com.palixander.scalesync

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourceContractTest {
    @Test
    fun `default and Russian resources expose matching keys formats and plurals`() {
        LOCALIZABLE_RESOURCE_FILES.forEach { fileName ->
            val default = readResources(File("src/main/res/values/$fileName"))
            val russian = readResources(File("src/main/res/values-ru/$fileName"))

            assertEquals("Resource keys differ in $fileName", default.keys, russian.keys)
            default.forEach { (key, value) ->
                assertEquals("Resource type differs for $key", value.type, russian.getValue(key).type)
                assertEquals("Format arguments differ for $key", value.arguments, russian.getValue(key).arguments)
                if (value.type == "plurals") {
                    assertEquals("Default plural $key must define other", true, "other" in value.quantities)
                    assertEquals("Russian plural $key must define other", true, "other" in russian.getValue(key).quantities)
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
                    arguments = texts.flatMap { FORMAT_ARGUMENT.findAll(it.textContent).map(MatchResult::value) }.toSet(),
                    quantities = texts.mapNotNull { it.getAttribute("quantity").takeIf(String::isNotEmpty) }.toSet(),
                    texts = texts.map { it.textContent.trim() },
                )
            }
    }

    private data class ResourceContract(
        val type: String,
        val arguments: Set<String>,
        val quantities: Set<String>,
        val texts: List<String>,
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
        val FORMAT_ARGUMENT = Regex("%\\d+\\$[a-zA-Z]")
        val PLACEHOLDER_PROSE =
            Regex("(?i)\\b(?:meaning|calculation|dependencies?|limitations?|warning|disclaimer|source)?\\s*information\\b")
    }
}
