package com.example.huaweimisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleSettingsPresentationTest {
    @Test fun readyShowsSelectedModelAndAllowsForget() {
        val value = presentation(address = "AA:BB", name = "MIBFS")
        assertEquals(ScaleSettingsStatus.READY, value.status)
        assertEquals("MIBFS · AA:BB", value.supportingText)
        assertEquals(ScaleSettingsAction.SEARCH, value.action)
        assertTrue(value.allowForget)
    }

    @Test fun emptyOffersSearchWithoutForget() {
        val value = presentation()
        assertEquals(ScaleSettingsStatus.EMPTY, value.status)
        assertEquals(ScaleSettingsAction.SEARCH, value.action)
        assertFalse(value.allowForget)
    }

    @Test fun checkingHasTextAndProgressButNoActionOrForget() {
        val value = presentation(address = "AA:BB", scanning = true)
        assertEquals(ScaleSettingsStatus.CHECKING, value.status)
        assertEquals("Идёт поиск весов…", value.supportingText)
        assertTrue(value.showProgress)
        assertNull(value.action)
        assertFalse(value.allowForget)
    }

    @Test fun missingPermissionOffersExistingApplicationSettingsRecovery() {
        val value = presentation(availability = ScaleAvailability.PERMISSION_REQUIRED)
        assertEquals(ScaleSettingsStatus.UNAVAILABLE, value.status)
        assertEquals(ScaleSettingsAction.OPEN_APP_SETTINGS, value.action)
        assertFalse(value.allowForget)
    }

    @Test fun disabledBluetoothExplainsReasonWithoutInventingRecoveryAction() {
        val value = presentation(availability = ScaleAvailability.BLUETOOTH_DISABLED)
        assertEquals(ScaleSettingsStatus.UNAVAILABLE, value.status)
        assertEquals("Bluetooth выключен", value.supportingText)
        assertNull(value.action)
    }

    @Test fun scannerErrorOffersRetryAndHidesForget() {
        val value = presentation(address = "AA:BB", error = "Ошибка BLE-сканирования: 2")
        assertEquals(ScaleSettingsStatus.ERROR, value.status)
        assertEquals(ScaleSettingsAction.RETRY, value.action)
        assertFalse(value.allowForget)
    }

    private fun presentation(
        address: String? = null,
        name: String? = null,
        scanning: Boolean = false,
        availability: ScaleAvailability = ScaleAvailability.AVAILABLE,
        error: String? = null,
    ) = scaleSettingsPresentation(address, name, scanning, availability, error)
}
