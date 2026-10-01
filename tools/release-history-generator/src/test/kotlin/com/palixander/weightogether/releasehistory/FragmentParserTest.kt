package com.palixander.weightogether.releasehistory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FragmentParserTest {
    private val parser = FragmentParser()

    @Test
    fun `historical metadata preserves filtering without admitting retired variants in current fragments`() {
        val input = "issue: 25\nuserVisible: true\ntext: Old change\nflavors: [retiredVariant]"
        org.junit.jupiter.api.Assertions.assertThrows(GenerationException::class.java) {
            parser.parse("25-change.yaml", input)
        }
        val historical = parser.parse("25-change.yaml", input, historical = true)
        assertEquals(false, historical.appliesTo(ReleaseFlavor.PERSONAL))
        val shared = parser.parse("25-change.yaml", input.replace("[retiredVariant]", "[retiredVariant, personal]"), historical = true)
        assertEquals(true, shared.appliesTo(ReleaseFlavor.PERSONAL))
    }

    @Test
    fun `parses technical and flavor metadata`() {
        val fragment = parser.parse(
            ".release-notes/25-generator.yaml",
            """
                issue: 25
                userVisible: false
                reason: Служебная генерация истории
                flavors:
                  - personal
            """.trimIndent(),
        )
        assertEquals(25, fragment.issue)
        assertEquals(setOf("personal"), fragment.flavors)
        assertEquals(false, fragment.suppressReleasedChange)
    }

    @Test
    fun `parses explicit released change suppression for technical fragments`() {
        val fragment = parser.parse(
            ".release-notes/25-generator.yaml",
            """
                issue: 25
                userVisible: false
                suppressReleasedChange: true
                reason: Служебная генерация истории
            """.trimIndent(),
        )

        assertEquals(true, fragment.suppressReleasedChange)
    }

    @Test
    fun `validates released change suppression type and visibility`() {
        assertThrows(GenerationException::class.java) {
            parser.parse(
                ".release-notes/25-a.yaml",
                "issue: 25\nuserVisible: false\nsuppressReleasedChange: yes\nreason: x",
            )
        }
        assertThrows(GenerationException::class.java) {
            parser.parse(
                ".release-notes/25-a.yaml",
                "issue: 25\nuserVisible: false\nsuppressReleasedChange: null\nreason: x",
            )
        }
        assertThrows(GenerationException::class.java) {
            parser.parse(
                ".release-notes/25-a.yaml",
                "issue: 25\nuserVisible: true\nsuppressReleasedChange: false\ntext: Видимое изменение",
            )
        }
    }

    @Test
    fun `rejects aliases duplicate keys and mismatched issue`() {
        assertThrows(GenerationException::class.java) {
            parser.parse(".release-notes/25-a.yaml", "issue: 25\nissue: 25\nuserVisible: false\nreason: x")
        }
        assertThrows(GenerationException::class.java) {
            parser.parse(".release-notes/25-a.yaml", "issue: &id 25\nuserVisible: false\nreason: *id")
        }
        assertThrows(GenerationException::class.java) {
            parser.parse(".release-notes/25-a.yaml", "issue: 26\nuserVisible: false\nreason: x")
        }
    }
}
