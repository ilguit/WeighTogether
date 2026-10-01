package com.palixander.weightogether.releasehistory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineParserTest {
    @Test
    fun `parses mixed confirmed and unknown historical evidence`() {
        val baseline = BaselineParser.parse(validBaseline())

        assertEquals(listOf("0.1.2", "0.1.1"), baseline.releases.map { it.release.version })
        assertEquals(HistoricalBoundaryStatus.CONFIRMED, baseline.releases[0].evidence.status)
        assertEquals(HistoricalBoundaryStatus.UNKNOWN, baseline.releases[1].evidence.status)
    }

    @Test
    fun `rejects invalid evidence sha status and fields`() {
        assertFailure(validBaseline().replace("a".repeat(40), "ABC"), "lowercase SHA")
        assertFailure(validBaseline().replace("status: confirmed", "status: inferred"), "confirmed or unknown")
        assertFailure(
            validBaseline().replace("boundaryCommit: ${"a".repeat(40)}", "candidateCommit: ${"a".repeat(40)}"),
            "inconsistent fields",
        )
    }

    @Test
    fun `rejects duplicate and non descending versions`() {
        assertFailure(validBaseline().replace("version: 0.1.1", "version: 0.1.2"), "unique versions")
        assertFailure(validBaseline().replace("version: 0.1.2", "version: 0.1.0"), "newest-first")
    }

    private fun assertFailure(input: String, expected: String) {
        val error = assertThrows(GenerationException::class.java) { BaselineParser.parse(input) }
        assertTrue(error.message!!.contains(expected), error.message)
    }

    private fun validBaseline(): String = """
        schemaVersion: 2
        boundaryCommit: ${"f".repeat(40)}
        releases:
          - version: 0.1.2
            evidence:
              status: confirmed
              boundaryCommit: ${"a".repeat(40)}
              source: immutable build metadata
            changes:
              - issue: 2
                text: Второе изменение
          - version: 0.1.1
            evidence:
              status: unknown
              candidateCommit: ${"b".repeat(40)}
              source: version span only
            changes:
              - issue: 1
                text: Первое изменение
    """.trimIndent()
}
