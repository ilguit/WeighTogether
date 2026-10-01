package com.palixander.weightogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.palixander.weightogether.ui.text.uiText

class ScaleSettingsPresentationTest {
    @Test fun readyShowsSelectedModelAndAllowsForget() {
        val value = presentation(address = "AA:BB", name = "MIBFS")
        assertEquals(ScaleSettingsStatus.READY, value.status)
        assertEquals(uiText(R.string.settings_scale_identity, uiText(R.string.settings_raw_value, "MIBFS"), "AA:BB"), value.supportingText)
        assertEquals(uiText(R.string.settings_raw_value, "MIBFS"), scaleRootSupportingText(value, "MIBFS"))
        assertEquals(ScaleSettingsAction.SEARCH, value.action)
        assertTrue(value.allowForget)
    }

    @Test fun rootUsesFallbackModelWithoutAddressWhileDetailKeepsFullIdentity() {
        val value = presentation(address = "AA:BB:CC:DD:EE:FF")

        assertEquals(
            uiText(R.string.settings_default_scale_name),
            scaleRootSupportingText(value, selectedName = null),
        )
        assertEquals(uiText(R.string.settings_scale_identity, uiText(R.string.settings_default_scale_name), "AA:BB:CC:DD:EE:FF"), value.supportingText)
    }

    @Test fun rootKeepsNonReadyStatusText() {
        val value = presentation(scanning = true)

        assertEquals(uiText(R.string.settings_scale_searching), scaleRootSupportingText(value, "Старое имя"))
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
        assertEquals(uiText(R.string.settings_scale_searching), value.supportingText)
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
        assertEquals(uiText(R.string.settings_bluetooth_off), value.supportingText)
        assertNull(value.action)
    }

    @Test fun unavailableStateTakesPriorityOverStaleScanningState() {
        val value = presentation(
            scanning = true,
            availability = ScaleAvailability.BLUETOOTH_DISABLED,
        )
        assertEquals(ScaleSettingsStatus.UNAVAILABLE, value.status)
        assertEquals(uiText(R.string.settings_bluetooth_off), value.supportingText)
        assertFalse(value.showProgress)
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
