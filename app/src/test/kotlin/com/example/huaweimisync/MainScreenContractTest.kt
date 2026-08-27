package com.example.huaweimisync

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
}
