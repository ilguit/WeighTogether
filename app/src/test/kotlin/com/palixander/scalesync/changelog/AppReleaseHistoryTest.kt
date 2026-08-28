package com.palixander.scalesync.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseHistoryTest {
    @Test
    fun historicalReleasesKeepCorrectedOrderAndContent() {
        val releases = AppReleaseHistory.releases
        val historicalVersions = setOf("0.1.1", "0.1.2", "0.1.3", "0.1.4", "0.1.5", "0.1.6")
        val historicalReleases = releases.filter { it.version in historicalVersions }

        assertEquals(
            listOf("0.1.6", "0.1.4", "0.1.3", "0.1.2", "0.1.1"),
            historicalReleases.map { it.version },
        )
        assertEquals(
            mapOf(
                "0.1.6" to listOf(20),
                "0.1.4" to listOf(16),
                "0.1.3" to listOf(13),
                "0.1.2" to listOf(4),
                "0.1.1" to listOf(6, 8, 11),
            ),
            historicalReleases.associate { release ->
                release.version to release.changes.map(ReleaseChange::issueNumber)
            },
        )
        assertTrue(releases.flatMap(AppRelease::changes).all { it.description.isNotBlank() })

        val allChanges = releases.flatMap(AppRelease::changes)
        assertTrue(allChanges.none { it.issueNumber in setOf(3, 7, 12, 19) })
        listOf(
            "Новые измерения отображаются сразу во время обработки",
            "Huawei Health скрыт в personal-сборке",
            "Добавлена ручная сборка APK с уникальными именами артефактов",
            "История версий дополнена изменениями версии 0.1.5",
        ).forEach { forbiddenText ->
            assertFalse(allChanges.any { it.description == forbiddenText })
        }
    }
}
