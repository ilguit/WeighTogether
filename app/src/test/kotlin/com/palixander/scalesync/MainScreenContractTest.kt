package com.palixander.scalesync

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenContractTest {
    @Test
    fun petProfileBackButtonDescribesReturningToProfiles() {
        assertEquals(
            "Вернуться к профилям",
            mainBackContentDescription(changelogOpen = false, petProfileOpen = true),
        )
    }

    @Test
    fun settingsDetailBackButtonDescribesReturningToSettings() {
        assertEquals(
            "Вернуться к настройкам",
            mainBackContentDescription(
                changelogOpen = false,
                petProfileOpen = false,
                settingsDetailOpen = true,
            ),
        )
    }
}
