package com.example.huaweimisync.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseHistoryTest {
    @Test
    fun releasesAreNewestFirstAndMapEveryVersionToItsIssues() {
        val releases = AppReleaseHistory.releases

        assertEquals(
            listOf("0.1.12", "0.1.6", "0.1.5", "0.1.4", "0.1.3", "0.1.2", "0.1.1"),
            releases.map { it.version },
        )
        assertEquals(
            mapOf(
                "0.1.12" to listOf(25, 27, 5),
                "0.1.6" to listOf(19, 20),
                "0.1.5" to listOf(3),
                "0.1.4" to listOf(16),
                "0.1.3" to listOf(13),
                "0.1.2" to listOf(12, 4),
                "0.1.1" to listOf(6, 7, 8, 11),
            ),
            releases.associate { release ->
                release.version to release.changes.map(ReleaseChange::issueNumber)
            },
        )
        assertTrue(releases.flatMap(AppRelease::changes).all { it.description.isNotBlank() })
    }
}
