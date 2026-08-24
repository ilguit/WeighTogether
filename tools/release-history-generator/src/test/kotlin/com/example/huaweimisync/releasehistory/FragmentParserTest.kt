package com.example.huaweimisync.releasehistory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FragmentParserTest {
    private val parser = FragmentParser()

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
        assertEquals(setOf(ReleaseFlavor.PERSONAL), fragment.flavors)
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
